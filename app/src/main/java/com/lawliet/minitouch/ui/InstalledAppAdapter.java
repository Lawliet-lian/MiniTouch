package com.lawliet.minitouch.ui;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lawliet.minitouch.R;
import com.lawliet.minitouch.core.AppInfo;
import com.lawliet.minitouch.core.AppRepo;
import com.lawliet.minitouch.core.PrefsRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已安装 App 勾选列表（F8：图标 + 名称 + 勾选 + 拖拽排序）。
 *
 * 显示顺序 = [已选区（按面板宫格顺序，带序号）] + [未选区（按名称拼音序）]：
 * - 所见即所得：已选区自上而下的顺序就是面板宫格顺序，排序结果直接可见；
 * - 点整行切换勾选：勾选后移入已选区末尾，取消后回落到未选区的名称序位置；
 * - 按住 ≡ 手柄（或长按整行）拖动，只能在已选区内调整顺序；
 * - 超出上限 MAX_FAVORITES（Q4）时拒绝勾选并 toast；所有改动即落库（无保存按钮）。
 * 搜索框仍留 TODO（M3 阶段补齐）。
 */
public class InstalledAppAdapter extends RecyclerView.Adapter<InstalledAppAdapter.Holder> {

    /** 常用列表（勾选或顺序）变化回调：SettingsActivity 侧整体写 currentFavorites() */
    public interface OnFavoritesChangedListener {
        void onFavoritesChanged();
    }

    /** 局部刷新标记：只更新序号/勾选/手柄/背景，不重载图标，也不触发 change 交叉淡入动画 */
    private static final Object PAYLOAD_STATE = new Object();

    /** 包名 → 名称拼音序下标，作为未选区的排序基准 */
    private final Map<String, Integer> nameOrder = new HashMap<>();
    /** 当前显示顺序：前 favCount 个为已选区（= 宫格顺序），其余为未选区 */
    private final List<AppInfo> rows = new ArrayList<>();
    private int favCount;
    private final OnFavoritesChangedListener listener;

    private RecyclerView recyclerView;
    private ItemTouchHelper touchHelper;
    /** 图标加载器：挂到 RecyclerView 时创建一次，避免每次 bind 都 new */
    private AppRepo iconRepo;

    /** 正在拖拽的行（用于保持拖拽态背景）及其起始位置（松手时判断顺序是否真的变了） */
    private RecyclerView.ViewHolder draggingHolder;
    private int dragFrom = RecyclerView.NO_POSITION;

    public InstalledAppAdapter(List<AppInfo> allApps,
                               List<String> initialFavorites,
                               OnFavoritesChangedListener listener) {
        this.listener = listener;
        Map<String, AppInfo> byPkg = new HashMap<>();
        for (int i = 0; i < allApps.size(); i++) {
            AppInfo app = allApps.get(i);
            nameOrder.put(app.packageName, i);
            byPkg.put(app.packageName, app);
        }
        // 已选区：按持久化顺序取出。已卸载（不在可启动列表中）的包既无法展示也无法取消，
        // 直接丢弃，避免白占名额；下次任意改动落库时一并清掉
        Set<String> picked = new HashSet<>();
        for (String pkg : initialFavorites) {
            AppInfo app = byPkg.get(pkg);
            if (app != null && picked.add(pkg)) {
                rows.add(app);
            }
        }
        favCount = rows.size();
        // 未选区：其余 App 保持名称顺序
        for (AppInfo app : allApps) {
            if (!picked.contains(app.packageName)) {
                rows.add(app);
            }
        }
    }

    /** 当前勾选（= 已选区自上而下的宫格顺序） */
    public List<String> currentFavorites() {
        List<String> out = new ArrayList<>(favCount);
        for (int i = 0; i < favCount; i++) {
            out.add(rows.get(i).packageName);
        }
        return out;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        super.onAttachedToRecyclerView(rv);
        recyclerView = rv;
        iconRepo = new AppRepo(rv.getContext());
        // 拖拽排序：随 Adapter 挂载自动接入，SettingsActivity 无需额外配置
        touchHelper = new ItemTouchHelper(new DragCallback());
        touchHelper.attachToRecyclerView(rv);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        super.onDetachedFromRecyclerView(rv);
        if (touchHelper != null) {
            touchHelper.attachToRecyclerView(null);
            touchHelper = null;
        }
        recyclerView = null;
    }

