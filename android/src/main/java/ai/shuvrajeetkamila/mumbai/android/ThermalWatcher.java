package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import ai.shuvrajeetkamila.mumbai.game.QualityProfile;

/**
 * Low frequency thermal / battery watcher.
 *
 * <p>Android's thermal status is polled at 0.5 Hz (or delivered by listener on API 29+)
 * rather than queried per frame. The resulting {@link QualityProfile} override drives frame
 * pacing (30 / 20 / 15 FPS), streaming ranges, agent caps and shadow work.
 */
final class ThermalWatcher {

    private static final long POLL_MS = 2000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager powerManager;
    private PowerManager.OnThermalStatusChangedListener listener;
    private GameSurface target;
    private boolean running;

    ThermalWatcher(Context context) {
        this.context = context.getApplicationContext();
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
    }

    void start(GameSurface surface) {
        this.target = surface;
        if (running) {
            return;
        }
        running = true;
        if (Build.VERSION.SDK_INT >= 29 && powerManager != null) {
            listener = new PowerManager.OnThermalStatusChangedListener() {
                @Override
                public void onThermalStatusChanged(int status) {
                    push(status);
                }
            };
            try {
                powerManager.addThermalStatusListener(listener);
                push(powerManager.getCurrentThermalStatus());
            } catch (Throwable ignored) {
                listener = null;
            }
        }
        handler.postDelayed(poll, POLL_MS);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            int status = 0;
            if (Build.VERSION.SDK_INT >= 29 && powerManager != null) {
                try {
                    status = powerManager.getCurrentThermalStatus();
                } catch (Throwable ignored) {
                    status = 0;
                }
            }
            boolean saver = powerManager != null && powerManager.isPowerSaveMode();
            if (saver && status < 2) {
                status = 2;
            }
            push(status);
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void push(int thermalStatus) {
        GameSurface s = target;
        if (s != null) {
            s.onThermalStatus(thermalStatus);
        }
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poll);
        if (listener != null && powerManager != null && Build.VERSION.SDK_INT >= 29) {
            try {
                powerManager.removeThermalStatusListener(listener);
            } catch (Throwable ignored) {
                // ignore
            }
            listener = null;
        }
    }
}
