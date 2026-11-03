import { createMobileFirstDevice as device } from "../mobile-first.mjs";

export const tabletDevices = [
  device({
    id: "galaxy-tab-s7",
    name: "Samsung Galaxy Tab S7",
    type: "tablet",
    viewport: { width: 800, height: 1280 },
    asset: "mockup-samsung-galaxy-tab-s7-transparent.webp",
    sourcePageUrl: "https://www.webmobilefirst.com/en/mockups/samsung-galaxy-tab-s7/",
    screenRect: { x: 27, y: 27, width: 466, height: 746 },
    screenRadiusRatio: 0.025
  })
];
