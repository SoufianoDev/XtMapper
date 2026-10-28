package xtr.keymapper.editor

import xtr.keymapper.log.DiagnosticLog
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.ui.platform.ComposeView
import xtr.keymapper.R
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.server.RemoteServiceHelper

class EditorService : ComposeLifecycleService() {
    private var editor: EditorUI? = null
    private var composeView: ComposeView? = null

    private fun editorCallback() {
        editor = null
        composeView = null
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val selectedProfile = intent?.getStringExtra(EditorActivity.PROFILE_NAME)
            ?: xtr.keymapper.TouchPointer.currentSelectedProfile
            ?: "Default"
        DiagnosticLog.info("EditorService", "service_start_requested", "profile" to selectedProfile, "flags" to flags, "startId" to startId)

        val keymapConfig = KeymapConfig(this)
        RemoteServiceHelper.useShizuku = keymapConfig.isShizukuMode

        if (keymapConfig.editorOverlay) {

            val context = ContextThemeWrapper(
                this,
                R.style.Theme_XtMapper
            )

            // Create the ComposeView using the themed context.
            composeView = createComposeView(context)

            editor = EditorUI(
                context,
                this::editorCallback,
                selectedProfile,
                EditorUI.START_EDITOR,
                composeView
            )

            RemoteServiceHelper.getInstance(this) { remoteService ->
                DiagnosticLog.info("EditorService", "remote_service_callback", "available" to (remoteService != null), "profile" to selectedProfile)
                if (remoteService == null) {
                    Log.w("EditorService", "Remote input service unavailable; skipping key listener registration")
                    return@getInstance
                }
                try {
                    editor?.registerOnKeyEventListener(remoteService)
                } catch (e: RemoteException) {
                    Log.e("EditorService", e.message, e)
                }
            }

            editor!!.open(true)
        } else {
            val newIntent = Intent(this, EditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

            newIntent.putExtra(
                EditorActivity.PROFILE_NAME,
                selectedProfile
            )

            startActivity(newIntent)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        DiagnosticLog.debug("EditorService", "service_bind_requested")
        super.onBind(intent)
        return null
    }
}
