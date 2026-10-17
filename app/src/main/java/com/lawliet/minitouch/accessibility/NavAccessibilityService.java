package com.lawliet.minitouch.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 三大金刚无障碍服务（§4.1 / F4 —— Phase 2 才接入面板交互，当前仅留桩）。
 *
 * 设计（§4.2）：静态单例持有自身引用，FloatingBallService/PanelController 点击金刚格时
 * 通过静态方法调用 performGlobalAction(...)；实例为空说明服务未开启/被回收，
 * 由调用方引导跳转 ACTION_ACCESSIBILITY_SETTINGS（§5-F4）。
 *
 * 车机若无无障碍设置入口，可按 §8-V1 用 ADB 预授权：
 *   adb shell settings put secure enabled_accessibility_services \
 *       com.lawliet.minitouch/.accessibility.NavAccessibilityService
 */
public class NavAccessibilityService extends AccessibilityService {

    private static final String TAG = "NavA11yService";

    /** 静态单例：供 Overlay 侧调用（§4.2 采用"静态单例 + 直接调用"最简方案） */
    private static volatile NavAccessibilityService sInstance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "accessibility service connected");
        // 本服务只需全局动作能力，onAccessibilityEvent 无需做任何事（§5-F4）。
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 有意留空：不需要监听事件
    }

    @Override
    public void onInterrupt() {
        // 无障碍被系统中断时无声处理；实例将在 onUnbind/onDestroy 置空
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        Log.w(TAG, "accessibility service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    // ==================== 对外静态 API（Phase 2 面板接入用） ====================

    /** 无障碍服务实例是否活跃（能执行全局动作） */
    public static boolean isReady() {
        return sInstance != null;
    }

    /**
     * 检查系统设置中本服务是否已被启用（不同于 isReady：已勾选但进程未起时也为 true）。
     * 用于设置页/引导页的"无障碍：已开/未开"状态卡片（F8）。
     */
    public static boolean isEnabledInSettings(Context context) {
        String expected = new ComponentName(context, NavAccessibilityService.class)
                .flattenToShortString();
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    /** @return true=动作已执行；false=无障碍未开启，调用方应引导用户开启（§5-F4） */
    public static boolean performBack() {
        return performGlobal(GLOBAL_ACTION_BACK);
    }

    public static boolean performHome() {
        return performGlobal(GLOBAL_ACTION_HOME);
    }

    public static boolean performRecents() {
        // 多任务 = 最近任务概览屏（GLOBAL_ACTION_RECENTS）。
        // 口径经竞品 oeiskd（com.oeiskd.easysoftkey）APK 拆解实证：其仅调 performGlobalAction，
        // dex 不含 MANAGE_APPLICATIONS 跳转，即“多任务”就是概览屏；本项目不做系统应用管理页（§5-F5）。
        return performGlobal(GLOBAL_ACTION_RECENTS);
    }

    /** 跳转系统无障碍设置页（§5-F4：未开启时的引导路径） */
    public static void openAccessibilitySettings(Context context) {
        Intent it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(it);
        } catch (Exception e) {
            // 车机若无无障碍入口（R1）：只能走 §8-V1 的 ADB 预授权，此处记录日志兜底
            Log.e(TAG, "open accessibility settings failed", e);
        }
    }

    private static boolean performGlobal(int action) {
        NavAccessibilityService svc = sInstance;
        if (svc == null) {
            Log.w(TAG, "service not running, action=" + action);
            return false;
        }
        boolean ok = svc.performGlobalAction(action);
        if (!ok) {
            Log.w(TAG, "performGlobalAction failed: " + action);
        }
        return ok;
    }
}
