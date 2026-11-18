# MiniTouch：最近任务重复卡片排查记录

> 此方案已停止，以下保留实机排查和历史设计记录。

> 给执行者（Codex）的完整上下文与决策清单。所有决策已定，按本文实现即可；
> 文中标注「待核实」「Phase 0 核实」的地方，必须先在车机上取证，再按取证结果填参数，不得凭猜测硬编码。
>
> 目标工程：`/Users/lawliet/Documents/WorkSpace/Lawliet-lian/MiniTouch`（包名 `com.lawliet.minitouch`）。
> 本文暂存在 MiAuto 仓库，执行前建议复制到 MiniTouch 的 `docs/最近任务去重-执行方案.md`。

---

## 0. 执行约束（必须遵守）

1. **全程中文**：代码注释、日志说明、文档、对话都用中文；代码注释要详细，风格对齐现有文件（`§x.x` 引用设计文档章节的写法可沿用）。
2. **禁止 root**：不得使用 `adb root`、`su`，也不得借助任何已是 root 的进程。
3. **风险命令先确认**：对车机的写操作（安装 APK、`uiautomator dump` 写 `/sdcard`、`settings put`、`pm grant`、`am force-stop` 等）先向用户说明，确认后再执行；只读命令（`dumpsys`、`logcat -d`、`getprop`、`wm size`）可直接执行。
4. **不提交代码**：不 `git commit`、不 `push`，由用户自己提交。
5. **工作区有未提交改动**：
   - 已修改：AndroidManifest.xml、NavAccessibilityService.java、PrefsRepository.java、FloatingBallService.java、PanelController.java、SettingsActivity.java、activity_settings.xml、strings.xml；
   - 未跟踪：README.md、AccessibilityRecovery.java。
   - 这些是用户进行中的工作，**在其基础上增量修改，不得回滚、覆盖或 `git checkout`**。
6. **不改版本号**：`versionCode 1` / `versionName "0.1.0-skeleton"` 保持不变，由用户决定。

---

## 1. 背景与目标

### 1.1 现象（吉利 AC1E 车机实测，Android 9）

`adb shell dumpsys activity recents` 共 18 条，其中 `com.android.car.carlauncher/.AtcCarLauncher` 占 5 条：

| 任务 | sz | StackId | 说明 |
|---|---|---|---|
| #453 | 1 | 5 | 正在运行的真实任务 |
| #446 / #438 / #427 / #404 | 0 | -1 | 往次开机恢复出来的空壳 |

根因（系统行为，第三方应用无法从源头修复）：
- AtcCarLauncher 声明了 `taskAffinity=""` + `singleTask`，系统只能用 `Intent.filterEquals` 去重；
- Android 9 持久化任务时，`Intent.saveToXml` **不保存 package 字段**。恢复后旧任务的 intent 没有 `pkg`，而系统（uid 1000）每次启动它时 intent 带 `pkg=com.android.car.carlauncher`，两者永远比较不等；
- 结果：每次冷启动后第一次打开它，最近任务就多一张空壳。

空壳**不占进程、不占 CPU**，只是最近任务界面看着乱。

### 1.2 为什么只能走无障碍

- 普通应用调用 `ActivityManager.getAppTasks()` 只能拿到**自己**的任务；
- 跨应用删除任务需要 `android.permission.REMOVE_TASKS`（`signature` 级），MiniTouch 拿不到；
- Shizuku 在 Android 9 非 root 模式下每次重启都要用电脑 adb 重新启动，而车辆每次锁车都会冷启动，不可行。

所以唯一可行、重启后仍然有效的方式是：**MiniTouch 的无障碍服务打开最近任务界面，读取卡片节点，把重复卡片逐个划掉。**

### 1.3 目标

给 MiniTouch 增加「最近任务一键去重」：
- 在最近任务界面里，**同一标题的卡片只保留最新的一张，其余划掉**；
- 只有一张的卡片（包括单张 `sz=0` 的历史卡片）**一律不动**。它们是正常的跨重启历史，点击可以重新打开应用；
- 永远不点「全部清除」。

