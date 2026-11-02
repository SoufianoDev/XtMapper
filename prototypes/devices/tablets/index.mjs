const telephoneBase = "https://cdn.jsdelivr.net/npm/@sneas/telephone@1";

export const tabletDevices = [
  {
    id: "tablet",
    name: "Android tablet",
    type: "tablet",
    viewport: { width: 800, height: 1280 },
    frame: {
      renderer: "telephone",
      tag: "android-tablet",
      scriptUrl: telephoneBase + "/android-tablet.js",
      viewBox: { width: 864, height: 1342 },
      screenInsets: { left: 0.034722, right: 0.037037, top: 0.022355, bottom: 0.022355 },
      mode: "dark"
    },
    mockup: null
  }
];