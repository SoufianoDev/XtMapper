import assert from "node:assert/strict";
import { chromium } from "playwright";

const BASE_URL = process.env.PROTOTYPE_URL || "http://127.0.0.1:4173/";
const REGISTRY_KEY = "xtmapper.app-picker.added-packages.v1";
const APP_PACKAGES = [
  "com.activision.callofduty.shooter",
  "com.dts.freefiremax",
  "com.shootergamesonline.blockstrike",
  "com.dts.freefireth",
  "com.mobile.legends",
  "com.riotgames.league.wildrift",
  "com.garena.game.df"
];

const browser = await chromium.launch({
  headless: true,
  args: ["--no-sandbox"]
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 1100 },
  reducedMotion: "no-preference"
});
const page = await context.newPage();
const pageErrors = [];
page.on("pageerror", error => pageErrors.push(error.message));
const app = page.frameLocator("#activityFrame");
const checks = [];

function check(name, callback) {
  checks.push({ name, callback });
}

async function setConfig({
  duration = 0,
  threshold = 180,
  minimumFeedback = false,
  minimumDuration = 350,
  forceSkeleton = false,
  reducedMotion = false,
  outcome = "success"
} = {}) {
  const preset = [0, 50, 100, 150, 200, 300, 500, 1000, 2000].includes(duration)
    ? String(duration)
    : "custom";
  await page.locator("#appPickerDurationPreset").selectOption(preset);
  if (preset === "custom") {
    await page.locator("#appPickerCustomDuration").fill(String(duration));
  }
  await page.locator("#appPickerSkeletonThreshold").fill(String(threshold));
  await page.locator("#appPickerMinimumDuration").fill(String(minimumDuration));
  const setToggle = async (selector, checked) => {
    await page.locator(selector).evaluate((input, nextValue) => {
      if (input.checked === nextValue) return;
      input.checked = nextValue;
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    }, checked);
  };
  await setToggle("#appPickerMinimumFeedback", minimumFeedback);
  await setToggle("#appPickerForceSkeleton", forceSkeleton);
  await setToggle("#appPickerReducedMotion", reducedMotion);
  await page.locator("#appPickerOutcome").selectOption(outcome);
  await page.waitForTimeout(50);
}

async function openPicker() {
  const dialog = app.locator("#dialog");
  if (await dialog.isVisible().catch(() => false)) {
    await page.locator("#appPickerCloseButton").click();
    await dialog.waitFor({ state: "hidden" });
  }
  await page.locator("#appPickerOpenButton").click();
  await dialog.waitFor({ state: "visible" });
}

async function closePicker() {
  const dialog = app.locator("#dialog");
  if (await dialog.isVisible().catch(() => false)) {
    await page.locator("#appPickerCloseButton").click();
    await dialog.waitFor({ state: "hidden" });
  }
}

async function waitForRows() {
  await app.locator(".app-picker-row").first().waitFor({ state: "visible", timeout: 7000 });
}