---

## 2. 关键判定规则

### 2.1 UI 上分不出活卡和空壳

无障碍只能看到界面节点：活卡和空壳显示同样的标题和图标，`sz`、`StackId` 在 UI 上不可见。所以只能**按标题去重**。

### 2.2 保留最近的那张

最近任务在系统 `RecentTasks.mTasks` 里的排序规则：
- 本次开机后新建或被重新激活的任务插在**最前**；
- 开机加载的历史任务追加在**后面**。

因此在同标题的一组卡片里，**排在最近一端的那张就是活卡**（如果存在活卡）。保留它、划掉其余，就不会误杀正在运行的任务。

> 为什么这很重要：Android 9 划掉一个**活**任务会 finish 其中的 Activity，进程如果没有其他组件，还可能被杀；划掉空壳则没有任何副作用。

### 2.3 已知的可接受误伤

- 同一应用的两个不同入口，如果标题相同（例如 `AtcCarLauncher` 和 `AppGridActivity` 可能都显示同一个应用名），旧的那张会被划掉。它们都是桌面类历史卡片，划掉后点图标可以重新打开，可以接受。
- 设置的 `.Settings` 与「无障碍设置」如果标题相同，同理。

### 2.4 永不操作的节点

- 文本或描述包含「全部清除 / 清除全部 / 全部关闭 / Clear all」的按钮；
- 标题为空、或无法定位到所属卡片的节点；
- 不属于最近任务窗口包名的任何节点。

---

## 3. Phase 0：车机取证（编码前必须完成）

目标：确认车机最近任务界面的节点结构，用真实值替换下文所有「待核实」参数。

### 3.1 已知信息

- 最近任务界面由 `com.android.systemui/.recents.RecentsActivity` 提供（已由 `am_create_activity` 日志实测确认），即 Android 9 AOSP 的 legacy Recents（`TaskStackView`）。
- 下表是 AOSP 参考值，**全部待核实**，车机 ROM 可能做过定制：

| 用途 | AOSP 预期 |
|---|---|
| 窗口包名 | `com.android.systemui` |
| 卡片根节点类名 | `com.android.systemui.recents.views.TaskView`（网格模式下为 `GridTaskView`） |
| 卡片标题 | `com.android.systemui:id/title` |
| 卡片关闭按钮 | `com.android.systemui:id/dismiss_task`（contentDescription 类似「关闭 xxx」「移除 xxx」） |
| 全部清除按钮 | `com.android.systemui:id/button` |
| 可滚动容器 | `TaskStackView`，支持 `ACTION_SCROLL_FORWARD/BACKWARD` |

### 3.2 取证步骤

1. 让最近任务里出现重复卡片：冷启动一次车机，打开过 AtcCarLauncher 即可。
2. 只读抓取任务列表：`adb shell "dumpsys activity recents" > recents.txt`
3. 打开最近任务界面后抓取节点树，**二选一**：
   - **方式 A（推荐，零副作用）**：先实现第 5.6 节的调试转储，让 MiniTouch 自己把节点树打印到 logcat（tag `RecentsDedupe`），再执行 `adb logcat -d -s RecentsDedupe`。
   - **方式 B**：`adb shell uiautomator dump /sdcard/recents.xml && adb pull /sdcard/recents.xml`
     - 会写 `/sdcard`，需要用户确认；
     - 注意 Android 9 的 uiautomator 默认会**暂时压制其他无障碍服务**。执行后要用 `adb shell settings get secure enabled_accessibility_services` 确认 MiniTouch 仍在列表中（AccessibilityRecovery 也可能自动补回）。
