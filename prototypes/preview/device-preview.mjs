import { deviceGroups, getDeviceById } from "../devices/index.mjs";
import {
  calculatePreviewScale,
  calculateViewportPlacement,
  getCssFrameMetrics,
  getTelephoneFrameMetrics,
  getViewportForOrientation
} from "./device-scaling.mjs";

const telephoneLoads = new Map();

function loadTelephoneElement(frame) {
  if (customElements.get(frame.tag)) return Promise.resolve();

  if (!telephoneLoads.has(frame.tag)) {
    const load = new Promise(function (resolve, reject) {
      const script = document.createElement("script");
      script.src = frame.scriptUrl;
      script.async = true;
      script.onload = resolve;
      script.onerror = function () {
        reject(new Error("Unable to load Telephone component: " + frame.tag));
      };
      document.head.appendChild(script);
    }).then(function () {
      return customElements.whenDefined(frame.tag);
    }).catch(function (error) {
      telephoneLoads.delete(frame.tag);
      throw error;
    });

    telephoneLoads.set(frame.tag, load);
  }

  return telephoneLoads.get(frame.tag);
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
    this.currentDevice = getDeviceById("android");
    this.currentViewport = { width: 393, height: 852 };
    this.orientation = "portrait";
    this.lastDeviceId = "android";
    this.frameSize = null;
    this.renderGeneration = 0;
    this.resizeObserver = null;
    this.fitFrameRequest = 0;
    this.viewportResizeHandler = null;
  }

  init() {
    if (!this.stage || !this.frame || !this.iframe || !this.select || !this.rotateButton) {
      throw new Error("Device preview markup is incomplete.");
    }

    this.populateDeviceOptions();
    this.select.value = "android";

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

  selectDevice(id) {
    const device = getDeviceById(id);
    if (!device) return;

    this.currentDevice = device;
    this.currentViewport = { width: device.viewport.width, height: device.viewport.height };
    this.orientation = this.currentViewport.width > this.currentViewport.height ? "landscape" : "portrait";
    this.lastDeviceId = device.id;
    this.render();
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

    const type = Math.min(width, height) >= 600 ? "tablet" : "phone";
    this.currentDevice = {
      id: "custom",
      name: "Custom Android viewport",
      type: type,
      viewport: { width: width, height: height },
      frame: { renderer: "css", padding: type === "tablet" ? 12 : 9 },
      mockup: null
    };
    this.currentViewport = { width: width, height: height };
    this.orientation = width > height ? "landscape" : "portrait";
    this.select.value = "custom";
    this.render();
  }

  rotate() {
    this.currentViewport = {
      width: this.currentViewport.height,
      height: this.currentViewport.width
    };
    this.orientation = this.currentViewport.width > this.currentViewport.height ? "landscape" : "portrait";
    this.render();
  }

  async render() {
    const landscape = this.orientation === "landscape";
    if (this.preview) this.preview.classList.toggle("preview--landscape", landscape);
    if (this.showcase) this.showcase.classList.toggle("showcase--landscape-preview", landscape);

    const generation = ++this.renderGeneration;
    const device = this.currentDevice;
    const viewport = getViewportForOrientation(this.currentViewport, this.orientation);

    if (device.mockup &&
        device.mockup.licenseStatus === "approved" &&
        device.mockup.asset &&
        device.mockup.screenRect) {
      try {
        await this.renderMockup(device, viewport, generation);
        return;
      } catch (error) {
        if (generation !== this.renderGeneration) return;
        console.warn("Device mockup unavailable; using the built-in Android frame.", error);
      }
    }

    if (device.frame.renderer === "telephone") {
      try {
        await this.renderTelephone(device, viewport, generation);
        return;
      } catch (error) {
        if (generation !== this.renderGeneration) return;
        console.warn("Telephone frame unavailable; using the built-in Android frame.", error);
      }
    }

    this.renderCss(device, viewport, generation);
  }

  async renderTelephone(device, viewport, generation) {
    const frame = device.frame;
    const landscape = this.orientation === "landscape";
    const frameViewport = landscape
      ? { width: viewport.height, height: viewport.width }
      : viewport;

    await loadTelephoneElement(frame);
    if (generation !== this.renderGeneration) return;

    const metrics = getTelephoneFrameMetrics(frame, frameViewport);
    const frameWidth = metrics.frameWidth;
    const frameHeight = metrics.frameHeight;
    const outerWidth = landscape ? frameHeight : frameWidth;
    const outerHeight = landscape ? frameWidth : frameHeight;

    // Keep the Telephone component in its native portrait coordinate space.
    // The stage handles centering and rotation without changing the component's
    // own width/height contract.
    const frameStage = document.createElement("div");
    frameStage.className = "device-frame__telephone-stage";
    frameStage.style.position = "absolute";
    frameStage.style.inset = "0";
    frameStage.style.display = "grid";
    frameStage.style.placeItems = "center";
    frameStage.style.width = "100%";
    frameStage.style.height = "100%";

    const telephone = document.createElement(frame.tag);
    telephone.setAttribute("mode", frame.mode || "dark");
    telephone.style.display = "block";
    telephone.style.position = "relative";
    telephone.style.width = frameWidth + "px";
    telephone.style.maxWidth = "none";
    telephone.style.height = "auto";
    telephone.style.transformOrigin = "center center";
    telephone.style.transform = landscape ? "rotate(90deg)" : "none";

    const placement = calculateViewportPlacement(
      metrics.screenWidth,
      metrics.screenHeight,
      viewport.width,
      viewport.height,
      this.orientation
    );

    // Telephone exposes its screen through a default slot. Keep the iframe in
    // light DOM so the component owns its frame, clipping and screen geometry.
    const viewportLayer = document.createElement("div");
    viewportLayer.className = "xtmapper-device-viewport";
    viewportLayer.style.position = "absolute";
    viewportLayer.style.left = placement.left + "px";
    viewportLayer.style.top = placement.top + "px";
    viewportLayer.style.width = viewport.width + "px";
    viewportLayer.style.height = viewport.height + "px";
    viewportLayer.style.transformOrigin = "center center";
    viewportLayer.style.transform = placement.transform;
    viewportLayer.style.overflow = "hidden";
    viewportLayer.style.pointerEvents = "auto";

    setIframeViewport(this.iframe, viewport);
    viewportLayer.appendChild(this.iframe);
    telephone.appendChild(viewportLayer);
    frameStage.appendChild(telephone);

    this.frame.className = "device-frame device-frame--telephone" +
      (landscape ? " device-frame--landscape" : "");
    this.frame.replaceChildren(frameStage);
    this.frame.style.width = outerWidth + "px";
    this.frame.style.height = outerHeight + "px";

    this.frameSize = {
      width: outerWidth,
      height: outerHeight,
      engine: "Telephone"
    };
    this.fitCurrentFrame();
  }

  async renderMockup(device, viewport, generation) {
    const mockup = device.mockup;
    const rectangle = mockup.screenRect;
    const landscape = this.orientation === "landscape";
    const frameViewport = landscape
      ? { width: viewport.height, height: viewport.width }
      : viewport;
    const image = new Image();

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
      throw new Error("Mockup screen rectangle is missing or outside the source image.");
    }

    const assetScale = frameViewport.width / rectangle.width;
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

    setIframeViewport(this.iframe, viewport);
    viewportLayer.appendChild(this.iframe);
    screenSurface.appendChild(viewportLayer);

    const overlay = document.createElement("img");
    overlay.className = "device-mockup__frame";
    overlay.src = image.src;
    overlay.alt = "";
    overlay.draggable = false;

    mockupFrame.append(screenSurface, overlay);
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
    const stageTop = Math.max(0, this.stage.getBoundingClientRect().top);
    const previewStyles = getComputedStyle(preview);
    const showcaseStyles = getComputedStyle(showcase);
    const rowGap = Number.parseFloat(previewStyles.rowGap) || 0;
    const captionHeight = this.caption
      ? this.caption.getBoundingClientRect().height
      : 0;
    const bottomPadding = Number.parseFloat(showcaseStyles.paddingBottom) || 0;
    const bottomSafetySpace = 20;

    // Measure the real space remaining below the stage instead of assuming
    // that every browser window has the same toolbar/header height.
    const availableWidth = Math.max(1, preview.clientWidth - 12);
    const availableHeight = Math.max(
      1,
      viewportHeight - stageTop - rowGap - captionHeight - bottomPadding - bottomSafetySpace
    );
    const scale = calculatePreviewScale(
      this.frameSize.width,
      this.frameSize.height,
      availableWidth,
      availableHeight
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