package com.lawliet.minitouch.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 执行全局导航动作，并为快捷面板查询当前前台应用。
 *
 * 设计（§4.2）：静态单例持有自身引用，FloatingBallService/PanelController 点击金刚格时
 * 通过静态方法调用 performGlobalAction(...)；实例为空时需另外检查启用状态，
 * 区分尚未授权与等待系统连接服务。
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
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 点击快捷入口时查询焦点窗口。
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

    /** 只比较输入焦点所在应用窗口的包名，避免把悬浮窗或最近触摸的窗口当成前台应用。 */
    @SuppressWarnings("deprecation") // Android 9 仍使用可回收的无障碍窗口和节点对象。
    public static boolean isForegroundApp(String packageName) {
        NavAccessibilityService service = sInstance;
        if (service == null) {
            return false;
        }
        List<AccessibilityWindowInfo> windows = service.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused()) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    return false;
                }
                try {
                    return TextUtils.equals(packageName, root.getPackageName());
                } finally {
                    root.recycle();
                }
            }
            return false;
        } finally {
            for (AccessibilityWindowInfo window : windows) {
                window.recycle();
            }
        }
    }

    /**
     * 检查系统设置中本服务是否已被启用（不同于 isReady：已勾选但进程未起时也为 true）。
     * 用于设置页/引导页的"无障碍：已开/未开"状态卡片（F8）。
     */
    public static boolean isEnabledInSettings(Context context) {
        ComponentName expected = new ComponentName(context, NavAccessibilityService.class);
        if (Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false;
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        for (String s : enabled.split(":")) {
            // 系统可能保存完整类名，也可能保存以点开头的缩写类名。
            if (expected.equals(ComponentName.unflattenFromString(s))) {
                return true;
            }
        }
        return false;
    }

    /** @return 系统是否接受动作；false 也可能是服务未连接或系统无法执行。 */
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
