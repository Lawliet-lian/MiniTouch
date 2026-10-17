package com.lawliet.minitouch.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lawliet.minitouch.R;
import com.lawliet.minitouch.core.AppRepo;
import com.lawliet.minitouch.core.PrefsRepository;

import java.util.List;

/**
 * 弹出面板控制器（F2，§5-F2 / §7.1 排布）。
 *
 * 结构：全屏 Overlay 容器（root）+ 锚定面板（view_panel）。
 * - 面板锚定球位置展开，超出屏幕边时由调用方给出 showToLeft 翻转方向；
 * - root 拦截面板外区域的 DOWN 事件 → 收起面板（§7.3"点击面板外收起"）；
 * - 上排：常用程序宫格（Phase 1，动态填充 LinearLayout 行，与金刚行同构保证列对齐）；
 * - 下排：三大金刚（返回/主页/多任务）—— Phase 2 已接入。
 *
 * 尺寸链路（F6）：先按屏高预算反推格子尺寸，面板宽度再随宫格内容自适应，横竖间隙统一；
 * 宽度上限仍按屏幕短边百分比换算（沿用底子 A 的横屏修复思路）。
 */
public class PanelController {

    private static final String TAG = "PanelController";

    /** 面板宽度上限 = 短边百分比（内容自适应宽度再经此夹取，防竖屏过宽） */
    private static final float PANEL_WIDTH_MAX_SHORT_SIDE_RATIO = 0.60f;
    /** 面板宽度下限（dp）：保证标题行（标题 + 两个按钮）放得下 */
    private static final int PANEL_WIDTH_MIN_DP = 260;
    /** 面板左右 padding（dp）：与 R.dimen.panel_padding 保持同步 */
    private static final int PANEL_PADDING_DP = 12;
    /** 宫格高度预算比例：三行宫格整体至多占屏高此比例，防面板底部溢出被裁 */
    private static final float GRID_HEIGHT_BUDGET_RATIO = 0.52f;
    /** 宫格统一间隙（dp）：横竖同值，修复"横疏竖挤"的节奏失衡 */
    private static final int GRID_GAP_DP = 8;
    /** 金刚键帽宽 = 格子比例：比键帽高略宽，呈稍扁的横向长方形（用户要求"底色更宽、长方形一点"） */
    private static final float NAV_KEY_WIDTH_RATIO = 0.7f;
    /** 金刚键帽高 = 格子比例：用户反馈旧 72% 方形键帽偏大，收敛为 55% */
    private static final float NAV_KEY_HEIGHT_RATIO = 0.55f;
    /** 宫格列数：Q4 拍板上限 9 格，横屏按 3 列 × 3 行排布 */
    private static final int GRID_COLUMN_COUNT = 3;

    /** 金刚行动作类型（Phase 2 使用） */
    public static final int NAV_BACK = 0;
    public static final int NAV_HOME = 1;
    public static final int NAV_RECENTS = 2;

    /** 面板事件回调，由 FloatingBallService 实现（§4.2 点击分发） */
    public interface Callback {
        /** 上排 App 格被点击 */
        void onAppCellClicked(String pkg);

        /** 标题行设置齿轮 */
        void onSettingsClicked();

        /** 标题行收起面板按钮：仅关闭当前悬浮窗口（面板），不影响悬浮球与服务 */
        void onClosePanelClicked();

        /** 下排金刚格被点击（Phase 2） */
        void onNavActionClicked(int action);
    }

    private final Context context;
    private final WindowManager windowManager;
    private final PrefsRepository prefs;
    private final AppRepo appRepo;
    private final Callback callback;

    private FrameLayout root;
    private WindowManager.LayoutParams rootLp;
    private LinearLayout panel;
    private boolean visible;

    // 宫格容器引用：每次 show() 时按最新 prefs 重建（修复：设置页勾选后面板不刷新）
    private LinearLayout appGrid;
    private int gridCellSize;

    public PanelController(Context context, WindowManager windowManager, Callback callback) {
        this.context = context;
        this.windowManager = windowManager;
        this.callback = callback;
        this.prefs = PrefsRepository.get(context);
        this.appRepo = new AppRepo(context);
        createViews();
    }

    // ==================== 视图构建 ====================