4. 把节点树和 `recents.txt` 对照，确认以下几项，并写进第 6 节「核实记录」：
   1. 窗口包名；
   2. 卡片根节点的类名或 ID；
   3. 标题节点 ID，以及标题与应用名的对应关系；
   4. 关闭按钮的 ID 和 contentDescription，是否可点击；
   5. 卡片是否支持 `ACTION_DISMISS`；
   6. **屏幕方向与新旧顺序的关系**：最近的卡片在列表哪一端（上/下/左/右），用 `recents.txt` 的顺序对照；
   7. 是否需要滚动才能看到全部卡片：滚动容器是哪个节点；`TYPE_VIEW_SCROLLED` 事件是否带 `fromIndex/itemCount`；卡片节点是否有 `CollectionItemInfo`。

---

## 4. 交互设计（已定）

| 入口 | 行为 |
|---|---|
| 面板「多任务」按钮**单击** | 保持现状，只打开最近任务。若设置开关「打开多任务时自动去重」已开启，打开后自动执行去重 |
| 面板「多任务」按钮**长按** | 不论开关状态，打开最近任务并执行一次去重 |
| 设置页新增开关「打开多任务时自动去重」 | 默认**关闭**；pref key 为 `recents_dedupe_on_open` |

执行反馈：
- 结束后弹 Toast：「已清理 N 张重复卡片」/「没有发现重复卡片」/「去重已中止：<原因>」；
- 去重完成后停留在最近任务界面，不自动返回，方便用户确认结果。

---

## 5. 实现方案

