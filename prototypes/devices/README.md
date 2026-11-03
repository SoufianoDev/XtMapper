# Android device mockups

The device preview uses the transparent WebP mockups from [Mobile FIRST](https://www.webmobilefirst.com/en/mockups/).

## Asset handling

- Mockup images are referenced from their original Mobile FIRST URLs.
- Image binaries are intentionally not mirrored into this repository. The product terms permit personal and commercial use and modification/cropping, but prohibit redistribution of the image file on its own.
- Do not download these assets into `prototypes/`, package them into a release, or re-host them unless Mobile FIRST grants separate redistribution permission.
- The browser places the app iframe beneath the transparent mockup image. The image is the visual frame; XtMapper owns the iframe sizing, screen placement, rotation, clipping, and preview scaling.

## Screen geometry

Each device profile stores:

- The CSS viewport used by the app iframe.
- The source WebP URL.
- `screenRect`, the transparent screen opening measured from the source image's alpha channel in source-image pixels.
- A screen-corner radius ratio used to clip the iframe to the display opening.

The profile dimensions and screen rectangles are metadata only. They are not copies of the third-party artwork.
