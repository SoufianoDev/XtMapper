import { deviceGroups, getDeviceById } from "../devices/index.mjs";
import {
  calculatePreviewScale,
  calculateScreenFit,
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

function nextFrame() {
  return new Promise(function (resolve) {
    requestAnimationFrame(resolve);
  });
}

function setIframeViewport(iframe, viewport) {
  iframe.style.display = "block";
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

    if ("ResizeObserver" in window) {
      this.resizeObserver = new ResizeObserver(() => this.fitCurrentFrame());
      this.resizeObserver.observe(this.stage.parentElement);
    } else {
      window.addEventListener("resize", () => this.fitCurrentFrame());
    }

    window.addEventListener("resize", () => this.fitCurrentFrame());
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

    const type = width >= 600 || height >= 600 && width > height ? "tablet" : "phone";
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
    const generation = ++this.renderGeneration;
    const device = this.currentDevice;
    const viewport = getViewportForOrientation(this.currentViewport, this.orientation);

    if (device.frame.renderer === "telephone" && this.orientation === "portrait") {
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
    await loadTelephoneElement(frame);
    if (generation !== this.renderGeneration) return;

    const metrics = getTelephoneFrameMetrics(frame, viewport);
    const telephone = document.createElement(frame.tag);
    telephone.setAttribute("mode", frame.mode || "dark");
    telephone.style.display = "block";
    telephone.style.position = "absolute";
    telephone.style.left = "0";
    telephone.style.top = "0";
    telephone.style.width = metrics.frameWidth + "px";

    const screenRoot = document.createElement("div");
    screenRoot.style.position = "absolute";
    screenRoot.style.transformOrigin = "top left";
    screenRoot.style.pointerEvents = "auto";
    screenRoot.style.overflow = "hidden";

    setIframeViewport(this.iframe, viewport);
    screenRoot.appendChild(this.iframe);
    telephone.appendChild(screenRoot);

    this.frame.className = "device-frame device-frame--telephone";
    this.frame.replaceChildren(telephone);

    await nextFrame();
    await nextFrame();
    if (generation !== this.renderGeneration) return;

    const screenshot = telephone.shadowRoot &&
      telephone.shadowRoot.querySelector(".screenshot");
    if (!screenshot) {
      throw new Error("Telephone screen slot was not found.");
    }

    const bounds = screenshot.getBoundingClientRect();
    const screenFit = calculateScreenFit(
      bounds.width,
      bounds.height,
      viewport.width,
      viewport.height
    );

    screenRoot.style.width = viewport.width + "px";
    screenRoot.style.height = viewport.height + "px";
    screenRoot.style.left = screenFit.offsetX + "px";
    screenRoot.style.top = screenFit.offsetY + "px";
    screenRoot.style.transform = "scale(" + screenFit.scale + ")";

    this.frameSize = {
      width: metrics.frameWidth,
      height: metrics.frameHeight,
      engine: "Telephone"
    };
    this.frame.style.width = metrics.frameWidth + "px";
    this.frame.style.height = metrics.frameHeight + "px";
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

  fitCurrentFrame() {
    if (!this.frameSize || !this.stage || !this.frame) return;

    const availableWidth = Math.max(220, this.stage.parentElement.clientWidth - 12);
    const availableHeight = Math.max(360, window.innerHeight - 150);
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