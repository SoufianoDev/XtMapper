const CONTROL_MESSAGE = "xtmapper-app-picker-test-control";

export class AppPickerTestPanel {
  constructor() {
    this.frame = document.getElementById("activityFrame");
    this.durationPreset = document.getElementById("appPickerDurationPreset");
    this.customDuration = document.getElementById("appPickerCustomDuration");
    this.threshold = document.getElementById("appPickerSkeletonThreshold");
    this.minimumFeedback = document.getElementById("appPickerMinimumFeedback");
    this.minimumDuration = document.getElementById("appPickerMinimumDuration");
    this.forceSkeleton = document.getElementById("appPickerForceSkeleton");
    this.reducedMotion = document.getElementById("appPickerReducedMotion");
    this.outcome = document.getElementById("appPickerOutcome");
    this.packageInput = document.getElementById("appPickerTestPackage");
    this.status = document.getElementById("appPickerTestStatus");
    this.storage = document.getElementById("appPickerTestStorage");
    this.results = document.getElementById("appPickerTestResults");
    this.resultSummary = document.getElementById("appPickerTestResultSummary");
    this.reopenTimer = 0;
    this.lastState = null;
    this.handleMessage = this.handleMessage.bind(this);
    this.sendConfiguration = this.sendConfiguration.bind(this);
  }

  init() {
    if (!this.frame || !this.durationPreset || !this.status) {
      throw new Error("App Picker test panel markup is incomplete.");
    }

    window.addEventListener("message", this.handleMessage);
    this.frame.addEventListener("load", this.sendConfiguration);

    for (const control of [
      this.durationPreset,
      this.customDuration,
      this.threshold,
      this.minimumFeedback,
      this.minimumDuration,
      this.forceSkeleton,
      this.reducedMotion,
      this.outcome
    ]) {
      control?.addEventListener("change", this.sendConfiguration);
      control?.addEventListener("input", () => {
        if (control === this.customDuration || control === this.threshold || control === this.minimumDuration) {
          this.sendConfiguration();
        }
      });
    }

    this.durationPreset.addEventListener("change", () => {
      this.customDuration.hidden = this.durationPreset.value !== "custom";
      this.sendConfiguration();
    });

    document.getElementById("appPickerOpenButton")?.addEventListener("click", () => {
      this.send("open");
      this.setStatus("Opening App Picker…");
    });
    document.getElementById("appPickerCancelButton")?.addEventListener("click", () => {
      this.send("cancel");
      this.setStatus("Cancellation requested.");
    });
    document.getElementById("appPickerCloseButton")?.addEventListener("click", () => {
      this.send("close");
      this.setStatus("Closing App Picker…");
    });
    document.getElementById("appPickerReopenButton")?.addEventListener("click", () => {
      window.clearTimeout(this.reopenTimer);
      this.send("close");
      this.setStatus("Closing the current dialog before reopening…");
      this.reopenTimer = window.setTimeout(() => {
        this.send("open");
        this.setStatus("Reopening App Picker…");
      }, 180);
    });
    document.getElementById("appPickerRetryButton")?.addEventListener("click", () => {
      this.send("retry");
      this.setStatus("Retry requested.");
    });
    document.getElementById("appPickerRunTestsButton")?.addEventListener("click", () => {
      this.results?.replaceChildren();
      this.resultSummary.textContent = "Running built-in checks…";
      this.send("run-tests");
    });
    document.getElementById("appPickerStorageRefreshButton")?.addEventListener("click", () => {
      this.send("snapshot");
      this.setStatus("Refreshing app registry…");
    });
    document.getElementById("appPickerStorageAddButton")?.addEventListener("click", () => {
      const packageName = this.packageInput.value.trim();
      if (!packageName) {
        this.setStatus("Enter a packageName first.");
        this.packageInput.focus();
        return;
      }
      this.send("storage-add", { packageName });
      this.setStatus("Adding a test-only registry entry…");
    });
    document.getElementById("appPickerStorageRemoveButton")?.addEventListener("click", () => {
      const packageName = this.packageInput.value.trim();
      if (!packageName) {
        this.setStatus("Enter a packageName first.");
        this.packageInput.focus();
        return;
      }
      this.send("storage-remove", { packageName });
      this.setStatus("Removing this panel's test-only registry entry…");
    });
    document.getElementById("appPickerStorageClearButton")?.addEventListener("click", () => {
      this.send("storage-clear-tests");
      this.setStatus("Clearing test-only registry entries…");
    });

    this.sendConfiguration();
    window.setTimeout(() => this.send("snapshot"), 150);
  }

