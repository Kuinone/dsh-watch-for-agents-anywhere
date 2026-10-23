package cn.dsh.watch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Agents Anywhere 手表客户端主界面。
 *
 * 手表直接连 AA 的云端 / 自托管服务器（方案 A）：邮箱 + 密码登录，会话、通知、
 * 模型切换全部走 AA 的 REST 接口，与手机客户端看到的是同一个世界。
 * 原来的局域网中继 / 发现 / 配对整条链路已删除。
 *
 * 页面用「重建整屏」的方式切换：手表上每屏内容都很小，重建比维护视图差异更不容易出错。
 * 所有网络调用都通过 Api 回到主线程后再重建，因此不存在跨线程改视图的问题。
 */
public class MainActivity extends Activity {
    /** 会话消息的自动刷新间隔；手表上过于频繁会明显耗电。 */
    private static final long SESSION_REFRESH_MS = 4_000L;

    /** 会话页自动刷新间隔：由设置页调整（2/4/8 秒）。 */
    private long sessionRefreshMs() {
        return store.refreshSeconds() * 1_000L;
    }

    /** 字号档位与刷新档位（设置页循环切换用）。 */
    private static final float[] FONT_STEPS = {0.85f, 1.0f, 1.15f, 1.3f};
    private static final String[] FONT_LABELS = {"小", "标准", "大", "特大"};
    private static final int[] REFRESH_STEPS = {2, 4, 8};
    private static final String[] REFRESH_LABELS = {"快（2 秒）", "标准（4 秒）", "省电（8 秒）"};
    /** 会话列表的自动刷新间隔。 */
    private static final long LIST_REFRESH_MS = 10_000L;

    private WatchUi ui;
    private Store store;
    private Api api;

    private final List<Api.Session> sessions = new ArrayList<>();
    private final List<Api.Project> projects = new ArrayList<>();
    private final List<Api.Step> steps = new ArrayList<>();
    /** 当前会话待处理的通知（提问 / 审批），与列表页共享。 */
    private final List<Api.Ask> currentNotices = new ArrayList<>();

    /** 会话页的展示模式：全部步骤 / 只看对话。 */
    private boolean showAllSteps = true;
    /** 当前项目分组标题（空串表示「项目列表」）。 */
    private String currentProject = "";
    /** 当前项目分组的过滤键：项目 id、{@link #ALL_SESSIONS} 或 {@link #NO_PROJECT}。 */
    private String currentProjectId = "";
    /** 历史翻页：当前已载入内容里最早的 orderSeq（orderSeq 序列域），以及是否还有更早的历史。 */
    private long currentOldestOrderSeq;
    private boolean currentHasMore;
    /** 增量轮询游标：timeline 的事件 nextSeq（与 orderSeq 是两个独立序列域）。 */
    private long lastSeq;
    /** 上一次成功渲染的内容指纹，用于避免无变化时重建整屏（闪烁的根因）。 */
    private int lastContentSignature;

    private String currentSessionId = "";
    private String currentSessionTitle = "";
    private Api.Ask currentAsk;
    private boolean currentRunning;
    /** 当前会话是否已接管（false 时只读，发消息/切模型前要先开启）。 */
    private boolean currentSessionTakeover;
    /**
     * 开启接管后待发送的消息：在只读会话里点发送时，先走接管确认页，
     * 确认成功后自动把这排消息发出去。
     */
    private String pendingComposeText;
    private boolean loadingSession;

    private boolean onSessionPage;
    private boolean destroyed;
    /**
     * 正在编辑（发消息 / 打字回答）时暂停自动刷新。
     *
     * 会话页每 4 秒重建一次整屏；若此时用户正在输入，重建会把输入框连同已输入的内容一起丢掉。
     */
    private boolean editing;
    /**
     * 用户刚触摸/滚动过的时间戳。
     *
     * 自动刷新虽然会保留滚动位置，但「重建整屏」本身就会让正在看的内容跳动一下；
     * 用户随手一滑时尤其明显。因此在最近一次交互后的静默窗口内跳过刷新，
     * 让阅读和滑动不被打断。
     */
    private long lastInteractionAt;
    /** 交互后暂停刷新的时长：足够覆盖一次连续滑动。 */
    private static final long INTERACTION_QUIET_MS = 2_500L;
    /** 下次重建整屏时是否回到顶部。 */
    private boolean resetScrollOnNextRender;
    /** 标记「刚从会话页返回」，用于让会话列表回顶部。 */
    private boolean returningFromSession;

