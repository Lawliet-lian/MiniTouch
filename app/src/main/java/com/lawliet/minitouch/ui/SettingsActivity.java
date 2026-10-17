package com.lawliet.minitouch.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lawliet.minitouch.R;
import com.lawliet.minitouch.core.AppRepo;
import com.lawliet.minitouch.core.PrefsRepository;
import com.lawliet.minitouch.overlay.FloatingBallService;

/**
 * 设置页（§4.1 / F8）。
 *
 * Phase 1 覆盖：
 * - 常用 App 管理：列表勾选（上限 Q4=9），已选置顶 + 拖拽排序；
 * - 球参数：大小（短边百分比）、活跃态透明度，以及启动悬浮球/恢复默认参数；
 * - 权限状态卡片（悬浮窗）；无障碍卡片标注 Phase 2。
 *
 * 说明：车机横屏优先，布局为单列滚动，宽屏适配后续用 lint 调整（M3 面板 UI 重绘阶段）。
 */
public class SettingsActivity extends AppCompatActivity {

    private PrefsRepository prefs;
    private AppRepo appRepo;
    private InstalledAppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // ActionBar 显示"←"向上按钮：设置页可从引导页或长按悬浮球进入，需要返回入口页
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        prefs = PrefsRepository.get(this);
        appRepo = new AppRepo(this);
        appRepo.invalidateCache(); // 每次进设置页强刷已安装列表（F8 要求含新装 App）