    private void createViews() {
        // root：全屏透明容器。FLAG_NOT_FOCUSABLE 不抢输入焦点（§5-F1 同样原则）；
        // 通过重写 onInterceptTouchEvent 实现"点面板外收起"。
        root = new OutsideDismissFrameLayout(context);
        rootLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        rootLp.gravity = Gravity.TOP | Gravity.START;

        panel = (LinearLayout) LayoutInflater.from(context)
                .inflate(R.layout.view_panel, (ViewGroup) root, false);

        // 面板尺寸（F6）：先按屏幕尺寸反推格子尺寸，面板宽度再随宫格内容自适应，
        // 避免"高度预算生效但宽度仍按短边比例算"造成的列内水平大空白
        int cellSize = computeCellSize();
        int panelWidth = computePanelWidth(cellSize);

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(panel, flp);

        bindPanelViews(cellSize);
        // 初始不可见：root 未加入 WindowManager，show() 时再 addView
    }

    /** 绑定面板内部：标题、齿轮、宫格容器、金刚行 */
    private void bindPanelViews(int cellSize) {
        // 标题行（Q5 拍板：面板标题不做用户自定义，固定显示应用名）
        TextView title = panel.findViewById(R.id.tv_panel_title);
        title.setText(R.string.app_name);

        // 设置齿轮 → 进设置页（§7.3）
        panel.findViewById(R.id.btn_panel_settings).setOnClickListener(v ->
                callback.onSettingsClicked());

        // 收起面板 → 仅关闭当前悬浮窗口，悬浮球与前台服务保持不动（§7.3）
        panel.findViewById(R.id.btn_panel_close).setOnClickListener(v ->
                callback.onClosePanelClicked());

        // 上排：常用程序宫格（Phase 1，F3）。保存引用供 show() 时按最新配置重建。
        // 格子尺寸统一由 computeCellSize() 按屏幕尺寸反推，面板宽度已随其自适应
        LinearLayout grid = panel.findViewById(R.id.grid_apps);
        this.appGrid = grid;
        this.gridCellSize = cellSize;
        populateAppGrid(grid, cellSize);

        // 下排：三大金刚（返回/主页/多任务）—— Phase 2 已接入，V1 无障碍实测通过 → 整行可见。
        LinearLayout navRow = panel.findViewById(R.id.ll_nav_row);
        navRow.setVisibility(View.VISIBLE);
        // 金刚按钮尺寸与宫格格子同源换算（F6）：键帽宽取格子 75%、高取 55%，呈稍扁横向长方形；
        // 内边距按键帽高的 12% 让图标相对更大
        int navW = (int) (cellSize * NAV_KEY_WIDTH_RATIO);
        int navH = (int) (cellSize * NAV_KEY_HEIGHT_RATIO);
        int navPad = (int) (navH * 0.12f);
        int[] navIds = {R.id.btn_nav_back, R.id.btn_nav_home, R.id.btn_nav_recents};
        for (int id : navIds) {
            View btn = panel.findViewById(id);
            ViewGroup.LayoutParams blp = btn.getLayoutParams();
            blp.width = navW;
            blp.height = navH;
            btn.setLayoutParams(blp);
            btn.setPadding(navPad, navPad, navPad, navPad);
        }
        navRow.findViewById(R.id.btn_nav_back).setOnClickListener(v ->
                callback.onNavActionClicked(NAV_BACK));
        navRow.findViewById(R.id.btn_nav_home).setOnClickListener(v ->
                callback.onNavActionClicked(NAV_HOME));
        navRow.findViewById(R.id.btn_nav_recents).setOnClickListener(v ->
                callback.onNavActionClicked(NAV_RECENTS));
    }

    /**
     * 按屏幕尺寸反推格子尺寸（不依赖面板宽，断开旧"面板宽→格子"的循环依赖）：
     * 高向 = 屏高 × 宫格高度预算 ÷ 3 行；宽向以面板宽度上限夹取；下限 dp(40)。
     */
    private int computeCellSize() {
        int cellByHeight = (int) (getScreenHeight() * GRID_HEIGHT_BUDGET_RATIO) / GRID_COLUMN_COUNT;
        int cellByWidth = (maxPanelWidth() - dp(2 * PANEL_PADDING_DP)) / GRID_COLUMN_COUNT;
        return Math.max(dp(40), Math.min(cellByHeight, cellByWidth));
    }

    /** 面板宽度 = 3 列格子 + 左右 padding（内容自适应），再夹在下限与上限之间 */
    private int computePanelWidth(int cellSize) {
        int contentWidth = cellSize * GRID_COLUMN_COUNT + dp(2 * PANEL_PADDING_DP);
        return clamp(dp(PANEL_WIDTH_MIN_DP), contentWidth, maxPanelWidth());
    }