    private final android.os.Handler refresh = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sessionTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !onSessionPage) return;
            if (currentSessionId.isEmpty()) return;
            // 正在输入、或用户刚滑过，就跳过本次刷新，避免重建把界面顶掉。
            if (!editing && !recentlyTouched()) loadSession(currentSessionId, true);
            refresh.postDelayed(this, sessionRefreshMs());
        }
    };
    private final Runnable listTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || onSessionPage) return;
            if (!recentlyTouched()) loadSessions(true, false);
            refresh.postDelayed(this, LIST_REFRESH_MS);
        }
    };

    /** 最近是否刚发生过触摸/滚动。 */
    private boolean recentlyTouched() {
        return System.currentTimeMillis() - lastInteractionAt < INTERACTION_QUIET_MS;
    }

    /**
     * 分发触摸事件时记一笔交互时间，并在这里判定横向手势。
     *
     * 手势必须在这一层判定：子层的 ScrollView 会先消费触摸，挂在页面容器上的
     * 监听器收不到完整的横向滑动，左滑会被当成纵向滚动。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_SCROLL) {
            lastInteractionAt = System.currentTimeMillis();
        }
        // 操作菜单已打开时不做手势判定，避免与面板内的点击冲突。
        if (!menuOpen && gesture != null && currentPage != null) {
            boolean consumed = gesture.onTouch(currentPage, event);
            if (consumed) {
                // 横向手势已消费本次事件：补一个取消事件给子视图，避免它们停在按下态。
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    /** 诊断用：把手势判定过程写入 logcat（tag=dshwatch）。 */
    private void debugLog(String message) {
        android.util.Log.d("dshwatch", message);
    }

    /** 手势识别器（与设备密度相关，延迟初始化）。 */
    private WatchUi.HorizontalGesture gesture;
    /** 当前页面的手势回调持有者。 */
    private WatchUi.Page currentPage;
    /** 操作菜单是否打开。 */
    private boolean menuOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new WatchUi(this);
        store = new Store(this);
        // 界面字号缩放来自设置页的持久化配置。
        ui.setFontScale(store.fontScale());
        api = new Api();
        api.bind(store);
        // 允许从外部带入登录信息（例如电脑端生成一条命令让手表直接登录），
        // 省去在手表小键盘上输入的麻烦。
        applyIntentExtras(getIntent());
        if (hasPendingLogin()) {
            // 带参数启动时优先去登录页：已登录过也要允许用新参数覆盖。
            showConnect();
            return;
        }
        // 提醒通知服务：AI 提问/审批时在表盘上提醒（见 Notify；应用在前台时自动去重）。
        if (store.notifyEnabled() && store.paired()) {
            startService(new Intent(this, Notify.class));
        }
        if (store.paired()) {
            currentSessionId = store.lastSession();
            if (currentSessionId.isEmpty()) showSessionList();
            else showSession(currentSessionId);
            // 通知点进来直接跳到对应会话（比「先回列表再找」顺手）。
            openPendingSession();
        } else {
            showConnect();
        }
    }

    /**
     * Activity 已存在时再次 `am start` 会走这里，而不是 onCreate。
     *
     * 不接住它的话，第二次带参数启动会被完全忽略——表现是「命令执行了但手表没反应」，
     * 因为旧的 Activity 还在，onCreate 不会再执行。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntentExtras(intent);
        if (hasPendingLogin()) {
            showConnect();
            return;
        }
        openPendingSession();
    }

    /** 当前是否有待用的登录参数（服务器、邮箱、密码都齐）。 */
    private boolean hasPendingLogin() {
        return !pendingServer.isEmpty() && !pendingEmail.isEmpty() && !pendingPassword.isEmpty();
    }

    /**
     * 读取启动参数并预填登录页。
     *
     * 支持 `--es server <地址>`（https:// 开头）、`--es email <邮箱>`、`--es password <密码>`、
     * `--ez autologin true`（自动提交登录），便于从电脑上用一条 adb 命令把登录信息送到手表。
     */
    private void applyIntentExtras(Intent intent) {
        if (intent == null) return;
        String server = intent.getStringExtra("server");
        String email = intent.getStringExtra("email");
        String password = intent.getStringExtra("password");
        if (server != null && !server.trim().isEmpty()) pendingServer = server.trim();
        if (email != null && !email.trim().isEmpty()) pendingEmail = email.trim();
        if (password != null && !password.isEmpty()) pendingPassword = password;
        // 通知点入带会话 id：进入应用后直接跳到该会话。
        String session = intent.getStringExtra("session");
        if (session != null && !session.trim().isEmpty()) pendingOpenSession = session.trim();
        if (intent.getBooleanExtra("autologin", false) && hasPendingLogin()) {
            autoLogin = true;
        }
    }

    /**
     * 若有待跳转的会话（通知点入），直接打开它。
     * 已在该会话页时跳过，避免刷新把用户的滚动位置重置。
     */
    private void openPendingSession() {
        if (pendingOpenSession.isEmpty() || !store.paired()) return;
        if (pendingOpenSession.equals(currentSessionId) && onSessionPage) {
            pendingOpenSession = "";
            return;
        }
        String sessionId = pendingOpenSession;
        pendingOpenSession = "";
        showSession(sessionId);
    }

    /** 延迟执行（自动登录需要等界面挂上以后再发请求）。 */
    private void postDelayed(final Runnable action, long delayMs) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) action.run();
            }
        }, delayMs);
    }

    private String pendingServer = "";
    private String pendingEmail = "";
    private String pendingPassword = "";
    /** 启动参数要求直接登录（见 applyIntentExtras）。 */
    private boolean autoLogin = false;
    /** 是否把密码记在本机（静默重登依赖它；关掉后令牌过期就要手动重登）。 */
    private boolean rememberPassword = true;
    /** 通知点入要跳转的会话（见 applyIntentExtras / openPendingSession）。 */
    private String pendingOpenSession = "";
    /** 当前前台正在看的会话（通知服务用它去重：正看着的会话不再弹通知）。 */
    static volatile String foregroundSessionId = "";

    /** 手表上弹一个短提示（没有 Toast 时降级为日志）。 */
    private void showToast(String message) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            // 某些手表系统禁用了 Toast，忽略即可。
        }
    }

    /**
     * 把新页面装进窗口并启用。
     *
     * 重建整屏会丢掉滚动位置，而会话页与会话列表都会定时自动刷新——不保留的话
     * 用户根本没法往下翻（列表有几十条，翻一屏就被弹回顶部）。因此这里默认记住
     * 旧页面的滚动位置并还原；确实需要回到顶部时调用 {@link #resetScroll()}。
     *
     * 动画：通过 {@link Page#navTag} 判断「换页」——自动刷新渲染的是同一个标签，
     * 不播动画；真正的导航（前进/返回）才播入场动画。方向优先用 {@link #pendingNav}
     * 显式指定的（返回时从左滑入），否则默认从右滑入（前进的手感）。
     */
    private void render(WatchUi.Page page) {
        if (gesture == null) {
            gesture = new WatchUi.HorizontalGesture(getResources().getDisplayMetrics().density);
        }
        int restore = resetScrollOnNextRender ? 0 : currentScroll();
        resetScrollOnNextRender = false;
        // 换页判定：标签变了才算导航。第一次渲染（启动）不播动画。
        boolean navigate = currentNavTag != null && !currentNavTag.equals(page.navTag);
        String previousTag = currentNavTag;
        int direction = pendingNav != 0 ? pendingNav : (navigate ? inferDirection(previousTag, page.navTag) : 0);
        pendingNav = 0;

        // 换页前收掉残留的菜单浮层（不播关闭动画——马上要换页了）。浮层视图
        // 由下面的替换清理统一移走，这里只复位状态。
        if (menuOpen) {
            menuOpen = false;
            menuBackdrop = null;
            menuWrapper = null;
            editing = false;
        }

        WatchUi.Page previous = currentPage;
        currentPage = page;

        /**
         * 层叠替换（不再用 setContentView）。
         *
         * setContentView 会先清空窗口再挂新页，中间露出一帧窗口背景——这就是
         * 「换页闪」，定时刷新同样中招。改成在同一窗口里先把新页叠上去、同帧
         * 移除旧页和一切残层（菜单、上一页的快照），窗口任何时刻都有内容。
         */
        ViewGroup content = (ViewGroup) findViewById(android.R.id.content);

        // 旧页快照铺底：一般应用的转场是「新页滑入盖住旧页」。快照让旧页在
        // 新页滑入期间保持静止可见，视觉连续；动画结束即移除并回收。
        boolean animate = direction != 0 && !pendingScrollToBottom
                && previous != null
                && previous.root.getWidth() > 0 && previous.root.getHeight() > 0;
        ImageView ghost = null;
        Bitmap shot = null;
        if (animate) {
            try {
                shot = Bitmap.createBitmap(previous.root.getWidth(),
                        previous.root.getHeight(), Bitmap.Config.ARGB_8888);
                previous.root.draw(new Canvas(shot));
                ghost = new ImageView(this);
                ghost.setImageBitmap(shot);
                content.addView(ghost, new FrameLayout.LayoutParams(-1, -1));
            } catch (Throwable ignored) {
                ghost = null;
                shot = null;
            }
        }

        content.addView(page.root, new FrameLayout.LayoutParams(-1, -1));
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            if (child != page.root && child != ghost) content.removeViewAt(i);
        }

        if (animate && ghost != null) {
            // 入场：新页从侧面滑入 + 淡入，盖在静止的旧页快照上（Activity 转场
            // 的样子）。滑入没盖住的部分透出的正是旧页，不存在空帧。
            float travel = getResources().getDisplayMetrics().widthPixels * 0.45f;
            View root = page.root;
            root.setTranslationX(direction > 0 ? travel : -travel);
            root.setAlpha(0f);
            final ImageView ghostView = ghost;
            final Bitmap ghostShot = shot;
            root.animate().translationX(0f).alpha(1f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                    .withEndAction(() -> {
                        content.removeView(ghostView);
                        if (ghostShot != null) ghostShot.recycle();
                    })
                    .start();
            // 初始下移（ScalingLazyColumn 惯例）：新页面首行从「略过边缘」的位置
            // 开始，标题不落在曲面淡出区。短内容会被滚动范围钳回 0。
            final WatchUi.Page landing = page;
            landing.scroll.post(() -> landing.scroll.post(() -> {
                if (landing.scroll.getScrollY() == 0 && landing.scroll.getHeight() > 0) {
                    landing.scroll.scrollTo(0, Math.round(landing.scroll.getHeight() * 0.085f));
                }
            }));
        }
        if (pendingScrollToBottom) {
            pendingScrollToBottom = false;
            // 三连 post：等布局完成、且固定底栏的让位 padding（下一帧才补上）
            // 都生效后再滚，否则最后一屏会被底栏盖住，看起来像「没到底」。
            page.scroll.post(() -> page.scroll.post(() -> page.scroll.post(page::scrollToBottom)));
            return;
        }
        page.scroll.post(() -> page.scroll.scrollTo(0, restore));
    }

    /** 当前页面的导航标签（换页判定用，见 render）。 */
    private String currentNavTag;
    /** 下一次渲染的动画方向：1 前进（从右滑入）、-1 返回（从左滑入）、0 自动。 */
    private int pendingNav;

    /**
     * 换页方向推断：进入浮层页（发消息、答题、模型、接管确认、设置、登录、新建
     * 会话……）是「前进」，回到会话或列表是「返回」。显式 navForward/navBack
     * 优先于此推断，推断不出来的换页一律按前进处理（视觉上仍然平滑）。
     */
    private static final String[] OVERLAY_TAG_PREFIXES =
            {"compose", "ask", "model", "takeover", "settings", "connect", "new-session"};

    private static int inferDirection(String from, String to) {
        if (from == null || to == null || from.equals(to)) return 0;
        boolean fromOverlay = isOverlayTag(from);
        boolean toOverlay = isOverlayTag(to);
        if (toOverlay && !fromOverlay) return 1;
        if (!toOverlay && fromOverlay) return -1;
        if (to.equals("projects") && from.equals("sessions-list")) return -1;
        return 1;
    }

    private static boolean isOverlayTag(String tag) {
        for (String prefix : OVERLAY_TAG_PREFIXES) {
            if (tag.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 标记下一次渲染为「前进」导航。 */
    private void navForward() {
        pendingNav = 1;
    }

    /** 标记下一次渲染为「返回」导航。 */
    private void navBack() {
        pendingNav = -1;
    }

    /** 上一次项目列表页右滑的时间（2.5 秒内连滑两次才退出应用）。 */
    private long lastExitSwipe;

    /**
     * 退出应用：当前页向右滑出并淡出，动画结束后真正退出。
     *
     * 两个防闪的关键：
     * 1. 摘掉定时刷新——动画进行中若整页被重建（setContentView 换掉正在动画的
     *    根视图），看起来就是「闪一下、没有动画」；
     * 2. 在滑出的根视图底下垫一层画布同色底——根视图飞走后露出的是主题窗口
     *    背景（不是画布色），不垫底会闪白/闪黑；finish 时再关掉系统转场，
     *    避免自绘滑出之后又叠一段系统默认的滑动动画。
     */
    private void exitApp() {
        if (currentPage == null || destroyed) {
            finishAndRemoveTask();
            return;
        }
        refresh.removeCallbacks(sessionTicker);
        refresh.removeCallbacks(listTicker);
        editing = false;
        View root = currentPage.root;
        if (root.getParent() instanceof ViewGroup) {
            View backdrop = new View(this);
            backdrop.setBackgroundColor(WatchUi.BG);
            ((ViewGroup) root.getParent()).addView(backdrop, 0,
                    new ViewGroup.LayoutParams(-1, -1));
        }
        float travel = getResources().getDisplayMetrics().widthPixels;
        root.animate().translationX(travel).alpha(0f)
                .setDuration(240)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    if (destroyed) return;
                    finishAndRemoveTask();
                    overridePendingTransition(0, 0);
                })
                .start();
    }

    /** 下一次渲染后滚到底部（进入会话时用，便于直接看到最新结果）。 */
    private void scrollToBottomAfterRender() {
        pendingScrollToBottom = true;
    }

    private boolean pendingScrollToBottom;

    /** 下一次重建时回到顶部（换页时用，避免带着上一页的偏移）。 */
    private void resetScroll() {
        resetScrollOnNextRender = true;
    }

    /** 读取当前屏幕上那个 ScrollView 的滚动位置。 */
    private int currentScroll() {
        View root = getWindow().getDecorView().findViewById(android.R.id.content);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.view.ViewGroup) {
                    android.view.ViewGroup frame = (android.view.ViewGroup) child;
                    for (int j = 0; j < frame.getChildCount(); j++) {
                        View inner = frame.getChildAt(j);
                        if (inner instanceof android.widget.ScrollView) {
                            return ((android.widget.ScrollView) inner).getScrollY();
                        }
                    }
                }
            }
        }
        return 0;
    }

    // ---------------------------------------------------------- 界面设置

    /**
     * 界面设置页。
     *
     * 字号 / 刷新频率用「点一下换一档」的循环选择——手表上塞滑杆太挤，
     * 循环行一眼能看出当前档位，也是 Wear OS 常见的做法。改动即时保存并生效。
     * 从会话页左滑菜单的「界面设置」进入，返回按钮回到来路。
     */
    private void showSettings() {
        onSessionPage = false;
        editing = false;
        WatchUi.Page page = ui.page();
        page.navTag = "settings";
        ui.heading(page, "界面设置", accountDisplayName());

        // ---- 字号 ----
        int fontIndex = nearestIndex(store.fontScale(), FONT_STEPS);
        ui.add(page, ui.row("字号", "当前：" + FONT_LABELS[fontIndex] + "（点一下换一档）", () -> {
            float next = FONT_STEPS[(fontIndex + 1) % FONT_STEPS.length];
            store.setFontScale(next);
            ui.setFontScale(next);
            showToast("字号：" + FONT_LABELS[(fontIndex + 1) % FONT_STEPS.length]);
            showSettings();
        }), 0, 4);

        // ---- 会话刷新频率 ----
        int refreshIndex = nearestIndex(store.refreshSeconds(), REFRESH_STEPS);
        ui.add(page, ui.row("会话刷新", "当前：" + REFRESH_LABELS[refreshIndex], () -> {
            int next = REFRESH_STEPS[(refreshIndex + 1) % REFRESH_STEPS.length];
            store.setRefreshSeconds(next);
            showToast(REFRESH_LABELS[(refreshIndex + 1) % REFRESH_STEPS.length]);
            showSettings();
        }), 0, 4);

        // ---- 进会话页默认「只看对话」 ----
        boolean only = store.onlyConversationDefault();
        ui.add(page, ui.row("默认只看对话", only ? "进会话页隐藏工具与思考" : "进会话页显示全部步骤", () -> {
            store.setOnlyConversationDefault(!only);
            showToast(!only ? "进会话默认只看对话" : "进会话显示全部步骤");
            showSettings();
        }), 0, 4);

        // ---- 提醒通知 ----
        boolean notify = store.notifyEnabled();
        ui.add(page, ui.row("提醒通知", notify ? "AI 提问/审批时震动提醒（约每 30 秒检查）" : "已关闭，只在应用里看得到", () -> {
            store.setNotifyEnabled(!notify);
            showToast(!notify ? "已开启提醒通知" : "已关闭提醒通知");
            if (!notify) {
                startService(new Intent(this, Notify.class));
            } else {
                stopService(new Intent(this, Notify.class));
            }
            showSettings();
        }), 0, 4);

        ui.divider(page);

        // ---- 关于 ----
        LinearLayout about = ui.card();
        about.addView(ui.text("DSH Watch · 1.2", 12, WatchUi.FG));
        TextView detail = ui.text("Agents Anywhere 云客户端\n服务器：" + store.base(), 10, WatchUi.MUTED);
        detail.setPadding(0, ui.dp(4), 0, 0);
        about.addView(detail);
        ui.add(page, about, 0, 8);

        ui.add(page, ui.button(currentSessionId.isEmpty() || !store.paired()
                ? "返回列表" : "返回会话", false, () -> {
            if (currentSessionId.isEmpty() || !store.paired()) showSessionList();
            else showSession(currentSessionId);
        }), 0, 0);
        page.setOnSwipeRight(() -> {
            if (currentSessionId.isEmpty() || !store.paired()) showSessionList();
            else showSession(currentSessionId);
        });
        render(page);
    }

    /** 在升序档位数组里找最接近 value 的下标（设置页回显用）。 */
    private static int nearestIndex(float value, float[] steps) {
        int best = 0;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < steps.length; i++) {
            float delta = Math.abs(steps[i] - value);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }

    private static int nearestIndex(int value, int[] steps) {
        int best = 0;
        int bestDelta = Integer.MAX_VALUE;
        for (int i = 0; i < steps.length; i++) {
            int delta = Math.abs(steps[i] - value);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------- 登录页

    /**
     * 登录页：填服务器地址、邮箱与密码，直连 Agents Anywhere。
     *
     * 从会话页左滑菜单的「设置」进入，未登录时也作为首次启动页使用。
     * 登录成功后密码按「记住密码」开关决定是否存本机——存了才能在令牌
     * 过期（7 天）后静默重登，否则要手动再来一次。
     */
    private void showConnect() {
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        page.navTag = "connect";
        ui.heading(page, "账号", store.paired() ? "已登录" : "登录 Agents Anywhere");

        if (store.paired()) {
            LinearLayout current = ui.card();
            current.addView(ui.text("当前账号", 11, WatchUi.MUTED));
            TextView name = ui.text(accountDisplayName(), 13, WatchUi.FG);
            name.setPadding(0, ui.dp(5), 0, 0);
            current.addView(name);
            TextView server = ui.text(store.base(), 10, WatchUi.MUTED);
            server.setPadding(0, ui.dp(3), 0, 0);
            current.addView(server);
            ui.add(page, current, 0, 10);
            ui.add(page, ui.button(store.lastSession().isEmpty() ? "返回列表" : "返回会话", false, () -> {
                if (currentSessionId.isEmpty()) showSessionList();
                else showSession(currentSessionId);
            }), 0, 8);
        }

        // ---- 已记住的账号：点一条直接切过去，不必重新输密码 ----
        final List<Store.Account> remembered = store.accounts();
        if (remembered.size() > 1 || (remembered.size() == 1 && !remembered.get(0).email.equalsIgnoreCase(store.email()))) {
            ui.add(page, ui.text("已记住的账号", 11, WatchUi.MUTED), 0, 6);
            String currentEmail = store.email();
            String currentBase = store.base();
            for (final Store.Account account : remembered) {
                boolean active = account.email.equalsIgnoreCase(currentEmail) && account.base.equals(currentBase);
                String title = accountDisplayName(account);
                String kind = account.isPublic() ? "云端" : "自建";
                ui.add(page, ui.row(title, kind + " · " + account.base,
                        () -> switchAccount(account)), 0, active ? 8 : 6);
            }
            ui.add(page, ui.text("点一条可直接切过去。", 10, WatchUi.MUTED), 0, 10);
        }

        ui.divider(page);

        final EditText serverField = new EditText(this);
        serverField.setHint("服务器，如 https://web.agents-anywhere.com");
        serverField.setText(pendingServer.isEmpty() ? (store.base().isEmpty() ? "https://web.agents-anywhere.com" : store.base()) : pendingServer);
        serverField.setTextSize(12);
        serverField.setTextColor(WatchUi.FG);
        serverField.setHintTextColor(WatchUi.MUTED);
        serverField.setSingleLine(true);
        serverField.setBackground(ui.surface(WatchUi.SURFACE, 14));
        serverField.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
        ui.add(page, serverField, 0, 8);

        final EditText emailField = new EditText(this);
        emailField.setHint("邮箱");
        emailField.setText(pendingEmail);
        emailField.setTextSize(14);
        emailField.setTextColor(WatchUi.FG);
        emailField.setHintTextColor(WatchUi.MUTED);
        emailField.setSingleLine(true);
        emailField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailField.setBackground(ui.surface(WatchUi.SURFACE, 14));
        emailField.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
        ui.add(page, emailField, 0, 8);

        final EditText passwordField = new EditText(this);
        passwordField.setHint("密码");
        passwordField.setText(pendingPassword);
        passwordField.setTextSize(14);
        passwordField.setTextColor(WatchUi.FG);
        passwordField.setHintTextColor(WatchUi.MUTED);
        passwordField.setSingleLine(true);
        passwordField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setBackground(ui.surface(WatchUi.SURFACE, 14));
        passwordField.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
        ui.add(page, passwordField, 0, 8);

        // 记住密码开关：静默重登的来源；不记的话令牌一过期就要手动登录。
        ui.add(page, ui.row("记住密码",
                rememberPassword ? "已开启 · 令牌过期后自动重登" : "已关闭 · 每次都要手动登录",
                () -> {
                    rememberPassword = !rememberPassword;
                    showConnect();
                }), 0, 6);

        final TextView status = ui.text("", 12, WatchUi.MUTED);
        status.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        ui.add(page, status, 0, 8);

        ui.add(page, ui.button("登录", true, () ->
                loginNow(serverField, emailField, passwordField, status)), 0, 8);

        if (store.paired()) {
            ui.add(page, ui.button("退出登录", false, this::disconnect), 0, 0);
        }

        // 带启动参数时直接登录，免去在手表上再点一次。
        if (autoLogin) {
            autoLogin = false;
            status.setTextColor(WatchUi.MUTED);
            status.setText("正在按启动参数登录…");
            postDelayed(() -> loginNow(serverField, emailField, passwordField, status), 300);
        }

        render(page);
        if (!store.paired() && pendingEmail.isEmpty()) emailField.requestFocus();
    }

    /** 当前账号的显示名：备注 > 昵称 > 邮箱。 */
    private String accountDisplayName() {
        Store.Account account = store.current();
        return account == null ? "未登录" : accountDisplayName(account);
    }

    private static String accountDisplayName(Store.Account account) {
        if (!account.label.isEmpty()) return account.label;
        if (!account.displayName.isEmpty()) return account.displayName;
        return account.email;
    }

    /**
     * 校验三个输入框并登录。
     *
     * 顺序：解析地址 → 健康检查（确认那真是一台 AA 服务器）→ 登录取令牌。
     * 抽成方法是为了让「登录」按钮与 autologin 启动参数走同一条路径。
     */
    private void loginNow(EditText serverField, EditText emailField, EditText passwordField, TextView status) {
        String server = serverField.getText().toString().trim();
        String email = emailField.getText().toString().trim();
        String password = passwordField.getText().toString();
        if (server.isEmpty()) {
            status.setTextColor(WatchUi.WARN);
            status.setText("请先填写服务器地址");
            return;
        }
        if (email.isEmpty()) {
            status.setTextColor(WatchUi.WARN);
            status.setText("请填写邮箱");
            return;
        }
        if (password.isEmpty()) {
            status.setTextColor(WatchUi.WARN);
            status.setText("请填写密码");
            return;
        }
        Store.Endpoint endpoint = Store.parseServer(server);
        if (endpoint == null) {
            status.setTextColor(WatchUi.DANGER);
            status.setText("地址格式不对，示例：https://web.agents-anywhere.com");
            return;
        }
        // url("") 会拼出以 / 结尾的根地址，供 Api 直接拼接路径。
        final String root = endpoint.url("/").replaceAll("/$", "");
        final String finalEmail = email;
        status.setTextColor(WatchUi.MUTED);
        status.setText("正在连接服务器…");
        api.health(root, healthResult -> {
            if (destroyed) return;
            if (!healthResult.ok) {
                status.setTextColor(WatchUi.DANGER);
                status.setText("连不上：" + healthResult.error);
                return;
            }
            status.setTextColor(WatchUi.MUTED);
            status.setText("正在登录…");
            api.login(root, finalEmail, password, loginResult -> {
                if (destroyed) return;
                if (!loginResult.ok) {
                    status.setTextColor(WatchUi.DANGER);
                    status.setText(loginResult.error);
                    return;
                }
                String token = Api.text(loginResult.value, "token");
                long expiresAt = loginResult.value.optLong("expiresAt", 0L);
                String userId = Api.text(loginResult.value, "userId");
                String displayName = Api.text(loginResult.value, "displayName");
                if (token.isEmpty()) {
                    status.setTextColor(WatchUi.DANGER);
                    status.setText("登录响应缺少令牌");
                    return;
                }
                store.saveLogin(root, finalEmail, rememberPassword ? password : "", token,
                        expiresAt, userId, displayName, "");
                pendingServer = "";
                pendingEmail = "";
                pendingPassword = "";
                clearAllState();
                showToast("已登录 " + (displayName.isEmpty() ? finalEmail : displayName));
                showSessionList();
            });
        });
    }

    /** 切换到一个已记住的账号。 */
    private void switchAccount(final Store.Account account) {
        if (account == null) return;
        if (!account.tokenUsable() && !account.valid()) {
            showToast("这个账号缺密码或令牌，请重新登录一次");
            return;
        }
        store.useAccount(account);
        // 换账号等同于换了一个世界：会话、展开状态、列表指纹全部要清，
        // 否则上一个账号的展开卡片会套在新账号的内容上。
        clearAllState();
        showToast("已切换到 " + accountDisplayName(account));
        showSessionList();
    }

    /** 清空与「当前账号/当前会话」相关的全部界面状态。 */
    private void clearAllState() {
        currentSessionId = "";
        currentSessionTitle = "";
        currentAsk = null;
        currentNotices.clear();
        currentRunning = false;
        currentSessionTakeover = false;
        pendingComposeText = null;
        currentOldestOrderSeq = 0;
        lastSeq = 0;
        currentHasMore = false;
        currentProject = "";
        currentProjectId = "";
        steps.clear();
        openGroups.clear();
        openRows.clear();
        lastListSignature = 0;
        lastContentSignature = 0;
        modelProviders.clear();
        modelDefault = null;
        customAnswers.clear();
    }

    /** 退出登录：清掉令牌回到登录页（账号与密码保留，便于下次直登）。 */
    private void disconnect() {
        store.clearToken();
        refresh.removeCallbacks(sessionTicker);
        clearAllState();
        store.setLastSession("", "");
        showConnect();
    }

    // ------------------------------------------------------------ 会话列表页

    /**
     * 进入会话列表页（首次进入或从别处返回时调用）。
     *
     * 这里只负责渲染与安排定时刷新，**不**直接触发列表请求——请求结果回来后会自己
     * 决定要不要重绘。此前写成「渲染 + 强制请求」，而请求回调又会调用本方法，
     * 形成每轮都重建整屏的循环，屏幕因此持续上下抽动。
     */
    private void showSessionList() {
        onSessionPage = false;
        // 从会话页返回时回到列表顶部；列表自身的定时刷新则由 render() 保持位置。
        if (returningFromSession) {
            resetScroll();
            returningFromSession = false;
        }
        refresh.removeCallbacks(listTicker);
        renderSessionListPage();

        // 首次进入需要真实数据；已有数据时后台静默刷新即可。
        if (sessions.isEmpty() && projects.isEmpty()) {
            loadSessions(false, true);
        } else {
            loadSessions(true, false);
        }
        refresh.postDelayed(listTicker, LIST_REFRESH_MS);
    }

    /** 只渲染当前列表页，不发请求、不安排定时器。 */
    private void renderSessionListPage() {
        WatchUi.Page page = ui.page();
        page.navTag = currentProjectId.isEmpty() ? "projects" : "sessions-list";
        if (currentProjectId.isEmpty()) {
            renderProjectList(page);
        } else {
            renderProjectSessions(page);
        }
        // 手势：项目列表页右滑 = 退出应用，但**连滑两次才真的退出**（一般应用的
        // 「再按一次退出」惯例）——单滑只提示，防止从会话一路滑回列表时顺手
        // 把应用滑没了。项目内的会话列表右滑回到项目列表；左滑打开操作菜单。
        page.setOnSwipeRight(() -> {
            if (!currentProjectId.isEmpty()) {
                navBack();
                currentProject = "";
                currentProjectId = "";
                resetScroll();
                showSessionList();
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastExitSwipe < 2500) {
                exitApp();
            } else {
                lastExitSwipe = now;
                showToast("再滑一次退出");
            }
        });
        page.setOnSwipeLeft(() -> showActionMenu(menuForProjects()));
        render(page);
    }

    /** 「全部会话」用一个不与真实项目 id 冲突的哨兵值表示。 */
    private static final String ALL_SESSIONS = "\u0000all";
    /** 「其他」（没挂到任何项目上的会话）的哨兵值。 */
    private static final String NO_PROJECT = "\u0000none";

    /** 按 id 找项目。 */
    private Api.Project findProjectById(String projectId) {
        if (projectId == null || projectId.isEmpty()) return null;
        for (Api.Project project : projects) {
            if (project.projectId.equals(projectId)) return project;
        }
        return null;
    }

    /** 某个分组键下的会话数与状态统计。 */
    private int[] countGroup(String groupId) {
        int count = 0;
        int waiting = 0;
        int running = 0;
        for (Api.Session session : sessions) {
            boolean match;
            if (groupId.equals(NO_PROJECT)) match = findProjectById(session.projectId) == null;
            else match = groupId.equals(session.projectId);
            if (!match) continue;
            count++;
            if (session.waiting) waiting++;
            else if (session.running) running++;
        }
        return new int[]{count, waiting, running};
    }

    /** 首屏：按项目分组，点进去看该项目的会话。 */
    private void renderProjectList(WatchUi.Page page) {
        ui.heading(page, "项目", loadingSession ? "正在刷新…" : "点一个项目看它的会话");

        if (projects.isEmpty() && sessions.isEmpty()) {
            ui.add(page, ui.text(loadingSession ? "正在载入…" : "还没有项目与会话", 13, WatchUi.MUTED), 0, 10);
        }

        for (final Api.Project project : projects) {
            int[] stat = countGroup(project.projectId);
            String subtitle = stat[0] + " 个会话";
            if (stat[1] > 0) subtitle = "待回答 · " + subtitle;
            else if (stat[2] > 0) subtitle = "运行中 · " + subtitle;
            if (!project.path.isEmpty()) subtitle = subtitle + " · " + shortenPaths(project.path);
            View row = ui.row(project.project, subtitle, () -> {
                currentProject = project.project;
                currentProjectId = project.projectId;
                resetScroll();
                showSessionList();
            });
            if (stat[1] > 0 || stat[2] > 0) {
                row.setBackground(ui.surface(WatchUi.SURFACE_HI, 16));
            }
            ui.add(page, row, 0, 7);
        }

        // 没挂到项目上的会话收进「其他」，避免无处可寻。
        int[] orphans = countGroup(NO_PROJECT);
        if (orphans[0] > 0) {
            String subtitle = orphans[0] + " 个会话";
            if (orphans[1] > 0) subtitle = "待回答 · " + subtitle;
            else if (orphans[2] > 0) subtitle = "运行中 · " + subtitle;
            View row = ui.row("其他", subtitle, () -> {
                currentProject = "其他";
                currentProjectId = NO_PROJECT;
                resetScroll();
                showSessionList();
            });
            if (orphans[1] > 0 || orphans[2] > 0) {
                row.setBackground(ui.surface(WatchUi.SURFACE_HI, 16));
            }
            ui.add(page, row, 0, 7);
        }

        ui.add(page, ui.button("全部会话", false, () -> {
            currentProject = "全部";
            currentProjectId = ALL_SESSIONS;
            resetScroll();
            showSessionList();
        }), 4, 7);
        ui.add(page, ui.button("新建会话", false, this::createSession), 0, 7);
        ui.add(page, ui.button("退出登录", false, this::disconnect), 0, 0);
    }

    /** 某个项目分组下的会话列表。 */
    private void renderProjectSessions(WatchUi.Page page) {
        boolean all = ALL_SESSIONS.equals(currentProjectId);
        List<Api.Session> visible = new ArrayList<>();
        for (Api.Session session : sessions) {
            boolean match;
            if (all) match = true;
            else if (NO_PROJECT.equals(currentProjectId)) match = findProjectById(session.projectId) == null;
            else match = currentProjectId.equals(session.projectId);
            if (match) visible.add(session);
        }
        String subtitle = all ? "全部 " + visible.size() + " 个" : visible.size() + " 个会话";
        ui.heading(page, currentProject.isEmpty() ? "会话" : currentProject,
                loadingSession ? "正在刷新…" : subtitle);

        if (visible.isEmpty()) {
            ui.add(page, ui.text("这个项目下没有会话", 13, WatchUi.MUTED), 0, 10);
        }

        for (final Api.Session session : visible) {
            String info = session.subtitle();
            if (!session.runtime.isEmpty()) info = info + " · " + session.runtime;
            View row = ui.row(session.title, info, () -> showSession(session.id));
            if (session.waiting) {
                // 待回答的会话加一个颜色块，扫一眼就能定位。
                row.setBackground(ui.surface(WatchUi.SURFACE_HI, 16));
            }
            ui.add(page, row, 0, 7);
        }

        ui.add(page, ui.button("返回项目", false, () -> {
            currentProject = "";
            currentProjectId = "";
            resetScroll();
            showSessionList();
        }), 4, 7);
        ui.add(page, ui.button("新建会话", false, this::createSession), 0, 7);
        ui.add(page, ui.button("退出登录", false, this::disconnect), 0, 0);
    }

    /** 列表页内容指纹：会话与项目任一变化都要重绘。 */
    private int listSignature(List<Api.Session> sessionList, List<Api.Project> projectList) {
        int signature = sessionList.size() * 31 + projectList.size() * 17;
        for (Api.Session session : sessionList) {
            signature = signature * 31 + session.id.hashCode()
                    + (session.running ? 1 : 0) + (session.waiting ? 2 : 0)
                    + (session.unread ? 4 : 0) + session.title.hashCode();
        }
        for (Api.Project project : projectList) {
            signature = signature * 31 + project.projectId.hashCode() + project.project.hashCode();
        }
        return signature;
    }

    private int lastListSignature;

    private void showListError(String error) {
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        page.navTag = "list-error";
        ui.heading(page, "连不上服务器", "");
        ui.add(page, ui.text(error, 13, WatchUi.DANGER), 0, 12);
        ui.add(page, ui.text("服务器：" + store.base(), 11, WatchUi.MUTED), 0, 12);
        ui.add(page, ui.button("重试", true, this::showSessionList), 0, 8);
        ui.add(page, ui.button("重新登录", false, () -> {
            store.clearToken();
            showConnect();
        }), 0, 0);
        // 错误页也要能开菜单（左滑），否则用户被锁在这一页。
        page.setOnSwipeLeft(() -> showActionMenu(menuForProjects()));
        render(page);
    }

    /** 列表页一次拉取的中间结果：项目与会话两个请求都回来后统一处理。 */
    private static final class ListPayload {
        List<Api.Project> projects = new ArrayList<>();
        List<Api.Session> sessions = new ArrayList<>();
        boolean projectsOk;
        boolean sessionsOk;
        String error = "";
    }

    private void loadSessions(boolean silent, boolean force) {
        if (!store.paired()) return;
        // 只有首次加载才显示「正在刷新…」；定时刷新时不要改副标题，
        // 否则文案在「正在刷新…」与正常描述之间来回跳，屏幕看起来就在抽动。
        if (!silent) loadingSession = true;
        final ListPayload payload = new ListPayload();
        final int[] remaining = {2};
        api.projects(result -> {
            if (destroyed) return;
            if (result.ok) {
                payload.projectsOk = true;
                payload.projects = Api.parseProjects(result.value);
            } else {
                payload.error = result.error;
            }
            finishListRequest(payload, remaining, silent, force);
        });
        api.sessions(result -> {
            if (destroyed) return;
            if (result.ok) {
                payload.sessionsOk = true;
                payload.sessions = Api.parseSessions(result.value);
            } else {
                payload.error = result.error;
            }
            finishListRequest(payload, remaining, silent, force);
        });
    }

    /** 项目、会话两个请求的汇合点：两个都回来（或都失败）后只处理一次。 */
    private void finishListRequest(ListPayload payload, int[] remaining, boolean silent, boolean force) {
        if (remaining[0] > 0 && --remaining[0] > 0) return;
        loadingSession = false;
        if (destroyed || onSessionPage) return;
        if (!payload.projectsOk && !payload.sessionsOk) {
            // 静默刷新失败时保持现有画面：把错误页反复重绘同样会闪。
            if (silent) return;
            sessions.clear();
            projects.clear();
            showListError(payload.error.isEmpty() ? "请求失败" : payload.error);
            return;
        }
        projects.clear();
        projects.addAll(payload.projects);
        sessions.clear();
        sessions.addAll(payload.sessions);
        // 会话本身只带 projectId，这里把它联成项目名，列表按名字分组展示。
        for (Api.Session session : sessions) {
            Api.Project project = findProjectByIdIn(payload.projects, session.projectId);
            if (project != null) {
                session.project = project.project;
                session.projectPath = project.path;
            } else {
                session.project = "其他";
                session.projectPath = "";
            }
        }
        int signature = listSignature(sessions, projects);
        // 内容没变就不重建：列表页每 10 秒刷新一次，重建会让正在滑的内容跳动。
        if (silent && !force && signature == lastListSignature) return;
        lastListSignature = signature;
        // 只重绘列表页，不再走 showSessionList：那条路径会重新发请求，
        // 与本次回调构成循环，表现为屏幕持续抽动。
        renderSessionListPage();
    }

    private static Api.Project findProjectByIdIn(List<Api.Project> projectList, String projectId) {
        if (projectId == null || projectId.isEmpty()) return null;
        for (Api.Project project : projectList) {
            if (project.projectId.equals(projectId)) return project;
        }
        return null;
    }

    // ------------------------------------------------------------ 新建会话

    /** 新建会话第一步：选项目（AA 的 create-and-start 需要projectId）。 */
    private Api.Project chosenProject;

    private void createSession() {
        onSessionPage = false;
        editing = false;
        WatchUi.Page page = ui.page();
        page.navTag = "new-session";
        ui.heading(page, "新建会话", "第 1 步 · 选项目");
        if (projects.isEmpty()) {
            ui.add(page, ui.text("服务器上还没有项目。请先在网页端或电脑端创建一个项目，再回来新建会话。", 12, WatchUi.MUTED), 0, 12);
        }
        for (final Api.Project project : projects) {
            String subtitle = project.path.isEmpty() ? "" : shortenPaths(project.path);
            ui.add(page, ui.row(project.project, subtitle, () -> showNewSessionCompose(project)), 0, 7);
        }
        ui.add(page, ui.button("返回", false, this::showSessionList), 0, 0);
        page.setOnSwipeRight(this::showSessionList);
        render(page);
    }

    /** 新建会话第二步：输入第一条消息。 */
    private void showNewSessionCompose(final Api.Project project) {
        chosenProject = project;
        onSessionPage = true;
        editing = true;
        WatchUi.Page page = ui.page();
        page.navTag = "new-session-compose";
        ui.heading(page, "新会话", clipLine(project.project, 16));
        final EditText input = new EditText(this);
        input.setHint("想先让 Agent 做什么？");
        input.setTextSize(14);
        input.setTextColor(WatchUi.FG);
        input.setHintTextColor(WatchUi.MUTED);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        input.setBackground(ui.surface(WatchUi.SURFACE, 14));
        input.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        ui.add(page, input, 8, 8);
        ui.add(page, ui.button("发送并创建", true, () ->
                sendMessageNew(input.getText().toString().trim())), 0, 8);
        ui.add(page, ui.button("返回", false, () -> {
            editing = false;
            createSession();
        }), 0, 0);
        page.setOnSwipeRight(() -> {
            editing = false;
            createSession();
        });
        render(page);
        input.requestFocus();
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    /**
     * 用第一条消息创建会话：连接器 → 运行时 → create-and-start。
     *
     * 连接器取第一台在线的；运行时优先挑 dsh 且可用的那一个。创建要等服务器
     * 冷启动运行时，读取超时已放宽到 75 秒（与 AA 手机端一致）。
     */
    private void sendMessageNew(String content) {
        final Api.Project project = chosenProject;
        if (project == null || content.isEmpty()) return;
        editing = false;
        WatchUi.Page page = ui.page();
        page.navTag = "new-session-sending";
        ui.heading(page, "正在创建…", clipLine(project.project, 16));
        ui.add(page, ui.text("正在连接运行时并创建会话，最长可能要等一分钟…", 12, WatchUi.MUTED), 0, 8);
        ui.add(page, ui.button("返回列表", false, this::showSessionList), 0, 0);
        render(page);
        api.devices(deviceResult -> {
            if (destroyed) return;
            if (!deviceResult.ok) { showCreateError(deviceResult.error); return; }
            Api.Device target = null;
            for (Api.Device device : Api.parseDevices(deviceResult.value)) {
                if ("online".equals(device.status)) { target = device; break; }
            }
            if (target == null) {
                showCreateError("没有在线的连接器。请先在电脑上打开 Agents Anywhere 连接器。");
                return;
            }
            final String connectorId = target.connectorId;
            api.runtimes(connectorId, runtimeResult -> {
                if (destroyed) return;
                // 默认 dsh；能拿到运行时表就优先挑可用且激活的那一个。
                String runtimeType = "dsh";
                String runtimeId = "";
                if (runtimeResult.ok) {
                    Api.Runtime pick = null;
                    for (Api.Runtime item : Api.parseRuntimes(runtimeResult.value)) {
                        if (!"dsh".equals(item.runtimeType)) continue;
                        if (pick == null || item.active || item.available) pick = item;
                        if (item.active && item.available) break;
                    }
                    if (pick != null) {
                        runtimeType = pick.runtimeType;
                        runtimeId = pick.runtimeId;
                    }
                }
                final String type = runtimeType;
                final String rid = runtimeId;
                api.createSession(connectorId, project.projectId, type, rid, content, "", created -> {
                    if (destroyed) return;
                    if (!created.ok) { showCreateError(created.error); return; }
                    String sessionId = Api.text(created.value, "sessionId");
                    if (sessionId.isEmpty()) {
                        JSONObject session = created.value.optJSONObject("session");
                        if (session != null) sessionId = Api.text(session, "id");
                    }
                    if (sessionId.isEmpty()) { showCreateError("创建响应缺少会话 id"); return; }
                    Api.Session fresh = new Api.Session();
                    fresh.id = sessionId;
                    fresh.title = "新会话";
                    fresh.projectId = project.projectId;
                    fresh.project = project.project;
                    fresh.connectorId = connectorId;
                    fresh.runtime = type;
                    fresh.updatedAt = System.currentTimeMillis();
                    sessions.add(0, fresh);
                    lastListSignature = 0;
                    chosenProject = null;
                    showSession(sessionId);
                });
            });
        });
    }

    /** 新建失败页：给出重试入口，避免用户被困在等待页。 */
    private void showCreateError(String error) {
        chosenProject = null;
        onSessionPage = false;
        WatchUi.Page page = ui.page();
        page.navTag = "new-session-error";
        ui.heading(page, "新建失败", "");
        ui.add(page, ui.text(error, 13, WatchUi.DANGER), 0, 12);
        ui.add(page, ui.button("重选项目", true, this::createSession), 0, 8);
        ui.add(page, ui.button("返回列表", false, this::showSessionList), 0, 0);
        page.setOnSwipeRight(this::showSessionList);
        render(page);
    }

    // ------------------------------------------------------------ 会话页

    /** 进入一个会话：清态、渲染骨架、拉快照、启动定时刷新。 */
    private void showSession(String sessionId) {
        onSessionPage = true;
        editing = false;
        currentSessionId = sessionId;
        currentSessionTitle = titleOf(sessionId);
        Api.Session known = findSession(sessionId);
        // 接管状态先从列表快照里取（列表接口有这个字段），快照回来后再校正。
        currentSessionTakeover = known != null && known.takeover;
        // 「只看对话」默认值来自设置页；进会话时套用一次。
        showAllSteps = !store.onlyConversationDefault();
        currentAsk = null;
        currentNotices.clear();
        currentRunning = false;
        currentOldestOrderSeq = 0;
        lastSeq = 0;
        currentHasMore = false;
        steps.clear();
        customAnswers.clear();
        store.setLastSession(sessionId, currentSessionTitle);
        autoScrollToBottom = true;
        foregroundSessionId = sessionId;
        refresh.removeCallbacks(sessionTicker);
        renderSession("正在载入…");
        // 进会话即清未读；结果无所谓。
        api.markRead(sessionId);
        loadSession(sessionId, false);
        refresh.postDelayed(sessionTicker, sessionRefreshMs());
    }

    /** 从会话列表里找会话标题；列表未载入时退回上次记住的标题。 */
    private String titleOf(String sessionId) {
        for (Api.Session session : sessions) {
            if (session.id.equals(sessionId)) return session.title;
        }
        if (sessionId.equals(store.lastSession())) {
            String stored = store.lastSessionTitle();
            if (!stored.isEmpty()) return stored;
        }
        return "会话";
    }

    /** 从会话列表里找会话对象；找不到返回 null。 */
    private Api.Session findSession(String sessionId) {
        for (Api.Session session : sessions) {
            if (session.id.equals(sessionId)) return session;
        }
        return null;
    }

    /**
     * 载入会话内容。首次进入（非静默）拉整页快照；4 秒一次的静默刷新只拉
     * 增量（changes + state + notices），避免每 4 秒重传 100 条时间线。
     */
    private void loadSession(String sessionId, boolean silent) {
        if (!store.paired() || sessionId == null || sessionId.isEmpty()) return;
        if (silent) {
            pollSessionDelta(sessionId);
            return;
        }
        api.snapshot(sessionId, result -> {
            if (destroyed || !onSessionPage || editing) return;
            if (!sessionId.equals(currentSessionId)) return;
            if (!result.ok) {
                handleSessionLoadError(result.error, false);
                return;
            }
            applySnapshot(sessionId, result.value);
            renderSession("");
        });
    }

    /**
     * 把快照落进界面状态。
     *
     * 快照结构：{ session, state, timeline:{items,nextSeq,hasMore}, notices, catalogs }。
     * 注意两个序列域：timeline.nextSeq 是事件序号（增量轮询游标），
     * 条目上的 orderSeq 是排序/翻历史用的另一套序号，互不通用。
     */
    private void applySnapshot(String sessionId, JSONObject root) {
        JSONObject session = root.optJSONObject("session");
        String title = session == null ? "" : Api.text(session, "title");
        if (!title.isEmpty()) {
            currentSessionTitle = title;
            store.setLastSession(sessionId, title);
        }
        // 快照的 session 对象里有实时的接管状态（列表接口的可能是旧值）。
        if (session != null && session.has("takeover")) {
            currentSessionTakeover = session.optBoolean("takeover", false);
        }
        JSONObject state = root.optJSONObject("state");
        if (state == null) state = root;
        currentRunning = isRunningStatus(Api.text(state, "status"));

        // 快照里的 timeline 是信封对象 { items, nextSeq, hasMore }；
        // 兼容直接给条目数组的服务器实现（此时游标不推进，等下一次 changes）。
        JSONObject timelineEnvelope = root.optJSONObject("timeline");
        JSONArray timeline = timelineEnvelope == null
                ? root.optJSONArray("timeline")
                : timelineEnvelope.optJSONArray("items");
        List<Api.Step> loaded = Api.parseTimelineItems(timeline);
        List<Api.Step> merged = Api.mergeSteps(steps, loaded);
        steps.clear();
        steps.addAll(merged);
        if (timelineEnvelope != null) {
            lastSeq = timelineEnvelope.optLong("nextSeq", 0L);
            currentHasMore = timelineEnvelope.optBoolean("hasMore", false);
        }
        if (!steps.isEmpty()) currentOldestOrderSeq = steps.get(0).seq;

        JSONArray noticesArray = root.optJSONArray("notices");
        if (noticesArray != null) {
            currentNotices.clear();
            currentNotices.addAll(Api.parseNotices(noticesArray));
        }
        currentAsk = currentNotices.isEmpty() ? null : currentNotices.get(0);

        JSONObject catalogs = root.optJSONObject("catalogs");
        if (catalogs != null) {
            JSONObject catalog = catalogs.optJSONObject("model");
            if (catalog != null) {
                modelProviders.clear();
                modelProviders.addAll(Api.parseModels(catalog));
                // 当前生效的选择以会话状态为准；还没有选择记录时用目录 default 兜底。
                Api.ModelChoice fromState = Api.choiceFromSelections(state, modelProviders);
                if (fromState != null && !fromState.model.isEmpty()) modelDefault = fromState;
                else modelDefault = Api.parseModelDefault(catalog);
            }
        }
    }

    private static boolean isRunningStatus(String status) {
        return "running".equals(status) || "stopping".equals(status);
    }

    /** 载入失败：登录过期直接回登录页，其余给错误页。 */
    private void handleSessionLoadError(String error, boolean silent) {
        String message = error == null ? "载入失败" : error;
        if (message.contains("重新登录") || message.contains("登录已过期")) {
            store.clearToken();
            showConnect();
            return;
        }
        // 静默刷新失败时保持现有画面：每 4 秒弹一次错误没有意义。
        if (silent) return;
        renderSessionError(message);
    }

    /** 静默增量轮询：changes + state + notices 三个请求并行，全部回来后统一合并。 */
    private void pollSessionDelta(String sessionId) {
        final JSONObject[] stateBox = new JSONObject[1];
        final JSONArray[] noticesBox = new JSONArray[1];
        final List<Api.Step>[] changesBox = new List[1];
        final long[] nextSeqBox = {-1L};
        final boolean[] changesOk = {false};
        final String[] errorBox = new String[1];
        final int[] remaining = {3};
        final Runnable done = () -> applySessionDelta(sessionId, stateBox[0], noticesBox[0],
                changesBox[0], nextSeqBox[0], changesOk[0], errorBox[0]);
        api.timelineChanges(sessionId, lastSeq, result -> {
            if (destroyed) return;
            if (result.ok) {
                changesOk[0] = true;
                changesBox[0] = Api.parseTimelineItems(result.value.optJSONArray("items"));
                nextSeqBox[0] = result.value.optLong("nextSeq", -1L);
            } else {
                errorBox[0] = result.error;
            }
            finishSessionDelta(remaining, done);
        });
        api.runtimeState(sessionId, result -> {
            if (destroyed) return;
            if (result.ok) {
                JSONObject state = result.value.optJSONObject("state");
                stateBox[0] = state == null ? result.value : state;
            } else {
                errorBox[0] = result.error;
            }
            finishSessionDelta(remaining, done);
        });
        api.notices(sessionId, result -> {
            if (destroyed) return;
            if (result.ok) {
                noticesBox[0] = result.value.optJSONArray("notices");
            } else {
                errorBox[0] = result.error;
            }
            finishSessionDelta(remaining, done);
        });
    }

    private void finishSessionDelta(int[] remaining, Runnable done) {
        if (remaining[0] > 0 && --remaining[0] > 0) return;
        done.run();
    }

    /** 增量结果落盘：内容有变化才重建，否则什么都不做（这是防闪烁的关键）。 */
    private void applySessionDelta(String sessionId, JSONObject state, JSONArray notices,
                                   List<Api.Step> changed, long nextSeq, boolean changesOk, String error) {
        if (destroyed || !onSessionPage || editing) return;
        if (!sessionId.equals(currentSessionId)) return;
        if (error != null && !changesOk && state == null && notices == null) {
            handleSessionLoadError(error, true);
            return;
        }
        boolean contentChanged = false;
        if (changed != null && !changed.isEmpty()) {
            List<Api.Step> merged = Api.mergeSteps(steps, changed);
            steps.clear();
            steps.addAll(merged);
            contentChanged = true;
        }
        if (nextSeq >= 0) lastSeq = nextSeq;
        boolean statusChanged = false;
        if (state != null) {
            boolean running = isRunningStatus(Api.text(state, "status"));
            if (running != currentRunning) {
                currentRunning = running;
                statusChanged = true;
            }
        }
        boolean askChanged = false;
        if (notices != null) {
            List<Api.Ask> parsed = Api.parseNotices(notices);
            if (!sameAsks(parsed)) {
                currentNotices.clear();
                currentNotices.addAll(parsed);
                currentAsk = currentNotices.isEmpty() ? null : currentNotices.get(0);
                askChanged = true;
            }
        }
        if (!contentChanged && !statusChanged && !askChanged) return;
        if (!steps.isEmpty()) currentOldestOrderSeq = steps.get(0).seq;
        int signature = contentSignature();
        if (signature == lastContentSignature) return;
        // 用户正在看底部时跟随新内容；已向上翻动时保持位置。
        autoScrollToBottom = nearBottom();
        renderSession("");
    }

    /** 通知列表是否与当前一致（callId + 问题数足够识别「还是那组问题」）。 */
    private boolean sameAsks(List<Api.Ask> parsed) {
        if (parsed.size() != currentNotices.size()) return false;
        for (int i = 0; i < parsed.size(); i++) {
            if (!parsed.get(i).callId.equals(currentNotices.get(i).callId)) return false;
            if (parsed.get(i).questions.size() != currentNotices.get(i).questions.size()) return false;
        }
        return true;
    }

    /** 当前是否已滚到内容底部附近（重建前判断要不要跟随到底）。 */
    private boolean nearBottom() {
        if (sessionScroll == null) return true;
        View content = sessionScroll.getChildAt(0);
        if (content == null) return true;
        int distance = content.getHeight() - sessionScroll.getScrollY() - sessionScroll.getHeight();
        return distance <= ui.dp(48);
    }

    /** 会话页滚动视图引用（重建时更新），用于「跳到最新」与跟随判断。 */
    private ScrollView sessionScroll;
    /** 进入会话或刚发送消息后跟随到底部一次。 */
    private boolean autoScrollToBottom;

    /** 会话页内容指纹：任何会影响渲染的字段变化都要触发重建。 */
    private int contentSignature() {
        int signature = 17;
        signature = signature * 31 + steps.size();
        for (Api.Step step : steps) {
            signature = signature * 31 + step.id.hashCode()
                    + step.status.hashCode() + (step.text.hashCode() >>> 3);
        }
        signature = signature * 31 + (currentRunning ? 1 : 0);
        signature = signature * 31 + (currentAsk == null ? 0 : 7);
        return signature;
    }

    /** 会话页副标题：状态 + 当前模型。 */
    private String sessionSubtitle() {
        StringBuilder builder = new StringBuilder();
        if (currentRunning) builder.append("正在运行");
        else if (currentAsk != null) builder.append("等你回答");
        else builder.append("空闲");
        Api.ModelChoice choice = currentChoice();
        if (choice != null && !choice.model.isEmpty()) {
            builder.append(" · ").append(shortModel(choice.model));
        }
        // 只读时标出来，配合下方的一键接管按钮。
        if (!currentSessionTakeover) builder.append(" · 只读");
        return builder.toString();
    }

    /** 载入失败页。 */
    private void renderSessionError(String error) {
        onSessionPage = true;
        WatchUi.Page page = ui.page();
        page.navTag = "session:" + currentSessionId;
        ui.heading(page, currentSessionTitle.isEmpty() ? "会话" : currentSessionTitle, "载入失败");
        ui.add(page, ui.text(error, 13, WatchUi.DANGER), 0, 12);
        ui.add(page, ui.button("重试", true, () -> loadSession(currentSessionId, false)), 0, 8);
        ui.add(page, ui.button("重新登录", false, () -> {
            store.clearToken();
            showConnect();
        }), 0, 8);
        ui.add(page, ui.button("返回列表", false, this::backToList), 0, 0);
        page.setOnSwipeRight(this::backToList);
        render(page);
    }

    /**
     * 渲染会话页骨架：标题 + 提示条 + 内容区 + 底部操作条。
     *
     * 定时刷新走到这里时（内容有变化），render() 会保留滚动位置，
     * 因此长对话里刷新不会把用户顶回顶部。
     */
    private void renderSession(String banner) {
        onSessionPage = true;
        WatchUi.Page page = ui.page();
        page.navTag = "session:" + currentSessionId;
        ui.heading(page, currentSessionTitle.isEmpty() ? "会话" : currentSessionTitle,
                sessionSubtitle());
        if (banner != null && !banner.isEmpty()) {
            ui.add(page, ui.text(banner, 11, WatchUi.TEAL), 0, 6);
        }
        // 只读会话给一条常驻提示：一眼知道为什么发不了消息，点一下就能开接管。
        if (!currentSessionTakeover && currentAsk == null) {
            ui.add(page, ui.button("只读模式 · 点此开启接管", false, this::confirmEnableTakeover), 0, 6);
        }
        // 待答问题卡片放在内容最上面，扫一眼就能看到。
        if (currentAsk != null) {
            ui.add(page, ui.button("AI 在等你回答", true, () -> showQuestion(currentAsk)), 0, 6);
        }
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        ui.add(page, contentArea, 0, 0);
        fillContent();
        // 底部三键栏：固定在屏幕底部（不随内容滚动，长对话里随时能按）；
        // 发消息常驻；运行中给停止（运行时不能切模型），空闲给模型；
        // 其余入口全收进「更多」菜单。
        LinearLayout bottom = ui.bottomBar(page);
        bottom.addView(weighted(ui.button("发消息", true, this::showCompose)));
        if (currentRunning) {
            bottom.addView(weighted(ui.button("停止", false, this::cancelRun)));
        } else {
            bottom.addView(weighted(ui.button("模型", false, this::showModelPicker)));
        }
        bottom.addView(weighted(ui.button("更多", false, () -> showActionMenu(menuForSession()))));
        page.setOnSwipeLeft(() -> showActionMenu(menuForSession()));
        page.setOnSwipeRight(this::backToList);
        render(page);
        sessionScroll = page.scroll;
    }

    /** 停止当前生成（AA 的 runtime/interrupt）。 */
    private void cancelRun() {
        if (currentSessionId.isEmpty()) return;
        renderSession("正在请求停止…");
        api.interrupt(currentSessionId, result -> {
            if (destroyed) return;
            if (!result.ok) {
                renderSession("停止失败：" + result.error);
                return;
            }
            showToast("已请求停止");
            loadSession(currentSessionId, false);
        });
    }

    // ------------------------------------------------------------ 接管

    /**
     * 接管确认页。
     *
     * AA 的会话默认只读：发消息、切模型都会被服务器拒绝（"session is read-only
     * until takeover is enabled"）。开启接管是持久状态，官方客户端在开启前会
     * 弹说明确认，这里同样确认一次，并把「确认后继续发送」串在同一条路径里。
     */
    private void confirmEnableTakeover() {
        if (currentSessionId.isEmpty()) return;
        editing = true;
        boolean willSend = pendingComposeText != null && !pendingComposeText.isEmpty();
        WatchUi.Page page = ui.page();
        page.navTag = "takeover-on";
        ui.heading(page, "开启接管？", clipLine(currentSessionTitle, 16));
        ui.add(page, ui.text(
                "开启后可以给这个会话发消息、切换模型。\n\n"
                        + "注意：如果手机或网页正开着这个会话，新消息可能不会立刻出现在那边，重启它们即可同步。",
                12, WatchUi.MUTED), 4, 8);
        ui.add(page, ui.button(willSend ? "开启并发送" : "开启接管", true, this::enableTakeoverNow), 0, 6);
        ui.add(page, ui.button("取消", false, () -> {
            pendingComposeText = null;
            editing = false;
            renderSession("");
        }), 0, 0);
        page.setOnSwipeRight(() -> {
            pendingComposeText = null;
            editing = false;
            renderSession("");
        });
        render(page);
    }

    /** 确认后真正开启接管；带着待发消息时自动接着发送。 */
    private void enableTakeoverNow() {
        final String sessionId = currentSessionId;
        if (sessionId.isEmpty()) return;
        renderSession("正在开启接管…");
        api.enableTakeover(sessionId, result -> {
            if (destroyed) return;
            if (!result.ok) {
                renderSession("开启失败：" + result.error);
                return;
            }
            currentSessionTakeover = true;
            showToast("已开启接管");
            if (pendingComposeText != null && !pendingComposeText.isEmpty()) {
                String text = pendingComposeText;
                pendingComposeText = null;
                sendMessage(text);
            } else {
                loadSession(sessionId, false);
            }
        });
    }

    /** 关闭接管（回到只读）确认页。 */
    private void confirmDisableTakeover() {
        if (currentSessionId.isEmpty()) return;
        editing = true;
        WatchUi.Page page = ui.page();
        page.navTag = "takeover-off";
        ui.heading(page, "关闭接管？", clipLine(currentSessionTitle, 16));
        ui.add(page, ui.text("关闭后会话回到只读，不能再发消息，直到重新开启接管。", 12, WatchUi.MUTED), 4, 8);
        ui.add(page, ui.button("关闭接管", true, () -> {
            final String sessionId = currentSessionId;
            renderSession("正在关闭接管…");
            api.disableTakeover(sessionId, result -> {
                if (destroyed) return;
                if (!result.ok) {
                    renderSession("关闭失败：" + result.error);
                    return;
                }
                currentSessionTakeover = false;
                showToast("已关闭接管");
                loadSession(sessionId, false);
            });
        }), 0, 6);
        ui.add(page, ui.button("取消", false, () -> {
            editing = false;
            renderSession("");
        }), 0, 0);
        page.setOnSwipeRight(() -> {
            editing = false;
            renderSession("");
        });
        render(page);
    }

    // ------------------------------------------------------------ 操作菜单

    private static final class MenuItem {
        final String label;
        final String subtitle;
        final Runnable action;

        MenuItem(String label, String subtitle, Runnable action) {
            this.label = label;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.action = action;
        }
    }

    private List<MenuItem> menuForSession() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("发消息", "给这个会话发一条新消息", this::showCompose));
        if (currentAsk != null) {
            items.add(new MenuItem("回答问题", "AI 正在等你回答", () -> showQuestion(currentAsk)));
        }
        if (currentRunning) {
            items.add(new MenuItem("停止生成", "打断当前回复", this::cancelRun));
        }
        items.add(new MenuItem(currentSessionTakeover ? "关闭接管" : "开启接管",
                currentSessionTakeover ? "会话回到只读，不能再发消息" : "允许发消息与切换模型",
                currentSessionTakeover ? this::confirmDisableTakeover : this::confirmEnableTakeover));
        items.add(new MenuItem(showAllSteps ? "只看对话" : "显示全部步骤",
                showAllSteps ? "隐藏工具与思考卡片" : "恢复工具与思考卡片", () -> {
            showAllSteps = !showAllSteps;
            lastContentSignature = 0;
            fillContent();
        }));
        items.add(new MenuItem("刷新", "重新拉取会话", () -> loadSession(currentSessionId, false)));
        items.add(new MenuItem("模型", "当前：" + currentModelLabel(), this::showModelPicker));
        if (currentHasMore) {
            items.add(new MenuItem("看更早的内容", "向上翻历史", this::loadOlder));
        }
        items.add(new MenuItem("跳到最新", "回到会话底部", () -> {
            if (sessionScroll != null) {
                sessionScroll.post(() -> sessionScroll.fullScroll(View.FOCUS_DOWN));
            }
        }));
        items.add(new MenuItem("界面设置", "字号 · 刷新频率", this::showSettings));
        items.add(new MenuItem("账号与服务器", store.email(), this::showConnect));
        items.add(new MenuItem("返回项目", "", this::backToList));
        return items;
    }

    private List<MenuItem> menuForProjects() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("新建会话", "选一个项目开始", this::createSession));
        if (!ALL_SESSIONS.equals(currentProjectId)) {
            items.add(new MenuItem("全部会话", "不按项目分组", () -> {
                currentProject = "全部";
                currentProjectId = ALL_SESSIONS;
                resetScroll();
                showSessionList();
            }));
        }
        if (!currentProjectId.isEmpty()) {
            items.add(new MenuItem("返回项目", "", () -> {
                currentProject = "";
                currentProjectId = "";
                resetScroll();
                showSessionList();
            }));
        }
        items.add(new MenuItem("刷新", "重新拉取列表", this::showSessionList));
        items.add(new MenuItem("界面设置", "字号 · 刷新频率", this::showSettings));
        items.add(new MenuItem("账号与服务器", store.email(), this::showConnect));
        items.add(new MenuItem("退出登录", "清除令牌，重新登录", this::disconnect));
        return items;
    }

    private View menuBackdrop;
    private ScrollView menuWrapper;

    /**
     * 右侧滑出的操作菜单。
     *
     * 面板贴右边缘、从右滑入——左滑手势是「把右侧的内容拉出来」，方向必须一致。
     * 菜单打开期间 editing = true：4 秒一次的会话刷新会重建整屏，把菜单连根拔掉。
     */
    private void showActionMenu(List<MenuItem> items) {
        if (menuOpen || currentPage == null) return;
        menuOpen = true;
        editing = true;
        android.view.ViewGroup root = (android.view.ViewGroup) findViewById(android.R.id.content);

        menuBackdrop = new View(this);
        menuBackdrop.setBackgroundColor(0x99000000);
        menuBackdrop.setOnClickListener(v -> dismissMenu());
        root.addView(menuBackdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panelContent = new LinearLayout(this);
        panelContent.setOrientation(LinearLayout.VERTICAL);
        panelContent.setBackground(ui.surface(WatchUi.SURFACE, 18));

        TextView title = ui.text("操作", 14, WatchUi.FG);
        title.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(6));
        panelContent.addView(title);
        panelContent.addView(ui.dividerView());
        for (final MenuItem item : items) {
            View row = ui.row(item.label, item.subtitle, () -> {
                dismissMenu();
                if (item.action != null) item.action.run();
            });
            panelContent.addView(row);
        }

        // 菜单项多于屏幕时面板本体也要能滚，所以内容包进 ScrollView。
        int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        menuWrapper = new ScrollView(this);
        menuWrapper.addView(panelContent, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(width, -1);
        panelParams.gravity = Gravity.END;
        root.addView(menuWrapper, panelParams);

        // 入场：从右滑入 + 淡入。
        menuWrapper.setTranslationX(width);
        menuWrapper.setAlpha(0f);
        menuWrapper.animate().translationX(0f).alpha(1f).setDuration(140).start();
        menuBackdrop.setAlpha(0f);
        menuBackdrop.animate().alpha(1f).setDuration(140).start();
    }

    /** 关闭操作菜单（150ms 反向动画，动画结束才移除视图）。 */
    private void dismissMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        editing = false;
        final android.view.ViewGroup root = (android.view.ViewGroup) findViewById(android.R.id.content);
        final View backdrop = menuBackdrop;
        final View panel = menuWrapper;
        menuBackdrop = null;
        menuWrapper = null;
        if (backdrop == null || panel == null) return;
        backdrop.animate().alpha(0f).setDuration(150).start();
        panel.animate().translationX(panel.getWidth() == 0 ? ui.dp(200) : panel.getWidth())
                .alpha(0f).setDuration(150).withEndAction(() -> {
            if (backdrop.getParent() == root) root.removeView(backdrop);
            if (panel.getParent() == root) root.removeView(panel);
        }).start();
    }

    // ------------------------------------------------------------ 内容渲染

    private LinearLayout contentArea;
    /** 展开的工具组（键 = groupKey）。 */
    private final List<String> openGroups = new ArrayList<>();
    /** 展开的行级明细 / 思考块（键 = groupKey#i 或 think:<seq>）。 */
    private final List<String> openRows = new ArrayList<>();

    /** 重建内容区（不重建整屏）。 */
    private void fillContent() {
        if (contentArea == null) return;
        lastContentSignature = contentSignature();
        contentArea.removeAllViews();
        try {
            if (showAllSteps) renderSteps(contentArea);
            else renderMessagesOnly(contentArea);
        } catch (Exception error) {
            debugLog("渲染步骤失败: " + error);
            ui.addTo(contentArea, ui.text("这一段内容渲染失败，可在菜单里换「只看对话」试试。", 12, WatchUi.WARN), 0, 8);
        }
        if (showAllSteps && currentHasMore) {
            ui.addTo(contentArea, ui.button("载入更早的内容", false, this::loadOlder), 0, 8);
        }
        if (autoScrollToBottom) {
            autoScrollToBottom = false;
            scrollToBottomAfterRender();
        }
    }

    /** 两个按钮平分一行的布局助手。 */
    private View weighted(View child) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        child.setLayoutParams(params);
        return child;
    }

    /** 一次连续调用的工具组。 */
    private static final class Block {
        final List<Api.Step> steps = new ArrayList<>();
        boolean toolGroup;
    }

    /**
     * 渲染全部步骤。
     *
     * 工具调用按「正文分界」分组：AA 的时间线里工具与正文平铺，工具之间几乎总隔着
     * 一条消息步骤；按相邻关系分组会让每次调用各自成组，折叠就失去意义。
     * 展开状态记在 openGroups / openRows，重建后按状态直接恢复可见性（不走动画，
     * 否则每 4 秒一次的刷新会让所有卡片闪一遍）。
     */
    private void renderSteps(LinearLayout container) {
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        for (Api.Step step : steps) {
            boolean isTool = "tool".equals(step.kind);
            if (current != null && isTool && current.toolGroup) {
                current.steps.add(step);
                continue;
            }
            current = new Block();
            current.toolGroup = isTool;
            current.steps.add(step);
            blocks.add(current);
        }
        for (Block block : blocks) {
            if (block.toolGroup) {
                renderToolGroup(container, block.steps);
            } else {
                Api.Step step = block.steps.get(0);
                if ("thinking".equals(step.kind)) thinkingBlock(container, step);
                else if ("message".equals(step.kind)) messageBubble(container, step);
                // 其它类型（marker/product）解析器已折算成 message/tool 或跳过。
            }
        }
        if (steps.isEmpty()) {
            ui.addTo(container, ui.text("还没有内容", 12, WatchUi.MUTED), 0, 10);
        }
    }

    /** 默认折叠的思考块；展开状态记在 openRows（键 think:<seq>）。 */
    private void thinkingBlock(LinearLayout container, Api.Step step) {
        if (step.thinking == null || step.thinking.isEmpty()) return;
        final String key = "think:" + step.seq;
        boolean expanded = openRows.contains(key);
        LinearLayout box = ui.card();
        final TextView head = ui.text(expanded ? "思考 ▾" : "思考 ▸", 12, WatchUi.MUTED);
        head.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        box.addView(head);
        final View body = ui.bubbleRich(step.thinking, false, step.thinking);
        body.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(10));
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        box.addView(body);
        head.setOnClickListener(v -> {
            if (openRows.contains(key)) {
                openRows.remove(key);
                animateCollapse(body, () -> body.setVisibility(View.GONE));
                head.setText("思考 ▸");
            } else {
                openRows.add(key);
                body.setVisibility(View.VISIBLE);
                animateExpand(body);
                head.setText("思考 ▾");
            }
        });
        ui.addTo(container, box, 0, 6);
    }

    /** 用户 / 助手消息：助手侧经 Markdown 渲染，长按复制原文。 */
    private void messageBubble(LinearLayout container, Api.Step step) {
        String body = step.text == null ? "" : step.text.trim();
        if (!step.files.isEmpty()) {
            StringBuilder builder = new StringBuilder(body.isEmpty() ? "" : body + "\n\n");
            builder.append("附件：").append(shortenPaths(join(step.files, "，")));
            body = builder.toString();
        }
        if (body.isEmpty()) return;
        boolean truncated = false;
        if (body.length() > 2400) {
            body = body.substring(0, 2400);
            truncated = true;
        }
        CharSequence display;
        if ("user".equals(step.role)) {
            // 用户消息按原样显示（用户输入的井号不是标题）。
            display = truncated ? body + "\n…（内容过长，已截断）" : body;
        } else {
            // 助手消息走 Markdown：标题、列表、行内代码、代码块、链接。
            CharSequence rendered = Markdown.render(body, WatchUi.FG, WatchUi.LINK,
                    WatchUi.MUTED, 0xFF27272A);
            display = truncated
                    ? new android.text.SpannableStringBuilder(rendered)
                            .append("\n…（内容过长，已截断）")
                    : rendered;
        }
        ui.addTo(container, ui.bubbleRich(display, "user".equals(step.role), body), 0, 6);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(separator);
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    /** 工具组卡片：折叠时一行摘要，展开后是逐条清单。 */
    private void renderToolGroup(LinearLayout container, List<Api.Step> group) {
        if (group.isEmpty()) return;
        final Api.Step first = group.get(0);
        final String key = groupKey(first, group.size());
        boolean expanded = openGroups.contains(key);
        LinearLayout box = ui.card();
        final TextView head = ui.text((expanded ? "▾ " : "▸ ") + stepLabel(first)
                + (group.size() > 1 ? " ×" + group.size() : "") + statusMark(first), 12, WatchUi.FG);
        head.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(2));
        box.addView(head);
        final TextView summary = ui.text(clipLine(summaryOf(group), 110), 10, statusColor(first));
        summary.setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(6));
        box.addView(summary);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // 展开状态恢复：直接设可见性，不走动画——整屏重建时动画会让每张卡片闪一遍。
        list.setVisibility(expanded ? View.VISIBLE : View.GONE);
        for (int i = 0; i < group.size(); i++) {
            final Api.Step step = group.get(i);
            final String rowKey = key + "#" + i;
            final LinearLayout detail = ui.card();
            TextView detailText = ui.text(toolDetailText(step), 11, WatchUi.FG);
            detailText.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
            detail.addView(detailText);
            detail.setVisibility(openRows.contains(rowKey) ? View.VISIBLE : View.GONE);
            View row = ui.row(stepLabel(step) + statusMark(step),
                    firstLine(step.detail.isEmpty() ? stepLabel(step) : step.detail), () -> {
                if (openRows.contains(rowKey)) {
                    openRows.remove(rowKey);
                    detail.setVisibility(View.GONE);
                } else {
                    openRows.add(rowKey);
                    detail.setVisibility(View.VISIBLE);
                    animateExpand(detail);
                }
            });
            row.setMinimumHeight(ui.dp(48));
            LinearLayout rowBox = new LinearLayout(this);
            rowBox.setOrientation(LinearLayout.VERTICAL);
            rowBox.addView(row);
            rowBox.addView(detail);
            list.addView(rowBox);
        }
        View.OnClickListener toggleGroup = v -> {
            if (openGroups.contains(key)) {
                openGroups.remove(key);
                // 收组时连带清掉内部明细，下次打开是干净的清单。
                for (int i = 0; i < group.size(); i++) openRows.remove(key + "#" + i);
                animateCollapse(list, () -> list.setVisibility(View.GONE));
                head.setText("▸ " + stepLabel(first)
                        + (group.size() > 1 ? " ×" + group.size() : "") + statusMark(first));
            } else {
                openGroups.add(key);
                list.setVisibility(View.VISIBLE);
                animateExpand(list);
                head.setText("▾ " + stepLabel(first)
                        + (group.size() > 1 ? " ×" + group.size() : "") + statusMark(first));
            }
        };
        // 收组监听只挂标题与摘要：清单区域必须留给单条明细，否则「点这一条」会被抢走。
        head.setOnClickListener(toggleGroup);
        summary.setOnClickListener(toggleGroup);
        box.addView(list);
        ui.addTo(container, box, 0, 6);
    }

    /** 组键：首条序号 + 条数 + 工具名，足以认出「还是那一组调用」。 */
    private String groupKey(Api.Step first, int size) {
        return first.seq + ":" + size + ":" + first.name;
    }

    private void animateExpand(View view) {
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(120).start();
    }

    private void animateCollapse(View view, Runnable onDone) {
        view.animate().alpha(0f).setDuration(120).withEndAction(onDone).start();
    }

    /** 组摘要：前几条工具的关键信息拼成一行。 */
    private String summaryOf(List<Api.Step> group) {
        List<String> parts = new ArrayList<>();
        for (Api.Step step : group) {
            String line = firstLine(step.detail.isEmpty() ? stepLabel(step) : step.detail);
            if (!line.isEmpty()) parts.add(line);
            if (parts.size() >= 3) break;
        }
        if (parts.isEmpty()) return stepLabel(group.get(0));
        return join(parts, " · ");
    }

    /** 取第一行文本（工具摘要最怕换行）。 */
    private String firstLine(String value) {
        if (value == null) return "";
        String line = value.split("\n", 2)[0].trim();
        return line;
    }

    /** 单条工具的参数与结果正文。 */
    private String toolDetailText(Api.Step step) {
        StringBuilder builder = new StringBuilder();
        if (step.detail != null && !step.detail.isEmpty()) {
            builder.append(step.detail).append("\n");
        }
        if (step.result != null && !step.result.isEmpty()) {
            String result = step.result;
            if (result.length() > 1600) result = result.substring(0, 1600) + "…（结果过长，已截断）";
            builder.append(result);
        }
        if (!step.files.isEmpty()) {
            builder.append("\n文件：").append(shortenPaths(join(step.files, "，")));
        }
        String text = builder.toString().trim();
        return text.isEmpty() ? "（无输出）" : text;
    }

    /** 步骤标题：显示名 > 工具名 > 兜底。 */
    private String stepLabel(Api.Step step) {
        if (step.label != null && !step.label.isEmpty()) return step.label;
        if (step.name != null && !step.name.isEmpty()) return step.name;
        return "工具";
    }

    /** 状态后缀：运行中 ⋯ / 出错 ✕。 */
    private String statusMark(Api.Step step) {
        if (step.isError) return " ✕";
        if ("running".equals(step.status)) return " ⋯";
        return "";
    }

    /** 状态配色。 */
    private int statusColor(Api.Step step) {
        if (step.isError) return WatchUi.DANGER;
        if ("running".equals(step.status)) return WatchUi.WARN;
        return WatchUi.MUTED;
    }

    /** 只看对话模式：仅渲染 message 步骤（与完整模式同一渲染路径）。 */
    private void renderMessagesOnly(LinearLayout container) {
        int shown = 0;
        for (Api.Step step : steps) {
            if (!"message".equals(step.kind)) continue;
            if (step.text == null || step.text.trim().isEmpty()) continue;
            messageBubble(container, step);
            shown++;
        }
        if (shown == 0) {
            ui.addTo(container, ui.text("还没有对话内容", 12, WatchUi.MUTED), 0, 10);
        }
    }

    /** 向上翻一页历史（orderSeq 序列域：最早一条再往前 1）。 */
    private void loadOlder() {
        if (currentSessionId.isEmpty()) return;
        if (!currentHasMore) {
            showToast("已经是最早的内容了");
            return;
        }
        final String sessionId = currentSessionId;
        long before = steps.isEmpty() ? 0 : steps.get(0).seq - 1;
        renderSession("正在载入更早的内容…");
        api.timelineHistory(sessionId, before, result -> {
            if (destroyed || !onSessionPage || editing) return;
            if (!sessionId.equals(currentSessionId)) return;
            if (!result.ok) {
                renderSession("载入失败：" + result.error);
                return;
            }
            List<Api.Step> older = Api.parseTimelineItems(result.value.optJSONArray("items"));
            int beforeCount = steps.size();
            List<Api.Step> merged = Api.mergeSteps(steps, older);
            steps.clear();
            steps.addAll(merged);
            currentHasMore = result.value.optBoolean("hasMore", false);
            if (!steps.isEmpty()) currentOldestOrderSeq = steps.get(0).seq;
            if (steps.size() == beforeCount) {
                currentHasMore = false;
                renderSession("已经是最早的内容了");
                return;
            }
            renderSession("已载入更早的 " + (steps.size() - beforeCount) + " 条");
        });
    }

    /** 从会话页返回列表（返回动画：新页从左滑入）。 */
    private void backToList() {
        navBack();
        returningFromSession = true;
        onSessionPage = false;
        currentSessionId = "";
        foregroundSessionId = "";
        currentAsk = null;
        currentNotices.clear();
        editing = false;
        refresh.removeCallbacks(sessionTicker);
        showSessionList();
    }

    private String shortModel(String name) {
        return clipLine(name, 18);
    }

    // ------------------------------------------------------------ 模型选择

    /** AA 的模型目录（按运行时给，无供应商层级），解析后仍是单供应商结构。 */
    private final List<Api.ProviderInfo> modelProviders = new ArrayList<>();
    /** 当前生效的选择（来自会话状态或目录 default）。 */
    private Api.ModelChoice modelDefault;

    /** 当前选择；目录还没载入时给空结构（界面上显示「未指定」）。 */
    private Api.ModelChoice currentChoice() {
        return modelDefault == null ? new Api.ModelChoice() : modelDefault;
    }

    /** 「模型」菜单项副标题用的一行文本。 */
    private String currentModelLabel() {
        Api.ModelChoice choice = currentChoice();
        if (choice.model.isEmpty()) return "未指定";
        return choice.effort.isEmpty() ? choice.model : choice.model + " · " + choice.effort;
    }

    /** 打开模型选择器：先拉目录，再进两级选择页（模型 → 推理强度）。 */
    private void showModelPicker() {
        if (currentSessionId.isEmpty()) return;
        onSessionPage = true;
        editing = true;
        WatchUi.Page page = ui.page();
        page.navTag = "model";
        ui.heading(page, "模型", "正在载入模型目录…");
        render(page);
        api.models(currentSessionId, result -> {
            if (destroyed) return;
            if (!result.ok || result.value == null) {
                renderModelError(result.error == null ? "载入失败" : result.error);
                return;
            }
            modelProviders.clear();
            modelProviders.addAll(Api.parseModels(result.value));
            if (modelProviders.isEmpty()) {
                renderModelError("这个运行时没有可选的模型");
                return;
            }
            // 目录 default 只是初次兜底；会话里已生效的选择以 modelDefault（来自快照）为准。
            if (modelDefault == null || modelDefault.model.isEmpty()) {
                modelDefault = Api.parseModelDefault(result.value);
            }
            renderModelList(0);
        });
    }

    /** 模型目录载入失败页。 */
    private void renderModelError(String error) {
        WatchUi.Page page = ui.page();
        page.navTag = "model-error";
        ui.heading(page, "模型", "载入失败");
        ui.add(page, ui.text(error, 13, WatchUi.DANGER), 0, 12);
        ui.add(page, ui.button("返回会话", false, () -> {
            editing = false;
            renderSession("");
        }), 0, 0);
        render(page);
    }

    /** 第一级：模型列表。 */
    private void renderModelList(int scrollTo) {
        onSessionPage = true;
        editing = true;
        WatchUi.Page page = ui.page();
        page.navTag = "model";
        ui.heading(page, "选择模型", "第 1 步 · 当前：" + currentModelLabel());
        currentModelCard(page);
        for (final Api.ProviderInfo provider : modelProviders) {
            for (final Api.ModelInfo model : provider.models) {
                boolean isCurrent = !modelDefault.model.isEmpty()
                        && model.selectionId.equals(modelDefault.modelSelectionId);
                String subtitle = model.efforts.isEmpty()
                        ? (isCurrent ? "当前使用" : "")
                        : (isCurrent ? "当前使用 · " : "") + "推理强度 " + model.efforts.size() + " 档";
                ui.add(page, ui.row(model.name, subtitle, () -> {
                    if (model.efforts.isEmpty()) applyModel(model, -1);
                    else renderEffortPicker(model, 0);
                }), 0, 7);
            }
        }
        ui.add(page, ui.button("返回会话", false, () -> {
            editing = false;
            renderSession("");
        }), 0, 0);
        page.setOnSwipeRight(() -> {
            editing = false;
            renderSession("");
        });
        render(page);
    }

    /** 页首的「当前模型」卡片。 */
    private void currentModelCard(WatchUi.Page page) {
        LinearLayout card = ui.card();
        card.addView(ui.text("当前使用", 11, WatchUi.MUTED));
        TextView label = ui.text(currentModelLabel(), 13, WatchUi.FG);
        label.setPadding(0, ui.dp(5), 0, 0);
        card.addView(label);
        ui.add(page, card, 0, 10);
    }

    /** 第二级：某个模型的推理强度档位。 */
    private void renderEffortPicker(final Api.ModelInfo model, int scrollTo) {
        onSessionPage = true;
        editing = true;
        WatchUi.Page page = ui.page();
        page.navTag = "model-effort";
        ui.heading(page, model.name, "第 2 步 · 选推理强度");
        currentModelCard(page);
        for (int i = 0; i < model.efforts.size(); i++) {
            final int index = i;
            String effort = model.efforts.get(i);
            boolean isCurrent = !modelDefault.model.isEmpty()
                    && model.selectionId.equals(modelDefault.modelSelectionId)
                    && model.effortIds.get(i).equals(modelDefault.reasoningSelectionId);
            ui.add(page, ui.row(effortName(effort), isCurrent ? "当前使用" : "",
                    () -> applyModel(model, index)), 0, 7);
        }
        ui.add(page, ui.button("返回模型列表", false, () -> renderModelList(0)), 0, 8);
        ui.add(page, ui.button("返回会话", false, () -> {
            editing = false;
            renderSession("");
        }), 0, 0);
        page.setOnSwipeRight(() -> renderModelList(0));
        render(page);
    }

    /** 推理档位显示名（AA 的 low/medium/high 翻成中文）。 */
    private String effortName(String effort) {
        if (effort == null || effort.isEmpty()) return "标准";
        String lower = effort.toLowerCase();
        if ("low".equals(lower) || "low_reasoning_effort".equals(lower)) return "低（更快）";
        if ("medium".equals(lower) || "medium_reasoning_effort".equals(lower)) return "中";
        if ("high".equals(lower) || "high_reasoning_effort".equals(lower)) return "高（更强）";
        return effort;
    }

    /**
     * 提交模型/推理强度切换。
     *
     * AA 的流程是先对会话取得控制权（takeover），再 PATCH selections；
     * Api.applySelections 已经封装了这两步。注意这条链路刻意不做 401 静默重登，
     * 失败就直接把错误给用户看。
     */
    private void applyModel(final Api.ModelInfo model, final int effortIndex) {
        if (currentSessionId.isEmpty()) return;
        final String sessionId = currentSessionId;
        String modelSelection = model.selectionId.isEmpty() ? model.id : model.selectionId;
        JSONObject selections = new JSONObject();
        try {
            selections.put("model", modelSelection);
            if (effortIndex >= 0 && effortIndex < model.effortIds.size()) {
                String reasoning = model.effortIds.get(effortIndex);
                if (!reasoning.isEmpty()) selections.put("reasoning", reasoning);
            }
        } catch (Exception ignored) {
            // 仅字符串，不会失败
        }
        WatchUi.Page page = ui.page();
        page.navTag = "model-apply";
        ui.heading(page, "切换中…", clipLine(model.name, 16));
        render(page);
        api.applySelections(sessionId, selections, result -> {
            if (destroyed) return;
            if (!result.ok) {
                WatchUi.Page failed = ui.page();
                ui.heading(failed, "切换失败", "");
                ui.add(failed, ui.text(result.error, 13, WatchUi.DANGER), 0, 12);
                ui.add(failed, ui.button("重试", true, () -> applyModel(model, effortIndex)), 0, 8);
                ui.add(failed, ui.button("返回会话", false, () -> {
                    editing = false;
                    renderSession("");
                }), 0, 0);
                render(failed);
                return;
            }
            // 本地同步当前选择，界面立刻反映；下一次快照会以服务器状态为准。
            Api.ModelChoice choice = new Api.ModelChoice();
            choice.model = model.name;
            choice.modelSelectionId = modelSelection;
            if (effortIndex >= 0 && effortIndex < model.effortIds.size()) {
                choice.effort = model.efforts.get(effortIndex);
                choice.reasoningSelectionId = model.effortIds.get(effortIndex);
            }
            modelDefault = choice;
            editing = false;
            lastContentSignature = 0;
            renderSession("已切换到 " + choice.model
                    + (choice.effort.isEmpty() ? "" : " · " + choice.effort));
            loadSession(sessionId, false);
        });
    }

    // ------------------------------------------------------------ 发送

    /** 输入一条新消息。 */
    private void showCompose() {
        if (currentSessionId.isEmpty()) return;
        onSessionPage = true;
        editing = true;
        WatchUi.Page page = ui.page();
        page.navTag = "compose";
        ui.heading(page, "发消息", clipLine(currentSessionTitle, 16));
        final EditText input = new EditText(this);
        input.setHint("输入消息…");
        input.setTextSize(14);
        input.setTextColor(WatchUi.FG);
        input.setHintTextColor(WatchUi.MUTED);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        input.setBackground(ui.surface(WatchUi.SURFACE, 14));
        input.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        ui.add(page, input, 8, 8);
        ui.add(page, ui.button("发送", true, () ->
                sendMessage(input.getText().toString())), 0, 8);
        ui.add(page, ui.button("返回会话", false, () -> {
            editing = false;
            renderSession("");
        }), 0, 0);
        page.setOnSwipeRight(() -> {
            editing = false;
            renderSession("");
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage(input.getText().toString());
                return true;
            }
            return false;
        });
        render(page);
        input.requestFocus();
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    /** 发送消息：成功后立刻拉一次快照（新消息往往马上就有回复）。 */
    private void sendMessage(String text) {
        // 空输入不再静默返回——给出提示，否则像「按了没反应」。
        if (text == null || text.trim().isEmpty()) {
            showToast("先输入消息内容");
            return;
        }
        if (currentSessionId.isEmpty()) return;
        // 只读会话：先开接管，确认成功后自动接着发这条消息。
        if (!currentSessionTakeover) {
            pendingComposeText = text.trim();
            confirmEnableTakeover();
            return;
        }
        final String sessionId = currentSessionId;
        editing = false;
        renderSession("正在发送…");
        api.send(sessionId, text.trim(), result -> {
            if (destroyed) return;
            if (!result.ok) {
                renderSession("发送失败：" + result.error);
                return;
            }
            autoScrollToBottom = true;
            loadSession(sessionId, false);
        });
    }

    // ------------------------------------------------------------ 回答通知

    /** 自由作答的内容，键 = 问题 id（服务器答案里同时带 optionIds 与 customText）。 */
    private final java.util.Map<String, String> customAnswers = new java.util.HashMap<>();

    /** 进入答题页。 */
    private void showQuestion(Api.Ask ask) {
        if (ask == null || ask.questions.isEmpty()) return;
        onSessionPage = true;
        editing = true;
        customAnswers.clear();
        List<List<String>> chosen = new ArrayList<>();
        for (int i = 0; i < ask.questions.size(); i++) chosen.add(new ArrayList<>());
        renderQuestionPage(ask, 0, chosen, "");
    }

    /**
     * 渲染一问。
     *
     * picked 存的是选项的**服务器 id**（审批类通知里选项 id 就是动作 id，
     * 直接回传）；空串表示「打字回答」的自定义答案，内容记在 customAnswers。
     */
    private void renderQuestionPage(Api.Ask ask, int index, List<List<String>> chosen, String error) {
        onSessionPage = true;
        editing = true;
        final Api.Question question = ask.questions.get(index);
        WatchUi.Page page = ui.page();
        page.navTag = "ask";
        String sub = question.header == null ? "" : question.header;
        if (ask.questions.size() > 1) {
            sub = "第 " + (index + 1) + "/" + ask.questions.size() + " 问"
                    + (sub.isEmpty() ? "" : " · " + sub);
        }
        ui.heading(page, "请回答", clipLine(sub, 18));
        if (question.question != null && !question.question.isEmpty()) {
            ui.add(page, ui.text(question.question, 13, WatchUi.FG), 0, 8);
        }
        if (error != null && !error.isEmpty()) {
            ui.add(page, ui.text(error, 12, WatchUi.DANGER), 0, 8);
        }
        if (question.multiSelect) {
            ui.add(page, ui.text("可多选，选好后点「选好了」", 10, WatchUi.MUTED), 0, 6);
        }
        final List<String> picked = index < chosen.size() ? chosen.get(index) : new ArrayList<String>();
        for (final Api.Option option : question.options) {
            boolean selected = picked.contains(option.id);
            String subtitle = (option.description == null ? "" : option.description)
                    + (selected ? "（已选）" : "");
            ui.add(page, ui.row(option.label, subtitle, () -> {
                if (question.multiSelect) {
                    if (picked.contains(option.id)) picked.remove(option.id);
                    else picked.add(option.id);
                    renderQuestionPage(ask, index, chosen, "");
                } else {
                    picked.clear();
                    picked.add(option.id);
                    advance(ask, index, chosen);
                }
            }), 0, 7);
        }
        if (question.options.isEmpty()) {
            // 没有选项的通知只能打字回答。
            ui.add(page, ui.text("这道题没有可点的选项，请打字回答。", 12, WatchUi.MUTED), 0, 8);
        }
        if (question.multiSelect && !question.options.isEmpty()) {
            ui.add(page, ui.button("选好了", true, () -> advance(ask, index, chosen)), 0, 8);
        }
        if (question.allowCustom) {
            ui.add(page, ui.button("打字回答", false, () ->
                    showCustomAnswer(ask, index, chosen)), 0, 7);
        }
        ui.add(page, ui.button("返回会话", false, () -> {
            editing = false;
            showSession(ask.sessionId);
        }), 0, 0);
        render(page);
    }

    /** 多问时推进到下一问；最后一问直接提交。 */
    private void advance(Api.Ask ask, int index, List<List<String>> chosen) {
        int next = index + 1;
        if (next < ask.questions.size()) renderQuestionPage(ask, next, chosen, "");
        else submitAnswers(ask, chosen);
    }

    /** 自由作答输入页。 */
    private void showCustomAnswer(final Api.Ask ask, final int index, final List<List<String>> chosen) {
        final Api.Question question = ask.questions.get(index);
        onSessionPage = true;
        editing = true;
        WatchUi.Page page = ui.page();
        page.navTag = "ask-custom";
        ui.heading(page, "打字回答", clipLine(question.header == null || question.header.isEmpty()
                ? question.question : question.header, 16));
        final EditText input = new EditText(this);
        input.setHint("输入你的回答…");
        String previous = customAnswers.get(question.id);
        if (previous != null) input.setText(previous);
        input.setTextSize(14);
        input.setTextColor(WatchUi.FG);
        input.setHintTextColor(WatchUi.MUTED);
        input.setMinLines(2);
        input.setGravity(Gravity.TOP);
        input.setBackground(ui.surface(WatchUi.SURFACE, 14));
        input.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ui.add(page, input, 8, 8);
        ui.add(page, ui.button("提交", true, () -> {
            customAnswers.put(question.id, input.getText().toString());
            if (!pickedHasCustom(chosen.get(index))) chosen.get(index).add("");
            advance(ask, index, chosen);
        }), 0, 8);
        ui.add(page, ui.button("返回选项", false, () -> renderQuestionPage(ask, index, chosen, "")), 0, 0);
        render(page);
        input.requestFocus();
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private static boolean pickedHasCustom(List<String> picked) {
        for (String id : picked) {
            if (id.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 提交答案。
     *
     * 两类通知的回法不同：
     * - input_request（表单）：POST input = { answers: { 问题id: { optionIds, customText? } } }，
     *   actionId 用表单自带的（通常是 submit）；
     * - 审批/确认（无表单）：选项 id 就是动作 id，回哪个动作服务器就执行哪个。
     */
    private void submitAnswers(final Api.Ask ask, final List<List<String>> chosen) {
        final String actionId;
        JSONObject input = null;
        if ("input_request".equals(ask.kind)) {
            actionId = ask.actionId;
            JSONObject answers = new JSONObject();
            try {
                for (int i = 0; i < ask.questions.size(); i++) {
                    Api.Question question = ask.questions.get(i);
                    List<String> picked = i < chosen.size() ? chosen.get(i) : new ArrayList<String>();
                    JSONObject answer = new JSONObject();
                    JSONArray optionIds = new JSONArray();
                    String customText = "";
                    for (String id : picked) {
                        if (id.isEmpty()) {
                            String custom = customAnswers.get(question.id);
                            if (custom != null) customText = custom;
                            continue;
                        }
                        optionIds.put(id);
                    }
                    if (!customText.isEmpty()) answer.put("customText", customText);
                    if (optionIds.length() > 0) answer.put("optionIds", optionIds);
                    if (answer.length() > 0) answers.put(question.id, answer);
                }
                input = new JSONObject();
                input.put("answers", answers);
            } catch (Exception error) {
                renderQuestionPage(ask, 0, chosen, "提交失败：" + error.getMessage());
                return;
            }
        } else {
            // 审批类：选中的选项 id 即 actionId；没选中任何项时退回通知自带的动作。
            String chosenAction = "";
            List<String> picked = chosen.isEmpty() ? new ArrayList<String>() : chosen.get(0);
            for (String id : picked) {
                if (!id.isEmpty()) {
                    chosenAction = id;
                    break;
                }
            }
            actionId = chosenAction.isEmpty() ? ask.actionId : chosenAction;
            if (actionId.isEmpty()) {
                renderQuestionPage(ask, 0, chosen, "这道通知没有可执行的动作");
                return;
            }
        }
        onSessionPage = true;
        WatchUi.Page page = ui.page();
        page.navTag = "ask-submit";
        ui.heading(page, "正在提交…", "");
        render(page);
        api.respond(ask.sessionId, ask.callId, actionId, input, result -> {
            if (destroyed) return;
            if (!result.ok) {
                editing = true;
                renderQuestionPage(ask, 0, chosen, "提交失败：" + result.error);
                return;
            }
            customAnswers.clear();
            editing = false;
            currentAsk = null;
            showSession(ask.sessionId);
        });
    }

    // ------------------------------------------------------------ 文本工具

    /** 单行截断（只加省略号，不折行——标题一旦折行整页就被挤出屏幕）。 */
    private String clipLine(String value, int max) {
        if (value == null) return "";
        String line = value.split("\n", 2)[0].trim();
        if (line.length() <= max) return line;
        if (max <= 1) return line.substring(0, max);
        return line.substring(0, max - 1) + "…";
    }

    /** 路径只留最后一段。 */
    private String shortPath(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /** 多个路径逐个压短（按逗号/分号分隔的常见形态）。 */
    private String shortenPaths(String value) {
        if (value == null || value.isEmpty()) return "";
        String[] parts = value.split("[,;，；]");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) builder.append("，");
            builder.append(shortPath(parts[i].trim()));
        }
        return builder.toString();
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (menuOpen) {
                dismissMenu();
                return true;
            }
            if (editing) {
                // 输入页 / 答题页 / 模型页：返回上一层，而不是退出应用。
                editing = false;
                if (onSessionPage && !currentSessionId.isEmpty()) {
                    renderSession("");
                } else if (!currentProjectId.isEmpty()) {
                    currentProject = "";
                    currentProjectId = "";
                    resetScroll();
                    showSessionList();
                } else {
                    showSessionList();
                }
                return true;
            }
            if (onSessionPage) {
                backToList();
                return true;
            }
            if (!currentProjectId.isEmpty()) {
                currentProject = "";
                currentProjectId = "";
                resetScroll();
                showSessionList();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        refresh.removeCallbacks(sessionTicker);
        refresh.removeCallbacks(listTicker);
        super.onDestroy();
    }
}

