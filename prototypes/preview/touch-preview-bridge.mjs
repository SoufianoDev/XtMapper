export class TouchPreviewBridge {
  constructor() {
    this.frame = document.getElementById("activityFrame");
    this.origin = window.location.origin;
    this.activeGesture = null;
    this.initialized = false;
    this.handleMessage = this.handleMessage.bind(this);
    this.handleFrameLeave = this.handleFrameLeave.bind(this);
    this.handlePointerUp = this.handlePointerUp.bind(this);
    this.handlePointerCancel = this.handlePointerCancel.bind(this);
    this.handleBlur = this.handleBlur.bind(this);
    this.handleVisibilityChange = this.handleVisibilityChange.bind(this);
    this.handleFrameLoad = this.handleFrameLoad.bind(this);
  }

  init() {
    if (this.initialized || !this.frame) return;
    this.initialized = true;

    window.addEventListener("message", this.handleMessage);
    this.frame.addEventListener("pointerleave", this.handleFrameLeave);
    window.addEventListener("pointerup", this.handlePointerUp, true);
    window.addEventListener("pointercancel", this.handlePointerCancel, true);
    window.addEventListener("blur", this.handleBlur);
    document.addEventListener("visibilitychange", this.handleVisibilityChange);
    this.frame.addEventListener("load", this.handleFrameLoad);
  }

  cancel(reason) {
    if (!this.activeGesture) return;

    const gesture = this.activeGesture;
    this.activeGesture = null;

    this.frame.contentWindow?.postMessage(
      {
        type: "xtmapper-touch-cancel",
        pointerId: gesture.pointerId,
        reason
      },
      this.origin
    );
  }

  handleMessage(event) {
    if (event.source !== this.frame.contentWindow || event.origin !== this.origin) return;

    const message = event.data;
    if (!message || typeof message !== "object") return;

    if (message.type === "xtmapper-touch-gesture-start" &&
        message.pointerType === "mouse" &&
        Number.isInteger(message.pointerId)) {
      this.activeGesture = { pointerId: message.pointerId };
      return;
    }

    if ((message.type === "xtmapper-touch-gesture-end" ||
         message.type === "xtmapper-touch-gesture-cancelled") &&
        this.activeGesture &&
        message.pointerId === this.activeGesture.pointerId) {
      this.activeGesture = null;
    }
  }

  handleFrameLeave(event) {
    if (this.activeGesture &&
        (typeof event.pointerId !== "number" ||
         event.pointerId === this.activeGesture.pointerId)) {
      this.cancel("left-device-screen");
    }
  }

  handlePointerUp(event) {
    if (!this.activeGesture || event.pointerId !== this.activeGesture.pointerId) return;

    const rect = this.frame.getBoundingClientRect();
    const releasedInsideFrame =
      event.clientX >= rect.left &&
      event.clientX <= rect.right &&
      event.clientY >= rect.top &&
      event.clientY <= rect.bottom;

    if (!releasedInsideFrame) {
      this.cancel("released-outside-device-screen");
    }
  }

  handlePointerCancel(event) {
    if (this.activeGesture && event.pointerId === this.activeGesture.pointerId) {
      this.cancel("parent-pointer-cancel");
    }
  }

  handleBlur() {
    this.cancel("window-blur");
  }

  handleVisibilityChange() {
    if (document.hidden) this.cancel("document-hidden");
  }

  handleFrameLoad() {
    this.cancel("activity-frame-reloaded");
  }

  destroy() {
    if (!this.initialized) return;
    this.cancel("bridge-destroyed");
    window.removeEventListener("message", this.handleMessage);
    this.frame.removeEventListener("pointerleave", this.handleFrameLeave);
    window.removeEventListener("pointerup", this.handlePointerUp, true);
    window.removeEventListener("pointercancel", this.handlePointerCancel, true);
    window.removeEventListener("blur", this.handleBlur);
    document.removeEventListener("visibilitychange", this.handleVisibilityChange);
    this.frame.removeEventListener("load", this.handleFrameLoad);
    this.initialized = false;
  }
}