### 5.1 新增文件

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/lawliet/minitouch/accessibility/RecentsDedupeEngine.java` | 去重流程：等待窗口、扫描、分页对齐、挑出重复、划掉、校验、结束回调。运行在主线程 Handler 上，用 `postDelayed` 串联各步骤；**不得阻塞主线程，禁止 `Thread.sleep`** |
| `app/src/main/java/com/lawliet/minitouch/accessibility/DedupePlanner.java` | **纯 Java、无 Android 依赖**的决策逻辑：标题归一化、分页对齐、按「保留最新」挑出待划掉的全局下标。方便做单元测试 |
| `app/src/test/java/com/lawliet/minitouch/accessibility/DedupePlannerTest.java` | DedupePlanner 的 JUnit 测试 |

### 5.2 去重算法

参数常量统一定义在 RecentsDedupeEngine 顶部，并带中文注释。标「待核实」的，用 Phase 0 的结果填写：

```
RECENTS_PACKAGE        = "com.android.systemui"                 // 待核实
TITLE_VIEW_ID          = "com.android.systemui:id/title"        // 待核实
DISMISS_VIEW_ID        = "com.android.systemui:id/dismiss_task" // 待核实
WINDOW_WAIT_TIMEOUT_MS = 2000   // 等待最近任务窗口出现
STEP_DELAY_MS          = 450    // 每次划掉或滚动后，等动画结束
VERIFY_TIMEOUT_MS      = 1200   // 划掉后确认卡片消失的最长等待
MAX_DISMISS            = 20     // 单次最多划掉张数
MAX_SCROLL             = 15     // 单次最多滚动次数
TOTAL_TIMEOUT_MS       = 20000  // 整个流程的总超时
```

流程：

1. **前置检查**：服务未连接时返回失败，交给 FloatingBallService 现有的 `handleNavigationFailure()` 处理。已有去重在运行时，忽略新请求（单例 + `running` 标志）。
2. **打开并等待**：
   - 调用 `performGlobalAction(GLOBAL_ACTION_RECENTS)`；如果是单击入口，界面已经打开，就不要重复打开；
   - 每 100ms 轮询一次 `getRootInActiveWindow()`，直到根节点包名等于 `RECENTS_PACKAGE`，并且至少能找到一张卡片；
   - 超时则中止。
3. **全量采集（只读，不划）**：
   - 先朝「最近」一端滚动到头（滚动失败即视为到头）；
   - 读取当前页可见卡片，按「最近 → 最旧」的屏幕顺序得到标题序列 `page`；
   - 用 `DedupePlanner.align(globalList, page)` 把 `page` 拼接进全局序列：找出 `globalList` 尾部与 `page` 头部的最大重叠长度 k，再追加 `page[k..]`；
   - 朝「最旧」一端滚动一步，重复上述过程，直到滚动失败或页面内容不再变化；
   - **安全阀**：重叠对齐出现歧义（多个 k 都成立且结果不同），或滚动报告成功但页面没有变化时，**中止，且不划任何卡片**；
   - 如果 Phase 0 发现卡片节点带 `CollectionItemInfo`，或滚动事件带 `fromIndex`，**优先使用真实下标**，不用重叠对齐；
   - 如果 Phase 0 确认一屏能放下全部卡片，就跳过滚动。
4. **挑出重复**：调用 `DedupePlanner.pickDuplicates(globalList)`，返回待划掉的全局下标。规则：按归一化标题分组，每组保留下标最小（即最近）的一张，其余全部划掉。
5. **从最旧端往最近端逐个划掉**：
   - 划掉旧卡片不会改变更近卡片的下标，所以从下标最大的开始；
   - 每次划掉前，在当前页重新定位目标卡片，并**核对标题一致**；不一致就中止；
   - 划掉方式按优先级依次尝试：
     1. 点击卡片内的关闭按钮（`DISMISS_VIEW_ID`，执行 `ACTION_CLICK`）；
     2. 卡片节点支持 `AccessibilityAction.ACTION_DISMISS` 时，执行它；
     3. 兜底：`dispatchGesture`，在卡片 bounds 内按 Phase 0 确认的方向滑动，时长 250ms；
   - 划掉后，在 `VERIFY_TIMEOUT_MS` 内确认卡片已消失（同标题卡片数减 1）；确认失败就中止，避免死循环；
   - 成功后从 `globalList` 删掉这一项；如有需要，滚动到下一个目标所在的页。
6. **结束**：回调结果（划掉数量或中止原因），并输出汇总日志。

**中止条件**（任一满足即停止，已划掉的不回滚）：
- 当前活动窗口的包名不再是 `RECENTS_PACKAGE`（用户点了卡片或回到桌面）；
- 达到总超时、划掉次数上限或滚动次数上限；
- 对齐出现歧义、目标核对失败、划掉后校验失败；
- 无障碍服务断开（`onUnbind` / `onInterrupt`）。

**节点回收**：Android 9 的 `AccessibilityNodeInfo` 用完必须 `recycle()`。每轮扫描结束后统一回收，不要跨轮缓存节点对象，只缓存标题、bounds 等纯数据。

### 5.3 DedupePlanner 接口

```java
// 纯逻辑，无 Android 依赖
public final class DedupePlanner {
    /** 标题归一化：去掉首尾空白，连续空白合并为一个空格；结果为空串时返回 null（不参与去重） */
    static String normalize(String title);

    /**
     * 把新页拼接到全局序列，返回新的全局序列；对齐有歧义时抛 IllegalStateException。
     * 约定：两个序列都按「最近 → 最旧」排列。
     */
    static List<String> align(List<String> global, List<String> page);

