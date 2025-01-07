package xtr.keymapper.macro;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.UiContext;

import xtr.keymapper.databinding.MacroStatusLayoutBinding;


/**
 * For displaying elapsed time
 */
public class MacroStatus {
    private final MacroStatusLayoutBinding binding;
    private long initThreadTimeMillis;
    private final AccessibilityManager mAccessibilityManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning;
    private final TimeUpdateRunnable timeUpdateRunnable = new TimeUpdateRunnable();
    private final ViewGroup container;

    public MacroStatus(@UiContext Context context, ViewGroup container) {
        this.container = container;
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        binding = MacroStatusLayoutBinding.inflate(LayoutInflater.from(context), container, true);
    }

    public void start() {
        initThreadTimeMillis = SystemClock.elapsedRealtime();
        isRunning = true;
        timeUpdateRunnable.run();
    }

    public void stop() {
        container.removeView(binding.getRoot());
        binding.getRoot().invalidate();
        isRunning = false;
    }

    private void updateTime() {
        final long totalTimeMillis = SystemClock.elapsedRealtime() - initThreadTimeMillis;
        
        final int totalTimeSeconds = (int) (totalTimeMillis / 1000);

        final int totalTimeMinutes = totalTimeSeconds / 60;

        // For stopwatch
        final long millis = totalTimeMillis - (totalTimeSeconds * 1000L);
        final int seconds = totalTimeSeconds - (totalTimeMinutes * 60);

        binding.textClockMinutes.setText(String.valueOf(totalTimeMinutes));
        binding.textClockSeconds.setText(String.valueOf(seconds));
        binding.textClockMilliSeconds.setText(String.valueOf(millis/10));
    }

    private final class TimeUpdateRunnable implements Runnable {
        @Override
        public void run() {
            final long startTime = SystemClock.elapsedRealtime();

            updateTime();

            if (isRunning) {
                // The stopwatch is still running so execute this runnable again after a delay.
                final boolean talkBackOn = mAccessibilityManager.isTouchExplorationEnabled();

                // Grant longer time between redraws when talk-back is on to let it catch up.
                final int period = talkBackOn ? 500 : 25;

                // Try to maintain a consistent period of time between redraws.
                final long endTime = SystemClock.elapsedRealtime();
                final long delay = Math.max(0, startTime + period - endTime);

                mHandler.postDelayed(this, delay);
            }
        }
    }

}
