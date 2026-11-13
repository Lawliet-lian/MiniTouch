package com.lawliet.minitouch.accessibility;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.lawliet.minitouch.core.PrefsRepository;

/** Only restores our own entry. The write budget survives process restarts. */
public final class AccessibilityRecovery implements Runnable {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences budget;

    public AccessibilityRecovery(Context context) {
        this.context = context.getApplicationContext();
        budget = context.getSharedPreferences("accessibility_recovery_budget", Context.MODE_PRIVATE);
    }

    public void start() {
        handler.removeCallbacks(this);
        handler.post(this);
    }

    public void stop() {
        handler.removeCallbacks(this);
    }

    @Override
    public void run() {
        try {
            restoreIfNeeded();
        } catch (RuntimeException e) {
            Log.w("A11yRecovery", "Recovery failed", e);
        } finally {
            handler.postDelayed(this, 10000);
        }
    }

    private void restoreIfNeeded() {
        if (!PrefsRepository.get(context).isAccessibilityRecoveryEnabled()
                || context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        // A missing boot counter must not silently reset the retry budget.
        if (boot < 0) return;
        int attempts = budget.getInt("boot", -1) == boot ? budget.getInt("attempts", 0) : 0;
        long now = SystemClock.elapsedRealtime();
        if (attempts >= 3 || (attempts > 0 && now - budget.getLong("last_attempt", 0) < 30000)) return;

        ComponentName own = new ComponentName(context, NavAccessibilityService.class);
        String current = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean containsOwn = false;
        if (current != null) {
            for (String entry : current.split(":")) {
                if (own.equals(ComponentName.unflattenFromString(entry))) containsOwn = true;
            }
        }
        boolean enabled = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        if (containsOwn && enabled) return;

        // Persist before writing so failures and process death also consume an attempt.
        if (!budget.edit().putInt("boot", boot).putInt("attempts", attempts + 1)
                .putLong("last_attempt", now).commit()) return;
        if (!containsOwn) {
            String updated = current == null || current.isEmpty() ? own.flattenToString()
                    : current + ":" + own.flattenToString();
            if (!Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)) return;
        }
        if (!enabled) {
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        }
        Log.i("A11yRecovery", "Restored own service, attempt=" + (attempts + 1));
    }
}
