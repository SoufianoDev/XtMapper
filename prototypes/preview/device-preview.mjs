import { deviceGroups, getDeviceById } from "../devices/index.mjs";
import {
  calculatePreviewScale,
  calculateViewportPlacement,
  getCssFrameMetrics,
  getViewportForOrientation
} from "./device-scaling.mjs";

const PREVIEW_STATE_STORAGE_KEY = "xtmapper.device-preview.state.v1";

function isValidViewport(viewport) {
  return viewport &&
    Number.isInteger(viewport.width) &&
    Number.isInteger(viewport.height) &&
    viewport.width >= 280 && viewport.width <= 1600 &&
    viewport.height >= 400 && viewport.height <= 2400;
}

function createCustomDevice(viewport) {
  const type = Math.min(viewport.width, viewport.height) >= 600 ? "tablet" : "phone";
  return {
    id: "custom",
    name: "Custom Android viewport",
    type,
    viewport: { width: viewport.width, height: viewport.height },
    frame: { renderer: "css", padding: type === "tablet" ? 12 : 9 },
    mockup: null
  };
}

function readSavedPreviewState() {
  try {
    const raw = window.localStorage.getItem(PREVIEW_STATE_STORAGE_KEY);
    if (!raw) return null;

    const state = JSON.parse(raw);
    if (!state || state.version !== 1 ||
        (state.orientation !== "portrait" && state.orientation !== "landscape")) {
      return null;
    }

    if (state.deviceId === "custom") {
      return isValidViewport(state.customViewport)
        ? {
            deviceId: "custom",
            orientation: state.orientation,
            customViewport: {
              width: state.customViewport.width,
              height: state.customViewport.height
            }
          }
        : null;
    }

    return getDeviceById(state.deviceId)
      ? { deviceId: state.deviceId, orientation: state.orientation }
      : null;
  } catch (_) {
    // Storage can be unavailable in private/restricted browser contexts.
    // The preview remains usable with its default state in that case.
    return null;
  }
}

function setIframeViewport(iframe, viewport) {
  iframe.style.display = "block";
  iframe.width = String(viewport.width);
  iframe.height = String(viewport.height);
  iframe.style.width = viewport.width + "px";
  iframe.style.height = viewport.height + "px";
  iframe.style.minWidth = "0";
  iframe.style.minHeight = "0";
  iframe.style.maxWidth = "none";
  iframe.style.maxHeight = "none";
  iframe.style.border = "0";
  iframe.style.background = "#101114";
  iframe.setAttribute("scrolling", "no");
}

export class DevicePreview {
  constructor() {
    this.stage = document.getElementById("previewStage");
    this.preview = this.stage && this.stage.parentElement;
    this.showcase = this.preview && this.preview.parentElement;
    this.frame = document.getElementById("deviceFrame");
    this.iframe = document.getElementById("activityFrame");
    this.select = document.getElementById("devicePreset");
    this.rotateButton = document.getElementById("rotateDevice");
    this.caption = document.getElementById("previewCaption");
    this.currentDevice = getDeviceById("google-pixel-10-pro");
    this.currentViewport = { width: 410, height: 912 };
    this.orientation = "portrait";
    this.lastDeviceId = "google-pixel-10-pro";
    this.frameSize = null;
    this.renderGeneration = 0;
    this.resizeObserver = null;
    this.fitFrameRequest = 0;
    this.viewportResizeHandler = null;
    this.gsap = window.gsap || null;
    this.motionBusy = false;
  }

  init() {
    if (!this.stage || !this.frame || !this.iframe || !this.select || !this.rotateButton) {
      throw new Error("Device preview markup is incomplete.");
    }

    this.populateDeviceOptions();
    this.restoreSavedState();
    this.select.value = this.currentDevice.id;

    this.select.addEventListener("change", () => {
      if (this.select.value === "custom") {
        this.applyCustomSize();
        return;
      }

      this.selectDevice(this.select.value);
    });

    this.rotateButton.addEventListener("click", () => this.rotate());

    // Observe the preview container for element-level layout changes, but
    // always listen to viewport changes as well. A window can become shorter
    // without changing the preview element's intrinsic size, so ResizeObserver
    // alone cannot keep the frame inside the visible viewport.
    if ("ResizeObserver" in window) {
      this.resizeObserver = new ResizeObserver(() => this.scheduleFitCurrentFrame());
      this.resizeObserver.observe(this.preview || this.stage.parentElement);
    }

    this.viewportResizeHandler = () => this.scheduleFitCurrentFrame();
    window.addEventListener("resize", this.viewportResizeHandler, { passive: true });

    if (window.visualViewport) {
      window.visualViewport.addEventListener("resize", this.viewportResizeHandler, { passive: true });
    }

    return this.render();
  }

