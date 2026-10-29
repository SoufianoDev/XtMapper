#include <stdbool.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <time.h>

#include <linux/input.h>
#include <fcntl.h>
#include <string.h>
#include <jni.h>
#include <pthread.h>
#include <assert.h>
#include <arpa/inet.h>
#include <paths.h>

typedef struct input_service_context {
    JavaVM *javaVM;
    jclass inputServiceClz;
    jobject inputServiceObj;
    pthread_mutex_t lock;
    int done; /* stop request; cleared only after the reader has fully exited */
    int reader_started; /* true while the detached reader owns the mouse fd */
    int mouse_lock;
    int mouse_fd;
} serviceContext;
serviceContext g_ctx;

/*
 * processing one time initialization:
 *     Cache the javaVM into our context
 * Note:
 *     All resources allocated here are never released by application
 *     we rely on system to free all global refs when it goes away;
 *     the pairing function JNI_OnUnload() never gets called at all.
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    setlinebuf(stdout);
    JNIEnv* env;
    memset(&g_ctx, 0, sizeof(g_ctx));

    g_ctx.javaVM = vm;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR; // JNI version not supported.
    }

    if (pthread_mutex_init(&g_ctx.lock, NULL) != 0) {
        return JNI_ERR;
    }
    g_ctx.done = 0;
    g_ctx.reader_started = 0;
    g_ctx.mouse_fd = -1;
    g_ctx.inputServiceObj = NULL;
    g_ctx.inputServiceClz = NULL;
    return  JNI_VERSION_1_6;
}

/*
 * Main working thread function. From a pthread,
 *     calling back to InputService::updateTimer() to send mouse events
 */
static void finish_mouse_reader(serviceContext *pctx, int mouse_fd, bool attached_here) {
    if (mouse_fd >= 0) {
        close(mouse_fd);
    }
    if (attached_here) {
        (*pctx->javaVM)->DetachCurrentThread(pctx->javaVM);
    }

    pthread_mutex_lock(&pctx->lock);
    if (pctx->mouse_fd == mouse_fd) {
        pctx->mouse_fd = -1;
    }
    pctx->done = 0;
    pctx->reader_started = 0;
    pthread_mutex_unlock(&pctx->lock);
}

/*
 * Poll with a bounded timeout so stopMouse() can terminate the reader even when
 * the physical mouse is idle or its device disappears.
 */
