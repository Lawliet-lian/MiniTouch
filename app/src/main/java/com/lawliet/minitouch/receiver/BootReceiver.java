package com.lawliet.minitouch.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import com.lawliet.minitouch.overlay.FloatingBallService;

/**
 * 开机自启 + 复活广播接收器（§4.1 BootReceiver / F7）。
 *
 * 触发来源：
 * 1. 系统广播 BOOT_COMPLETED —— 开机完成后拉起悬浮球；
 * 2. ReviveScheduler 的 AlarmManager 广播（ACTION_REVIVE）—— 服务被杀后复活。
 *
 * 注意（F7/§5）：安卓 9 后台无焦点时不允许 startService，
 * 必须统一使用 startForegroundService()，服务侧需在 5 秒内 startForeground()。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    /** 复活动作（由 ReviveScheduler 发送，显式组件 Intent，无需 Manifest filter） */
    public static final String ACTION_REVIVE = "com.lawliet.minitouch.action.REVIVE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onReceive: " + action);

        // 未授予悬浮窗权限时拉起服务也无意义（addView 会失败），直接跳过；
        // 权限引导页会覆盖首次授权流程。
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "no overlay permission, skip starting service");
            return;
        }

        Intent service = new Intent(context, FloatingBallService.class);
        try {
            // 开机广播 / 闹钟广播均属受限后台场景，必须 startForegroundService（§5-F7）
            context.startForegroundService(service);
        } catch (Exception e) {
            Log.e(TAG, "start FloatingBallService failed", e);
        }
    }
}