  populateDeviceOptions() {
    this.select.replaceChildren();

    deviceGroups.forEach((group) => {
      const optgroup = document.createElement("optgroup");
      optgroup.label = group.label;

      group.devices.forEach((device) => {
        const option = document.createElement("option");
        option.value = device.id;
        option.textContent = device.name + " · " +
          device.viewport.width + " × " + device.viewport.height;
        optgroup.appendChild(option);
      });

      this.select.appendChild(optgroup);
    });

    const customOption = document.createElement("option");
    customOption.value = "custom";
    customOption.textContent = "Custom size…";
    this.select.appendChild(customOption);
  }

  restoreSavedState() {
    const savedState = readSavedPreviewState();
    if (!savedState) return false;

    this.orientation = savedState.orientation;

    if (savedState.deviceId === "custom") {
      this.currentDevice = createCustomDevice(savedState.customViewport);
      this.currentViewport = { ...savedState.customViewport };
      return true;
    }

    const device = getDeviceById(savedState.deviceId);
    if (!device) return false;

    this.currentDevice = device;
    this.currentViewport = getViewportForOrientation(device.viewport, this.orientation);
    this.lastDeviceId = device.id;
    return true;
  }

  saveState() {
    const state = {
      version: 1,
      deviceId: this.currentDevice.id,
      orientation: this.orientation
    };

    if (this.currentDevice.id === "custom") {
      state.customViewport = { ...this.currentViewport };
    }

    try {
      window.localStorage.setItem(PREVIEW_STATE_STORAGE_KEY, JSON.stringify(state));
    } catch (_) {
      // Keep the live preview functional even when browser storage is blocked.
    }
  }

  shouldAnimate() {
    const reducedMotion = window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    return !!this.gsap && !reducedMotion;
  }

  setMotionBusy(busy) {
    this.motionBusy = busy;
    this.select.disabled = busy;
    this.rotateButton.disabled = busy;

    if (this.preview) {
      if (busy) this.preview.setAttribute("aria-busy", "true");
      else this.preview.removeAttribute("aria-busy");
    }
  }

  async animateFrameSwap(updateState) {
    if (this.motionBusy) return;

    if (!this.shouldAnimate()) {
      updateState();
      await this.render();
      return;
    }

    const gsap = this.gsap;
    let stateUpdated = false;
    this.setMotionBusy(true);
    this.frame.style.willChange = "opacity";

    try {
      await new Promise((resolve) => {
        gsap.to(this.frame, {
          autoAlpha: 0,
          duration: 0.16,
          ease: "power1.out",
          onComplete: resolve
        });
      });

      updateState();
      stateUpdated = true;
      await this.render();

      await new Promise((resolve) => {
        gsap.fromTo(
          this.frame,
          { autoAlpha: 0 },
          {
            autoAlpha: 1,
            duration: 0.24,
            ease: "power2.out",
            onComplete: resolve
          }
        );
      });
    } catch (error) {
      console.warn("Device switch animation failed; completing the preview update.", error);

      if (!stateUpdated) updateState();
      await this.render();
    } finally {
      gsap.set(this.frame, { clearProps: "opacity,visibility,willChange" });
      this.setMotionBusy(false);
      this.fitCurrentFrame();
    }
  }

  selectDevice(id) {
    const device = getDeviceById(id);
    if (!device || this.motionBusy) return;

    void this.animateFrameSwap(() => {
      this.currentDevice = device;
      // Device changes keep the current rotation; only the logical viewport
      // dimensions are adapted to the newly selected device.
      this.currentViewport = getViewportForOrientation(device.viewport, this.orientation);
      this.lastDeviceId = device.id;
      this.saveState();
    });
  }

  applyCustomSize() {
    const width = Number(prompt("Viewport width in CSS pixels", String(this.currentViewport.width)));
    if (!Number.isInteger(width) || width < 280 || width > 1600) {
      this.select.value = this.lastDeviceId;
      return;
    }

    const height = Number(prompt("Viewport height in CSS pixels", String(this.currentViewport.height)));
    if (!Number.isInteger(height) || height < 400 || height > 2400) {
      this.select.value = this.lastDeviceId;
      return;
    }

    const viewport = { width, height };
    const customDevice = createCustomDevice(viewport);
    const orientation = width > height ? "landscape" : "portrait";

    void this.animateFrameSwap(() => {
      this.currentViewport = viewport;
      this.currentDevice = customDevice;
      this.orientation = orientation;
      this.select.value = "custom";
      this.saveState();
    });
  }

