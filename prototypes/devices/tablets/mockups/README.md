# Android Tablet Mockups

Keep Android tablet-frame PNG or WebP assets in this directory.

Do not add iPad or other iOS mockups.

Before copying an asset into this public repository, verify that the terms on that exact device page permit local hosting and embedding in a public project. Mobile FIRST allows personal and commercial use on its individual model pages, but prohibits redistribution of the mockup file alone. Confirm that this repository integration falls within the permitted use or obtain written permission first.

After approval, add the asset path and measured screen rectangle to the matching entry in `index.mjs`. Resolve the asset relative to this module:

~~~js
mockup: {
  provider: "Mobile FIRST",
  sourcePageUrl: "https://www.webmobilefirst.com/en/mockups/samsung-galaxy-tab-s7/",
  asset: new URL("./mockups/samsung-galaxy-tab-s7.png", import.meta.url).href,
  licenseStatus: "approved",
  screenRect: { x: 0, y: 0, width: 0, height: 0 }
}
~~~

Replace the screen rectangle with measured pixel coordinates from the actual image. Do not estimate its insets. The renderer validates the rectangle before placing the activity iframe behind the transparent frame.
