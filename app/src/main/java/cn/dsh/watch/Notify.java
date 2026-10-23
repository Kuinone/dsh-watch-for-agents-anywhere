package cn.dsh.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提醒通知服务：手表不在会话页时，AI 提问/审批用系统通知提醒。
 *
 * 实现：启动后每 30 秒轮询一次——
 * 1. 拉会话列表，筛出「有未答提问」（waiting/unread）且不是当前前台正看着的会话；
 * 2. 拉这些会话的 runtime/notices，解析出必须回答的交互（复用 Api.parseNotices）；
 * 3. 没提醒过的发一条通知，点开直接跳到该会话（带 session extra）；
 * 4. 上次提醒过的会话如果提问已被处理，自动撤掉通知。
 *
 * 取舍：
 * - 30 秒间隔是震动及时性与耗电的折中；Doze 深睡时跳过该轮（系统本来也会延迟投递）。
 * - 前台正看着的会话不再通知（MainActivity.foregroundSessionId 去重），避免应用内
 *   刚弹出答题页、系统通知又同时震一遍。
 * - 令牌过期时请求内部会静默重登（依赖记住的密码），失败则本轮静默跳过。
 */
public final class Notify extends Service {
    /** 通知渠道 id：设置里显示为「AI 的提问与审批」。 */
    public static final String CHANNEL_ID = "asks";
    /** 轮询间隔；太密伤电池，太疏问题会在表上滞留。 */
    private static final long INTERVAL_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 已提醒过的「会话:通知」键，防止同一问题每 30 秒震一次。 */
    private final Set<String> seen = new HashSet<>();
    /** 已发出的通知：会话 id → 通知 id，提问处理完后撤掉用。 */
    private final Map<String, Integer> posted = new HashMap<>();
    private Store store;
    private Api api;
    private boolean polling;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        api = new Api();
        api.bind(store);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel(this);
        if (!polling) {
            polling = true;
            handler.post(tick);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        polling = false;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 幂等创建通知渠道（应用第一次发通知前调用一次即可）。 */
    static void ensureChannel(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "AI 的提问与审批",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("AI 在会话里等你回答或审批时的提醒");
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    /** 一轮轮询：调度下一轮永远执行（即使本轮跳过），网络失败静默。 */
    private void poll() {
        if (!polling) return;
        handler.postDelayed(tick, INTERVAL_MS);
        if (!store.paired() || !store.notifyEnabled()) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && power.isDeviceIdleMode()) return; // Doze 下跳过，醒来再查
        api.sessions(result -> {
            if (!polling || !result.ok || result.value == null) return;
            List<Api.Session> candidates = new ArrayList<>();
            for (Api.Session session : Api.parseSessions(result.value)) {
                if (session.id.isEmpty()) continue;
                // 前台正看着的会话不提醒（应用内已经能直接答题）。
                if (session.id.equals(MainActivity.foregroundSessionId)) {
                    cancelPosted(session.id);
                    continue;
                }
                if (session.waiting || session.unread) candidates.add(session);
            }
            for (Api.Session session : candidates) {
                checkSession(session);
            }
        });
    }

    /** 检查单个会话的待答通知并发系统通知。 */
    private void checkSession(final Api.Session session) {
        api.notices(session.id, result -> {
            if (!polling || !result.ok || result.value == null) return;
            android.util.Log.d("dshwatch", "notify notices resp=" + result.value);
            org.json.JSONArray array = result.value.optJSONArray("notices");
            if (array == null) return;
            List<Api.Ask> asks = Api.parseNotices(array);
            if (asks.isEmpty()) {
                cancelPosted(session.id);
                return;
            }
            for (Api.Ask ask : asks) {
                String key = session.id + ":" + ask.callId;
                if (seen.contains(key)) continue;
                seen.add(key);
                post(session, ask);
            }
        });
    }

    /** 发一条通知；点开跳到对应会话。 */
    private void post(Api.Session session, Api.Ask ask) {
        String question = "";
        if (!ask.questions.isEmpty() && ask.questions.get(0) != null) {
            question = ask.questions.get(0).question;
            if (question.isEmpty()) question = ask.questions.get(0).header;
        }
        String text = question.isEmpty() ? "AI 在等你处理一个确认" : question;
        int id = session.id.hashCode();
        posted.put(session.id, id);

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("session", session.id);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(session.title.isEmpty() ? "AI 在等你回答" : clip(session.title))
                .setContentText(clip(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(id, builder.build());
    }

    /** 某会话的问题都处理完了：撤掉它的通知。 */
    private void cancelPosted(String sessionId) {
        Integer id = posted.remove(sessionId);
        if (id == null) return;
        seen.removeIf(key -> key.startsWith(sessionId + ":"));
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(id);
    }

    /** 通知文本太长在表盘上读不完，压到 60 字符。 */
    private static String clip(String value) {
        if (value == null) return "";
        return value.length() <= 60 ? value : value.substring(0, 59) + "…";
    }
}