    /** 返回待划掉的全局下标，按从大到小排序；每个标题保留下标最小的一张；null 标题跳过 */
    static List<Integer> pickDuplicates(List<String> global);
}
```

### 5.4 修改现有文件

| 文件 | 改动 |
|---|---|
| `res/xml/accessibility_service_config.xml` | <ul><li>`canRetrieveWindowContent="true"`</li><li>`canPerformGestures="true"`（兜底滑动用）</li><li>`accessibilityFlags="flagDefault\|flagReportViewIds\|flagIncludeNotImportantViews"`</li><li>`accessibilityEventTypes="typeWindowStateChanged\|typeViewScrolled"`</li><li>`packageNames` **不设置**，避免影响现有的全局动作</li><li>同步更新文件头注释</li></ul> |
| `res/values/strings.xml` | <ul><li>`a11y_service_desc` 改为如实描述：除三个全局动作外，**仅在用户触发去重时**读取最近任务界面的卡片标题并划掉重复卡片，不读取其他应用的内容</li><li>新增开关文案、开关说明、三种 Toast 文案</li></ul> |
| `accessibility/NavAccessibilityService.java` | <ul><li>新增静态方法 `startRecentsDedupe(boolean alreadyOpened, DedupeCallback cb)`，内部委托 RecentsDedupeEngine</li><li>`onAccessibilityEvent` 把 `TYPE_WINDOW_STATE_CHANGED`、`TYPE_VIEW_SCROLLED` 转发给正在运行的引擎；没有任务时照旧忽略</li><li>`onUnbind`、`onDestroy` 时中止引擎</li><li>更新类注释中过时的「仅留桩」描述</li></ul> |
| `overlay/PanelController.java` | <ul><li>给 `btn_nav_recents` 加长按监听</li><li>Callback 接口新增 `void onRecentsDedupeRequested();`</li></ul> |
| `overlay/FloatingBallService.java` | <ul><li>实现 `onRecentsDedupeRequested()`：收起面板 → 启动去重 → 在回调里弹 Toast</li><li>`NAV_RECENTS` 单击分支：`performRecents()` 成功且开关已开启时，以 `alreadyOpened=true` 启动去重</li></ul> |
| `core/PrefsRepository.java` | <ul><li>新增 key `recents_dedupe_on_open`（默认 `false`）及 getter/setter，风格对齐 `quick_switch_mode`</li><li>「恢复默认」不重置它，与闪切开关一致</li></ul> |
| `ui/SettingsActivity.java` + `res/layout/activity_settings.xml` | 在闪切开关附近新增 Switch「打开多任务时自动去重」及说明文字，切换即持久化 |
| `app/build.gradle` | 新增 `testImplementation 'junit:junit:4.13.2'` |
| `docs/车机悬浮球迷你版-技术设计文档.md` | <ul><li>新增一节「最近任务去重」：背景、规则、算法、安全阀、Phase 0 核实结果</li><li>同步无障碍能力描述的变更</li></ul> |
| `README.md` | <ul><li>增加功能说明</li><li>注明：升级后如果去重提示「读取不到窗口内容」，到系统无障碍设置里把 MiniTouch 关掉再打开一次（新能力需要服务重新绑定）</li></ul> |

### 5.5 日志

- tag 固定为 `RecentsDedupe`，使用 `android.util.Log`；
- 每个阶段一条 `i` 级日志，包括：开始、窗口就绪、每页标题序列、全局序列、待划掉下标、每次划掉的方式和结果、结束汇总（划掉 N 张 / 中止原因 / 耗时 ms）；
- 只记录卡片标题和 bounds，**不记录卡片里的其他文本**。

### 5.6 调试转储（对应 Phase 0 方式 A）

- 在 RecentsDedupeEngine 中提供 `dumpTree(AccessibilityNodeInfo root)`：递归打印每个节点的 className、viewIdResourceName、text、contentDescription、bounds、isClickable、isScrollable、actionList，以及 CollectionItemInfo（如果有）；
- 触发方式：仅在 `BuildConfig.DEBUG` 下生效。设置页加一个调试按钮「转储最近任务节点」：点击后打开最近任务，等窗口就绪后转储一次，不做任何划掉动作；
- release 包不显示这个按钮。

---

## 6. 核实记录（Phase 0 完成后由执行者填写）

取证时间：11-14，几何车机（Android 9，legacy Recents）。方式 A 调试转储 + `dumpsys activity recents` / `dumpsys accessibility` / `wm size`（均为只读）。

| 项 | 实测值 |
|---|---|
| 窗口包名 | `com.android.systemui`（与 AOSP 一致） |
| 卡片根节点类名 / ID | 类名 `android.widget.FrameLayout`、无 ID、clickable=true（不是 `TaskView`）；由「只含一个标题的最高祖先」规则识别，5 张卡片全部正确 |
| 标题节点 ID | `com.android.systemui:id/title`，text 为应用标签（如 `Car Launcher`、`HKSettings`、`无障碍`）；被更近卡片盖住的标题 visible=false，但 text 仍可读 |
| 关闭按钮 ID / 描述 | `com.android.systemui:id/dismiss_task`，desc「移除xxx。」；**visible=false、clickable=false，点不了** |
| 是否支持 ACTION_DISMISS | **不支持**。卡片动作只有 CLICK、LONG_CLICK 和两个自定义动作（0x7f0a0033/0x7f0a0034，推测为分屏）；划掉只能靠水平滑动手势 |
| 最近一端的位置 / 划掉方向 | 最近一端在**最下方**（最下一张 = recents #1）；卡片纵向层叠，越近越靠下且盖在上层，旧卡片只露出顶部一条（实测最窄 10px）。划掉方向待去重实测 |
| 是否需要滚动、下标来源 | 需要：屏幕上只有 5 张，recents 共 19 张（不含桌面与 Recents 自身），6 张 AtcCarLauncher 空壳都在屏外。滚动容器是 `android.widget.ScrollView`（TaskStackView），刚打开时只提供 `SCROLL_BACKWARD`。卡片节点无 `CollectionItemInfo`。**滚动事件带真实序号**：`TYPE_VIEW_SCROLLED` 的 `fromIndex`/`toIndex` = 可见卡片中最旧/最近的 stack 序号（0 = 最旧），`itemCount` = 卡片总数，`contentDescription` = 最近一张可见卡片的标题；实测 `fromIndex=16、toIndex=20、itemCount=21`，与屏幕上 5 张卡片一致 |
| 屏幕分辨率（`adb shell wm size`） | 1920x720，density 160 |

补充结论：

- **覆盖安装后的能力生效**：从无「读取窗口内容」能力的旧版本升级时，必须到系统无障碍设置里关闭再开启 MiniTouch 一次；之后 `dumpsys accessibility` 显示 `capabilities=33`（读取窗口内容 + 手势）。日常重启不受影响，仅在新增无障碍能力的升级时需要。
- **滚动方向**：按 AOSP TaskStackView 源码，`SCROLL_BACKWARD` 移向更旧、`SCROLL_FORWARD` 移向更近。实测：刚打开时系统没有焦点任务，第一次 `SCROLL_BACKWARD` 只是对齐焦点（可见区间不变）；`SCROLL_FORWARD` 让卡片继续上移、`scrollY` 11400 → 11547，确认朝最近端。有焦点任务后 `SCROLL_BACKWARD` 是否逐张朝最旧端，由新版调试转储的逐步探测确认。
- **可能的回绕**：无障碍滚动走焦点切换逻辑（AOSP 用取模计算下一个焦点），到尽头时可能绕回另一端；手指拖动走有边界的滚动器，不会绕回（用户实测）。两者路径不同，因此代码按「可能回绕」兜底：只在那一端还有没看到的卡片时才朝那一端滚，且每次滚动后检查序号区间是否与上一次相邻、方向正确，整段跳走即中止（`INDEX_JUMP`），一张不划。调试转储会滚到序号 0 后再多滚一步，用于实测是否回绕。
- **手势落点**：原先取标题行中线，会落到更近的卡片上（例如划第一张 Car Launcher 实际会划到第二张）。已改为取卡片露出区域中线；露出不足 40px 时先用焦点滚动展开目标，展开失败即中止。
- **已确认的可接受误伤**：两张 `Car Launcher` 分别是应用列表 AppGridActivity（#3）和本次开机的 AtcCarLauncher 活任务（#5），标题完全相同。打开过应用列表后，去重会划掉 AtcCarLauncher 活卡，用户已确认接受。
- **已解决：同名空壳连续排列的对齐歧义**。已按 §5.2「优先使用真实下标」改为用滚动事件的序号定位，不再按标题重叠对齐：每次滚动后等待动作之后到达的新滚动事件，要求「可见卡片数 = 序号区间长度」且标题与已知序列逐项一致才算锚定；当前页第 i 张（最近→最旧）的 stack 序号为 `toIndex - i`，全局下标 g = `itemCount - 1 - stack`。划掉前核对目标序号在可见区间内、标题一致；划掉后核对卡片总数减 1、页面与「去掉目标后的序列」一致。
- **仍待实测**：有焦点任务后的滚动步长与方向、尽头是否回绕、事件序号与 450ms 后读到的页面是否一致、划掉卡片是否触发滚动事件（不触发时代码会主动滚一步再核对）、兜底手势方向 `SWIPE_DIRECTION`。

---

## 7. 构建与验证

### 7.1 构建（Mac 本机）

工程**没有 gradlew**，使用本机的 Gradle 8.4 和 JDK 17：

```bash
cd /Users/lawliet/Documents/WorkSpace/Lawliet-lian/MiniTouch
export JAVA_HOME="$(jenv javahome)"   # 必须显式导出，否则可能用错 JDK
/Users/lawliet/.gradle/wrapper/dists/gradle-8.4-bin/1w5dpkrfk8irigvoxmyhowfim/gradle-8.4/bin/gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

