package com.lawliet.minitouch.overlay;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import com.lawliet.minitouch.R;
import com.lawliet.minitouch.accessibility.NavAccessibilityService;
import com.lawliet.minitouch.core.AppRepo;
import com.lawliet.minitouch.core.PrefsRepository;
import com.lawliet.minitouch.core.ReviveScheduler;
import com.lawliet.minitouch.ui.SettingsActivity;

import java.util.List;

/**
 * 悬浮球前台服务（§4.1 FloatingBallService；覆盖 F1/F2/F3/F6/F7 —— Phase 1 全部核心）。
 *
 * 职责：
 * - WindowManager Overlay 悬浮球：拖拽、松手贴边、N 秒无操作半透明隐藏、防烧屏微调（F1/§5-F1）；
 * - 短按球展开/收起面板，长按球进设置页（§7.3）；
 * - 前台服务 + 低打扰通知提升车机存活率，被杀后经 ReviveScheduler 复活（F7/§4.3）；
 * - 横屏尺寸按屏幕短边计算（F6）。
 *
 * 启动方式（§5-F7）：一律 Context.startForegroundService()，onCreate 内立即 startForeground()。
 */
public class FloatingBallService extends Service implements PanelController.Callback {

    private static final String TAG = "BallService";

    public static final String ACTION_STOP = "com.lawliet.minitouch.action.STOP";

    private static final String CHANNEL_ID = "minitouch_ball";
    private static final int NOTIFICATION_ID = 1001;

    /** 贴边动画时长（ms） */
    private static final int SNAP_ANIM_MS = 180;
    /** 防烧屏微调周期：隐藏态每分钟 1px（§5-F1） */
    private static final long ANTI_BURN_PERIOD_MS = 60_000L;
    /** 闪切模式下长按球打开面板的时长：GestureDetector 的长按阈值是系统固定的（约 400ms），无法满足自定义需求，故手写定时器；
     * 1.5s 既能与正常点击（几百毫秒）拉开差距防误触，又不像 3s 那样手感偏久 */
    private static final long LONG_PRESS_OPEN_PANEL_MS = 1500L;
    /** 长按消费标志的延迟清除：须晚于 onSingleTapConfirmed 的确认延迟（约 300ms），防残留标志误吞下一次单击 */
    private static final long LONG_PRESS_FLAG_CLEAR_DELAY_MS = 600L;

    private WindowManager windowManager;
    private PrefsRepository prefs;
    private AppRepo appRepo;

    // ---- 悬浮球 ----
    private ImageView ballView;
    private WindowManager.LayoutParams ballLp;
    private GestureDetector gestureDetector;
    private int ballSize;          // 球直径 px（按短边计算，F6）
    private int touchSlop;
    private float downRawX, downRawY;
    private int downX, downY;      // 按下时的 ballLp.x/y
    private boolean dragging;
    private boolean hidden;        // 当前是否处于"半透明隐藏态"

    // ---- 面板 ----
    private PanelController panelController;
    private boolean panelShowing;

    // ---- 闪切模式（单击球在首项 App 与主页间来回切换） ----
    /** 两态翻转标志：true = 下次单击切回首项 App，false = 下次单击切回主页 */
    private boolean quickSwitchToApp = true;
    /** 闪切模式无可用首项 App 时的 toast 节流，避免连点刷屏 */
    private long lastQuickSwitchToastAt;
    /** 长按定时器已触发标志：抑制随后的单击手势与 GestureDetector 默认长按（进设置页） */
    private boolean longPressConsumed;

    // ---- 空闲隐藏 / 防烧屏 ----
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleHideTask = this::hideBallToIdle;
    /** 闪切模式下的长按定时器：到点即展开面板（普通模式不启用，仍走 GestureDetector 默认长按进设置） */
    private final Runnable longPressOpenPanelTask = this::onLongPressOpenPanelFired;
    /** 延迟清除长按消费标志 */
    private final Runnable clearLongPressFlagTask = () -> longPressConsumed = false;
    private final Runnable antiBurnTask = new Runnable() {
        @Override
        public void run() {
            nudgeForAntiBurn();
            mainHandler.postDelayed(this, ANTI_BURN_PERIOD_MS);
        }
    };

