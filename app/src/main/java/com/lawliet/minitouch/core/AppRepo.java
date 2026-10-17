package com.lawliet.minitouch.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 已安装 App 的枚举与启动（F3，§5-F3）。
 *
 * 要点：
 * - targetSdk=28 无 Android 11+ 包可见性限制，queryIntentActivities 可直接枚举全部可启动 App，
 *   被桌面"隐藏图标"的 App（仅 launcher 不放图标）依然会出现在列表中；
 * - 启动统一走 getLaunchIntentForPackage + FLAG_ACTIVITY_NEW_TASK（Service 上下文必需）；
 * - R7：启动前检测应用是否被系统级 disable，不可用则返回 false 由调用方 toast 说明。
 */
public final class AppRepo {

    private static final String TAG = "AppRepo";

    private final Context appContext;
    private final PackageManager pm;

    /** 可启动 App 列表缓存（安装/卸载后需失效，骨架阶段：每次进设置页强制刷新） */
    private List<AppInfo> cachedLaunchableApps;

    public AppRepo(Context context) {
        this.appContext = context.getApplicationContext();
        this.pm = appContext.getPackageManager();
    }

    /**
     * 枚举所有带 LAUNCHER 入口的已安装 App，按名称本地化排序。
     */
    public List<AppInfo> loadLaunchableApps() {
        if (cachedLaunchableApps != null) {
            return cachedLaunchableApps;
        }
        Intent filter = new Intent(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved =
                pm.queryIntentActivities(filter, PackageManager.MATCH_DISABLED_COMPONENTS);

        List<AppInfo> out = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null || ri.activityInfo.packageName == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            // 去重：同一包可能有多个 launcher activity
            boolean dup = false;
            for (AppInfo a : out) {
                if (a.packageName.equals(pkg)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            CharSequence label = ri.loadLabel(pm);
            out.add(new AppInfo(pkg, label == null ? pkg : label.toString()));
        }
        // 按当前语言排序，中文环境用拼音序
        final Collator collator = Collator.getInstance(Locale.CHINA);
        out.sort((a, b) -> collator.compare(a.label, b.label));

        cachedLaunchableApps = out;
        return out;
    }

    /** 查找某包名对应的 AppInfo，找不到返回 null（App 可能已卸载） */
    public AppInfo findByPackage(String pkg) {
        for (AppInfo a : loadLaunchableApps()) {
            if (a.packageName.equals(pkg)) {
                return a;
            }
        }
        return null;
    }

    /** 按需加载图标，失败返回系统默认图标 */
    public Drawable loadIcon(String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            Log.w(TAG, "loadIcon failed: " + pkg, e);
            return pm.getDefaultActivityIcon();
        }
    }

    /**
     * 启动指定 App（§4.2：getLaunchIntentForPackage + NEW_TASK）。
     *
     * @return true 表示启动 Intent 已成功发出；false 时调用方应 toast 提示（F3/§10-R7）
     */
    public boolean launch(String pkg) {
        // R7：被系统级 disable 的应用拿不到 launch intent，先行检测给出明确原因
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (!ai.enabled) {
                Log.w(TAG, "app disabled by system: " + pkg);
                return false;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "app not installed: " + pkg, e);
            return false;
        }

        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Log.w(TAG, "no launch intent for: " + pkg);
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            appContext.startActivity(launch);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startActivity failed: " + pkg, e);
            return false;
        }
    }

    /** 使缓存失效（安装/卸载广播、进入设置页时调用） */
    public void invalidateCache() {
        cachedLaunchableApps = null;
    }
}
