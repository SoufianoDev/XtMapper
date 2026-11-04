const app = document.getElementById("app");
const mainScroller = document.querySelector("#app.phone > main");
const html = document.documentElement;
const gsap = window.gsap;
const ScrollTrigger = window.ScrollTrigger;

if (!app || !mainScroller) {
  console.error("XtMapper touch preview could not find its device viewport.");
} else {
  const cursor = document.createElement("div");
  cursor.className = "touch-preview-cursor";
  cursor.setAttribute("aria-hidden", "true");
  cursor.hidden = true;
  app.append(cursor);

  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const cursorX = gsap?.quickTo
    ? gsap.quickTo(cursor, "x", { duration: reduceMotion ? 0 : 0.075, ease: "power3.out" })
    : value => { cursor.style.left = value + "px"; };
  const cursorY = gsap?.quickTo
    ? gsap.quickTo(cursor, "y", { duration: reduceMotion ? 0 : 0.075, ease: "power3.out" })
    : value => { cursor.style.top = value + "px"; };

  let gesture = null;
  let inertiaTween = null;
  let suppressNextClick = false;

  function setCursorPosition(event) {
    const rect = app.getBoundingClientRect();
    const size = cursor.offsetWidth || 28;
    const x = Math.max(0, Math.min(rect.width - size, event.clientX - rect.left - size / 2));
    const y = Math.max(0, Math.min(rect.height - size, event.clientY - rect.top - size / 2));

    if (gsap?.quickTo) {
      cursorX(x);
      cursorY(y);
    } else {
      cursor.style.left = x + "px";
      cursor.style.top = y + "px";
    }
  }

  function showCursor(event) {
    if (event.pointerType !== "mouse" || !app.contains(event.target)) return;

    html.classList.add("touch-preview-pointer");
    cursor.hidden = false;
    setCursorPosition(event);

    if (gsap) {
      gsap.to(cursor, {
        autoAlpha: 1,
        duration: reduceMotion ? 0 : 0.12,
        overwrite: "auto"
      });
    } else {
      cursor.style.opacity = "1";
    }
  }

  function hideCursor() {
    html.classList.remove("touch-preview-pointer");

    if (gsap) {
      gsap.to(cursor, {
        autoAlpha: 0,
        duration: reduceMotion ? 0 : 0.09,
        overwrite: "auto",
        onComplete: () => {
          if (!html.classList.contains("touch-preview-pointer")) cursor.hidden = true;
        }
      });
    } else {
      cursor.style.opacity = "0";
      cursor.hidden = true;
    }
  }

  function stopInertia() {
    inertiaTween?.kill();
    inertiaTween = null;
  }

  function isNativeEditingTarget(target) {
    return target instanceof Element && Boolean(target.closest(
      'input, textarea, select, [contenteditable=""], [contenteditable="true"], [role="textbox"], input[type="range"]'
    ));
  }

  function canScrollInDirection(element, axis, pointerDelta) {
    const style = getComputedStyle(element);

    if (axis === "y") {
      if (!["auto", "scroll", "overlay"].includes(style.overflowY)) return false;
      const max = element.scrollHeight - element.clientHeight;
      if (max <= 1) return false;
      return pointerDelta < 0 ? element.scrollTop < max - 1 : element.scrollTop > 1;
    }

    if (!["auto", "scroll", "overlay"].includes(style.overflowX)) return false;
    const max = element.scrollWidth - element.clientWidth;
    if (max <= 1) return false;
    return pointerDelta < 0 ? element.scrollLeft < max - 1 : element.scrollLeft > 1;
  }

  function findScrollableElement(target, deltaX, deltaY) {
    const axis = Math.abs(deltaY) >= Math.abs(deltaX) ? "y" : "x";
    let element = target instanceof Element ? target : mainScroller;

    while (element && app.contains(element)) {
      if (canScrollInDirection(element, axis, axis === "y" ? deltaY : deltaX)) {
        return { element, axis };
      }
      if (element === mainScroller) break;
      element = element.parentElement;
    }

    if (element !== mainScroller &&
        canScrollInDirection(mainScroller, axis, axis === "y" ? deltaY : deltaX)) {
      return { element: mainScroller, axis };
    }

    return null;
  }

  function beginGesture(event) {
    if (event.pointerType !== "mouse" ||
        event.button !== 0 ||
        !app.contains(event.target) ||
        isNativeEditingTarget(event.target) ||
        gesture) {
      return;
    }

    stopInertia();
    showCursor(event);

    gesture = {
      pointerId: event.pointerId,
      phase: "pressed",
      startTarget: event.target,
      startX: event.clientX,
      startY: event.clientY,
      lastX: event.clientX,
      lastY: event.clientY,
      lastMoveAt: performance.now(),
      velocityX: 0,
      velocityY: 0,
      scroller: null
    };

    html.classList.add("touch-preview-pressed");
    if (gsap) {
      gsap.to(cursor, {
        scale: 0.76,
        duration: reduceMotion ? 0 : 0.1,
        ease: "power2.out",
        overwrite: "auto"
      });
    }

    window.parent.postMessage({
      type: "xtmapper-touch-gesture-start",
      pointerType: "mouse",
      pointerId: event.pointerId
    }, window.location.origin);
  }

  function moveGesture(event) {
    if (event.pointerType === "mouse" && app.contains(event.target)) {
      showCursor(event);
    }

    if (!gesture || event.pointerId !== gesture.pointerId) return;

    const now = performance.now();
    const elapsed = Math.max(1, now - gesture.lastMoveAt);
    let deltaX = event.clientX - gesture.lastX;
    let deltaY = event.clientY - gesture.lastY;

    if (gesture.phase === "pressed") {
      const totalX = event.clientX - gesture.startX;
      const totalY = event.clientY - gesture.startY;

      if (Math.max(Math.abs(totalX), Math.abs(totalY)) < 8) {
        gesture.lastX = event.clientX;
        gesture.lastY = event.clientY;
        gesture.lastMoveAt = now;
        return;
      }

      gesture.phase = "dragging";
      deltaX = totalX;
      deltaY = totalY;
      html.classList.add("touch-preview-dragging");
    }

    if (gesture.phase !== "dragging") return;

    const scrollTarget = findScrollableElement(
      gesture.startTarget,
      deltaX,
      deltaY
    );

    if (scrollTarget) {
      gesture.scroller = scrollTarget.element;

      if (scrollTarget.axis === "y") {
        const max = Math.max(0, gesture.scroller.scrollHeight - gesture.scroller.clientHeight);
        gesture.scroller.scrollTop = Math.max(0, Math.min(max, gesture.scroller.scrollTop - deltaY));
      } else {
        const max = Math.max(0, gesture.scroller.scrollWidth - gesture.scroller.clientWidth);
        gesture.scroller.scrollLeft = Math.max(0, Math.min(max, gesture.scroller.scrollLeft - deltaX));
      }

      event.preventDefault();
    }

    gesture.velocityX = deltaX / elapsed;
    gesture.velocityY = deltaY / elapsed;
    gesture.lastX = event.clientX;
    gesture.lastY = event.clientY;
    gesture.lastMoveAt = now;
  }

  function startInertia(completedGesture) {
    const scroller = completedGesture.scroller;
    if (!scroller || reduceMotion || !gsap) return;

    const vertical = Math.abs(completedGesture.velocityY) >= Math.abs(completedGesture.velocityX);
    const axis = vertical ? "y" : "x";
    const velocity = vertical ? completedGesture.velocityY : completedGesture.velocityX;
    if (Math.abs(velocity) < 0.12) return;

    const property = vertical ? "scrollTop" : "scrollLeft";
    const maximum = vertical
      ? Math.max(0, scroller.scrollHeight - scroller.clientHeight)
      : Math.max(0, scroller.scrollWidth - scroller.clientWidth);
    const current = scroller[property];
    const distance = Math.max(-260, Math.min(260, velocity * 240));
    const destination = Math.max(0, Math.min(maximum, current - distance));
    if (Math.abs(destination - current) < 2) return;

    const position = { value: current };
    inertiaTween = gsap.to(position, {
      value: destination,
      duration: Math.max(0.18, Math.min(0.42, Math.abs(velocity) * 0.42)),
      ease: "power2.out",
      onUpdate: () => {
        scroller[property] = position.value;
      },
      onComplete: () => {
        inertiaTween = null;
      }
    });
  }

  function finishGesture(event, cancelled = false, reason = "pointer-released") {
    if (!gesture || (event && event.pointerId !== gesture.pointerId)) return;

    const completedGesture = gesture;
    gesture = null;
    html.classList.remove("touch-preview-pressed", "touch-preview-dragging");

    if (gsap) {
      gsap.to(cursor, {
        scale: 1,
        duration: reduceMotion ? 0 : 0.14,
        ease: "power2.out",
        overwrite: "auto"
      });
    }

    if (cancelled) {
      stopInertia();
      hideCursor();
      window.parent.postMessage({
        type: "xtmapper-touch-gesture-cancelled",
        pointerId: completedGesture.pointerId,
        reason
      }, window.location.origin);
      return;
    }

    if (completedGesture.phase === "dragging") {
      suppressNextClick = true;
      window.setTimeout(() => {
        suppressNextClick = false;
      }, 0);
      startInertia(completedGesture);
    }

    window.parent.postMessage({
      type: "xtmapper-touch-gesture-end",
      pointerId: completedGesture.pointerId
    }, window.location.origin);
  }

  document.addEventListener("pointermove", moveGesture, {
    capture: true,
    passive: false
  });
  document.addEventListener("pointerdown", beginGesture, true);
  document.addEventListener("pointerup", event => finishGesture(event), true);
  document.addEventListener("pointercancel", event => {
    finishGesture(event, true, "pointer-cancelled");
  }, true);

  document.addEventListener("click", event => {
    if (!suppressNextClick || !app.contains(event.target)) return;
    suppressNextClick = false;
    event.preventDefault();
    event.stopImmediatePropagation();
  }, true);

  document.addEventListener("pointerout", event => {
    if (event.pointerType === "mouse" &&
        app.contains(event.target) &&
        (!event.relatedTarget || !app.contains(event.relatedTarget))) {
      if (gesture) finishGesture(null, true, "pointer-left-activity");
      hideCursor();
    }
  }, true);

  document.addEventListener("contextmenu", event => {
    if (app.contains(event.target) && !isNativeEditingTarget(event.target)) {
      event.preventDefault();
    }
  }, true);

  document.addEventListener("dragstart", event => {
    if (app.contains(event.target) && !isNativeEditingTarget(event.target)) {
      event.preventDefault();
    }
  }, true);

  app.addEventListener("wheel", stopInertia, { capture: true, passive: true });

  window.addEventListener("message", event => {
    if (event.source !== window.parent || event.origin !== window.location.origin) return;
    const message = event.data;
    if (message?.type !== "xtmapper-touch-cancel" ||
        !gesture ||
        message.pointerId !== gesture.pointerId) {
      return;
    }

    finishGesture(null, true, String(message.reason || "parent-cancelled"));
  });

  window.addEventListener("blur", () => {
    if (gesture) finishGesture(null, true, "window-blur");
    hideCursor();
  });

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      if (gesture) finishGesture(null, true, "document-hidden");
      hideCursor();
    }
  });

  window.__xtmapperTouchPreview = Object.freeze({
    get state() {
      return gesture?.phase || "idle";
    },
    get cursorVisible() {
      return !cursor.hidden && Number.parseFloat(getComputedStyle(cursor).opacity) > 0;
    },
    get activeScroller() {
      return gesture?.scroller?.id || gesture?.scroller?.className || null;
    }
  });
  window.__xtmapperTouchPreviewReady = true;

  if (gsap && ScrollTrigger) {
    gsap.registerPlugin(ScrollTrigger);
    const revealSelector = ".section-heading, .service-row, .data-row, .game-profile-card--grid";
    const motionMedia = gsap.matchMedia();

    motionMedia.add("(prefers-reduced-motion: no-preference)", () => {
      const revealContexts = [];
      const revealedElements = new WeakSet();

      function addRevealTriggers(root = mainScroller) {
        const candidates = [];
        if (root instanceof Element && root.matches(revealSelector)) candidates.push(root);
        if (root.querySelectorAll) candidates.push(...root.querySelectorAll(revealSelector));

        for (const element of candidates) {
          if (revealedElements.has(element)) continue;
          revealedElements.add(element);

          const context = gsap.context(() => {
            gsap.fromTo(element, {
              autoAlpha: 0,
              y: 12
            }, {
              autoAlpha: 1,
              y: 0,
              duration: 0.42,
              ease: "power2.out",
              overwrite: "auto",
              scrollTrigger: {
                trigger: element,
                scroller: mainScroller,
                start: "top 93%",
                toggleActions: "play none none none",
                once: true
              }
            });
          }, element);

          revealContexts.push(context);
        }
      }

      addRevealTriggers();

      let refreshFrame = 0;
      const observer = new MutationObserver(records => {
        let hasNewElements = false;

        for (const record of records) {
          for (const node of record.addedNodes) {
            if (node instanceof Element) {
              addRevealTriggers(node);
              hasNewElements = true;
            }
          }
        }

        if (!hasNewElements) return;
        cancelAnimationFrame(refreshFrame);
        refreshFrame = requestAnimationFrame(() => ScrollTrigger.refresh());
      });

      const handleImageLoad = () => ScrollTrigger.refresh();
      observer.observe(mainScroller, { childList: true, subtree: true });
      document.addEventListener("load", handleImageLoad, true);

      if (document.fonts?.ready) {
        document.fonts.ready.then(() => {
          if (mainScroller.isConnected) ScrollTrigger.refresh();
        });
      }

      return () => {
        observer.disconnect();
        document.removeEventListener("load", handleImageLoad, true);
        cancelAnimationFrame(refreshFrame);
        revealContexts.splice(0).forEach(context => context.revert());
      };
    });

    window.__xtmapperScrollTriggerReady = true;
  } else {
    window.__xtmapperScrollTriggerReady = false;
  }
}
