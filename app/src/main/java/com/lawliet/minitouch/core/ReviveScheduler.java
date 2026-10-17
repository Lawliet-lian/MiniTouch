package com.lawliet.minitouch.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.lawliet.minitouch.receiver.BootReceiver;

/**
 * 服务被杀后的复活调度（§4.1 ReviveScheduler / §4.3 / F7）。
 *
 * 策略：FloatingBallService 意外销毁（onDestroy/onTaskRemoved）时，
 * 排一个数秒后的一次性 AlarmManager 广播 → BootReceiver 收到 REVIVE 动作后重新拉起服务。
 * 选 AlarmManager 而非 JobScheduler：车机 Android 9 上语义更直接，且无需网络约束。
 */
public final class ReviveScheduler {

    private static final String TAG = "ReviveScheduler";

    /** 复活请求 requestCode，固定值保证"覆盖旧闹钟"语义 */
    private static final int REQ_CODE = 2001;
    /** 默认复活延迟：3 秒（§4.3"延迟重启"） */
    public static final long DEFAULT_REVIVE_DELAY_MS = 3000L;

    private ReviveScheduler() {
    }

    /**
     * 排一个 delayMs 后的复活闹钟。
     * 使用 ELAPSED_REALTIME_WAKEUP：不依赖墙上时钟（车机断网时 RTC 可能不准）。
     */
    public static void schedule(Context context, long delayMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent it = new Intent(context, BootReceiver.class)
                .setAction(BootReceiver.ACTION_REVIVE);
        // FLAG_IMMUTABLE：广播不需要携带动态 extra；minSdk 26 可用
        PendingIntent pi = PendingIntent.getBroadcast(context, REQ_CODE, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pi);
        Log.i(TAG, "revive alarm scheduled in " + delayMs + "ms");
    }

    public static void schedule(Context context) {
        schedule(context, DEFAULT_REVIVE_DELAY_MS);
    }

    /** 用户主动停止服务时取消复活闹钟，避免"停不掉" */
    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent it = new Intent(context, BootReceiver.class)
                .setAction(BootReceiver.ACTION_REVIVE);
        PendingIntent pi = PendingIntent.getBroadcast(context, REQ_CODE, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        pi.cancel();
        Log.i(TAG, "revive alarm cancelled");
    }
}
