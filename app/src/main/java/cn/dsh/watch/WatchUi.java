package cn.dsh.watch;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 圆屏适配的界面构件。
 *
 * 圆屏适配 v2：不再把内容压进一个「圆的内接矩形」（那样四角全是空白，
 * 用户看到的是上下两条黑边、两侧空间全部浪费）。现在的做法：
 *
 * - 静态留白缩到很小（圆屏左右 7%、上下 4.5%），屏幕中段的行几乎铺满全宽；
 * - {@link CurvedScrollView} 按每一行的纵向位置做缩放与淡出，越靠屏幕
 *   上下边缘的行越小越透明——视觉上内容沿圆弧「弯进去」，既用满空间，
 *   也不会有文字被表圈硬切（边缘行早已缩到安全区内且接近透明）；
 * - 底部操作栏（可选）钉在屏幕底部，内容从它下面滚过。
 *
 * 安全区只在 {@link #page()} 里按比例算一次，其余控件一律满宽，不加边距。
 */
final class WatchUi {
    /**
     * 圆屏静态留白（v2）：只留最小必要的边，屏幕中段的行几乎铺满全宽；
     * 边缘行由 {@link CurvedScrollView} 的缩放/淡出收进圆弧里。
     */
    private static final float SIDE_RATIO = 0.07f;
    private static final float VERTICAL_RATIO = 0.045f;
    /**
     * AA 暗色配色（对照 AA 官方安卓客户端 Theme.kt 的 dark 方案）：
     * 近黑中性底 + 锌灰面板 + 白色主操作按钮 + 语义色只用于状态。
     * 主操作不再是蓝色——AA 的 primaryAction 是白底黑字，链接才是金色。
     */
    static final int BG = Color.rgb(9, 9, 11);            // canvas #09090B
    static final int SURFACE = Color.rgb(31, 31, 33);     // raisedSurface #1F1F21
    static final int SURFACE_HI = Color.rgb(42, 42, 45);  // subtle #2A2A2D
    static final int FG = Color.rgb(250, 250, 250);       // ink #FAFAFA
    static final int MUTED = Color.rgb(163, 163, 166);    // muted #A3A3A6
    static final int FAINT = Color.rgb(115, 115, 118);    // faint #737376
    /** 主操作色：AA 用纯白（primaryAction），文字用 ON_ACCENT 黑。 */
    static final int ACCENT = Color.rgb(250, 250, 250);
    static final int ON_ACCENT = Color.rgb(9, 9, 11);
    /** 链接与可点文本：AA 的 linkColor 金。 */
    static final int LINK = Color.rgb(235, 179, 83);
    static final int WARN = Color.rgb(245, 158, 11);      // #F59E0B
    static final int OK = Color.rgb(34, 197, 94);         // #22C55E
    static final int DANGER = Color.rgb(249, 112, 102);   // #F97066
    /** 进行中/切换中的蓝（runtimeTransitioning），只用于状态，不用于按钮。 */
    static final int INFO = Color.rgb(59, 130, 246);
    /** 会话状态强调色（AA 的 sessionStatusAccent 青绿）。 */
    static final int TEAL = Color.rgb(101, 196, 166);     // sessionStatusAccentText #65C4A6

    /** 卡片顶部的浅一档颜色，与 SURFACE 组成几乎察觉不到、但能拉出厚度的垂直渐变。 */
    private static final int CARD_TOP = Color.rgb(38, 38, 41);
    /** 页面顶部一层极弱的聚光：整屏纯平底色在圆屏上会显得发闷。 */
    private static final int BACKDROP_TOP = Color.rgb(16, 16, 18);
    /** 面板描边：低不透明度的浅灰，只负责把边界勾出来，不参与配色。 */
    private static final int STROKE = Color.argb(60, 82, 82, 88);
    /** 分隔线，比正文弱两档；也用于「左侧细竖线」这类弱化标记。 */
    static final int LINE = Color.rgb(39, 39, 42);        // border #27272A
    /** 按下波纹（暗面板上用白波纹）。 */
    private static final int RIPPLE = Color.argb(38, 250, 250, 250);

    final Activity activity;

    /** 一个可滚动的页面容器。 */
    static final class Page {
        final FrameLayout root;
        final ScrollView scroll;
        final LinearLayout content;
        /** 导航标签：MainActivity.render() 用它判断「换页」，决定是否播放入场动画。 */
        String navTag;
        /** 固定底栏（可选）：由 {@link #bottomBar} 创建，钉在页面底部、不随内容滚动。 */
        LinearLayout bottomBar;
        /* 拖拽状态机需要直接探测「这页有没有右滑动作」——包可见。 */
        Runnable onSwipeLeft;
        Runnable onSwipeRight;

        Page(FrameLayout root, ScrollView scroll, LinearLayout content) {
            this.root = root;
            this.scroll = scroll;
            this.content = content;
        }

        /** 左滑（手指向左）时触发：按约定用于打开操作菜单。 */
        void setOnSwipeLeft(Runnable action) {
            this.onSwipeLeft = action;
        }

        /** 右滑（手指向右）时触发：按约定用于返回上一级。 */
        void setOnSwipeRight(Runnable action) {
            this.onSwipeRight = action;
        }

        void fireSwipeLeft() {
            if (onSwipeLeft != null) onSwipeLeft.run();
        }

        void fireSwipeRight() {
            if (onSwipeRight != null) onSwipeRight.run();
        }

        /** 滚动到底部（进入会话时用来直接看到最新内容）。 */
        void scrollToBottom() {
            // 直接滚到「内容高度 − 可视高度」，不依赖焦点：fullScroll(FOCUS_DOWN)
            // 在没有可聚焦子控件时行为不稳定，长对话有时就停在中途。
            scroll.post(() -> {
                View content = scroll.getChildAt(0);
                if (content == null) return;
                int max = Math.max(0, content.getHeight() - scroll.getHeight());
                scroll.scrollTo(0, max);
            });
        }
    }

    WatchUi(Activity activity) {
        this.activity = activity;
    }

    /** 界面字号缩放：设置页里调（0.85 / 1.0 / 1.15 / 1.3），作用于全部文字。 */
    private float fontScale = 1.0f;

    void setFontScale(float value) {
        if (value < 0.8f) value = 0.8f;
        if (value > 1.6f) value = 1.6f;
        this.fontScale = value;
    }

    float fontScale() {
        return fontScale;
    }

    int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * 建立一个页面：全屏滚动 + 曲面列表效果 + 可选固定底栏。
     *
     * 页面同时识别左右滑动：由调用方通过 {@link Page#setOnSwipeLeft} / {@link Page#setOnSwipeRight}
     * 注册回调，这样各页面可以自行决定「右滑返回、左滑开菜单」这类行为。
     */
    Page page() {
        FrameLayout root = new FrameLayout(activity);
        // 背景用一层从上到下由 BACKDROP_TOP 收到 BG 的渐变，只差几个色阶，
        // 作用是在圆屏四周露出一圈底色时仍有层次，而不是一块死板的纯色。
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BACKDROP_TOP, BG}));
        CurvedScrollView scroll = new CurvedScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(true);
        scroll.setDescendantFocusability(ScrollView.FOCUS_AFTER_DESCENDANTS);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        // 滚动区满屏：留白做在内容列的 padding 上（比 v1 的 ScrollView margin
        // 小得多），曲面缩放负责把边缘行的宽度收进圆里。
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        Page page = new Page(root, scroll, content);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob2) -> {
            int width = r - l;
            int height = b - t;
            // 圆屏判定不能只信 isScreenRound()：不少全安卓圆表（如 454×454 的
            // 1.39 寸表）配置里报的是方形屏幕，此时按方屏只留 8dp 边距，
            // 内容四角会被圆弧切掉。凡接近正方形的小屏一律按圆屏布局。
            boolean round = activity.getResources().getConfiguration().isScreenRound()
                    || Math.abs(width - height) <= Math.max(4, dp(3));
            scroll.curved = round;
            // 圆屏：左右 7%、上下 4.5% 的最小留白；中段行几乎铺满，边缘交给
            // 曲面缩放收窄。方屏维持 8dp。
            int side = round ? Math.round(width * SIDE_RATIO) : dp(8);
            int vertical = round ? Math.round(height * VERTICAL_RATIO) : dp(8);
            int bottomKeep = content.getPaddingBottom();
            if (content.getPaddingLeft() != side || content.getPaddingTop() != vertical) {
                content.setPadding(side, vertical, side, bottomKeep);
            }
        });
        scroll.setOnGenericMotionListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL
                    && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                float factor = ViewConfiguration.get(activity).getScaledVerticalScrollFactor();
                scroll.scrollBy(0, Math.round(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * factor));
                return true;
            }
            return false;
        });
        // 横向手势不在这里拦：挂在页面的 FrameLayout 上时，触摸会先被子层
        // ScrollView 消费掉，左滑会被当成纵向滚动。改由 MainActivity 的
        // dispatchTouchEvent 统一判定（配合 page 上的滑动回调）。
        return page;
    }

    /**
     * 取页面的固定底栏；没有就建一个。
     *
     * 底栏钉在屏幕底部（不随内容滚动），背景不透明并带一条上边线，滚动内容
     * 从它下面穿过去。调用方往返回的行容器里加按钮即可（配 weighted 平分）。
     * 内容列会自动让出底栏高度，保证最后几行能滚到底栏上方完整可见。
     */
    LinearLayout bottomBar(Page page) {
        if (page.bottomBar != null) return page.bottomBar;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(BG);
        View line = new View(activity);
        line.setBackgroundColor(LINE);
        wrapper.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.6f))));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, -2));
        page.root.addView(wrapper, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        page.bottomBar = row;
        // 内容列让出底栏高度：等底栏量完尺寸后设置（下一帧），期间内容仍可见。
        Runnable applyPadding = () -> {
            int extra = wrapper.getHeight();
            if (extra > 0 && page.content.getPaddingBottom() != extra) {
                page.content.setPadding(page.content.getPaddingLeft(),
                        page.content.getPaddingTop(),
                        page.content.getPaddingRight(), extra);
            }
        };
        page.root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob2) -> applyPadding.run());
        return row;
    }

    /**
     * 曲面滚动容器（仅圆屏启用）。
     *
     * Wear OS ScalingLazyColumn 思路的轻量实现：每一行按它在可视区里的纵向
     * 位置做缩放与淡出，越靠上下边缘越小越透明。行本身仍是满宽布局，只是
     * 视觉收窄，不产生布局反馈环；触摸/点击坐标跟随视图变换矩阵，交互不变。
     */
    private final class CurvedScrollView extends ScrollView {
        /** 是否启用曲面效果：由 page() 的布局监听按圆屏判定设置。 */
        boolean curved;

        CurvedScrollView(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            applyCurve();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            applyCurve();
        }

        /** 边缘缩放与淡出：靠边区段内平滑过渡（中心区完全不动）。 */
        private void applyCurve() {
            if (!curved || getHeight() == 0) return;
            View column = getChildAt(0);
            if (!(column instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup rows = (android.view.ViewGroup) column;
            float half = getHeight() / 2f;
            float center = half;
            for (int i = 0; i < rows.getChildCount(); i++) {
                transformRow(rows.getChildAt(i), 0, half, center);
            }
        }

        /**
         * 对一行应用曲面变换。
         *
         * 会话页的内容整体包在一个透明竖排容器（contentArea）里，气泡外又各包
         * 一层透明的 wrapper——若只把最外层容器当成一行变换，整块对话要么完全
         * 不动、要么一起淡出，看起来就是「对话没有曲面效果」。因此对「透明的
         * 竖排 LinearLayout」逐层下钻，把真正的内容行（气泡文本、卡片、按钮）
         * 按它们在滚动区里的绝对位置各自变换。有底色或带点击的行视为整体，
         * 不再下钻。
         */
        private void transformRow(View row, int topOffset, float half, float center) {
            if (row.getVisibility() != View.VISIBLE || row.getHeight() == 0) return;
            if (isPlainColumn(row)) {
                android.view.ViewGroup group = (android.view.ViewGroup) row;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    transformRow(child, topOffset + row.getTop(), half, center);
                }
                return;
            }
            int top = topOffset + row.getTop();
            float d = Math.abs(top + row.getHeight() / 2f - getScrollY() - center) / half;
            if (d > 1f) d = 1f;
            // 中心 45% 不动，之后越远缩得越多：边缘行约 84% 大小、55% 透明度。
            // 刻意比 ScalingLazyColumn 温和——会话页顶部是标题，静止时不能太暗。
            float edge = d <= 0.45f ? 0f : (d - 0.45f) / 0.55f;
            float scale = 1f - 0.16f * edge * edge;
            float alpha = 1f - 0.55f * edge * edge * edge;
            row.setPivotX(row.getWidth() / 2f);
            row.setPivotY(row.getHeight() / 2f);
            if (row.getScaleX() != scale) row.setScaleX(scale);
            if (row.getScaleY() != scale) row.setScaleY(scale);
            if (row.getAlpha() != alpha) row.setAlpha(alpha);
        }

        /** 透明的竖排容器：没有底色、不响应点击，只是排版用的分组。 */
        private boolean isPlainColumn(View row) {
            if (!(row instanceof LinearLayout)) return false;
            LinearLayout column = (LinearLayout) row;
            return column.getOrientation() == LinearLayout.VERTICAL
                    && column.getBackground() == null
                    && !column.hasOnClickListeners();
        }
    }

    /**
     * 基础文本视图。
     *
     * 行距比系统默认略松：13sp 的中文正文在圆屏上贴行会更难读。倍数刻意压得低，
     * 因为一屏可见高度只有三百多像素，行距一大内容就被挤出屏幕。
     */
    TextView text(CharSequence value, float size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size * fontScale);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(2), 1.16f);
        return view;
    }

    /** 顶部标题区；副标题为空时不占位。 */
    void heading(Page page, String title, String subtitle) {
        boolean hasSubtitle = subtitle != null && !subtitle.isEmpty();
        TextView head = headingView(title);
        // 顶部留一点空间，避免第一行贴着圆角；有副标题时把两者收成一组。
        add(page, head, 2, hasSubtitle ? 0 : 4);
        if (hasSubtitle) {
            TextView sub = text(subtitle, 10, MUTED);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            add(page, sub, 0, 8);
        }
    }

    /**
     * 标题视图。
     *
     * 会话标题常常很长（如「制作手表端DSH客户端应用」）。圆屏上一屏可见高度只有
     * 三百多像素，标题一旦折成两三行，按钮和内容区就被挤出屏幕、整页也滚不动。
     * 因此强制单行 + 省略号；调用方负责先把文本截短。
     */
    TextView headingView(String title) {
        TextView view = text(title, 16, FG);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    /**
     * 把视图加进页面。
     *
     * 边距由 {@link #page()} 的圆屏安全区统一负责，这里不再逐块调整——
     * 曾经按控件自身位置反推边距，结果形成布局反馈环：算边距 → 控件变窄 →
     * 文字折行 → 位置变化 → 再算出不同边距，最终把按钮压成窄条且无法拉伸。
     */
    void add(Page page, View view, int top, int bottom) {
        addTo(page.content, view, top, bottom);
    }

    /** 往任意容器里加视图（满宽 + 上下间距）。 */
    void addTo(LinearLayout container, View view, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(top), 0, dp(bottom));
        container.addView(view, params);
    }

    void space(Page page, int height) {
        View view = new View(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        page.content.addView(view, params);
    }

    void divider(Page page) {
        View line = new View(activity);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.6f)));
        params.setMargins(0, dp(8), 0, dp(8));
        page.content.addView(line, params);
    }

    /** 同一条分隔线，返回视图本身，供操作菜单面板等非 Page 容器复用。 */
    View dividerView() {
        View line = new View(activity);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.6f)));
        params.setMargins(0, dp(8), 0, dp(8));
        line.setLayoutParams(params);
        return line;
    }

    /**
     * 一个通栏按钮。
     *
     * 用 TextView 而不是 Button：Button 的默认样式带 9-patch 背景与最小宽度约束，
     * 在窄圆屏上会被固有宽度顶住、无法撑满整行（曾出现同排两个按钮宽度不一致、
     * 标题折成两行）。这里自己画背景，宽度完全交给父容器决定。
     *
     * AA 风格的主按钮是白底黑字胶囊（primaryAction #FAFAFA），
     * 次按钮是深灰面板 + 白字的胶囊（secondaryAction）。
     */
    TextView button(String label, boolean primary, Runnable action) {
        TextView button = text(label, 14, primary ? ON_ACCENT : FG);
        button.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setGravity(Gravity.CENTER);
        button.setMaxLines(1);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // 至少要有一个规范要求的最小点击高度。
        button.setMinHeight(dp(48));
        // 关掉 TextView 默认的额外字体留白，让高度完全由 padding 决定。
        button.setIncludeFontPadding(false);
        // 单行按钮不需要行距，留着会把文字顶得偏上。
        button.setLineSpacing(0, 1f);
        button.setPadding(dp(10), dp(14), dp(10), dp(14));
        button.setBackground(primary ? buttonPrimaryBackground() : buttonSecondaryBackground());
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    /** 次按钮背景：深灰面板 + 细描边，与主按钮的白形成层次而不是靠颜色。 */
    private android.graphics.drawable.Drawable buttonSecondaryBackground() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(SURFACE_HI);
        shape.setCornerRadius(dp(24));
        shape.setStroke(Math.max(1, dp(0.8f)), STROKE);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null);
    }

    /** 可点击的行：标题 + 可选副标题，整行高度不低于 48dp。 */
    View row(CharSequence title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(11), dp(11), dp(11), dp(11));
        row.setMinimumHeight(dp(52));
        row.setBackground(surface(SURFACE, 16));
        TextView top = text(title, 14, FG);
        top.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(top);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 11, MUTED);
            sub.setPadding(0, dp(5), 0, 0);
            sub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(sub);
        }
        row.setContentDescription(title + (subtitle == null || subtitle.isEmpty() ? "" : "，" + subtitle));
        row.setFocusable(true);
        row.setClickable(true);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /**
     * 小圆角标签，用于「运行中」「待回答」等状态。
     *
     * 底色不再固定为 SURFACE_HI，而是调用方传入的语义色（运行中/失败/完成）派生出的
     * 同色低不透明度版本：橙底橙字、红底红字、绿底绿字，状态一眼可辨。
     */
    TextView chip(String label, int color) {
        TextView chip = text(label, 10, color);
        chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chip.setLineSpacing(0, 1f);
        chip.setPadding(dp(7), dp(3), dp(7), dp(3));
        chip.setBackground(chipBackground(color));
        return chip;
    }

    /**
     * 对话气泡。
     *
     * 对齐 AA 的聊天排版：助手消息不用气泡底色，直接铺在画布上、占满整行；
     * 用户消息靠右，用深一档的面板色胶囊。彩底气泡已去掉——AA 风格里
     * 颜色只标状态，不标说话人。
     */
    View bubble(String body, boolean fromUser) {
        return bubbleRich(body, fromUser);
    }

    /**
     * 富文本气泡。
     *
     * 正文可能带 Markdown 样式（粗体、等宽、底色），因此用 Spannable 而不是纯字符串。
     * 长文本要能正常折行，所以宽度用 WRAP_CONTENT 加上限，不能固定宽度。
     *
     * copyText 不为空时支持长按复制（复制的是原始 Markdown 文本）。
     */
    View bubbleRich(CharSequence body, boolean fromUser) {
        return bubbleRich(body, fromUser, null);
    }

    View bubbleRich(CharSequence body, boolean fromUser, String copyText) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        // 15sp：手表 1.39 寸屏上 13sp 偏小；Markdown 标题等相对缩放都以此为基准。
        TextView view = text(body, 15, FG);
        view.setPadding(dp(11), dp(9), dp(11), dp(9));
        if (fromUser) {
            view.setBackground(bubbleBackground(true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.gravity = Gravity.END;
            wrapper.addView(view, params);
        } else {
            // 助手消息直接铺在画布上（AA 的做法），不占底色、不吃宽度。
            view.setPadding(0, dp(1), 0, dp(1));
            view.setBackground(null);
            wrapper.addView(view, new LinearLayout.LayoutParams(-1, -2));
        }
        if (copyText != null && !copyText.isEmpty()) {
            view.setOnLongClickListener(v -> {
                Object service = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (service instanceof android.content.ClipboardManager) {
                    ((android.content.ClipboardManager) service).setPrimaryClip(
                            android.content.ClipData.newPlainText("消息", copyText));
                    android.widget.Toast.makeText(activity, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        return wrapper;
    }

    /** 纯展示卡片。 */
    LinearLayout card() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
        card.setBackground(cardBackground());
        return card;
    }

    /**
     * 通用面板背景。
     *
     * 深色主题里卡片与页面底色只差一档亮度，纯靠明度差看不出边界，
     * 因此统一补一条 1dp 低不透明度描边——它只勾边，不改变任何一块的配色。
     */
    android.graphics.drawable.Drawable surface(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(Math.max(1, dp(0.8f)), STROKE);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null);
    }

    /** 主按钮：白色胶囊（AA 的 primaryAction），按下时用暗波纹显出反馈。 */
    private android.graphics.drawable.Drawable buttonPrimaryBackground() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ACCENT);
        shape.setCornerRadius(dp(24));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(46, 0, 0, 0)), shape, null);
    }

    /** 卡片：极轻的垂直渐变让卡片有厚度，描边把边界从背景里勾出来。 */
    private android.graphics.drawable.Drawable cardBackground() {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{CARD_TOP, SURFACE});
        shape.setCornerRadius(dp(16));
        shape.setStroke(Math.max(1, dp(0.8f)), STROKE);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null);
    }

    /**
     * 用户消息的胶囊背景：右下角收小，形成指向右侧的「尾巴」。
     * 助手消息现在没有底色，此方法只剩用户一侧在用（保留完整分支便于调整）。
     * 角的顺序是「左上、右上、右下、左下」，每角两个值（x/y 半径）。
     */
    private android.graphics.drawable.Drawable bubbleBackground(boolean fromUser) {
        int big = dp(18);
        int small = dp(5);
        float[] radii = fromUser
                ? new float[]{big, big, big, big, small, small, big, big}
                : new float[]{small, small, big, big, big, big, big, big};
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(SURFACE_HI);
        shape.setCornerRadii(radii);
        shape.setStroke(Math.max(1, dp(0.8f)), STROKE);
        return shape;
    }

    /** 标签背景：由文字色派生的低不透明度底 + 同色细描边，圆角取满即胶囊形。 */
    private android.graphics.drawable.Drawable chipBackground(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)));
        shape.setCornerRadius(dp(12));
        shape.setStroke(Math.max(1, dp(0.6f)),
                Color.argb(72, Color.red(color), Color.green(color), Color.blue(color)));
        return shape;
    }
}