    @SuppressLint("ClickableViewAccessibility")
    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View item = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_installed_app, parent, false);
        Holder holder = new Holder(item);
        // 整行点击切换勾选：车机上点小勾选框不方便，整行都是触摸目标
        item.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                toggleFavorite(pos);
            }
        });
        // 按下 ≡ 手柄立即进入拖拽（无需长按）；消费 DOWN 事件，避免触发整行点击/按压波纹
        holder.handle.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null
                    && holder.getBindingAdapterPosition() < favCount) {
                touchHelper.startDrag(holder);
                return true;
            }
            return false;
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        AppInfo app = rows.get(position);
        holder.name.setText(app.label);
        holder.pkg.setText(app.packageName);
        // 图标按需加载（N2：不做整表常驻缓存）
        holder.icon.setImageDrawable(app.loadIcon(iconRepo));
        applyRowState(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            // 局部刷新：位置变化后只同步序号与勾选态
            applyRowState(holder, position);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    /**
     * 按行所处分区刷新状态：已选行显示序号徽标 + ≡ 手柄 + 浅蓝底；未选行隐藏二者
     * （INVISIBLE 保持列对齐）。拖拽中的行用不透明白底，浮起时不与下层行重叠透色。
     */
    private void applyRowState(Holder holder, int position) {
        boolean checked = position < favCount;
        holder.check.setChecked(checked);
        holder.order.setText(checked ? String.valueOf(position + 1) : "");
        holder.order.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        holder.handle.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        int bgColor = holder == draggingHolder ? R.color.app_row_dragging
                : checked ? R.color.app_row_selected : android.R.color.transparent;
        holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.getContext(), bgColor));
    }

    /**
     * 切换勾选：
     * - 已选 → 取消：移出已选区，按名称序插回未选区；
     * - 未选 → 勾选：移到已选区末尾（即宫格最后一格），超上限则拒绝并提示。
     */
    private void toggleFavorite(int pos) {
        int target;
        if (pos < favCount) {
            AppInfo app = rows.remove(pos);
            favCount--;
            // 在未选区里找第一个名称序比它靠后的位置插入
            int base = orderOf(app);
            target = favCount;
            while (target < rows.size() && orderOf(rows.get(target)) < base) {
                target++;
            }
            rows.add(target, app);
        } else {
            if (favCount >= PrefsRepository.MAX_FAVORITES) {
                // 超上限：不移动，仅提示（Q4 拍板上限 9）
                Toast.makeText(recyclerView.getContext(),
                        R.string.settings_favorite_limit_reached, Toast.LENGTH_SHORT).show();
                return;
            }
            AppInfo app = rows.remove(pos);
            target = favCount;
            rows.add(target, app);
            favCount++;
        }
        moveRowKeepingScroll(pos, target);
        notifyFavoritesChanged();
    }

    private int orderOf(AppInfo app) {
        Integer i = nameOrder.get(app.packageName);
        return i == null ? Integer.MAX_VALUE : i;
    }

    /**
     * 带动画移动一行，并刷新受影响区间的序号。
     * LinearLayoutManager 以首个可见项为锚点：若被移走/移入的正是首个可见项，列表会"跟着它跑"，
     * 这里记录移动前的首项位置与偏移并恢复，让用户视角保持不动。
     */
    private void moveRowKeepingScroll(int from, int to) {
        LinearLayoutManager lm = recyclerView != null
                && recyclerView.getLayoutManager() instanceof LinearLayoutManager
                ? (LinearLayoutManager) recyclerView.getLayoutManager() : null;
        int first = lm == null ? RecyclerView.NO_POSITION : lm.findFirstVisibleItemPosition();
        View firstView = lm == null ? null : lm.findViewByPosition(first);
        int offset = firstView == null ? 0 : firstView.getTop() - recyclerView.getPaddingTop();

        if (from != to) {
            notifyItemMoved(from, to);
        }
        // [min, max] 内所有行的序号/分区都可能变化（含被移动行自身）
        notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1, PAYLOAD_STATE);

        if (lm != null && first != RecyclerView.NO_POSITION && (first == from || first == to)) {
            lm.scrollToPositionWithOffset(first, offset);
        }
    }

    /** 拖拽过程中实时刷新可见行的序号（不走 notify，避免打断正在进行的拖拽） */
    private void refreshVisibleRows() {
        if (recyclerView == null) {
            return;
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            RecyclerView.ViewHolder vh =
                    recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
            int pos = vh.getBindingAdapterPosition();
            if (vh instanceof Holder && pos != RecyclerView.NO_POSITION) {
                applyRowState((Holder) vh, pos);
            }
        }
    }

    private void notifyFavoritesChanged() {
        if (listener != null) {
            listener.onFavoritesChanged();
        }
    }

    /** 拖拽排序回调：仅已选区可拖、仅能落在已选区内；松手后顺序有变才落库 */
    private class DragCallback extends ItemTouchHelper.Callback {

        @Override
        public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            int pos = vh.getBindingAdapterPosition();
            boolean draggable = pos != RecyclerView.NO_POSITION && pos < favCount;
            return makeMovementFlags(
                    draggable ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            // 长按整行也可拖（未选行 getMovementFlags 为 0，长按无反应）
            return true;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = vh.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION
                    || to >= favCount) {
                return false; // 不允许拖进未选区
            }
            rows.add(to, rows.remove(from));
            notifyItemMoved(from, to);
            refreshVisibleRows();
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
            // 未启用滑动
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
            super.onSelectedChanged(vh, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh instanceof Holder) {
                draggingHolder = vh;
                dragFrom = vh.getBindingAdapterPosition();
                applyRowState((Holder) vh, dragFrom); // 切到拖拽态白底
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            int to = vh.getBindingAdapterPosition();
            boolean changed = dragFrom != RecyclerView.NO_POSITION
                    && to != RecyclerView.NO_POSITION && dragFrom != to;
            draggingHolder = null;
            dragFrom = RecyclerView.NO_POSITION;
            refreshVisibleRows(); // 恢复背景并校正序号
            if (changed) {
                notifyFavoritesChanged();
            }
        }
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView order;
        final ImageView icon;
        final TextView name;
        final TextView pkg;
        final View handle;
        final CheckBox check;

        Holder(View itemView) {
            super(itemView);
            order = itemView.findViewById(R.id.tv_order);
            icon = itemView.findViewById(R.id.iv_app_icon);
            name = itemView.findViewById(R.id.tv_app_name);
            pkg = itemView.findViewById(R.id.tv_app_pkg);
            handle = itemView.findViewById(R.id.iv_drag_handle);
            check = itemView.findViewById(R.id.cb_favorite);
        }
    }
}
