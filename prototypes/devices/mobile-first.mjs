const ASSET_BASE = "https://www.webmobilefirst.com/img/mockups/";

export function createMobileFirstDevice({
  id,
  name,
  type = "phone",
  viewport,
  asset,
  screenRect,
  sourcePageUrl,
  screenRadiusRatio
}) {
  return {
    id,
    name,
    type,
    viewport: { width: viewport.width, height: viewport.height },
    frame: { renderer: "mockup" },
    mockup: {
      provider: "Mobile FIRST",
      asset: ASSET_BASE + asset,
      screenRect: { ...screenRect },
      sourcePageUrl,
      screenRadiusRatio: screenRadiusRatio ?? (type === "tablet" ? 0.025 : 0.04),
      reuseNote: "Embedded from the original host; do not redistribute the image file separately."
    }
  };
}