async function readRegistry() {
  return page.evaluate(key => {
    const parsed = JSON.parse(localStorage.getItem(key) || "null");
    return Array.isArray(parsed?.packages) ? parsed.packages : [];
  }, REGISTRY_KEY);
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function createStoredZip(profileName, text) {
  const name = Buffer.from(profileName, "utf8");
  const data = Buffer.from(text, "utf8");
  const crc = crc32(data);
  const local = Buffer.alloc(30 + name.length + data.length);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt16LE(0x0800, 6);
  local.writeUInt16LE(0, 8);
  local.writeUInt16LE(0, 10);
  local.writeUInt16LE(0, 12);
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(data.length, 18);
  local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(name.length, 26);
  local.writeUInt16LE(0, 28);
  name.copy(local, 30);
  data.copy(local, 30 + name.length);

  const central = Buffer.alloc(46 + name.length);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(20, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt16LE(0x0800, 8);
  central.writeUInt16LE(0, 10);
  central.writeUInt16LE(0, 12);
  central.writeUInt16LE(0, 14);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(data.length, 20);
  central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(name.length, 28);
  central.writeUInt16LE(0, 30);
  central.writeUInt16LE(0, 32);
  central.writeUInt16LE(0, 34);
  central.writeUInt16LE(0, 36);
  central.writeUInt32LE(0, 38);
  central.writeUInt32LE(0, 42);
  name.copy(central, 46);

  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(central.length, 12);
  end.writeUInt32LE(local.length, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([local, central, end]);
}

check("Prototype Environment exposes its App Picker test panel", async () => {
  await page.goto(BASE_URL, { waitUntil: "domcontentloaded" });
  await page.locator("#appPickerOpenButton").waitFor({ state: "visible" });
  await page.waitForFunction(() => {
    const frame = document.querySelector("#activityFrame");
    return frame?.contentDocument?.readyState === "complete";
  });
  assert.equal(await page.locator("#appPickerDurationPreset").inputValue(), "150");
  assert.equal(await page.locator("#appPickerSkeletonThreshold").inputValue(), "180");
});

check("Built-in registry and filtering tests pass", async () => {
  await page.locator("#appPickerRunTestsButton").click();
  await page.waitForFunction(() => {
    const summary = document.querySelector("#appPickerTestResultSummary")?.textContent || "";
    return /total/.test(summary);
  });
  const summary = await page.locator("#appPickerTestResultSummary").textContent();
  assert.match(summary || "", /0 failed/);
});

check("Fast discovery skips Shimmer below the 180 ms threshold", async () => {
  for (const duration of [0, 50, 100, 150, 160, 180]) {
    await setConfig({ duration });
    await openPicker();
    await waitForRows();
    assert.equal(await app.locator(".app-picker-skeleton-row").count(), 0, `Unexpected Skeleton at ${duration} ms`);
    await closePicker();
  }
});

check("Discovery above the threshold shows Shimmer and keeps it visible through completion", async () => {
  for (const duration of [200, 300, 500, 1000, 2000]) {
    await setConfig({ duration });
    await openPicker();
    await app.locator(".app-picker-skeleton-row").first().waitFor({ state: "visible" });
    await waitForRows();
    assert.equal(await app.locator(".app-picker-skeleton-row").count(), 0);
    assert.equal(await app.locator(".app-picker-row").count(), 7);
    await closePicker();
  }
});

check("Minimum feedback enabled holds Shimmer for fast and slow discovery", async () => {
  for (const duration of [0, 50, 100, 150, 200, 300, 500, 1000, 2000]) {
    await setConfig({ duration, minimumFeedback: true, minimumDuration: 350 });
    const started = Date.now();
    await openPicker();
    await app.locator(".app-picker-skeleton-row").first().waitFor({ state: "visible" });
    await waitForRows();
    const elapsed = Date.now() - started;
    assert.ok(elapsed >= 330, `Minimum feedback was too short at ${duration} ms: ${elapsed} ms`);
    await closePicker();
  }
});

check("Minimum feedback disabled does not add an artificial hold to instant discovery", async () => {
  await setConfig({ duration: 0, minimumFeedback: false });
  const started = Date.now();
  await openPicker();
  await waitForRows();
  const elapsed = Date.now() - started;
  assert.ok(elapsed < 300, `Instant discovery received an unexpected artificial hold: ${elapsed} ms`);
  await closePicker();
});

check("Force Skeleton and reduced-motion preference work together", async () => {
  await setConfig({ duration: 0, forceSkeleton: true, reducedMotion: true });
  await openPicker();
  const skeleton = app.locator(".app-picker-skeleton-icon").first();
  await skeleton.waitFor({ state: "visible" });
  const animationName = await skeleton.evaluate(el =>
    getComputedStyle(el, "::after").animationName
  );
  assert.equal(animationName, "none");
  await waitForRows();
  await closePicker();
});

check("Empty, partial, all-added and search-empty states are distinct", async () => {
  await setConfig({ duration: 0, outcome: "empty" });
  await openPicker();
  await app.locator("#appPickerStatus").filter({ hasText: "No sample apps were discovered." }).waitFor({ state: "visible" });
  await closePicker();

  await setConfig({ duration: 0, outcome: "partial" });
  await openPicker();
  await waitForRows();
  assert.equal(await app.locator(".app-picker-row").count(), 3);
  await closePicker();

  await page.evaluate(({ key, packages }) => {
    localStorage.setItem(key, JSON.stringify({
      version: 1,
      packages: packages.map((packageName, index) => ({
        packageName,
        profileIds: [`seed-${index}`]
      }))
    }));
  }, { key: REGISTRY_KEY, packages: APP_PACKAGES });
  await setConfig({ duration: 0, outcome: "success" });
  await openPicker();
  await app.getByText("All discovered apps have already been added to XtMapper.").waitFor({ state: "visible" });
  await closePicker();

  await page.evaluate(key => localStorage.removeItem(key), REGISTRY_KEY);
  await setConfig({ duration: 0, outcome: "success" });
  await openPicker();
  await waitForRows();
  await app.locator("#appSearch").fill("not-a-real-game");
  await app.getByText("No apps match your search.").waitFor({ state: "visible" });
  await closePicker();
});

check("Search matches game names and package names", async () => {
  await setConfig({ duration: 0 });
  await openPicker();
  await waitForRows();
  await app.locator("#appSearch").fill("League of Legends");
  assert.equal(await app.locator(".app-picker-row").count(), 1);
  await app.locator("#appSearch").fill("com.garena.game.df");
  assert.equal(await app.locator(".app-picker-row").count(), 1);
  await closePicker();
});

check("Test registry entries filter an app and can be removed without deleting profile links", async () => {
  const packageName = APP_PACKAGES[0];
  await page.locator("#appPickerTestPackage").fill(packageName);
  await page.locator("#appPickerStorageAddButton").click();
  await page.waitForFunction(({ key, pkg }) => {
    const data = JSON.parse(localStorage.getItem(key) || "null");
    return data?.packages?.some(entry => entry.packageName === pkg &&
      entry.profileIds?.includes("__prototype_test__"));
  }, { key: REGISTRY_KEY, pkg: packageName });

  await setConfig({ duration: 0 });
  await openPicker();
  assert.equal(await app.locator(`.app-picker-row[data-package-name="${packageName}"]`).count(), 0);
  await closePicker();

  await page.locator("#appPickerStorageRemoveButton").click();
  await page.waitForFunction(({ key, pkg }) => {
    const data = JSON.parse(localStorage.getItem(key) || "null");
    return !data?.packages?.some(entry => entry.packageName === pkg);
  }, { key: REGISTRY_KEY, pkg: packageName });

  await openPicker();
  assert.equal(await app.locator(`.app-picker-row[data-package-name="${packageName}"]`).count(), 1);
  await closePicker();
});

check("Custom discovery durations are accepted by the side panel", async () => {
  await setConfig({ duration: 275 });
  assert.equal(await page.locator("#appPickerDurationPreset").inputValue(), "custom");
  assert.equal(await page.locator("#appPickerCustomDuration").inputValue(), "275");
  await openPicker();
  await app.locator(".app-picker-skeleton-row").first().waitFor({ state: "visible" });
  await waitForRows();
  await closePicker();
});

check("Discovery errors expose a retry that can recover", async () => {
  await setConfig({ duration: 0, outcome: "failure" });
  await openPicker();
  await app.getByText("Could not discover apps. Try again.").first().waitFor({ state: "visible" });
  await app.locator(".app-picker-retry").click();
  await waitForRows();
  await closePicker();
});

check("Added-app registry survives a full page reload", async () => {
  const packageName = APP_PACKAGES[2];
  await page.locator("#appPickerTestPackage").fill(packageName);
  await page.locator("#appPickerStorageAddButton").click();
  await page.waitForFunction(({ key, pkg }) => {
    const data = JSON.parse(localStorage.getItem(key) || "null");
    return data?.packages?.some(entry => entry.packageName === pkg);
  }, { key: REGISTRY_KEY, pkg: packageName });

  await page.reload({ waitUntil: "domcontentloaded" });
  await page.locator("#appPickerOpenButton").waitFor({ state: "visible" });
  await page.waitForFunction(({ key, pkg }) => {
    const data = JSON.parse(localStorage.getItem(key) || "null");
    return data?.packages?.some(entry => entry.packageName === pkg);
  }, { key: REGISTRY_KEY, pkg: packageName });
  await setConfig({ duration: 0 });
  await openPicker();
  assert.equal(await app.locator(`.app-picker-row[data-package-name="${packageName}"]`).count(), 0);
  await closePicker();
  await page.locator("#appPickerStorageRemoveButton").click();
});

check("Creating and deleting a profile updates the independent app registry", async () => {
  await setConfig({ duration: 0 });
  await openPicker();
  const packageName = APP_PACKAGES[0];
  await app.locator(`.app-picker-row[data-package-name="${packageName}"]`).click();
  const nameInput = app.locator("#dialogInput");
  await nameInput.waitFor({ state: "visible" });
  await nameInput.fill("Picker Create Delete Test");
  await app.locator("#dialogConfirm").click();
  await app.locator(".game-profile-card").waitFor({ state: "visible" });

  let registry = await readRegistry();
  assert.ok(registry.some(entry => entry.packageName === packageName), "Created profile was not registered.");

  await app.locator(".game-profile-card__menu").first().click();
  await app.locator('[data-profile-action="delete"]').click();
  await app.locator("#dialogConfirm").click();
  await page.waitForFunction(({ key, pkg }) => {
    const data = JSON.parse(localStorage.getItem(key) || "null");
    return !data?.packages?.some(entry => entry.packageName === pkg);
  }, { key: REGISTRY_KEY, pkg: packageName });

  registry = await readRegistry();
  assert.ok(!registry.some(entry => entry.packageName === packageName), "Deleting the last profile did not unregister its app.");
});

check("Changing assigned app releases the old package and registers the new one", async () => {
  const oldPackage = APP_PACKAGES[0];
  const newPackage = APP_PACKAGES[1];
  await setConfig({ duration: 0 });
  await openPicker();
  await app.locator(`.app-picker-row[data-package-name="${oldPackage}"]`).click();
  await app.locator("#dialogInput").fill("Picker Reassignment Test");
  await app.locator("#dialogConfirm").click();
  await app.locator(".game-profile-card__menu").first().click();
  await app.locator('[data-profile-action="change"]').click();
  await app.locator(`.app-picker-row[data-package-name="${oldPackage}"]`).waitFor({ state: "visible" });
  await app.locator(`.app-picker-row[data-package-name="${newPackage}"]`).click();

  await page.waitForFunction(({ key, oldPkg, newPkg }) => {
    const data = JSON.parse(localStorage.getItem(key) || "null");
    const packages = new Set((data?.packages || []).map(entry => entry.packageName));
    return !packages.has(oldPkg) && packages.has(newPkg);
  }, { key: REGISTRY_KEY, oldPkg: oldPackage, newPkg: newPackage });

  await app.locator(".game-profile-card__menu").first().click();
  await app.locator('[data-profile-action="delete"]').click();
  await app.locator("#dialogConfirm").click();
});

check("Modified imported backup package names enter the independent registry", async () => {
  const importedPackage = APP_PACKAGES[6];
  const zip = createStoredZip(
    "Imported Profile With Edited Package",
    `APPLICATION ${importedPackage}\nENABLED\n`
  );
  await app.locator("#backupFile").setInputFiles({
    name: "edited-profile.zip",
    mimeType: "application/zip",
    buffer: zip
  });
  await page.waitForFunction(({ key, pkg }) => {
    const data = JSON.parse(localStorage.getItem(key) || "null");
    return data?.packages?.some(entry => entry.packageName === pkg);
  }, { key: REGISTRY_KEY, pkg: importedPackage });

  await setConfig({ duration: 0 });
  await openPicker();
  assert.equal(await app.locator(`.app-picker-row[data-package-name="${importedPackage}"]`).count(), 0);
  await closePicker();
});

check("Cancelling and reopening rejects stale discovery results", async () => {
  await setConfig({ duration: 2000 });
  await openPicker();
  await app.locator(".app-picker-skeleton-row").first().waitFor({ state: "visible" });
  await page.locator("#appPickerCancelButton").click();
  await app.getByText("Discovery cancelled.").waitFor({ state: "visible" });
  await setConfig({ duration: 0 });
  await page.locator("#appPickerReopenButton").click();
  await waitForRows();
  const visibleBeforeStaleCompletion = await app.locator(".app-picker-row").count();
  await page.waitForTimeout(2100);
  assert.equal(await app.locator(".app-picker-row").count(), visibleBeforeStaleCompletion);
  await closePicker();
});

let failures = [];
try {
  for (const item of checks) {
    try {
      await item.callback();
      console.log(`PASS ${item.name}`);
    } catch (error) {
      failures.push({ name: item.name, error: error?.stack || String(error) });
      console.error(`FAIL ${item.name}\n${error?.stack || error}`);
      try { await closePicker(); } catch (_) {}
    }
  }

  assert.deepEqual(pageErrors, [], "Browser page errors were reported.");
  assert.equal(failures.length, 0, `${failures.length} App Picker check(s) failed.`);
  console.log(`\nApp Picker verification: ${checks.length} checks passed.`);
} finally {
  await browser.close();
}