  getConfig() {
    const duration = this.durationPreset.value === "custom"
      ? Number(this.customDuration.value)
      : Number(this.durationPreset.value);
    return {
      discoveryDurationMs: Number.isFinite(duration) ? duration : 150,
      skeletonThresholdMs: Number(this.threshold.value),
      minimumFeedbackEnabled: this.minimumFeedback.checked,
      minimumFeedbackMs: Number(this.minimumDuration.value),
      forceSkeleton: this.forceSkeleton.checked,
      reducedMotion: this.reducedMotion.checked,
      outcome: this.outcome.value
    };
  }

  sendConfiguration() {
    this.send("configure", { config: this.getConfig() });
  }

  send(action, payload = {}) {
    if (!this.frame?.contentWindow) {
      this.setStatus("Activity preview is not available.");
      return;
    }
    this.frame.contentWindow.postMessage(
      { type: CONTROL_MESSAGE, action, ...payload },
      window.location.origin
    );
  }

  handleMessage(event) {
    if (event.source !== this.frame?.contentWindow || event.origin !== window.location.origin) return;
    const message = event.data;
    if (!message || typeof message !== "object") return;

    if (message.type === "xtmapper-app-picker-state") {
      this.lastState = message;
      this.renderState(message);
      return;
    }

    if (message.type === "xtmapper-app-picker-test-results") {
      this.renderResults(message.summary);
    }
  }

  renderState(message) {
    const details = [];
    if (message.phase) details.push(message.phase.replaceAll("-", " "));
    if (Number.isFinite(message.durationMs)) details.push(`${message.durationMs} ms`);
    if (Number.isFinite(message.discoveredCount)) details.push(`${message.discoveredCount} discovered`);
    if (Number.isFinite(message.availableCount)) details.push(`${message.availableCount} available`);
    if (message.skeletonVisible) details.push("Shimmer shown");
    if (message.emptyState) details.push(message.emptyState.replaceAll("-", " "));
    if (message.message) details.push(message.message);
    this.setStatus(details.join(" · ") || "Waiting for App Picker activity.");

    const records = Array.isArray(message.addedPackageRecords)
      ? message.addedPackageRecords
      : null;
    const packageNames = records
      ? records.map(record => ({
          packageName: record.packageName,
          profileCount: Array.isArray(record.profileIds) ? record.profileIds.length : 0
        }))
      : (Array.isArray(message.addedPackages)
          ? message.addedPackages.map(packageName => ({ packageName, profileCount: null }))
          : []);

    if (this.storage) {
      this.storage.replaceChildren();
      if (!packageNames.length) {
        const empty = document.createElement("p");
        empty.className = "capability-description";
        empty.textContent = "No app packages are currently registered.";
        this.storage.append(empty);
      } else {
        packageNames.forEach(record => {
          const row = document.createElement("div");
          row.className = "app-picker-test-storage-row";
          const name = document.createElement("code");
          name.textContent = record.packageName;
          const count = document.createElement("span");
          count.textContent = record.profileCount === null
            ? "registered"
            : `${record.profileCount} link${record.profileCount === 1 ? "" : "s"}`;
          row.append(name, count);
          this.storage.append(row);
        });
      }
    }
  }

  renderResults(summary) {
    if (!summary || !Array.isArray(summary.tests)) return;
    this.results.replaceChildren();
    summary.tests.forEach(result => {
      const item = document.createElement("li");
      item.className = result.passed ? "app-picker-test-pass" : "app-picker-test-fail";
      const label = document.createElement("span");
      label.textContent = result.passed ? "PASS" : "FAIL";
      const copy = document.createElement("span");
      copy.textContent = result.name + (result.error ? ` — ${result.error}` : "");
      item.append(label, copy);
      this.results.append(item);
    });
    this.resultSummary.textContent =
      `${summary.passed} passed · ${summary.failed} failed · ${summary.total} total`;
    this.setStatus(summary.failed ? "Built-in checks finished with failures." : "All built-in checks passed.");
  }

  setStatus(message) {
    if (this.status) this.status.textContent = message;
  }

  destroy() {
    window.clearTimeout(this.reopenTimer);
    window.removeEventListener("message", this.handleMessage);
    this.frame?.removeEventListener("load", this.sendConfiguration);
  }
}