void* send_mouse_events(void* context) {
    serviceContext *pctx = (serviceContext *) context;
    JavaVM *javaVM = pctx->javaVM;
    JNIEnv *env = NULL;
    bool attached_here = false;

    pthread_mutex_lock(&pctx->lock);
    int mouse_fd = pctx->mouse_fd;
    pthread_mutex_unlock(&pctx->lock);
    if (mouse_fd < 0) {
        finish_mouse_reader(pctx, mouse_fd, false);
        return context;
    }

    jint res = (*javaVM)->GetEnv(javaVM, (void **)&env, JNI_VERSION_1_6);
    if (res != JNI_OK) {
        res = (*javaVM)->AttachCurrentThread(javaVM, &env, NULL);
        if (JNI_OK != res) {
            fprintf(stderr, "E: Failed to attach mouse reader thread to the JVM\n");
            finish_mouse_reader(pctx, mouse_fd, false);
            return context;
        }
        attached_here = true;
    }

    jmethodID mouseEvent = (*env)->GetMethodID(
        env, pctx->inputServiceClz, "sendMouseEvent", "(II)V"
    );
    if (mouseEvent == NULL) {
        fprintf(stderr, "E: Unable to resolve InputService.sendMouseEvent\n");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        finish_mouse_reader(pctx, mouse_fd, attached_here);
        return context;
    }

    int mouse_lock = 1;
    if (ioctl(mouse_fd, EVIOCGRAB, mouse_lock) == -1) {
        perror("EVIOCGRAB mouse device");
    }

    struct input_event ie;
    while (true) {
        pthread_mutex_lock(&pctx->lock);
        int stop_requested = pctx->done;
        int requested_lock = pctx->mouse_lock;
        pthread_mutex_unlock(&pctx->lock);
        if (stop_requested) {
            break;
        }

        if (requested_lock != mouse_lock) {
            mouse_lock = requested_lock;
            if (ioctl(mouse_fd, EVIOCGRAB, mouse_lock) == -1) {
                perror("EVIOCGRAB mouse device");
            }
        }

        struct pollfd poll_fd;
        poll_fd.fd = mouse_fd;
        poll_fd.events = POLLIN | POLLPRI;
        poll_fd.revents = 0;
        int poll_result = poll(&poll_fd, 1, 100);
        if (poll_result == 0) {
            continue;
        }
        if (poll_result < 0) {
            if (errno == EINTR) {
                continue;
            }
            perror("poll mouse device");
            break;
        }
        if (poll_fd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            if (!(poll_fd.revents & (POLLIN | POLLPRI))) {
                break;
            }
        }
        if (!(poll_fd.revents & (POLLIN | POLLPRI))) {
            continue;
        }

        ssize_t bytes = read(mouse_fd, &ie, sizeof(struct input_event));
        if (bytes < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            perror("read mouse device");
            break;
        }
        if (bytes != sizeof(struct input_event)) {
            if (bytes == 0) {
                fprintf(stderr, "I: Mouse input device reached EOF\n");
            }
            break;
        }

        switch (ie.code) {
            case REL_X:
            case REL_Y:
            case REL_WHEEL:
            case BTN_MOUSE:
            case BTN_RIGHT:
            case BTN_MIDDLE:
            case BTN_EXTRA:
            case BTN_SIDE:
                if (mouse_lock) {
                    (*env)->CallVoidMethod(
                        env, pctx->inputServiceObj, mouseEvent, ie.code, ie.value
                    );
                    if ((*env)->ExceptionCheck(env)) {
                        fprintf(stderr, "E: InputService.sendMouseEvent threw an exception\n");
                        (*env)->ExceptionClear(env);
                    }
                }
                break;
        }
    }

    finish_mouse_reader(pctx, mouse_fd, attached_here);
    return context;
}

int startMouseThread(JNIEnv *env, jobject thiz) {
    jclass localClass = (*env)->GetObjectClass(env, thiz);
    if (localClass == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }
    jclass globalClass = (*env)->NewGlobalRef(env, localClass);
    jobject globalObject = (*env)->NewGlobalRef(env, thiz);
    (*env)->DeleteLocalRef(env, localClass);
    if (globalClass == NULL || globalObject == NULL) {
        if (globalClass != NULL) (*env)->DeleteGlobalRef(env, globalClass);
        if (globalObject != NULL) (*env)->DeleteGlobalRef(env, globalObject);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }

    pthread_mutex_lock(&g_ctx.lock);
    g_ctx.inputServiceClz = globalClass;
    g_ctx.inputServiceObj = globalObject;
    g_ctx.done = 0;
    pthread_mutex_unlock(&g_ctx.lock);

    pthread_attr_t threadAttr;
    bool attr_initialized = false;
    int result = pthread_attr_init(&threadAttr);
    if (result == 0) {
        attr_initialized = true;
        result = pthread_attr_setdetachstate(&threadAttr, PTHREAD_CREATE_DETACHED);
    }
    if (result == 0) {
        pthread_t threadInfo;
        result = pthread_create(&threadInfo, &threadAttr, send_mouse_events, &g_ctx);
    }
    if (attr_initialized) {
        pthread_attr_destroy(&threadAttr);
    }
    if (result == 0) {
        return 1;
    }

    fprintf(stderr, "E: Failed to start mouse reader thread: %s\n", strerror(result));
    pthread_mutex_lock(&g_ctx.lock);
    jclass failedClass = g_ctx.inputServiceClz;
    jobject failedObject = g_ctx.inputServiceObj;
    g_ctx.inputServiceClz = NULL;
    g_ctx.inputServiceObj = NULL;
    g_ctx.reader_started = 0;
    g_ctx.done = 0;
    pthread_mutex_unlock(&g_ctx.lock);
    if (failedClass != NULL) (*env)->DeleteGlobalRef(env, failedClass);
    if (failedObject != NULL) (*env)->DeleteGlobalRef(env, failedObject);
    return 0;
}

