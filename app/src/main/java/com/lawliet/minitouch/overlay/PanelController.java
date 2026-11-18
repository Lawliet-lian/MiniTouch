package com.lawliet.minitouch.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
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
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.lawliet.minitouch.R;
import com.lawliet.minitouch.core.AppInfo;
import com.lawliet.minitouch.core.AppRepo;
import com.lawliet.minitouch.core.PrefsRepository;

import java.util.List;

/**
 * 弹出面板控制器（F2，§5-F2 / §7.1 排布）。
 *
 * 结构：全屏 Overlay 容器（root）+ 锚定面板（view_panel）。
 * - 面板锚定球位置展开，超出屏幕边时由调用方给出 showToLeft 翻转方向；
 * - root 拦截面板外区域的 DOWN 事件 → 收起面板（§7.3"点击面板外收起"）；
 * - 标题行：应用名 + 收起（×）/ 设置（齿轮）按钮；
 * - 中部：每页最多 8 个常用应用，按 4 列 × 2 行排列。
 * - 下排：返回、主页、多任务。
 */
public class PanelController {

    private static final String TAG = "PanelController";

    private static final int PANEL_EDGE_MARGIN_DP = 4;
    private static final int PANEL_EXTRA_WIDTH_DP = 48;
    private static final int PANEL_WIDTH_MIN_DP = 260;
    private static final int GRID_COLUMN_COUNT = 4;
    private static final int GRID_ROW_COUNT = 2;
    /** 两行宫格的目标高度，最终以整块面板的实测高度为准。 */
    private static final float GRID_HEIGHT_RATIO = 0.44f;
    private static final int APPS_PER_PAGE = GRID_COLUMN_COUNT * GRID_ROW_COUNT;
    private static final int MAX_VISIBLE_PAGE_DOTS = 7;
    /** 宫格基础间隙（dp），横向余量还包括额外面板宽度。 */
    private static final int GRID_GAP_DP = 8;
    /** 应用名单行字高以外预留的空间，含字体上下留白和 2dp 间距。 */
    private static final int APP_LABEL_EXTRA_HEIGHT_DP = 8;
    /** 导航键沿用与宫格的高度比例，将原标签的空间用于点击区域。 */
    private static final float NAV_KEY_HEIGHT_RATIO = 0.55f;
    private static final int NAV_KEY_EXTRA_HEIGHT_DP = 12;

    /** 金刚行动作类型（Phase 2 使用） */
    public static final int NAV_BACK = 0;
    public static final int NAV_HOME = 1;
    public static final int NAV_RECENTS = 2;

    /** 面板事件回调，由 FloatingBallService 实现（§4.2 点击分发） */
    public interface Callback {
        /** 上排 App 格被点击 */
        void onAppCellClicked(String pkg);

        /** 长按应用格编辑面板内的名称。 */
        void onEditAppName(String pkg, String originalName);

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
    private int anchorScreenX;
    private int anchorScreenY;
    private boolean expandLeft;

    private ViewPager appsPager;
    private LinearLayout pageDots;
    private List<String> favorites;
    private int currentPage;
    private int pageCount;
    private int gridCellSize;
    private int fittedWidth;
    private int fittedHeight;

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

        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addOnLayoutChangeListener((v, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> root.post(this::fitPanelToWindow));
        bindPanelViews();
    }