  rotate() {
    if (this.motionBusy) return;

    const nextViewport = {
      width: this.currentViewport.height,
      height: this.currentViewport.width
    };
    const nextOrientation = nextViewport.width > nextViewport.height
      ? "landscape"
      : "portrait";

    const updateOrientation = () => {
      this.currentViewport = nextViewport;
      this.orientation = nextOrientation;
      this.saveState();
    };

    if (!this.shouldAnimate() || !this.frameSize) {
      updateOrientation();
      void this.render();
      return;
    }

    const gsap = this.gsap;
    const frameWidth = this.frameSize.width;
    const frameHeight = this.frameSize.height;
    const translateX = (frameHeight - frameWidth) / 2;
    const translateY = (frameWidth - frameHeight) / 2;
    const nextPortraitPhone = nextOrientation === "portrait" &&
      this.currentDevice.type !== "tablet";
    const nextStageOffset = nextPortraitPhone ? 24 : 0;

    this.setMotionBusy(true);
    this.frame.style.transformOrigin = "50% 50%";
    this.frame.style.willChange = "transform";
    this.stage.style.willChange = "transform";

    const timeline = gsap.timeline({
      onComplete: async () => {
        try {
          updateOrientation();
          await this.render();
        } catch (error) {
          console.error("Device rotation could not finish rendering.", error);
        } finally {
          gsap.set(this.frame, {
            clearProps: "transform,transformOrigin,willChange"
          });
          gsap.set(this.stage, {
            clearProps: "transform,willChange"
          });
          this.setMotionBusy(false);
          this.fitCurrentFrame();
        }
      }
    });

    timeline.to(this.frame, {
      rotation: nextOrientation === "landscape" ? 90 : -90,
      x: translateX,
      y: translateY,
      duration: 0.64,
      ease: "power3.inOut"
    }, 0);

    timeline.to(this.stage, {
      x: nextStageOffset,
      duration: 0.64,
      ease: "power2.inOut"
    }, 0);
  }

  async render() {
    const landscape = this.orientation === "landscape";
    const generation = ++this.renderGeneration;
    const device = this.currentDevice;
    const portraitPhone = !landscape && device.type !== "tablet";
    if (this.preview) {
      this.preview.classList.toggle("preview--landscape", landscape);
      this.preview.classList.toggle("preview--portrait-phone", portraitPhone);
    }
    if (this.showcase) this.showcase.classList.toggle("showcase--landscape-preview", landscape);

    const viewport = getViewportForOrientation(this.currentViewport, this.orientation);

    if (device.mockup && device.mockup.asset && device.mockup.screenRect) {
      try {
        await this.renderMockup(device, viewport, generation);
        return;
      } catch (error) {
        if (generation !== this.renderGeneration) return;
        console.warn("External device mockup unavailable; using the built-in Android frame.", error);
      }
    }

    this.renderCss(device, viewport, generation);
  }

  async renderMockup(device, viewport, generation) {
    const mockup = device.mockup;
    const rectangle = mockup.screenRect;
    const landscape = this.orientation === "landscape";
    const frameViewport = landscape
      ? { width: viewport.height, height: viewport.width }
      : viewport;
    const image = new Image();

    image.decoding = "async";
    image.alt = "";
    image.draggable = false;
    image.src = mockup.asset;

    if (typeof image.decode === "function") {
      await image.decode();
    } else {
      await new Promise(function (resolve, reject) {
        image.onload = resolve;
        image.onerror = reject;
      });
    }

    if (generation !== this.renderGeneration) return;

    const validRectangle = rectangle &&
      [rectangle.x, rectangle.y, rectangle.width, rectangle.height].every(Number.isFinite) &&
      rectangle.x >= 0 && rectangle.y >= 0 &&
      rectangle.width > 0 && rectangle.height > 0 &&
      rectangle.x + rectangle.width <= image.naturalWidth &&
      rectangle.y + rectangle.height <= image.naturalHeight;

    if (!validRectangle) {
      throw new Error("Mockup screen rectangle is missing or outside the image.");
    }

    // Fit the original portrait mockup around its measured transparent screen
    // opening. Keep the iframe's logical viewport independent of preview scale.
    const assetScale = Math.min(
      frameViewport.width / rectangle.width,
      frameViewport.height / rectangle.height
    );
    const frameWidth = image.naturalWidth * assetScale;
    const frameHeight = image.naturalHeight * assetScale;
    const outerWidth = landscape ? frameHeight : frameWidth;
    const outerHeight = landscape ? frameWidth : frameHeight;
    const screenWidth = rectangle.width * assetScale;
    const screenHeight = rectangle.height * assetScale;
    const placement = calculateViewportPlacement(
      screenWidth,
      screenHeight,
      viewport.width,
      viewport.height,
      this.orientation
    );

    const mockupFrame = document.createElement("div");
    mockupFrame.className = "device-mockup";
    mockupFrame.style.position = "absolute";
    mockupFrame.style.left = (outerWidth - frameWidth) / 2 + "px";
    mockupFrame.style.top = (outerHeight - frameHeight) / 2 + "px";
    mockupFrame.style.width = frameWidth + "px";
    mockupFrame.style.height = frameHeight + "px";
    mockupFrame.style.right = "auto";
    mockupFrame.style.bottom = "auto";
    mockupFrame.style.transformOrigin = "center center";
    mockupFrame.style.transform = landscape ? "rotate(90deg)" : "none";

    const screenSurface = document.createElement("div");
    screenSurface.className = "device-mockup__screen";
    screenSurface.style.left = rectangle.x * assetScale + "px";
    screenSurface.style.top = rectangle.y * assetScale + "px";
    screenSurface.style.width = screenWidth + "px";
    screenSurface.style.height = screenHeight + "px";
    screenSurface.style.borderRadius =
      screenWidth * (mockup.screenRadiusRatio || 0.04) + "px";

    const viewportLayer = document.createElement("div");
    viewportLayer.className = "device-mockup__viewport";
    viewportLayer.style.position = "absolute";
    viewportLayer.style.left = placement.left + "px";
    viewportLayer.style.top = placement.top + "px";
    viewportLayer.style.width = viewport.width + "px";
    viewportLayer.style.height = viewport.height + "px";
    viewportLayer.style.transformOrigin = "center center";
    viewportLayer.style.transform = placement.transform;
    viewportLayer.style.overflow = "hidden";
    viewportLayer.style.background = "#101114";

    setIframeViewport(this.iframe, viewport);
    viewportLayer.appendChild(this.iframe);
    screenSurface.appendChild(viewportLayer);

    image.className = "device-mockup__frame";
    mockupFrame.append(screenSurface, image);

    this.frame.className = "device-frame device-frame--mockup" +
      (landscape ? " device-frame--landscape" : "");
    this.frame.replaceChildren(mockupFrame);
    this.frame.style.width = outerWidth + "px";
    this.frame.style.height = outerHeight + "px";

    this.frameSize = {
      width: outerWidth,
      height: outerHeight,
      engine: mockup.provider || "Device mockup"
    };
    this.fitCurrentFrame();
  }

