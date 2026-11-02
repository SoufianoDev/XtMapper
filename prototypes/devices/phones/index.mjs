const telephoneBase = "https://cdn.jsdelivr.net/npm/@sneas/telephone@1";

export const phoneDevices = [
  {
    id: "android",
    name: "Android phone",
    type: "phone",
    viewport: { width: 393, height: 852 },
    frame: { renderer: "css", padding: 9 },
    mockup: null
  },
  {
    id: "pixel",
    name: "Google Pixel 9 Pro",
    type: "phone",
    viewport: { width: 321, height: 716 },
    frame: {
      renderer: "telephone",
      tag: "pixel-9-pro",
      scriptUrl: telephoneBase + "/pixel-9-pro.js",
      viewBox: { width: 353, height: 745 },
      screenInsets: { left: 0.041, right: 0.048, top: 0.0205, bottom: 0.018 },
      mode: "dark"
    },
    mockup: null
  },
  {
    id: "galaxy",
    name: "Samsung Galaxy S24",
    type: "phone",
    viewport: { width: 360, height: 780 },
    frame: { renderer: "css", padding: 9 },
    mockup: {
      provider: "Mobile FIRST",
      sourcePageUrl: "https://www.webmobilefirst.com/en/mockups/samsung-galaxy-s24-2024/",
      asset: null,
      screenRect: null,
      licenseStatus: "pending-asset-specific-review"
    }
  },
  {
    id: "compact",
    name: "Compact Android",
    type: "phone",
    viewport: { width: 360, height: 800 },
    frame: { renderer: "css", padding: 9 },
    mockup: null
  }
];