JNIEXPORT jint JNICALL
Java_xtr_keymapper_server_InputService_openDevice(JNIEnv *env, jobject thiz, jstring device) {
    if (device == NULL) {
        return -1;
    }

    const char *evdev = (*env)->GetStringUTFChars(env, device, NULL);
    if (evdev == NULL) {
        return -1;
    }

    int mouse_fd = open(evdev, O_RDONLY);
    if (mouse_fd == -1) {
        perror("opening mouse device");
        (*env)->ReleaseStringUTFChars(env, device, evdev);
        return -1;
    }

    pthread_mutex_lock(&g_ctx.lock);
    if (g_ctx.reader_started || g_ctx.mouse_fd >= 0) {
        pthread_mutex_unlock(&g_ctx.lock);
        close(mouse_fd);
        fprintf(stderr, "W: Refusing to start a second mouse reader before the first stops\n");
        (*env)->ReleaseStringUTFChars(env, device, evdev);
        return -1;
    }
    g_ctx.mouse_fd = mouse_fd;
    g_ctx.reader_started = 1; /* includes the interval while pthread_create is in progress */
    g_ctx.done = 0;
    pthread_mutex_unlock(&g_ctx.lock);

    if (!startMouseThread(env, thiz)) {
        pthread_mutex_lock(&g_ctx.lock);
        if (g_ctx.mouse_fd == mouse_fd) {
            g_ctx.mouse_fd = -1;
        }
        g_ctx.reader_started = 0;
        g_ctx.done = 0;
        pthread_mutex_unlock(&g_ctx.lock);
        close(mouse_fd);
        mouse_fd = -1;
    }

    (*env)->ReleaseStringUTFChars(env, device, evdev);
    return mouse_fd;
}

/*
 * Interface to Java side to stop:
 */
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_stopMouse(JNIEnv *env, jobject thiz) {
    int should_wait = 0;
    int fd_to_close = -1;

    pthread_mutex_lock(&g_ctx.lock);
    should_wait = g_ctx.reader_started;
    if (should_wait) {
        g_ctx.done = 1;
    } else {
        fd_to_close = g_ctx.mouse_fd;
        g_ctx.mouse_fd = -1;
        g_ctx.done = 0;
    }
    pthread_mutex_unlock(&g_ctx.lock);

    if (fd_to_close >= 0) {
        close(fd_to_close);
    }

    if (should_wait) {
        struct timespec sleepTime;
        memset(&sleepTime, 0, sizeof(sleepTime));
        sleepTime.tv_nsec = 100000000;
        for (;;) {
            pthread_mutex_lock(&g_ctx.lock);
            int reader_running = g_ctx.reader_started;
            pthread_mutex_unlock(&g_ctx.lock);
            if (!reader_running) break;
            nanosleep(&sleepTime, NULL);
        }
    }

    /*
     * No reader is using these global references now. If no reader was ever started
     * (or it already exited after a disconnect), this path returns immediately.
     */
    pthread_mutex_lock(&g_ctx.lock);
    jclass serviceClass = g_ctx.inputServiceClz;
    jobject serviceObject = g_ctx.inputServiceObj;
    g_ctx.inputServiceClz = NULL;
    g_ctx.inputServiceObj = NULL;
    pthread_mutex_unlock(&g_ctx.lock);

    if (serviceClass != NULL) (*env)->DeleteGlobalRef(env, serviceClass);
    if (serviceObject != NULL) (*env)->DeleteGlobalRef(env, serviceObject);
}

JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_setMouseLock(JNIEnv *env, jobject thiz, jboolean lock) {
    pthread_mutex_lock(&g_ctx.lock);
    if (lock == JNI_TRUE) {
        g_ctx.mouse_lock = 1;
    } else {
        g_ctx.mouse_lock = 0;
    }
    pthread_mutex_unlock(&g_ctx.lock);
}