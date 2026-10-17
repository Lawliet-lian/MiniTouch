package com.lawliet.minitouch.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 配置持久化封装（§4.1 PrefsRepository）。
 * Service 与 Activity 共享同一份 SharedPreferences（文件级共享，进程内单例）。
 *
 * 当前覆盖：常用 App 列表、球大小/透明度、隐藏延迟、球位置记忆。
 * （面板标题固定为应用名、无任务管理器 —— Q5/Q1 已定不做。）
 */
public final class PrefsRepository {

    private static final String FILE_NAME = "minitouch_prefs";

    // ===== Key 定义（对外公开球参数 Key，供 FloatingBallService 热更新监听比对） =====
    public static final String KEY_FAVORITE_PKGS = "favorite_pkgs";       // F3：常用 App 包名（有序，'|' 分隔）
    public static final String KEY_BALL_SIZE_PERCENT = "ball_size_percent"; // F1/F6：球直径占短边百分比
    public static final String KEY_ALPHA_ACTIVE = "alpha_active";          // F8：活跃态透明度 0~1
    public static final String KEY_ALPHA_IDLE = "alpha_idle";              // F1：半透明隐藏态透明度 0~1
    public static final String KEY_HIDE_DELAY_MS = "hide_delay_ms";        // F1：无操作隐藏延迟（默认 3s）
    private static final String KEY_BALL_X = "ball_x";                     // 球位置记忆（贴边后的 x）
    private static final String KEY_BALL_Y = "ball_y";                     // 球位置记忆（y）

    // ===== 默认值（Q3/Q4 未拍板前的缺省口径，见 §13）=====
    /** 默认球直径 = 屏幕短边的 8% */
    public static final float DEFAULT_BALL_SIZE_PERCENT = 8f;
    public static final float DEFAULT_ALPHA_ACTIVE = 0.92f;
    public static final float DEFAULT_ALPHA_IDLE = 0.30f;
    public static final long DEFAULT_HIDE_DELAY_MS = 3000L;
    /** Q4 拍板：常用程序宫格上限 9 个（横屏面板 3 列 × 3 行） */
    public static final int MAX_FAVORITES = 9;

    private static volatile PrefsRepository sInstance;

    private final SharedPreferences sp;

    private PrefsRepository(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static PrefsRepository get(Context context) {
        if (sInstance == null) {
            synchronized (PrefsRepository.class) {
                if (sInstance == null) {
                    sInstance = new PrefsRepository(context);
                }
            }
        }
        return sInstance;
    }

    // ==================== 配置变化监听（球参数热更新） ====================

    /** 注册 SharedPreferences 变化监听（同一进程内共享同一实例，Service/Activity 通用） */
    public void registerPrefsListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        sp.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterPrefsListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener);
    }

    // ==================== 常用 App 列表（F3/F8） ====================

    /** 读取常用 App 包名（保持用户排序，F8 要求支持上移/下移） */
    public List<String> getFavoritePkgs() {
        String raw = sp.getString(KEY_FAVORITE_PKGS, "");
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String p : raw.split("\\|")) {
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** 覆盖保存常用 App 包名 */
    public void setFavoritePkgs(Collection<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(p);
        }
        sp.edit().putString(KEY_FAVORITE_PKGS, sb.toString()).apply();
    }

    // ==================== 球参数（F1/F8） ====================

    public float getBallSizePercent() {
        return sp.getFloat(KEY_BALL_SIZE_PERCENT, DEFAULT_BALL_SIZE_PERCENT);
    }

    public void setBallSizePercent(float percent) {
        sp.edit().putFloat(KEY_BALL_SIZE_PERCENT, percent).apply();
    }

    public float getAlphaActive() {
        return sp.getFloat(KEY_ALPHA_ACTIVE, DEFAULT_ALPHA_ACTIVE);
    }

    public void setAlphaActive(float alpha) {
        sp.edit().putFloat(KEY_ALPHA_ACTIVE, alpha).apply();
    }

    public float getAlphaIdle() {
        return sp.getFloat(KEY_ALPHA_IDLE, DEFAULT_ALPHA_IDLE);
    }

    public void setAlphaIdle(float alpha) {
        sp.edit().putFloat(KEY_ALPHA_IDLE, alpha).apply();
    }

    public long getHideDelayMs() {
        return sp.getLong(KEY_HIDE_DELAY_MS, DEFAULT_HIDE_DELAY_MS);
    }

    public void setHideDelayMs(long ms) {
        sp.edit().putLong(KEY_HIDE_DELAY_MS, ms).apply();
    }

    /**
     * 恢复球参数默认值：直接 remove 相关 Key，读取时自动回退到 DEFAULT_* 常量，
     * 避免在多处重复硬编码默认值。球位置记忆（ball_x/y）不属于"参数"，不清除。
     * 服务侧的 SharedPreferences 监听会收到回调并自动热更新悬浮球。
     */
    public void resetBallParams() {
        sp.edit()
                .remove(KEY_BALL_SIZE_PERCENT)
                .remove(KEY_ALPHA_ACTIVE)
                .remove(KEY_ALPHA_IDLE)
                .remove(KEY_HIDE_DELAY_MS)
                .apply();
    }

    // ==================== 球位置记忆 ====================

    public void saveBallPosition(int x, int y) {
        sp.edit().putInt(KEY_BALL_X, x).putInt(KEY_BALL_Y, y).apply();
    }

    public int getBallX(int defX) {
        return sp.getInt(KEY_BALL_X, defX);
    }

    public int getBallY(int defY) {
        return sp.getInt(KEY_BALL_Y, defY);
    }
}