    /** 面板宽度上限：屏幕短边 60%（防竖屏过宽） */
    private int maxPanelWidth() {
        return (int) (Math.min(getScreenWidth(), getScreenHeight()) * PANEL_WIDTH_MAX_SHORT_SIDE_RATIO);
    }

    /** 动态填充常用 App 宫格（数据源：PrefsRepository，图标：AppRepo 按需加载） */
    private void populateAppGrid(LinearLayout grid, int cellSize) {
        grid.removeAllViews();
        List<String> pkgs = prefs.getFavoritePkgs();
        if (pkgs.isEmpty()) {
            // F8 引导：尚未配置常用 App 时给出文案提示
            TextView empty = new TextView(context);
            empty.setText(R.string.panel_empty_hint);
            empty.setTextColor(0xB3FFFFFF);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            lp.bottomMargin = dp(8);
            grid.addView(empty, lp);
            return;
        }
        // 逐行填充：每行一个横向 LinearLayout，三格 weight 等分 + 列内居中——
        // 与下排金刚行（weight 三等分）同构，列中心同在 1/6、1/2、5/6，任意屏宽下对齐
        for (int start = 0; start < pkgs.size(); start += GRID_COLUMN_COUNT) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(GRID_GAP_DP);
            grid.addView(row, rowLp);
            for (int col = 0; col < GRID_COLUMN_COUNT; col++) {
                int index = start + col;
                // weight 单元容器：末行不足 3 格时列位也不偏移
                LinearLayout cellWrap = new LinearLayout(context);
                cellWrap.setGravity(Gravity.CENTER);
                row.addView(cellWrap, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (index < pkgs.size()) {
                    // 格径 = 格子 - 统一间隙：水平间隙（列宽-格径）与垂直间隙（行 margin）同值
                    cellWrap.addView(createAppCell(pkgs.get(index), cellSize),
                            new LinearLayout.LayoutParams(cellSize - dp(GRID_GAP_DP), cellSize - dp(GRID_GAP_DP)));
                }
            }
        }
    }

    /** 单个 App 格：圆形半透明底（按下提亮）+ 应用图标，点击 → callback（§4.2 App 格分发） */
    private View createAppCell(String pkg, int cellSize) {
        ImageView cell = new ImageView(context);
        cell.setBackgroundResource(R.drawable.bg_app_cell);
        Drawable icon = appRepo.loadIcon(pkg);
        if (icon != null) {
            // 图标视觉归一化：裁掉透明边后统一尺寸（用户要求每个图案一样大）
            cell.setImageDrawable(normalizeIcon(icon));
        }
        int iconPad = (int) (cellSize * 0.18f); // 图标内边距：格子的 18%（图标内容约占圆径 64%，圆底不显空）
        cell.setPadding(iconPad, iconPad, iconPad, iconPad);
        cell.setOnClickListener(v -> callback.onAppCellClicked(pkg));
        cell.setOnLongClickListener(v -> {
            // TODO(F8)：长按格子弹"移除/换位置"快捷菜单，先留桩
            return true;
        });
        return cell;
    }

