export function getViewportForOrientation(viewport, orientation) {
  const width = Number(viewport.width);
  const height = Number(viewport.height);

  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    throw new TypeError("A device viewport must have positive numeric dimensions.");
  }

  if (orientation === "landscape" && width < height) {
    return { width: height, height: width };
  }

  if (orientation === "portrait" && width > height) {
    return { width: height, height: width };
  }

  return { width: width, height: height };
}

export function getCssFrameMetrics(viewport, padding) {
  const inset = Number.isFinite(padding) && padding >= 0 ? padding : 9;

  return {
    frameWidth: viewport.width + inset * 2,
    frameHeight: viewport.height + inset * 2,
    screenWidth: viewport.width,
    screenHeight: viewport.height,
    padding: inset
  };
}

export function getTelephoneFrameMetrics(frame, viewport) {
  const viewBox = frame && frame.viewBox;
  const insets = frame && frame.screenInsets;

  if (!viewBox || !insets || viewBox.width <= 0 || viewBox.height <= 0) {
    throw new TypeError("Telephone frame metadata is incomplete.");
  }

  const screenWidthRatio = 1 - insets.left - insets.right;
  const screenHeightRatio = 1 - insets.top - insets.bottom;

  if (screenWidthRatio <= 0 || screenHeightRatio <= 0) {
    throw new RangeError("Telephone screen insets leave no usable screen area.");
  }

  const frameWidth = viewport.width / screenWidthRatio;
  const frameHeight = frameWidth * viewBox.height / viewBox.width;

  return {
    frameWidth: frameWidth,
    frameHeight: frameHeight,
    screenWidth: frameWidth * screenWidthRatio,
    screenHeight: frameHeight * screenHeightRatio
  };
}

export function calculatePreviewScale(frameWidth, frameHeight, availableWidth, availableHeight) {
  const values = [frameWidth, frameHeight, availableWidth, availableHeight];

  if (!values.every(Number.isFinite) || values.some(function (value) { return value <= 0; })) {
    return 1;
  }

  return Math.min(1, availableWidth / frameWidth, availableHeight / frameHeight);
}

export function calculateScreenFit(screenWidth, screenHeight, viewportWidth, viewportHeight) {
  const values = [screenWidth, screenHeight, viewportWidth, viewportHeight];

  if (!values.every(Number.isFinite) || values.some(function (value) { return value <= 0; })) {
    throw new TypeError("Screen and viewport dimensions must be positive numbers.");
  }

  const scale = Math.min(screenWidth / viewportWidth, screenHeight / viewportHeight);
  const renderedWidth = viewportWidth * scale;
  const renderedHeight = viewportHeight * scale;

  return {
    scale: scale,
    offsetX: (screenWidth - renderedWidth) / 2,
    offsetY: (screenHeight - renderedHeight) / 2
  };
}

export function calculateViewportPlacement(
  screenWidth,
  screenHeight,
  viewportWidth,
  viewportHeight,
  orientation
) {
  const values = [screenWidth, screenHeight, viewportWidth, viewportHeight];
  if (!values.every(Number.isFinite) || values.some(function (value) { return value <= 0; })) {
    throw new TypeError("Screen and viewport dimensions must be positive numbers.");
  }

  const landscape = orientation === "landscape";
  const availableWidth = landscape ? screenHeight : screenWidth;
  const availableHeight = landscape ? screenWidth : screenHeight;
  const scale = Math.min(
    availableWidth / viewportWidth,
    availableHeight / viewportHeight
  );

  return {
    left: (screenWidth - viewportWidth) / 2,
    top: (screenHeight - viewportHeight) / 2,
    scale: scale,
    transform: landscape
      ? "rotate(-90deg) scale(" + scale + ")"
      : "scale(" + scale + ")"
  };
}