- 产物路径：`app/build/outputs/apk/debug/app-debug.apk`。核对 mtime，确认产物确实刷新了；
- 命令不要接管道，否则退出码会被管道吞掉。

### 7.2 单元测试（DedupePlannerTest 至少覆盖以下用例）

1. 无重复 → 返回空列表；
2. `[A, B, A, C, A]` → 返回 `[4, 2]`（保留下标 0）；
3. null 或空白标题不参与去重；
4. 归一化后相同（首尾空格、多个空格）视为同一标题；
5. 按序号核对（`matchesAt`）：给定偏移逐项核对标题；越界或空页返回 false；同名卡片连续排列时位置由序号给定，不再歧义；
6. 区间连续性（`isContinuous`）：首次锚定直接通过；朝最旧端滚时区间不变、后移一格、整段相邻都算连续，跳页或方向反了不连续；
7. 回绕识别：在一端继续朝该端滚却整段跳到另一端，判为不连续。

（原「分页对齐 / 歧义对齐」用例随 `align` 一并删除：已改用滚动事件的真实序号定位。）

### 7.3 车机验收（安装 APK 前需用户确认）

1. 冷启动车机，打开一次 AtcCarLauncher，制造重复卡片；用 `dumpsys activity recents` 记录去重前的状态。
2. 长按面板「多任务」：Toast 显示清理数量。再抓一次 `dumpsys`，确认：
   - 每个 `realActivity` 只剩一条（标题相同的不同入口可能也被合并，属于预期）；
   - 原来 `sz≥1` 的活任务仍在，尤其是 #453 这类正在运行的 AtcCarLauncher；
   - 单张 `sz=0` 的历史卡片（文件管理、canbus 等）仍在。
3. 去重过程中点任意一张卡片：流程应立即中止，不再继续划卡片。
4. 没有重复时长按：Toast 显示「没有发现重复卡片」，不划任何卡片。
5. 打开「打开多任务时自动去重」开关后单击多任务：自动去重。关闭开关后单击：只打开界面。
6. 全程确认「全部清除」从未被触发：`dumpsys` 中的非重复卡片都还在。
7. 返回、主页、多任务三个原有功能，以及闪切、自动恢复无障碍，都不受影响。
8. `adb logcat -d -s RecentsDedupe` 日志完整，没有异常栈。

---

## 8. 不做的事

- 不做开机自动去重：开机时自动打开最近任务界面会打断驾驶员操作；
- 不用 `getRecentTasks`、`removeTask`、Shizuku、本机 adb 等越权路径；
- 不区分活卡和空壳（UI 层做不到）；
- 不做多语言（工程目前只有 `values/` 一套中文资源）；
- 不改 MiAuto：MiAuto 自己的重复卡片已在 0.1.7 中由 `removeStaleEmptyTasks` 处理。