    /** 供外部（引导页等）判断服务是否已在运行，避免重复拉起 */
    private static volatile boolean sRunning;

    /**
     * 用户主动停止标志：onDestroy 见到该标志则跳过复活调度。
     * 否则 STOP/隐藏路径在 onDestroy 里再排一次复活闹钟，球会 3 秒后被重新拉起（"隐藏"失效）。
     */
    private static volatile boolean sUserStopRequested;

    /**
     * 旋转/配置变化接收器：Service 的 Resources 不跟随旋转更新，
     * 收到配置变化后用 WindowManager 实时尺寸重建球与面板（修竖屏显示 bug）。
     */
    private final BroadcastReceiver configChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            rebuildForCurrentOrientation();
        }
    };

    /** 球参数相关 Key 变化时需要重建球（引用 PrefsRepository 常量，避免硬编码字符串漂移） */
    private static final String[] BALL_PREF_KEYS = {
            PrefsRepository.KEY_BALL_SIZE_PERCENT,
            PrefsRepository.KEY_ALPHA_ACTIVE,
            PrefsRepository.KEY_ALPHA_IDLE,
            PrefsRepository.KEY_HIDE_DELAY_MS };

    /** 闪切模式 toast 节流间隔（ms） */
    private static final long QUICK_SWITCH_TOAST_GAP_MS = 3000L;

    /** 球参数热更新：设置页拖动滑块即时刻生效（原 TODO(M4) 落地） */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> {
                // 闪切模式开关变化：若面板正展开，立即收起（该模式下不再展开面板）
                if (PrefsRepository.KEY_QUICK_SWITCH_MODE.equals(key)) {
                    mainHandler.post(() -> {
                        if (panelShowing) {
                            closePanel();
                        }
                    });
                    return;
                }
                for (String k : BALL_PREF_KEYS) {
                    if (k.equals(key)) {
                        // 回调在写库线程触发，回主线程刷 UI
                        mainHandler.post(this::reloadPrefs);
                        return;
                    }
                }
                // 其余 Key（如 favorite_pkgs）无需处理：面板每次展开时重建宫格
            };

    public static boolean isRunning() {
        return sRunning;
    }

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = PrefsRepository.get(this);
        appRepo = new AppRepo(this);

        // §5-F7：必须在启动约 5 秒内进入前台态，这里 onCreate 立即执行
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        computeSizes();
        createBall();
        panelController = new PanelController(this, windowManager, this);

        // 监听配置变化：球大小/透明度等参数在设置页调整后实时生效
        PrefsRepository.get(this).registerPrefsListener(prefListener);

        // 监听旋转等配置变化（动态注册受保护广播，不受隐式广播限制）
        IntentFilter filter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        registerReceiver(configChangeReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // 用户主动停止（隐藏悬浮球）：取消复活闹钟后退出
            stopByUser();
            return START_NOT_STICKY;
        }
        // START_STICKY：系统层面也尝试重建（与 AlarmManager 复活双保险，§4.3）
        return START_STICKY;
    }

    /**
     * 用户主动隐藏/停止：先置标志再取消复活闹钟并退出，
     * onDestroy 据此不再排复活（原 TODO(M4) 落地：区分"主动停止"与"被杀"）。
     */
    private void stopByUser() {
        sUserStopRequested = true;
        ReviveScheduler.cancel(this);
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 任务被划掉：排活（§4.3）
        ReviveScheduler.schedule(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        try {
            unregisterReceiver(configChangeReceiver);
        } catch (Exception ignored) {
            // 未注册成功时做兜底，不影响销毁
        }
        PrefsRepository.get(this).unregisterPrefsListener(prefListener);
        mainHandler.removeCallbacks(idleHideTask);
        mainHandler.removeCallbacks(antiBurnTask);
        if (panelController != null) {
            panelController.destroy();
        }
        if (ballView != null) {
            windowManager.removeView(ballView);
        }
        // 意外销毁 → 复活调度（§4.3）；用户主动 STOP/隐藏不在此列，见下方标志判断。
        // 用户主动 STOP/隐藏路径消费标志跳过；否则这里再排的复活闹钟
        // 会 3 秒后把服务重新拉起，"隐藏"等于没藏。
        if (sUserStopRequested) {
            sUserStopRequested = false; // 消费标志：后续意外被杀仍正常复活
        } else {
            ReviveScheduler.schedule(this);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 无绑定调用方
    }

    // ==================== 前台通知（F7） ====================

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW); // 低打扰：无声、不横幅
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent tap = new Intent(this, SettingsActivity.class);
        tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text_running))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ==================== 尺寸计算（F6：短边换算） ====================

    private void computeSizes() {
        int shortSide = Math.min(getScreenWidth(), getScreenHeight());
        int minPx = dp(40);
        int maxPx = (int) (shortSide * 0.16f); // 上限：短边 16%，防烧屏/防遮挡过大
        ballSize = (int) (shortSide * prefs.getBallSizePercent() / 100f);
        ballSize = Math.max(minPx, Math.min(ballSize, maxPx));
        touchSlop = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                10f, getResources().getDisplayMetrics());
    }

    private int getScreenWidth() {
        return screenSize().x;
    }

    private int getScreenHeight() {
        return screenSize().y;
    }

    /**
     * 屏幕尺寸：从 WindowManager 实时读取（getRealSize）。
     * Service 的 Resources.getDisplayMetrics() 在旋转后仍是旧值，
     * 竖屏下会导致球尺寸/位置错乱（显示 bug 根因）。
     */
    @SuppressWarnings("deprecation") // targetSdk 28：getRealSize 在当前目标版本下为正规 API
    private Point screenSize() {
        Point p = new Point();
        windowManager.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics());
    }

    // ==================== 悬浮球（F1） ====================

    private void createBall() {
        ballView = new ImageView(this);
        ballView.setBackgroundResource(R.drawable.bg_ball);
        ballView.setImageResource(R.drawable.ic_ball);
        ballView.setContentDescription(getString(R.string.ball_desc));

        // §5-F1：窗口类型统一 TYPE_APPLICATION_OVERLAY（安卓 9），不抢输入焦点
        ballLp = new WindowManager.LayoutParams(
                ballSize, ballSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.START;

        // 初始位置：优先记忆上次位置；Q3 拍板默认改为「左侧中间」贴边（原缺省为右中）
        int defaultX = dp(4);
        int defaultY = (getScreenHeight() - ballSize) / 2;
        ballLp.x = prefs.getBallX(defaultX);
        ballLp.y = prefs.getBallY(defaultY);
        clampBallPosition();

        gestureDetector = new GestureDetector(this, new BallGestureListener());
        ballView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureDetector.onTouchEvent(event);
                handleDrag(event);
                return true;
            }
        });

        ballView.setAlpha(prefs.getAlphaActive());
        windowManager.addView(ballView, ballLp);
        restartIdleTimer();
    }

    /** 将 ballLp.x/y 夹在屏幕范围内（横竖屏切换/异常值兜底，F6） */
    private void clampBallPosition() {
        ballLp.x = Math.max(0, Math.min(ballLp.x, getScreenWidth() - ballSize));
        ballLp.y = Math.max(0, Math.min(ballLp.y, getScreenHeight() - ballSize));
    }

    /**
     * 拖拽处理（§5-F1：onTouch 记录位移实时更新坐标）。
     * 移动超过 touchSlop 判定为拖拽，此时收起面板、恢复球不透明度。
     */
    private void handleDrag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downX = ballLp.x;
                downY = ballLp.y;
                dragging = false;
                // 闪切模式：按下即起长按定时器（1.5s），到点展开面板；普通模式不启用，维持原“长按进设置”
                mainHandler.removeCallbacks(longPressOpenPanelTask);
                mainHandler.removeCallbacks(clearLongPressFlagTask);
                longPressConsumed = false;
                if (prefs.isQuickSwitchMode()) {
                    mainHandler.postDelayed(longPressOpenPanelTask, LONG_PRESS_OPEN_PANEL_MS);
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    cancelIdleHide(); // 拖拽期间不隐藏
                    // 拖动中不算长按：取消长按定时器
                    mainHandler.removeCallbacks(longPressOpenPanelTask);
                    if (panelShowing) {
                        closePanel(); // 拖球时面板自动收起
                    }
                    restoreBallAlpha();
                }
                if (dragging) {
                    ballLp.x = downX + (int) dx;
                    ballLp.y = downY + (int) dy;
                    clampBallPosition();
                    windowManager.updateViewLayout(ballView, ballLp);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mainHandler.removeCallbacks(longPressOpenPanelTask);
                if (longPressConsumed) {
                    // 长按已触发：标志延迟清除（须晚于单击确认的约 300ms 判定窗口），
                    // 本次抬手不再当单击处理；已展开的面板保持不收起
                    mainHandler.postDelayed(clearLongPressFlagTask, LONG_PRESS_FLAG_CLEAR_DELAY_MS);
                }
                if (dragging) {
                    snapToEdge();      // 松手贴边动画（§5-F1）
                    restartIdleTimer();
                }
                dragging = false;
                break;
            default:
                break;
        }
    }

    /** 贴边：动画吸附到最近的一侧屏幕边（§5-F1） */
    private void snapToEdge() {
        final int startX = ballLp.x;
        int centerX = startX + ballSize / 2;
        int targetX = centerX < getScreenWidth() / 2 ? 0 : getScreenWidth() - ballSize;
        ValueAnimator anim = ValueAnimator.ofInt(startX, targetX);
        anim.setDuration(SNAP_ANIM_MS);
        anim.addUpdateListener(a -> {
            ballLp.x = (int) a.getAnimatedValue();
            windowManager.updateViewLayout(ballView, ballLp);
        });
        anim.start();
        // 位置记忆（贴边完成后 x 收敛到终值，此处直接存终值即可）
        prefs.saveBallPosition(targetX, ballLp.y);
    }

    // ==================== 空闲隐藏 + 防烧屏（F1） ====================

    private void restartIdleTimer() {
        mainHandler.removeCallbacks(idleHideTask);
        mainHandler.postDelayed(idleHideTask, prefs.getHideDelayMs());
    }

    private void cancelIdleHide() {
        mainHandler.removeCallbacks(idleHideTask);
        mainHandler.removeCallbacks(antiBurnTask);
        hidden = false;
    }

    /** N 秒无操作：降 alpha 进入隐藏态，并启动防烧屏微循环（§5-F1） */
    private void hideBallToIdle() {
        if (panelShowing) {
            return; // 面板展开期间不隐藏
        }
        hidden = true;
        ballView.setAlpha(prefs.getAlphaIdle());
        mainHandler.postDelayed(antiBurnTask, ANTI_BURN_PERIOD_MS);
    }

    /** 隐藏态每分钟微调 1px，防车机屏烧屏（§5-F1） */
    private void nudgeForAntiBurn() {
        if (!hidden || panelShowing) {
            return;
        }
        int limit = getScreenWidth() - ballSize;
        int nx = ballLp.x + 1;
        if (nx > limit) {
            nx = Math.max(0, ballLp.x - 1); // 已到边则反向
        }
        ballLp.x = nx;
        windowManager.updateViewLayout(ballView, ballLp);
    }

    private void restoreBallAlpha() {
        hidden = false;
        if (ballView != null) {
            ballView.setAlpha(prefs.getAlphaActive());
        }
    }

    // ==================== 手势（§7.3 交互） ====================

    /** 球的单击/长按监听：短按展开/收起面板，长按进设置页 */
    private class BallGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true; // 必须返回 true 才能收到后续单击/长按
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (dragging || longPressConsumed) {
                return true; // 长按已展开面板，本次抬手不再当单击
            }
            restoreBallAlpha();
            // 闪切模式：不默认展开 9 宫格面板（长按 1.5 秒才开），单击即在首项 App 与主页间来回切换
            if (prefs.isQuickSwitchMode()) {
                if (panelShowing) {
                    closePanel(); // 长按打开的面板：单击球先收起（不闪切，防误触切走界面）
                } else {
                    quickSwitchToggle();
                }
            } else {
                togglePanel();
            }
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (dragging) {
                return;
            }
            // 闪切模式：长按语义改为由定时器（1.5s）展开面板，忽略 GestureDetector 默认长按（约 400ms 即触发）；
            // 若定时器已触发过则直接吞掉
            if (prefs.isQuickSwitchMode()) {
                return;
            }
            openSettings();
        }
    }

    /** 长按定时器（1.5s）到点（仅闪切模式）：展开面板并置消费标志，抑制后续单击/默认长按 */
    private void onLongPressOpenPanelFired() {
        if (!prefs.isQuickSwitchMode()) {
            return; // 定时器挂载后用户中途关了开关，此处兜底忽略
        }
        longPressConsumed = true;
        restoreBallAlpha();
        showPanel();
    }

    // ==================== 面板开合（F2） ====================

    /**
     * 闪切模式：在「序号第一的常用 App」与「主页」之间来回快速切换。
     * 采用两态翻转标志而非实时探测前台包名（探测需更高权限且不可靠）：
     * 首次单击启动首项 App，再次单击回主页，依次往复。
     */
    private void quickSwitchToggle() {
        // 每次单击都重新取列表，设置页排序/勾选变化即时生效
        List<String> favorites = prefs.getFavoritePkgs();
        if (favorites.isEmpty()) {
            // 无首项可切：节流 toast 引导去设置页添加
            long now = System.currentTimeMillis();
            if (now - lastQuickSwitchToastAt > QUICK_SWITCH_TOAST_GAP_MS) {
                lastQuickSwitchToastAt = now;
                Toast.makeText(this, R.string.toast_quick_switch_no_app, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (quickSwitchToApp) {
            if (appRepo.launch(favorites.get(0))) {
                quickSwitchToApp = false; // 已切到 App，下次单击回主页
            } else {
                // 首项已卸载/被停用（§10-R7）：保持标志不变，下次重试
                Toast.makeText(this, R.string.toast_launch_failed, Toast.LENGTH_SHORT).show();
            }
        } else if (NavAccessibilityService.performHome()) {
            quickSwitchToApp = true; // 已回主页，下次单击切回 App
        } else {
            // 回主页依赖无障碍服务（三大金刚同源）
            Toast.makeText(this, R.string.toast_a11y_needed, Toast.LENGTH_LONG).show();
            NavAccessibilityService.openAccessibilitySettings(this);
        }
    }

    private void togglePanel() {
        if (panelShowing) {
            closePanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        // 展开面板时暂停隐藏计时（§5-F1：面板外点击收起后重开）
        mainHandler.removeCallbacks(idleHideTask);
        mainHandler.removeCallbacks(antiBurnTask);
        hidden = false;
        ballView.setAlpha(prefs.getAlphaActive());

        // 面板锚定球位置展开，靠边自动翻转方向（§5-F2 / F6 短边换算）
        int screenW = getScreenWidth();
        int screenH = getScreenHeight();
        boolean showToLeft = (ballLp.x + ballSize / 2) > screenW / 2; // 球在右半屏 → 面板向左展开
        int anchorX = showToLeft ? ballLp.x : ballLp.x + ballSize;
        int anchorY = ballLp.y + ballSize / 2;
        panelController.show(anchorX, anchorY, screenW, screenH, showToLeft);
        panelShowing = true;
    }

    private void closePanel() {
        panelController.hide();
        panelShowing = false;
        restartIdleTimer();
    }

    /**
     * 面板被"点击面板外区域"收起时由 PanelController 回调（§7.3）。
     * 只需重置服务侧状态，hide() 已在控制器内部完成。
     */
    void onPanelDismissedByOutsideTap() {
        panelShowing = false;
        restartIdleTimer();
    }

    // ==================== PanelController.Callback（§4.2 点击分发） ====================

    @Override
    public void onAppCellClicked(String pkg) {
        closePanel();
        if (!appRepo.launch(pkg)) {
            // F3：拿不到 launch intent（已卸载/系统级 disable，§10-R7）→ toast 说明
            Toast.makeText(this, R.string.toast_launch_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onSettingsClicked() {
        closePanel();
        openSettings();
    }

    @Override
    public void onClosePanelClicked() {
        // 面板标题行"收起面板"：仅关闭当前悬浮窗口（面板），
        // 悬浮球与前台服务保持不动（隐藏/启动整个悬浮球走设置页/引导页的对应按钮）。
        closePanel();
    }

    @Override
    public void onNavActionClicked(int action) {
        // Phase 2 已接入（§1.4：V1 无障碍实测通过）。执行系统动作前先收起面板，
        // 让位给即将弹出的界面（返回/桌面/最近任务概览屏）。
        closePanel();
        boolean handled;
        switch (action) {
            case PanelController.NAV_BACK:
                handled = NavAccessibilityService.performBack();
                break;
            case PanelController.NAV_HOME:
                handled = NavAccessibilityService.performHome();
                break;
            case PanelController.NAV_RECENTS:
                // 多任务 = 最近任务概览屏（RECENTS）；本项目无任务管理器（§5-F5）。
                handled = NavAccessibilityService.performRecents();
                break;
            default:
                handled = false;
        }
        if (!handled) {
            // 无障碍未开启或被系统回收（§5-F4 / §10-R2）：提示并跳转系统无障碍设置引导开启。
            Toast.makeText(this, R.string.toast_a11y_needed, Toast.LENGTH_LONG).show();
            NavAccessibilityService.openAccessibilitySettings(this);
        }
    }

    // ==================== 其它 ====================

    private void openSettings() {
        Intent it = new Intent(this, SettingsActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(it);
    }

    /**
     * 旋转/配置变化：收起面板并按新屏幕尺寸重建球与面板视图。
     * 面板视图尺寸（宽/宫格格子）在构建时固定，必须整体重建才能适配新方向。
     */
    private void rebuildForCurrentOrientation() {
        Log.i(TAG, "config changed, rebuild ball & panel for "
                + getScreenWidth() + "x" + getScreenHeight());
        closePanel();
        if (ballView != null) {
            windowManager.removeView(ballView);
        }
        computeSizes();
        createBall(); // 内部会按 clampBallPosition 把旧坐标夹回新屏幕范围
        if (panelController != null) {
            panelController.destroy();
        }
        panelController = new PanelController(this, windowManager, this);
    }

    /**
     * 配置热更新：球参数（大小/透明度/隐藏延迟）变化时重建悬浮球。
     * 由 SharedPreferences 监听触发（设置页无保存按钮、改动即落库）。
     */
    public void reloadPrefs() {
        // 面板展开期间不重建（避免抽掉球视图导致锚定状态错乱），下次变化再刷
        if (panelShowing) {
            return;
        }
        cancelIdleHide();
        windowManager.removeView(ballView);
        computeSizes();
        createBall();
    }
}
