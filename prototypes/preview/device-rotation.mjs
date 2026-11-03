import {
  calculatePreviewScale,
  calculateViewportPlacement,
  getCssFrameMetrics,
  getViewportForOrientation
} from "./device-scaling.mjs";

function snapshotRect(element) {
  if (!element) return null;
  const rect = element.getBoundingClientRect();
  return {
    left: rect.left,
    top: rect.top,
    centerX: rect.left + rect.width / 2,
    centerY: rect.top + rect.height / 2
  };
}

function getRectDelta(before, after) {
  if (!before || !after) return { x: 0, y: 0 };
  return {
    x: before.left - after.left,
    y: before.top - after.top
  };
}

function orientationAngle(orientation) {
  return orientation === "landscape" ? 90 : 0;
}

function setIframeViewport(iframe, viewport) {
  iframe.width = String(viewport.width);
  iframe.height = String(viewport.height);
  iframe.style.display = "block";
  iframe.style.width = "100%";
  iframe.style.height = "100%";
  iframe.style.minWidth = "0";
  iframe.style.minHeight = "0";
  iframe.style.maxWidth = "none";
  iframe.style.maxHeight = "none";
  iframe.style.border = "0";
  iframe.style.background = "#101114";
  iframe.setAttribute("scrolling", "no");
}

function setCenteredRotation(element, angle, scale = 1) {
  element.style.left = "50%";
  element.style.top = "50%";
  element.style.transformOrigin = "center center";
  element.style.transform =
    "translate(-50%, -50%) rotate(" + angle + "deg) scale(" + scale + ")";
}

function isValidScreenRectangle(rectangle, image) {
  return rectangle &&
    [rectangle.x, rectangle.y, rectangle.width, rectangle.height].every(Number.isFinite) &&
    rectangle.x >= 0 &&
    rectangle.y >= 0 &&
    rectangle.width > 0 &&
    rectangle.height > 0 &&
    rectangle.x + rectangle.width <= image.naturalWidth &&
    rectangle.y + rectangle.height <= image.naturalHeight;
}

function createViewportLayer(className, viewport, angle, scale) {
  const layer = document.createElement("div");
  layer.className = className + " device-rotation-viewport";
  layer.style.position = "absolute";
  layer.style.left = "50%";
  layer.style.top = "50%";
  layer.style.width = viewport.width + "px";
  layer.style.height = viewport.height + "px";
  layer.style.transformOrigin = "center center";
  setCenteredRotation(layer, angle, scale);
  layer.style.overflow = "hidden";
  layer.style.background = "#101114";
  return layer;
}

const ROTATION_STYLES = `
#deviceFrame.device-frame {
  top: 50% !important;
  left: 50% !important;
  transform: translate(-50%, -50%) scale(var(--device-scale, 1)) !important;
  transform-origin: center center !important;
}
#deviceFrame.device-frame--css {
  padding: 0 !important;
  border: 0 !important;
  border-radius: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  overflow: visible !important;
}
#deviceFrame.device-frame--css::before {
  display: none !important;
}
.device-rotation-shell {
  position: absolute;
  left: 50%;
  top: 50%;
  transform-origin: center center;
}
.device-css-shell {
  padding: 9px;
  border: 1px solid rgba(255,255,255,.13);
  border-radius: 43px;
  background: linear-gradient(145deg, #393940, #08090d 35%, #25252d 76%, #090a0e);
  box-shadow: 0 40px 100px rgba(0,0,0,.66), 0 0 0 1px rgba(255,255,255,.035), inset 0 1px 0 rgba(255,255,255,.12);
}
.device-css-shell::before {
  content: "";
  position: absolute;
  z-index: 2;
  top: 10px;
  left: 50%;
  width: 92px;
  height: 19px;
  transform: translateX(-50%);
  border-radius: 0 0 13px 13px;
  background: #08090d;
  pointer-events: none;
}
.device-css-shell--tablet {
  padding: 12px;
  border-radius: 28px;
}
.device-css-shell--tablet::before {
  display: none;
}
.device-screen {
  position: absolute;
  overflow: hidden;
  background: #101114;
}
.device-frame--mockup {
  overflow: visible;
}
.device-rotation-shell.device-mockup {
  inset: auto;
}
.device-mockup__screen {
  position: absolute;
  z-index: 0;
  overflow: hidden;
  background: #101114;
}
.device-mockup__frame {
  display: block;
  position: absolute;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
  object-fit: fill;
  pointer-events: none;
}
.device-rotation-viewport {
  position: absolute;
  left: 50%;
  top: 50%;
  overflow: hidden;
  background: #101114;
  transform-origin: center center;
}
.device-rotation-viewport iframe {
  display: block;
  width: 100% !important;
  height: 100% !important;
  min-width: 0;
  min-height: 0;
  max-width: none;
  max-height: none;
  border: 0;
  background: #101114;
}
`;