    /**
     * 图标视觉归一化：不同 App 图标自带大小不一的透明边，fitCenter 下视觉内容忽大忽小。
     * 先绘制到固定尺寸画布，扫描非透明像素包围盒后裁紧，统一 padding 下内容即等大。
     * AdaptiveIcon 背景铺满不透明，裁剪区域为整幅，不受影响。
     */
    private Drawable normalizeIcon(Drawable src) {
        final int canvas = 128; // 采样画布边长（足够精确，单次开销微小）
        try {
            Bitmap bmp = Bitmap.createBitmap(canvas, canvas, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            src.setBounds(0, 0, canvas, canvas);
            src.draw(c);
            // 扫描非透明像素包围盒（阈值 8：滤掉抗锯齿伪透明）
            int left = canvas, top = canvas, right = -1, bottom = -1;
            int[] rowPx = new int[canvas];
            for (int y = 0; y < canvas; y++) {
                bmp.getPixels(rowPx, 0, canvas, 0, y, canvas, 1);
                for (int x = 0; x < canvas; x++) {
                    if (Color.alpha(rowPx[x]) > 8) {
                        if (x < left) {
                            left = x;
                        }
                        if (x > right) {
                            right = x;
                        }
                        if (y < top) {
                            top = y;
                        }
                        if (y > bottom) {
                            bottom = y;
                        }
                    }
                }
            }
            if (right <= left || bottom <= top) {
                return src; // 全透明等异常：原样返回兜底
            }
            Bitmap tight = Bitmap.createBitmap(bmp, left, top, right - left + 1, bottom - top + 1);
            return new BitmapDrawable(context.getResources(), tight);
        } catch (Exception e) {
            Log.w(TAG, "normalizeIcon failed, use raw drawable", e);
            return src;
        }
    }

    // ==================== 显示 / 收起 ====================

    /**
     * 在球锚点处展开面板。
     *
     * @param anchorX    球的 x（TOP|START 坐标系）
     * @param anchorY    球的 y
     * @param screenW    当前屏幕宽
     * @param screenH    当前屏幕高
     * @param showToLeft 球在右半屏时面板向左展开；否则向右（§5-F2 超边翻转）
     */
    public void show(int anchorX, int anchorY, int screenW, int screenH, boolean showToLeft) {
        if (visible) {
            return;
        }
        // 每次展开都按最新 prefs 重建宫格：设置页勾选/取消常用 App 后无需重启服务即生效
        if (appGrid != null) {
            populateAppGrid(appGrid, gridCellSize);
        }
        // 先 measure 拿到面板实际宽高，再计算落点
        panel.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST));
        int pw = panel.getMeasuredWidth();
        int ph = panel.getMeasuredHeight();

        int x = showToLeft ? anchorX - pw - dp(6) : anchorX + dp(6);
        int y = anchorY - ph / 2;
        // 越界夹取（R4：面板溢出兜底；TODO(F6)：方向变化时整体重排）
        x = clamp(dp(4), x, screenW - pw - dp(4));
        y = clamp(dp(4), y, screenH - ph - dp(4));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(x, y, 0, 0);
        panel.setLayoutParams(lp);

        windowManager.addView(root, rootLp);
        visible = true;
        Log.d(TAG, "panel shown at (" + x + "," + y + ") size (" + pw + "x" + ph + ")");
    }

    /** 收起面板并从 WindowManager 移除全屏 root（避免长期拦截全屏触摸） */
    public void hide() {
        if (!visible) {
            return;
        }
        try {
            windowManager.removeView(root);
        } catch (Exception e) {
            Log.w(TAG, "remove root failed (already removed?)", e);
        }
        visible = false;
    }

    public boolean isVisible() {
        return visible;
    }

    /** 服务销毁时兜底移除 */
    public void destroy() {
        hide();
    }

    // ==================== 工具 ====================

    /**
     * 屏幕尺寸：必须从 WindowManager 实时读取（getRealSize）。
     * Service 类型的 Context 不跟随旋转重配置，Resources.getDisplayMetrics()
     * 会停留在旧横屏值，导致竖屏下面板按旧尺寸布局溢出（显示 bug 根因）。
     */
    @SuppressWarnings("deprecation") // targetSdk 28：getRealSize 在当前目标版本下为正规 API
    private Point screenSize() {
        Point p = new Point();
        windowManager.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private int getScreenWidth() {
        return screenSize().x;
    }

    private int getScreenHeight() {
        return screenSize().y;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, context.getResources().getDisplayMetrics());
    }

    private static int clamp(int min, int value, int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }

    // ==================== 全屏容器：点面板外收起（§7.3） ====================

    /**
     * 拦截规则：DOWN 落在面板矩形之外 → 拦截并消费（触发收起）；
     * 之内 → 不拦截，交给面板子 View 正常响应点击。
     */
    private class OutsideDismissFrameLayout extends FrameLayout {

        OutsideDismissFrameLayout(Context ctx) {
            super(ctx);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                float tx = ev.getRawX();
                float ty = ev.getRawY();
                if (!isInsidePanel(tx, ty)) {
                    hide();          // 点击面板外区域收起面板
                    callbackOnOutsideTap();
                    return true;     // 本次事件由 root 消费
                }
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // 消费被拦截下来的"面板外"触摸，防止穿透到底层应用
            return true;
        }

        private void callbackOnOutsideTap() {
            // 通知服务面板已收起（重置其内部状态），复用 hide 的对外语义
            if (callback instanceof FloatingBallService) {
                ((FloatingBallService) callback).onPanelDismissedByOutsideTap();
            }
        }
    }

    private boolean isInsidePanel(float rawX, float rawY) {
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + panel.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + panel.getHeight();
    }
}