    private void bindPanelViews() {
        // 标题行（Q5 拍板：面板标题不做用户自定义，固定显示应用名）
        TextView title = panel.findViewById(R.id.tv_panel_title);
        title.setText(R.string.app_name);

        // 设置齿轮 → 进设置页（§7.3）
        panel.findViewById(R.id.btn_panel_settings).setOnClickListener(v ->
                callback.onSettingsClicked());

        // 收起面板 → 仅关闭当前悬浮窗口，悬浮球与前台服务保持不动（§7.3）
        panel.findViewById(R.id.btn_panel_close).setOnClickListener(v ->
                callback.onClosePanelClicked());

        appsPager = panel.findViewById(R.id.apps_pager);
        pageDots = panel.findViewById(R.id.panel_page_dots);
        appsPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                currentPage = position;
                updatePageDots();
            }
        });

        panel.findViewById(R.id.btn_nav_back).setOnClickListener(v ->
                callback.onNavActionClicked(NAV_BACK));
        panel.findViewById(R.id.btn_nav_home).setOnClickListener(v ->
                callback.onNavActionClicked(NAV_HOME));
        panel.findViewById(R.id.btn_nav_recents).setOnClickListener(v ->
                callback.onNavActionClicked(NAV_RECENTS));
    }

    private void fitPanelToWindow() {
        int width = root.getWidth();
        int height = root.getHeight();
        if (!visible || width <= 0 || height <= 0
                || (width == fittedWidth && height == fittedHeight)) {
            return;
        }
        fittedWidth = width;
        fittedHeight = height;

        int edgeSpace = 2 * dp(PANEL_EDGE_MARGIN_DP);
        int availableWidth = Math.max(1, width - edgeSpace);
        int availableHeight = Math.max(1, height - edgeSpace);
        int padding = panel.getPaddingLeft() + panel.getPaddingRight();
        int gridLabelHeight = context.getResources().getDimensionPixelSize(R.dimen.app_label_text)
                + dp(APP_LABEL_EXTRA_HEIGHT_DP);
        int cellByHeight = (int) (height * GRID_HEIGHT_RATIO / GRID_ROW_COUNT) - gridLabelHeight;
        int cellByWidth = (availableWidth - padding) / GRID_COLUMN_COUNT;
        int minCellSize = dp(GRID_GAP_DP + 8);
        gridCellSize = Math.max(minCellSize, Math.min(cellByWidth, cellByHeight));

        int panelWidth;
        int rows = pageCount > 1 ? GRID_ROW_COUNT
                : (favorites.size() + GRID_COLUMN_COUNT - 1) / GRID_COLUMN_COUNT;
        pageDots.setVisibility(pageCount > 1 ? View.VISIBLE : View.GONE);
        do {
            panelWidth = Math.min(availableWidth, Math.max(dp(PANEL_WIDTH_MIN_DP),
                    gridCellSize * GRID_COLUMN_COUNT + padding + dp(PANEL_EXTRA_WIDTH_DP)));
            sizeNavigationButtons();
            ViewGroup.LayoutParams pagerParams = appsPager.getLayoutParams();
            int contentWidth = Math.max(1, panelWidth - padding);
            if (rows == 0) {
                View emptyPage = createAppPage(0, gridLabelHeight);
                emptyPage.measure(View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                pagerParams.height = emptyPage.getMeasuredHeight();
            } else {
                pagerParams.height = rows * (gridCellSize + gridLabelHeight);
            }
            appsPager.setLayoutParams(pagerParams);
            measurePanel(panelWidth);
            int overflow = panel.getMeasuredHeight() - availableHeight;
            if (overflow <= 0 || gridCellSize == minCellSize) {
                break;
            }
            gridCellSize = Math.max(minCellSize, gridCellSize - (overflow + 1) / 2);
        } while (true);
        int selectedPage = currentPage;
        appsPager.setAdapter(new AppPages(gridLabelHeight));
        appsPager.setCurrentItem(selectedPage, false);
        currentPage = selectedPage;
        updatePageDots();
        positionPanel(panelWidth);
    }

    private void sizeNavigationButtons() {
        int navHeight = Math.max(dp(48), (int) (gridCellSize * NAV_KEY_HEIGHT_RATIO)
                + dp(NAV_KEY_EXTRA_HEIGHT_DP));
        int navPadding = (int) (navHeight * 0.24f);
        int[] navIds = {R.id.btn_nav_back, R.id.btn_nav_home, R.id.btn_nav_recents};
        for (int id : navIds) {
            View button = panel.findViewById(id);
            ViewGroup.LayoutParams params = button.getLayoutParams();
            params.height = navHeight;
            button.setLayoutParams(params);
            button.setPadding(navPadding, navPadding, navPadding, navPadding);
        }
    }

    private void updatePageDots() {
        pageDots.removeAllViews();
        if (pageCount <= 1) {
            return;
        }
        pageDots.setContentDescription(context.getString(
                R.string.panel_page_number, currentPage + 1, pageCount));
        int count = Math.min(pageCount, MAX_VISIBLE_PAGE_DOTS);
        int first = Math.max(0, Math.min(currentPage - count / 2, pageCount - count));
        for (int i = first; i < first + count; i++) {
            View dot = new View(context);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(i == currentPage ? Color.WHITE : 0x66FFFFFF);
            dot.setBackground(circle);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(6), dp(6));
            params.setMarginStart(dp(4));
            params.setMarginEnd(dp(4));
            pageDots.addView(dot, params);
        }
    }

    private View createAppPage(int page, int labelHeight) {
        int start = page * APPS_PER_PAGE;
        int end = Math.min(start + APPS_PER_PAGE, favorites.size());
        List<String> pkgs = favorites.subList(start, end);
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        if (pkgs.isEmpty()) {
            // F8 引导：尚未配置常用 App 时给出文案提示
            TextView empty = new TextView(context);
            empty.setText(R.string.panel_empty_hint);
            empty.setTextColor(0xB3FFFFFF);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setTypeface(empty.getTypeface(), Typeface.BOLD);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            lp.bottomMargin = dp(8);
            grid.addView(empty, lp);
            return grid;
        }
        // 分页时保留两行的空间，末页不足一行也不会移动底部导航键。
        int rows = pageCount > 1 ? GRID_ROW_COUNT
                : (pkgs.size() + GRID_COLUMN_COUNT - 1) / GRID_COLUMN_COUNT;
        int rowHeight = gridCellSize - dp(GRID_GAP_DP) + labelHeight;
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
            rowLp.bottomMargin = dp(GRID_GAP_DP);
            grid.addView(row, rowLp);
            for (int col = 0; col < GRID_COLUMN_COUNT; col++) {
                int index = rowIndex * GRID_COLUMN_COUNT + col;
                View cell = index < pkgs.size()
                        ? createAppCell(pkgs.get(index), gridCellSize)
                        : new Space(context);
                row.addView(cell, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
        return grid;
    }

    private class AppPages extends PagerAdapter {
        private final int labelHeight;

        AppPages(int labelHeight) {
            this.labelHeight = labelHeight;
        }

        @Override
        public int getCount() {
            return pageCount;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            View page = createAppPage(position, labelHeight);
            container.addView(page);
            return page;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object page) {
            container.removeView((View) page);
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object page) {
            return view == page;
        }
    }

    /** 单个 App 格：圆形半透明底图标 + 下方应用名标签；点击/长按绑整格（点名字也能启动），整格按下时圆底同步提亮（§4.2 App 格分发） */
    private View createAppCell(String pkg, int cellSize) {
        // 圆形图标固定大小，名称使用整列宽度。
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView iconView = new ImageView(context);
        iconView.setBackgroundResource(R.drawable.bg_app_cell);
        // drawable 状态跟随父容器：点击落在整格上，圆底 selector 仍能收到 pressed 提亮反馈
        iconView.setDuplicateParentStateEnabled(true);
        Drawable icon = appRepo.loadIcon(pkg);
        if (icon != null) {
            // 图标视觉归一化：裁掉透明边后统一尺寸（用户要求每个图案一样大）
            iconView.setImageDrawable(normalizeIcon(icon));
        }
        int iconSize = cellSize - dp(GRID_GAP_DP);
        int iconPad = (int) (iconSize * 0.16f);
        iconView.setPadding(iconPad, iconPad, iconPad, iconPad);
        cell.addView(iconView, new LinearLayout.LayoutParams(iconSize, iconSize));

        // 优先显示用户自定义名称，其次是应用的本地化名称。
        // 宽度限在格内，超长单行省略，不换行不撑高格子
        TextView label = new TextView(context);
        AppInfo info = appRepo.findByPackage(pkg);
        String originalName = info != null ? info.label : pkg;
        label.setText(prefs.getAppDisplayName(pkg, originalName));
        label.setTextColor(context.getColor(R.color.text_on_panel));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.app_label_text));
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(2);
        labelLp.setMarginStart(dp(GRID_GAP_DP / 2));
        labelLp.setMarginEnd(dp(GRID_GAP_DP / 2));
        cell.addView(label, labelLp);

        cell.setOnClickListener(v -> callback.onAppCellClicked(pkg));
        cell.setOnLongClickListener(v -> {
            callback.onEditAppName(pkg, originalName);
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
     * @param showToLeft 球在右半屏时面板向左展开；否则向右
     */
    public void show(int anchorX, int anchorY, boolean showToLeft) {
        if (visible) {
            return;
        }
        appsPager.setAdapter(null);
        favorites = prefs.getFavoritePkgs();
        pageCount = Math.max(1, (favorites.size() + APPS_PER_PAGE - 1) / APPS_PER_PAGE);
        currentPage = 0;
        fittedWidth = 0;
        fittedHeight = 0;
        anchorScreenX = anchorX;
        anchorScreenY = anchorY;
        expandLeft = showToLeft;
        panel.setVisibility(View.INVISIBLE);
        windowManager.addView(root, rootLp);
        visible = true;
        root.post(this::fitPanelToWindow);
    }

    private void measurePanel(int panelWidth) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    }

    private void positionPanel(int panelWidth) {
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        measurePanel(panelWidth);
        int pw = panel.getMeasuredWidth();
        int ph = panel.getMeasuredHeight();
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        int anchorX = anchorScreenX - location[0];
        int anchorY = anchorScreenY - location[1];
        int edge = dp(PANEL_EDGE_MARGIN_DP);
        int x = clamp(edge, expandLeft ? anchorX - pw - dp(6) : anchorX + dp(6), width - pw - edge);
        int y = clamp(edge, anchorY - ph / 2, height - ph - edge);
        if (lp.width != panelWidth || lp.leftMargin != x || lp.topMargin != y) {
            lp.width = panelWidth;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setMargins(x, y, 0, 0);
            panel.setLayoutParams(lp);
        }
        panel.setVisibility(View.VISIBLE);
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