function calculateRotationLayerScale(screenSize, viewport, orientation) {
  const availableWidth = orientation === "landscape"
    ? screenSize.height
    : screenSize.width;
  const availableHeight = orientation === "landscape"
    ? screenSize.width
    : screenSize.height;
  return Math.min(
    availableWidth / viewport.width,
    availableHeight / viewport.height
  );
}

function installRotationStyles() {
  if (document.getElementById("xtmapper-device-rotation-styles")) return;
  const style = document.createElement("style");
  style.id = "xtmapper-device-rotation-styles";
  style.textContent = ROTATION_STYLES;
  document.head.appendChild(style);
}

export function installDeviceRotation(DevicePreview) {
  installRotationStyles();
  DevicePreview.prototype.renderMockup = async function (device, viewport, generation) {
    const mockup = device.mockup;
    const rectangle = mockup.screenRect;
    const landscape = this.orientation === "landscape";
    const screenOrientation = this.rotationRenderOrientation || this.orientation;
    const screenViewport = this.rotationRenderViewport || viewport;
    const shellAngle = Number.isFinite(this.rotationRenderAngle)
      ? this.rotationRenderAngle
      : orientationAngle(this.orientation);
    const baseViewport = getViewportForOrientation(screenViewport, "portrait");
    const image = new Image();

    image.decoding = "async";
    image.alt = "";
    image.draggable = false;
    image.src = mockup.asset;

    if (typeof image.decode === "function") {
      await image.decode();
    } else {
      await new Promise((resolve, reject) => {
        image.onload = resolve;
        image.onerror = reject;
      });
    }

    if (generation !== this.renderGeneration) return;
    if (!isValidScreenRectangle(rectangle, image)) {
      throw new Error("Mockup screen rectangle is missing or outside the image.");
    }

    const assetScale = Math.min(
      baseViewport.width / rectangle.width,
      baseViewport.height / rectangle.height
    );
    const shellWidth = image.naturalWidth * assetScale;
    const shellHeight = image.naturalHeight * assetScale;
    const outerWidth = landscape ? shellHeight : shellWidth;
    const outerHeight = landscape ? shellWidth : shellHeight;
    const screenWidth = rectangle.width * assetScale;
    const screenHeight = rectangle.height * assetScale;
    const placement = calculateViewportPlacement(
      screenWidth,
      screenHeight,
      screenViewport.width,
      screenViewport.height,
      screenOrientation
    );
    const targetPlacement = calculateViewportPlacement(
      screenWidth,
      screenHeight,
      viewport.width,
      viewport.height,
      this.orientation
    );

    const shell = document.createElement("div");
    shell.className = "device-rotation-shell device-mockup";
    shell.style.width = shellWidth + "px";
    shell.style.height = shellHeight + "px";
    setCenteredRotation(shell, shellAngle);

    const screen = document.createElement("div");
    screen.className = "device-mockup__screen";
    screen.style.left = rectangle.x * assetScale + "px";
    screen.style.top = rectangle.y * assetScale + "px";
    screen.style.width = screenWidth + "px";
    screen.style.height = screenHeight + "px";
    screen.style.borderRadius =
      screenWidth * (mockup.screenRadiusRatio || 0.04) + "px";

    const viewportLayer = createViewportLayer(
      "device-mockup__viewport",
      screenViewport,
      -shellAngle,
      placement.scale
    );

    setIframeViewport(this.iframe, screenViewport);
    viewportLayer.appendChild(this.iframe);
    screen.appendChild(viewportLayer);

    image.className = "device-mockup__frame";
    shell.append(screen, image);

    this.frame.className = "device-frame device-frame--mockup" +
      (landscape ? " device-frame--landscape" : "");
    this.frame.replaceChildren(shell);
    this.frame.style.width = outerWidth + "px";
    this.frame.style.height = outerHeight + "px";

    this.rotationShell = shell;
    this.rotationViewportLayer = viewportLayer;
    this.rotationLayerScale = placement.scale;
    this.rotationTargetLayerScale = targetPlacement.scale;
    this.rotationScreenSize = { width: screenWidth, height: screenHeight };
    this.frameSize = {
      width: outerWidth,
      height: outerHeight,
      engine: mockup.provider || "Device mockup"
    };
    this.fitCurrentFrame();
  };

  DevicePreview.prototype.renderCss = function (device, viewport, generation) {
    if (generation !== this.renderGeneration) return;

    const landscape = this.orientation === "landscape";
    const screenOrientation = this.rotationRenderOrientation || this.orientation;
    const screenViewport = this.rotationRenderViewport || viewport;
    const shellAngle = Number.isFinite(this.rotationRenderAngle)
      ? this.rotationRenderAngle
      : orientationAngle(this.orientation);
    const padding = device.frame && Number.isFinite(device.frame.padding)
      ? device.frame.padding
      : (device.type === "tablet" ? 12 : 9);
    const baseViewport = getViewportForOrientation(screenViewport, "portrait");
    const metrics = getCssFrameMetrics(baseViewport, padding);
    const outerWidth = landscape ? metrics.frameHeight : metrics.frameWidth;
    const outerHeight = landscape ? metrics.frameWidth : metrics.frameHeight;
    const placement = calculateViewportPlacement(
      baseViewport.width,
      baseViewport.height,
      screenViewport.width,
      screenViewport.height,
      screenOrientation
    );
    const targetPlacement = calculateViewportPlacement(
      baseViewport.width,
      baseViewport.height,
      viewport.width,
      viewport.height,
      this.orientation
    );

    const shell = document.createElement("div");
    shell.className = "device-rotation-shell device-css-shell" +
      (device.type === "tablet" ? " device-css-shell--tablet" : "");
    shell.style.width = metrics.frameWidth + "px";
    shell.style.height = metrics.frameHeight + "px";
    setCenteredRotation(shell, shellAngle);

    const screen = document.createElement("div");
    screen.className = "device-screen";
    screen.style.top = padding + "px";
    screen.style.left = padding + "px";
    screen.style.width = baseViewport.width + "px";
    screen.style.height = baseViewport.height + "px";
    screen.style.borderRadius = device.type === "tablet" ? "18px" : "34px";

    const viewportLayer = createViewportLayer(
      "device-screen__viewport",
      screenViewport,
      -shellAngle,
      placement.scale
    );

    setIframeViewport(this.iframe, screenViewport);
    viewportLayer.appendChild(this.iframe);
    screen.appendChild(viewportLayer);
    shell.appendChild(screen);

    this.frame.className = "device-frame device-frame--css" +
      (device.type === "tablet" ? " device-frame--tablet" : "") +
      (landscape ? " device-frame--landscape" : "");
    this.frame.replaceChildren(shell);
    this.frame.style.width = outerWidth + "px";
    this.frame.style.height = outerHeight + "px";

    this.rotationShell = shell;
    this.rotationViewportLayer = viewportLayer;
    this.rotationLayerScale = placement.scale;
    this.rotationTargetLayerScale = targetPlacement.scale;
    this.rotationScreenSize = { width: baseViewport.width, height: baseViewport.height };
    this.frameSize = {
      width: outerWidth,
      height: outerHeight,
      engine: "Built-in Android frame"
    };
    this.fitCurrentFrame();
  };

  DevicePreview.prototype.fitCurrentFrame = function () {
    if (!this.frameSize || !this.stage || !this.frame) return;
    if (this.rotationTransitionActive && this.rotationTransitionReady) return;

    const preview = this.preview || this.stage.parentElement;
    const showcase = this.showcase || preview.parentElement;
    const viewportHeight = window.visualViewport
      ? Math.min(window.innerHeight, window.visualViewport.height)
      : window.innerHeight;
    const scrollTop = window.scrollY || window.pageYOffset || 0;
    const stageRect = this.stage.getBoundingClientRect();
    const stageDocumentTop = stageRect.top + scrollTop;
    const previewStyles = getComputedStyle(preview);
    const showcaseStyles = getComputedStyle(showcase);
    const rowGap = Number.parseFloat(previewStyles.rowGap) || 0;
    const captionHeight = this.caption
      ? this.caption.getBoundingClientRect().height
      : 0;
    const bottomPadding = Number.parseFloat(showcaseStyles.paddingBottom) || 0;
    const bottomSafetySpace = 28;
    const previewGaps = rowGap * 2;
    const landscape = this.orientation === "landscape";
    const availableWidth = Math.max(1, preview.clientWidth - (landscape ? 24 : 12));
    const baseAvailableHeight = Math.max(
      1,
      viewportHeight - stageDocumentTop - previewGaps -
        captionHeight - bottomPadding - bottomSafetySpace
    );
    const portrait = !landscape;
    const isTablet = this.currentDevice.type === "tablet";
    const isFoldable = this.currentDevice.type === "foldable";
    const tabletPortraitScaleLimit = Math.min(
      0.58,
      (baseAvailableHeight / this.frameSize.height) * 1.08
    );
    const portraitScaleLimit = isTablet
      ? tabletPortraitScaleLimit
      : (isFoldable ? 0.56 : 0.74);
    const availableHeight = portrait
      ? Math.max(baseAvailableHeight, this.frameSize.height * portraitScaleLimit)
      : baseAvailableHeight;
    const scale = calculatePreviewScale(
      this.frameSize.width,
      this.frameSize.height,
      availableWidth,
      availableHeight,
      portrait ? portraitScaleLimit : 1
    );

    const stageCenterY = stageRect.top + stageRect.height / 2;
    const topCenteredSpace = Math.max(1, stageCenterY - 28);
    const bottomCenteredSpace = Math.max(1, viewportHeight - stageCenterY - bottomSafetySpace);
    const centeredRotationHeight = Math.max(
      1,
      Math.min(baseAvailableHeight, topCenteredSpace * 2, bottomCenteredSpace * 2)
    );

    this.previewFitBounds = {
      width: availableWidth,
      height: centeredRotationHeight
    };
    this.previewScale = scale;
    this.stage.style.width = this.frameSize.width * scale + "px";
    this.stage.style.height = this.frameSize.height * scale + "px";
    this.stage.style.setProperty("--stage-width", this.frameSize.width * scale + "px");
    this.stage.style.setProperty("--stage-height", this.frameSize.height * scale + "px");
    this.frame.style.width = this.frameSize.width + "px";
    this.frame.style.height = this.frameSize.height + "px";
    this.frame.style.setProperty("--device-scale", String(scale));

    if (this.caption) {
      this.caption.textContent = this.currentDevice.name + " · " +
        this.currentViewport.width + " × " + this.currentViewport.height +
        " CSS px · " + this.orientation + " · " + this.frameSize.engine;
    }
  };

  DevicePreview.prototype.rotate = async function () {
    if (this.motionBusy || !this.rotationShell || !this.rotationViewportLayer) return;

    const previousViewport = { ...this.currentViewport };
    const previousOrientation = this.orientation;
    const previousFrameSize = { ...this.frameSize };
    const previousScale = this.previewScale || 1;
    const previousLayerScale = this.rotationLayerScale || 1;
    const nextViewport = {
      width: previousViewport.height,
      height: previousViewport.width
    };
    const nextOrientation = nextViewport.width > nextViewport.height
      ? "landscape"
      : "portrait";
    const startAngle = orientationAngle(previousOrientation);
    const targetAngle = orientationAngle(nextOrientation);
    const nextFrameSize = {
      width: previousFrameSize.height,
      height: previousFrameSize.width,
      engine: previousFrameSize.engine
    };
    const shell = this.rotationShell;
    const viewportLayer = this.rotationViewportLayer;
    const screenSize = this.rotationScreenSize || {
      width: Math.min(previousViewport.width, previousViewport.height),
      height: Math.max(previousViewport.width, previousViewport.height)
    };
    const nextLayerScale = calculateRotationLayerScale(
      screenSize,
      nextViewport,
      nextOrientation
    );

    const previousClasses = {
      landscape: this.preview?.classList.contains("preview--landscape") || false,
      portraitPhone: this.preview?.classList.contains("preview--portrait-phone") || false,
      showcaseLandscape: this.showcase?.classList.contains("showcase--landscape-preview") || false
    };
    const previousFrameStyle = this.frame.style.cssText;
    const previousStageStyle = this.stage.style.cssText;
    const previousCaption = this.caption?.textContent;
    const previousFitBounds = this.previewFitBounds;
    const previousPreviewScale = this.previewScale;
    const previousRotationLayerScale = this.rotationLayerScale;
    const previousRotationTargetLayerScale = this.rotationTargetLayerScale;

    const setOrientationClasses = (orientation) => {
      if (this.preview) {
        this.preview.classList.toggle("preview--landscape", orientation === "landscape");
        this.preview.classList.toggle(
          "preview--portrait-phone",
          orientation === "portrait" && this.currentDevice.type !== "tablet"
        );
      }
      if (this.showcase) {
        this.showcase.classList.toggle(
          "showcase--landscape-preview",
          orientation === "landscape"
        );
      }
    };

    // Measure the target layout without replacing the live iframe or changing
    // the visible frame. The same iframe node stays mounted throughout motion.
    let targetScale = previousScale;
    let targetFitBounds = previousFitBounds;
    try {
      this.currentViewport = nextViewport;
      this.orientation = nextOrientation;
      this.frameSize = nextFrameSize;
      setOrientationClasses(nextOrientation);
      this.fitCurrentFrame();
      targetScale = this.previewScale || previousScale;
      targetFitBounds = this.previewFitBounds || previousFitBounds;
    } finally {
      this.currentViewport = previousViewport;
      this.orientation = previousOrientation;
      this.frameSize = previousFrameSize;
      setOrientationClasses(previousOrientation);
      this.frame.style.cssText = previousFrameStyle;
      this.stage.style.cssText = previousStageStyle;
      if (this.caption) this.caption.textContent = previousCaption;
      this.previewFitBounds = previousFitBounds;
      this.previewScale = previousPreviewScale;
      this.rotationLayerScale = previousRotationLayerScale;
      this.rotationTargetLayerScale = previousRotationTargetLayerScale;
    }

    const diagonal = Math.hypot(
      Math.max(previousFrameSize.width, nextFrameSize.width),
      Math.max(previousFrameSize.height, nextFrameSize.height)
    );
    const viewportHeight = window.visualViewport
      ? Math.min(window.innerHeight, window.visualViewport.height)
      : window.innerHeight;
    const stageRect = this.stage.getBoundingClientRect();
    const stageCenterY = stageRect.top + stageRect.height / 2;
    const topCenteredSpace = Math.max(1, stageCenterY - 28);
    const bottomCenteredSpace = Math.max(1, viewportHeight - stageCenterY - 28);
    const rotationFitBounds = {
      width: Math.max(1, this.preview.clientWidth - 24),
      height: Math.max(
        1,
        Math.min(topCenteredSpace * 2, bottomCenteredSpace * 2)
      )
    };
    // Use the same diagonal-fit bounds in both directions. Orientation-specific
    // layout heights differ substantially, but the physical rotating device
    // should keep a consistent size while it passes through the diagonal pose.
    const rotationFitScale = Math.max(
      0.1,
      Math.min(
        previousScale,
        targetScale,
        rotationFitBounds.width / diagonal,
        rotationFitBounds.height / diagonal
      )
    );

    const commitOrientation = () => {
      this.currentViewport = nextViewport;
      this.orientation = nextOrientation;
      this.frameSize = nextFrameSize;
      this.rotationLayerScale = nextLayerScale;
      this.rotationTargetLayerScale = nextLayerScale;
      this.frame.style.width = nextFrameSize.width + "px";
      this.frame.style.height = nextFrameSize.height + "px";
      this.frame.style.setProperty("--device-scale", String(targetScale));
      setIframeViewport(this.iframe, nextViewport);
      setOrientationClasses(nextOrientation);
      this.saveState();
    };

    if (!this.shouldAnimate()) {
      const gsap = this.gsap;
      if (gsap) {
        gsap.set(shell, { rotation: targetAngle, clearProps: "willChange" });
        gsap.set(viewportLayer, {
          rotation: -targetAngle,
          width: nextViewport.width,
          height: nextViewport.height,
          scale: nextLayerScale,
          clearProps: "willChange"
        });
      } else {
        shell.style.transform = "translate(-50%, -50%) rotate(" + targetAngle + "deg)";
        viewportLayer.style.width = nextViewport.width + "px";
        viewportLayer.style.height = nextViewport.height + "px";
        viewportLayer.style.transform =
          "translate(-50%, -50%) rotate(" + (-targetAngle) + "deg) scale(" + nextLayerScale + ")";
      }
      commitOrientation();
      this.fitCurrentFrame();
      return;
    }

    const gsap = this.gsap;
    this.setMotionBusy(true);
    this.frame.style.willChange = "transform";
    shell.style.willChange = "transform";
    viewportLayer.style.willChange = "transform,width,height";

    try {
      gsap.set(shell, {
        rotation: startAngle,
        scale: 1,
        transformOrigin: "50% 50%"
      });
      gsap.set(viewportLayer, {
        rotation: -startAngle,
        scale: previousLayerScale,
        xPercent: -50,
        yPercent: -50,
        transformOrigin: "50% 50%"
      });

      await new Promise((resolve) => {
        const timeline = gsap.timeline({
          onComplete: resolve,
          onInterrupt: resolve
        });
        this.frame.__xtmapperRotationTimeline = timeline;

        timeline.to(shell, {
          rotation: targetAngle,
          duration: 0.9,
          ease: "power3.inOut"
        }, 0);

        // Reflow and counter-rotate the display during the second half in
        // both directions, mirroring how the physical device changes posture.
        timeline.to(viewportLayer, {
          rotation: -targetAngle,
          width: nextViewport.width,
          height: nextViewport.height,
          scale: nextLayerScale,
          duration: 0.45,
          ease: "power3.inOut"
        }, 0.45);

        timeline.to(this.frame, {
          "--device-scale": rotationFitScale,
          duration: 0.45,
          ease: "power2.in"
        }, 0);

        timeline.to(this.frame, {
          "--device-scale": targetScale,
          duration: 0.45,
          ease: "power2.out"
        }, 0.45);
      });

      const beforeLayout = {
        frame: snapshotRect(this.frame),
        stage: snapshotRect(this.stage),
        toolbar: snapshotRect(this.preview?.querySelector(".preview-toolbar")),
        caption: snapshotRect(this.caption)
      };

      commitOrientation();
      this.fitCurrentFrame();
      const settledScale = this.previewScale || targetScale;
      this.frame.style.setProperty("--device-scale", String(targetScale));

      const afterLayout = {
        frame: snapshotRect(this.frame),
        stage: snapshotRect(this.stage),
        toolbar: snapshotRect(this.preview?.querySelector(".preview-toolbar")),
        caption: snapshotRect(this.caption)
      };

      const frameDelta = {
        x: beforeLayout.frame.centerX - afterLayout.frame.centerX,
        y: beforeLayout.frame.centerY - afterLayout.frame.centerY
      };
      const flipElements = [
        { element: this.stage, before: beforeLayout.stage, after: afterLayout.stage, center: true },
        {
          element: this.preview?.querySelector(".preview-toolbar"),
          before: beforeLayout.toolbar,
          after: afterLayout.toolbar
        },
        { element: this.caption, before: beforeLayout.caption, after: afterLayout.caption }
      ];

      await new Promise((resolve) => {
        const timeline = gsap.timeline({
          onComplete: resolve,
          onInterrupt: resolve
        });

        flipElements.forEach((item) => {
          if (!item.element || !item.before || !item.after) return;

          const delta = item.center
            ? frameDelta
            : getRectDelta(item.before, item.after);
          const baseX = Number(gsap.getProperty(item.element, "x")) || 0;
          const baseY = Number(gsap.getProperty(item.element, "y")) || 0;

          if (Math.abs(delta.x) < 0.5 && Math.abs(delta.y) < 0.5) return;

          timeline.fromTo(item.element, {
            x: baseX + delta.x,
            y: baseY + delta.y
          }, {
            x: baseX,
            y: baseY,
            duration: 0.28,
            ease: "power2.out",
            clearProps: "transform"
          }, 0);
        });

        if (Math.abs(settledScale - targetScale) > 0.001) {
          timeline.to(this.frame, {
            "--device-scale": settledScale,
            duration: 0.28,
            ease: "power2.out"
          }, 0);
        }

        if (timeline.duration() === 0) resolve();
      });
    } catch (error) {
      console.warn("The device rotation animation failed; applying the requested orientation.", error);
      gsap.set(shell, { rotation: targetAngle });
      gsap.set(viewportLayer, {
        rotation: -targetAngle,
        width: nextViewport.width,
        height: nextViewport.height,
        scale: nextLayerScale
      });
      commitOrientation();
      this.fitCurrentFrame();
    } finally {
      gsap.set([shell, viewportLayer, this.frame], { clearProps: "willChange" });
      gsap.set([
        this.stage,
        this.preview?.querySelector(".preview-toolbar"),
        this.caption
      ].filter(Boolean), { clearProps: "transform" });
      delete this.frame.__xtmapperRotationTimeline;
      this.setMotionBusy(false);
      this.fitCurrentFrame();
    }
  };
}
