package com.lawliet.minitouch.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.lawliet.minitouch.R;
import com.lawliet.minitouch.accessibility.NavAccessibilityService;
import com.lawliet.minitouch.core.ReviveScheduler;
import com.lawliet.minitouch.overlay.FloatingBallService;

/**
 * 权限引导页（§4.1 PermissionGuideActivity）。
 *
 * Phase 1 只需一项手动权限：悬浮窗（SYSTEM_ALERT_WINDOW，§6.1）。
 * 无障碍卡片为 Phase 2 预留：状态展示保留，但文案标注"Phase 2 功能"，不做强制引导（§1.4）。
 *
 * 车机若无悬浮窗授权入口（§8-V3），备选 ADB：
 *   adb shell appops set com.lawliet.minitouch SYSTEM_ALERT_WINDOW allow
 */
public class PermissionGuideActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_guide);

        findViewById(R.id.btn_grant_overlay).setOnClickListener(v -> requestOverlayPermission());
        findViewById(R.id.btn_start_ball).setOnClickListener(v -> startBallService());
        findViewById(R.id.btn_hide_ball).setOnClickListener(v -> hideBallService());
        findViewById(R.id.btn_open_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // Phase 2：无障碍跳转按钮（当前仅演示跳转，不驱动任何功能）
        findViewById(R.id.btn_open_a11y).setOnClickListener(v ->
                NavAccessibilityService.openAccessibilitySettings(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    /** 刷新两张状态卡的"已开/未开"文案（§7 权限状态展示的引导页版） */
    private void refreshStatus() {
        boolean overlayGranted = Settings.canDrawOverlays(this);
        setText(R.id.tv_overlay_status, overlayGranted
                ? R.string.status_overlay_granted : R.string.status_overlay_denied);
        // "启动悬浮球"按钮仅在已授权时可点
        findViewById(R.id.btn_start_ball).setEnabled(overlayGranted);

        // 无障碍：Phase 2 才作为功能门槛，这里只做状态展示
        boolean a11yOn = NavAccessibilityService.isEnabledInSettings(this);
        setText(R.id.tv_a11y_status, a11yOn
                ? R.string.status_a11y_on_phase2 : R.string.status_a11y_off_phase2);
    }

    /** 跳转系统"显示在其他应用上层"授权页（§6.1 手动授权方式） */
    private void requestOverlayPermission() {
        Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(it);
        } catch (Exception e) {
            // 车机缺失该设置页（V3 验证项）：提示走 ADB appops 预授权
            setText(R.id.tv_overlay_status, R.string.status_overlay_no_settings_entry);
        }
    }

    /** 启动悬浮球前台服务（§5-F7：统一 startForegroundService） */
    private void startBallService() {
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        Intent service = new Intent(this, FloatingBallService.class);
        startForegroundService(service);
        finish(); // 启动成功后退出引导页，球已常驻
    }

    /** 隐藏悬浮球：与"启动"配对，停止前台服务并取消复活 */
    private void hideBallService() {
        if (!FloatingBallService.isRunning()) {
            Toast.makeText(this, R.string.toast_ball_not_running, Toast.LENGTH_SHORT).show();
            return;
        }
        stopBallService();
        Toast.makeText(this, R.string.toast_ball_hidden, Toast.LENGTH_SHORT).show();
    }

    /** 停止服务：取消复活闹钟后发 ACTION_STOP（服务运行中，普通 startService 传递 action 即可） */
    private void stopBallService() {
        ReviveScheduler.cancel(this);
        Intent stop = new Intent(this, FloatingBallService.class);
        stop.setAction(FloatingBallService.ACTION_STOP);
        startService(stop);
    }

    private void setText(int viewId, int stringRes) {
        android.widget.TextView tv = findViewById(viewId);
        if (tv != null) {
            tv.setText(stringRes);
        }
    }
}
