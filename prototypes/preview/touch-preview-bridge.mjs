const activityFrame = document.getElementById("activityFrame");
const previewOrigin = window.location.origin;

if (activityFrame) {
  let activeGesture = null;

  function cancelActivityGesture(reason) {
    if (!activeGesture) return;

    const gesture = activeGesture;
    activeGesture = null;

    activityFrame.contentWindow?.postMessage(
      {
        type: "xtmapper-touch-cancel",
        pointerId: gesture.pointerId,
        reason
      },
      previewOrigin
    );
  }

  window.addEventListener("message", event => {
    if (event.source !== activityFrame.contentWindow || event.origin !== previewOrigin) return;

    const message = event.data;
    if (!message || typeof message !== "object") return;

    if (message.type === "xtmapper-touch-gesture-start" &&
        message.pointerType === "mouse" &&
        Number.isInteger(message.pointerId)) {
      activeGesture = { pointerId: message.pointerId };
      return;
    }

    if ((message.type === "xtmapper-touch-gesture-end" ||
         message.type === "xtmapper-touch-gesture-cancelled") &&
        activeGesture &&
        message.pointerId === activeGesture.pointerId) {
      activeGesture = null;
    }
  });

  activityFrame.addEventListener("pointerleave", event => {
    if (activeGesture &&
        (typeof event.pointerId !== "number" ||
         event.pointerId === activeGesture.pointerId)) {
      cancelActivityGesture("left-device-screen");
    }
  });

  window.addEventListener("pointerup", event => {
    if (!activeGesture || event.pointerId !== activeGesture.pointerId) return;

    const rect = activityFrame.getBoundingClientRect();
    const releasedInsideFrame =
      event.clientX >= rect.left &&
      event.clientX <= rect.right &&
      event.clientY >= rect.top &&
      event.clientY <= rect.bottom;

    if (!releasedInsideFrame) {
      cancelActivityGesture("released-outside-device-screen");
    }
  }, true);

  window.addEventListener("pointercancel", event => {
    if (activeGesture && event.pointerId === activeGesture.pointerId) {
      cancelActivityGesture("parent-pointer-cancel");
    }
  }, true);

  window.addEventListener("blur", () => cancelActivityGesture("window-blur"));
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) cancelActivityGesture("document-hidden");
  });

  activityFrame.addEventListener("load", () => {
    cancelActivityGesture("activity-frame-reloaded");
  });
}