  renderCss(device, viewport, generation) {
    if (generation !== this.renderGeneration) return;

    const padding = device.frame && Number.isFinite(device.frame.padding)
      ? device.frame.padding
      : (device.type === "tablet" ? 12 : 9);
    const metrics = getCssFrameMetrics(viewport, padding);
    const screen = document.createElement("div");

    screen.className = "device-screen";
    screen.style.top = padding + "px";
    screen.style.left = padding + "px";
    screen.style.width = viewport.width + "px";
    screen.style.height = viewport.height + "px";
    screen.style.borderRadius = device.type === "tablet" ? "18px" : "34px";
    screen.appendChild(this.iframe);
    setIframeViewport(this.iframe, viewport);

    this.frame.className = "device-frame device-frame--css" +
      (device.type === "tablet" ? " device-frame--tablet" : "") +
      (this.orientation === "landscape" ? " device-frame--landscape" : "");
    this.frame.replaceChildren(screen);
    this.frame.style.width = metrics.frameWidth + "px";
    this.frame.style.height = metrics.frameHeight + "px";

    this.frameSize = {
      width: metrics.frameWidth,
      height: metrics.frameHeight,
      engine: "Built-in Android frame"
    };
    this.fitCurrentFrame();
  }

  scheduleFitCurrentFrame() {
    if (this.fitFrameRequest) cancelAnimationFrame(this.fitFrameRequest);

    this.fitFrameRequest = requestAnimationFrame(() => {
      this.fitFrameRequest = 0;
      this.fitCurrentFrame();
    });
  }

  fitCurrentFrame() {
    if (!this.frameSize || !this.stage || !this.frame) return;

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

    // Use the stage's document position, not its current viewport-relative
    // top. That keeps the scale stable when the page has been scrolled and
    // resized, instead of treating a negative top as zero and oversizing it.
    // The preview has two grid gaps: toolbar -> stage and stage -> caption.
    const landscape = this.orientation === "landscape";
    const availableWidth = Math.max(1, preview.clientWidth - (landscape ? 24 : 12));
    const baseAvailableHeight = Math.max(
      1,
      viewportHeight - stageDocumentTop - previewGaps -
        captionHeight - bottomPadding - bottomSafetySpace
    );
    // Tune portrait sizing by device class: standard phones keep their
    // established size, unfolded foldables are reduced, and tablets gain a
    // small amount of preview size. Landscape sizing remains fit-to-space.
    const portrait = !landscape;
    const isTablet = this.currentDevice.type === "tablet";
    const isFoldable = this.currentDevice.type === "foldable";
    // Tablets grow by 8% over their normal fit, capped at 0.58 so they
    // remain balanced on tall displays and do not become oversized on short ones.
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
  }
}