        setupAppList();
        setupBallParams();
        setupQuickSwitch();
        setupBallActions();
        setupPermissionCard();
    }

    /**
     * 处理 ActionBar 左上角"←"返回按钮：
     * - 正常从引导页进入：任务栈里有引导页，finish() 即回到 PermissionGuideActivity；
     * - 从悬浮球长按进入（栈内无其他 Activity）：显式跳回引导页后再 finish，避免直接退出应用。
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (isTaskRoot()) {
                // 栈底就是设置页：回引导页（复用已实例，避免重复压栈）
                startActivity(new Intent(this, PermissionGuideActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ==================== 常用 App 管理（F8） ====================

    private void setupAppList() {
        RecyclerView rv = findViewById(R.id.rv_installed_apps);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new InstalledAppAdapter(
                appRepo.loadLaunchableApps(),
                prefs.getFavoritePkgs(),
                () -> {
                    // 勾选或拖拽排序变化即整体落库（简单直接；批量编辑场景 M3 再优化）
                    prefs.setFavoritePkgs(adapter.currentFavorites());
                    refreshFavoriteCount();
                });
        rv.setAdapter(adapter);
        refreshFavoriteCount();
    }

    private void refreshFavoriteCount() {
        TextView tv = findViewById(R.id.tv_favorite_count);
        tv.setText(getString(R.string.settings_favorite_count,
                adapter.currentFavorites().size(), PrefsRepository.MAX_FAVORITES));
    }

    // ==================== 球参数（F8） ====================

    private void setupBallParams() {
        // 大小：SeekBar 0~100 映射短边百分比 4%~16%
        SeekBar sbSize = findViewById(R.id.sb_ball_size);
        sbSize.setMax(100);
        sbSize.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    prefs.setBallSizePercent(4f + progress / 100f * 12f);
                }
            }
        });

        // 活跃透明度：0.3~1.0
        SeekBar sbAlpha = findViewById(R.id.sb_ball_alpha);
        sbAlpha.setMax(100);
        sbAlpha.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    prefs.setAlphaActive(0.3f + progress / 100f * 0.7f);
                }
            }
        });

        refreshBallSliders();

        // TODO(F8/M4)：默认贴边位置（左/右）单选、隐藏延迟滑块
        // 说明：Q5 已定面板标题不自定义、Q1 已定任务管理器=概览屏，二者均无需在设置页加开关
    }

    /** 按当前 prefs 值同步两个滑块位置（初始进入 / 恢复默认后调用） */
    private void refreshBallSliders() {
        ((SeekBar) findViewById(R.id.sb_ball_size))
                .setProgress(Math.round((prefs.getBallSizePercent() - 4f) / 12f * 100));
        ((SeekBar) findViewById(R.id.sb_ball_alpha))
                .setProgress(Math.round((prefs.getAlphaActive() - 0.3f) / 0.7f * 100));
    }

    // ==================== 闪切模式开关 ====================

    /**
     * 闪切模式：开启后单击悬浮球不展开 9 宫格面板，
     * 改为在序号第一的常用 App 与主页间来回切换（切换逻辑在 FloatingBallService）。
     * 与滑块一致“改动即落库”，服务侧监听 prefs 自动生效，无需额外通知。
     */
    private void setupQuickSwitch() {
        SwitchCompat sw = findViewById(R.id.sw_quick_switch);
        sw.setChecked(prefs.isQuickSwitchMode());
        sw.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.setQuickSwitchMode(isChecked));
    }

    // ==================== 球服务操作：启动 / 隐藏 / 恢复默认 ====================

    private void setupBallActions() {
        // 启动悬浮球：§5-F7 统一 startForegroundService；无悬浮窗权限时服务无法建球，先拦截提示
        findViewById(R.id.btn_start_ball).setOnClickListener(v -> {
            if (FloatingBallService.isRunning()) {
                Toast.makeText(this, R.string.toast_ball_already_running, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_ball_need_overlay, Toast.LENGTH_SHORT).show();
                return;
            }
            startForegroundService(new Intent(this, FloatingBallService.class));
            Toast.makeText(this, R.string.notif_title, Toast.LENGTH_SHORT).show();
        });

        // 隐藏悬浮球：与"启动"配对，发 ACTION_STOP 停服务并取消复活（服务侧 onDestroy 见标志不再排复活）
        findViewById(R.id.btn_hide_ball).setOnClickListener(v -> {
            if (!FloatingBallService.isRunning()) {
                Toast.makeText(this, R.string.toast_ball_not_running, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent stop = new Intent(this, FloatingBallService.class);
            stop.setAction(FloatingBallService.ACTION_STOP);
            startService(stop); // 服务前台运行中，普通 startService 传递 action 即可
            Toast.makeText(this, R.string.toast_ball_hidden, Toast.LENGTH_SHORT).show();
        });

        // 恢复默认球参数：remove 相关 Key 回退默认值；服务侧 SharedPreferences 监听自动热更新球，
        // 本页只需同步刷新滑块
        findViewById(R.id.btn_reset_ball_params).setOnClickListener(v -> {
            prefs.resetBallParams();
            refreshBallSliders();
            Toast.makeText(this, R.string.toast_ball_params_reset, Toast.LENGTH_SHORT).show();
        });
    }

    // ==================== 权限状态卡片（F8） ====================

    private void setupPermissionCard() {
        TextView tvOverlay = findViewById(R.id.tv_overlay_status);
        tvOverlay.setText(android.provider.Settings.canDrawOverlays(this)
                ? R.string.status_overlay_granted : R.string.status_overlay_denied);
        findViewById(R.id.btn_overlay_go).setOnClickListener(v -> {
            android.content.Intent it = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(it);
        });

        // 无障碍：Phase 2 状态展示（§8 V1 车机确认后再做强引导）
        TextView tvA11y = findViewById(R.id.tv_a11y_status);
        tvA11y.setText(com.lawliet.minitouch.accessibility.NavAccessibilityService
                .isEnabledInSettings(this)
                ? R.string.status_a11y_on_phase2 : R.string.status_a11y_off_phase2);
        findViewById(R.id.btn_a11y_go).setOnClickListener(v ->
                com.lawliet.minitouch.accessibility.NavAccessibilityService
                        .openAccessibilitySettings(this));
    }

    /** SeekBar 只关心进度变化的简化监听 */
    private static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
