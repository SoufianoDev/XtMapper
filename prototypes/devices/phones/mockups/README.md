# Android Phone Mockups

Keep Android phone-frame PNG or WebP assets in this directory.

Do not add iPhone or other iOS mockups.

Before copying an asset into this public repository, verify that the terms on that exact device page permit local hosting and embedding in a public project. The Mobile FIRST collection page and individual mockup pages may use different wording. A collection-wide statement is not enough to establish the rights for a specific file.

After approval, add the asset path and measured screen rectangle to the matching entry in `index.mjs`. Resolve the asset relative to this module:

~~~js
mockup: {
  provider: "Mobile FIRST",
  sourcePageUrl: "https://www.webmobilefirst.com/en/mockups/samsung-galaxy-s24-2024/",
  asset: new URL("./mockups/samsung-galaxy-s24.png", import.meta.url).href,
  licenseStatus: "approved",
  screenRect: { x: 0, y: 0, width: 0, height: 0 }
}
~~~

Replace the screen rectangle with measured pixel coordinates from the actual image. Do not estimate its insets. The renderer validates that the rectangle stays inside the source image and fits the iframe viewport without changing its logical CSS dimensions.

The prototype deliberately ignores mockup records unless an asset path, measured screen rectangle, and approved usage status are all present.
