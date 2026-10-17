package com.lawliet.minitouch.core;

import android.graphics.drawable.Drawable;

/**
 * 已安装可启动 App 的轻量数据对象（F3）。
 * 图标不在此持有，避免整表常驻内存（N2：车机内存占用低），按需通过 AppRepo 获取。
 */
public class AppInfo {

    /** 包名，唯一标识，也是 PrefsRepository 中持久化的内容 */
    public final String packageName;
    /** 应用显示名 */
    public final String label;

    public AppInfo(String packageName, String label) {
        this.packageName = packageName;
        this.label = label;
    }

    /**
     * 按需加载图标（调用方注意缓存/复用，宫格与设置列表各画一次即可）。
     */
    public Drawable loadIcon(AppRepo repo) {
        return repo.loadIcon(packageName);
    }
}
