package cn.dsh.watch;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKeyFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.crypto.spec.PBEKeySpec;

/**
 * Agents Anywhere 服务器的 HTTP 通信层：全部在后台线程执行，结果回到主线程。
 *
 * 手表是 AA 的一个普通客户端（与官方手机应用同一条通道）：
 * - 登录用「邮箱 + 密码」：先拿盐，再把密码派生成校验值（PBKDF2）提交，
 *   换回 7 天有效的访问令牌；过期后用记住的密码静默重登。
 * - 数据接口全部是 `/api/v2` 下的 REST：会话列表、快照、时间线增量、通知。
 * - 发消息、停止、答题、切换模型也都走 REST（AA 的 WebSocket 只做服务端推送，
 *   手表沿用轮询刷新，节奏与旧版一致：会话页 4 秒、列表 10 秒）。
 */
final class Api {
    /** 单次请求超时；手表在弱网下也不该长时间无反馈。 */
    private static final int TIMEOUT_MS = 15_000;
    /** 会话消息可能较长，读取上限放宽。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** API 命名空间：AA 服务器的全部业务接口都在它下面。 */
    private static final String API_ROOT = "/api/v2";
    /** AA 签发的令牌有效期是 7 天；本地按 6 天记，留出时钟偏差余量。 */
    private static final long TOKEN_TTL_MS = 6L * 24 * 60 * 60 * 1000;
    /** 官方云端服务器；设置页可以直接用回车确认。 */
    static final String DEFAULT_SERVER = "https://web.agents-anywhere.com";

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private Store store;

    /** 通信层要知道当前账号（服务器地址与令牌）才能发请求。 */
    void bind(Store store) {
        this.store = store;
    }

    /** 一次调用的结果：成功时 value 非空，失败时 error 为可展示的中文说明。 */
    static final class Result {
        final boolean ok;
        final JSONObject value;
        final String error;

        private Result(boolean ok, JSONObject value, String error) {
            this.ok = ok;
            this.value = value;
            this.error = error;
        }

        static Result success(JSONObject value) {
            return new Result(true, value, "");
        }

        static Result failure(String error) {
            return new Result(false, null, error);
        }
    }

    interface Callback {
        void onResult(Result result);
    }

    /** 在主线程回调，便于直接更新界面。 */
    private void deliver(Callback callback, Result result) {
        main.post(() -> callback.onResult(result));
    }

    private static javax.net.ssl.SSLSocketFactory insecureFactory;

    /**
     * 放行全部证书的 SSL 工厂。
     *
     * 用途：自建 AA 服务器常用自签名证书或私有域名，手表上没有便捷的证书安装入口。
     * 访问控制由账号令牌承担。
     */
    private static synchronized javax.net.ssl.SSLSocketFactory insecureFactory() {
        if (insecureFactory != null) return insecureFactory;
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            insecureFactory = context.getSocketFactory();
        } catch (Exception error) {
            throw new IllegalStateException("无法初始化 TLS：", error);
        }
        return insecureFactory;
    }

    // ------------------------------------------------------------ 底层请求

    /**
     * 发一次带鉴权的请求。
     *
     * 401 时先尝试用记住的密码静默重登一次再重试——令牌 7 天过期是常态，
     * 不能让用户每次都手动重新登录。
     */
    private void request(String method, String path, Object body, boolean withAuth, Callback callback) {
        requestWithTimeout(method, path, body, withAuth, TIMEOUT_MS, callback);
    }

    /** 同 request，但可以指定读取超时（新建会话要冷启动运行时，得放宽）。 */
    private void requestWithTimeout(String method, String path, Object body, boolean withAuth,
                                    int readTimeoutMs, Callback callback) {
        pool.execute(() -> {
            Result result = requestOnce(method, path, body, withAuth, readTimeoutMs);
            if (result.ok || !withAuth || store == null || !store.paired()) {
                deliver(callback, result);
                return;
            }
            // 401：静默重登后重试一次。
            if (!reloginSilently()) {
                deliver(callback, Result.failure("登录已过期，请到设置里重新登录"));
                return;
            }
            deliver(callback, requestOnce(method, path, body, withAuth, readTimeoutMs));
        });
    }

    /** 同步执行一次请求（只在后台线程调用）。body 可以是 JSONObject 或 JSONArray。 */
    private Result requestOnce(String method, String path, Object body, boolean withAuth, int readTimeoutMs) {
        if (store == null) return Result.failure("内部错误：通信层未初始化");
        String base = store.base();
        if (base == null || base.isEmpty()) return Result.failure("尚未登录任何账号");
        String url = base + API_ROOT + path;
        try {
            HttpURLConnection connection = open(url, method, readTimeoutMs);
            String token = store.token();
            if (withAuth && token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                connection.setDoOutput(true);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(payload);
                }
            }
            int status = connection.getResponseCode();
            String text = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            connection.disconnect();
            if (status < 200 || status >= 300) {
                return Result.failure(friendly(status, text));
            }
            return Result.success(parseObject(text));
        } catch (Exception error) {
            return Result.failure("连接失败：" + describe(error));
        }
    }

    private HttpURLConnection open(String url, String method, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        // AA 官方客户端都带这个头；自建服务器架在 ngrok 后面时没有它会被拦成网页。
        connection.setRequestProperty("ngrok-skip-browser-warning", "true");
        if (url.startsWith("https:")) {
            try {
                // setSSLSocketFactory / setHostnameVerifier 定义在 HttpsURLConnection 上，
                // 直接在 HttpURLConnection 变量上调会编译不过。
                HttpsURLConnection secure = (HttpsURLConnection) connection;
                secure.setSSLSocketFactory(insecureFactory());
                secure.setHostnameVerifier((hostname, session) -> true);
            } catch (Exception ignored) {
                // 初始化失败就用默认校验，至少请求还能发。
            }
        }
        return connection;
    }

    /** 不带鉴权的原始请求（登录、健康检查用）。 */
    private Result requestRoot(String method, String serverRoot, String path, JSONObject body) {
        try {
            HttpURLConnection connection = open(serverRoot + path, method, TIMEOUT_MS);
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(payload);
                }
            }
            int status = connection.getResponseCode();
            String text = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            connection.disconnect();
            if (status < 200 || status >= 300) {
                return Result.failure(friendly(status, text));
            }
            return Result.success(parseObject(text));
        } catch (Exception error) {
            return Result.failure("连接失败：" + describe(error));
        }
    }

    private static JSONObject parseObject(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        int total = 0;
        while ((read = stream.read(chunk)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) break;
            buffer.write(chunk, 0, read);
        }
        buffer.close();
        return buffer.toString("UTF-8");
    }

    /** 把异常翻成一句能看懂的中文。 */
    static String describe(Exception error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.contains("UnknownHost")) return "找不到服务器，检查地址或网络";
        if (message.contains("ConnectException")) return "连不上服务器";
        if (message.contains("timeout") || message.contains("Timeout")) return "请求超时，稍后再试";
        if (message.contains("SSL")) return "安全连接失败（自建服务器可检查证书）";
        return message.isEmpty() ? "网络错误" : message;
    }

    /**
     * 把 HTTP 状态码与错误响应翻成中文。
     *
     * AA（FastAPI）的错误体形如 `{"detail": {"code": ..., "message": ...}}` 或
     * `{"detail": "文本"}`；先取 message，取不到再退到状态码文案。
     */
    static String friendly(int status, String body) {
        if (body != null && !body.isEmpty()) {
            try {
                JSONObject root = new JSONObject(body);
                Object detail = root.opt("detail");
                if (detail instanceof JSONObject) {
                    JSONObject object = (JSONObject) detail;
                    String message = translateServer(text(object, "message"));
                    if (!message.isEmpty()) return message;
                    String code = translateServer(text(object, "code"));
                    if (!code.isEmpty()) return code;
                } else if (detail instanceof String && !((String) detail).isEmpty()) {
                    return translateServer((String) detail);
                }
            } catch (Exception ignored) {
                // 非 JSON 响应，退回通用文案
            }
        }
        switch (status) {
            case 401: return "登录已过期或密码不正确";
            case 403: return "没有权限执行这个操作";
            case 404: return "接口或会话不存在";
            case 409: return "设备不在线或状态冲突，稍后再试";
            case 429: return "请求太频繁，稍等几秒再试";
            default: return "服务器返回错误（" + status + "）";
        }
    }

    /** 把几条高频出现的英文服务器报错翻成中文，其余原样返回。 */
    static String translateServer(String message) {
        if (message == null || message.isEmpty()) return "";
        switch (message) {
            case "invalid credentials": return "邮箱或密码不正确";
            case "user no longer exists": return "账号已被删除";
            case "account disabled": return "账号已被禁用";
            case "registration is closed": return "该服务器已关闭注册";
            default: return message;
        }
    }

    // ------------------------------------------------------------ 登录

    /** 健康检查：确认地址上真的有一台 AA 服务器。 */
    void health(String serverRoot, Callback callback) {
        pool.execute(() -> {
            Result result = requestRoot("GET", serverRoot, API_ROOT + "/health", null);
            if (result.ok && result.value != null && !"ok".equals(result.value.optString("status", "ok"))) {
                deliver(callback, Result.failure("地址上不是 AA 服务器"));
                return;
            }
            deliver(callback, result);
        });
    }

    /**
     * 邮箱 + 密码登录。
     *
     * AA 的浏览器登录流程：`POST /auth/password-salt` 拿盐 →
     * 密码经 PBKDF2-HMAC-SHA256（120000 轮、32 字节、盐按 UTF-8 字节）派生成
     * base64url 校验值 → `POST /auth/login` 提交，换回令牌。
     * 明文密码只保存在本机存档里用于静默重登，从不出现在请求中。
     */
    void login(String serverRoot, String email, String password, Callback callback) {
        pool.execute(() -> {
            JSONObject saltBody = new JSONObject();
            try {
                saltBody.put("email", email.trim().toLowerCase());
            } catch (Exception ignored) {
                // 仅字符串，不会失败
            }
            Result salt = requestRoot("POST", serverRoot, API_ROOT + "/auth/password-salt", saltBody);
            if (!salt.ok) {
                deliver(callback, salt);
                return;
            }
            String saltValue = salt.value == null ? "" : Api.text(salt.value, "salt");
            if (saltValue.isEmpty()) {
                deliver(callback, Result.failure("服务器没有返回密码盐"));
                return;
            }
            String verifier = passwordVerifier(password, saltValue);
            JSONObject loginBody = new JSONObject();
            try {
                loginBody.put("email", email.trim().toLowerCase());
                loginBody.put("passwordVerifier", verifier);
            } catch (Exception ignored) {
                // 仅字符串，不会失败
            }
            Result loggedIn = requestRoot("POST", serverRoot, API_ROOT + "/auth/login", loginBody);
            if (!loggedIn.ok) {
                deliver(callback, loggedIn);
                return;
            }
            JSONObject value = new JSONObject();
            try {
                value.put("token", Api.text(loggedIn.value, "accessToken"));
                value.put("userId", Api.text(loggedIn.value, "userId"));
                value.put("email", email.trim().toLowerCase());
                value.put("displayName", Api.text(loggedIn.value, "displayName"));
                value.put("expiresAt", System.currentTimeMillis() + TOKEN_TTL_MS);
            } catch (Exception ignored) {
                // 不会失败
            }
            deliver(callback, Result.success(value));
        });
    }

    /** 401 后的静默重登：成功返回 true。在后台线程调用。 */
    private synchronized boolean reloginSilently() {
        if (store == null || !store.paired()) return false;
        // 别的请求可能刚完成重登：令牌变了就不必再来一次。
        String before = store.token();
        JSONObject saltBody = new JSONObject();
        try {
            saltBody.put("email", store.email());
        } catch (Exception ignored) {
            // 仅字符串
        }
        Result salt = requestRoot("POST", store.base(), API_ROOT + "/auth/password-salt", saltBody);
        if (!salt.ok) return false;
        String saltValue = salt.value == null ? "" : Api.text(salt.value, "salt");
        if (saltValue.isEmpty()) return false;
        JSONObject loginBody = new JSONObject();
        try {
            loginBody.put("email", store.email());
            loginBody.put("passwordVerifier", passwordVerifier(store.password(), saltValue));
        } catch (Exception ignored) {
            // 仅字符串
        }
        Result loggedIn = requestRoot("POST", store.base(), API_ROOT + "/auth/login", loginBody);
        if (!loggedIn.ok || loggedIn.value == null) return false;
        String token = Api.text(loggedIn.value, "accessToken");
        if (token.isEmpty()) return false;
        store.updateToken(store.base(), store.email(), token, System.currentTimeMillis() + TOKEN_TTL_MS);
        return !before.equals(store.token());
    }

    /**
     * 密码校验值：PBKDF2-HMAC-SHA256，120000 轮，32 字节，base64url（43 字符）。
     *
     * 盐是服务器返回的字符串，按 UTF-8 字节直接使用——与 AA 浏览器端
     * `pbkdf2Sync(password, salt, 120000, 32, "sha256")` 完全一致。
     */
    static String passwordVerifier(String password, String salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8), 120_000, 32 * 8);
            byte[] key = factory.generateSecret(spec).getEncoded();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        } catch (Exception error) {
            return "";
        }
    }

    // ------------------------------------------------------------ 业务接口

    /**
     * RPC 形状的响应归一：`runtime/messages`、`runtime/interrupt`、`notices/.../respond`、
     * `runtime/selections` 这类请求 HTTP 一律 200，真正成败在 body 的 `ok` 字段里。
     * `ok=false` 时把 `error.message`（或 code）翻成失败结果。
     */
    private static Result rpcResult(Result result) {
        if (!result.ok || result.value == null) return result;
        if (!result.value.optBoolean("ok", true)) {
            String message = "";
            JSONObject error = result.value.optJSONObject("error");
            if (error != null) {
                message = translateServer(Api.text(error, "message"));
                if (message.isEmpty()) message = Api.text(error, "code");
            }
            return Result.failure(message.isEmpty() ? "设备拒绝了请求" : message);
        }
        return result;
    }

    /** 服务器上的连接器（跑 Agent 的那台电脑）。 */
    void devices(Callback callback) {
        request("GET", "/connectors", null, true, callback);
    }

    /** 某台连接器上可用的运行时（新建会话要挑 dsh 运行时）。 */
    void runtimes(String connectorId, Callback callback) {
        request("GET", "/connectors/" + urlEncode(connectorId) + "/runtimes", null, true, callback);
    }

    /** 全部项目。 */
    void projects(Callback callback) {
        request("GET", "/projects", null, true, callback);
    }

    /** 未归档的会话列表。 */
    void sessions(Callback callback) {
        request("GET", "/sessions?archived=false&limit=100", null, true, callback);
    }

    /** 会话快照：会话信息 + 运行状态 + 最近时间线 + 通知 + 模型目录，一次全拿。 */
    void snapshot(String sessionId, Callback callback) {
        request("GET", "/sessions/" + urlEncode(sessionId) + "/snapshot?limit=100", null, true, callback);
    }

    /** 时间线增量：只要 afterSeq 之后变化的条目。 */
    void timelineChanges(String sessionId, long afterSeq, Callback callback) {
        request("GET", "/sessions/" + urlEncode(sessionId) + "/timeline?mode=changes&afterSeq=" + afterSeq + "&limit=100",
                null, true, callback);
    }

    /** 更早的历史：取 orderSeq 之前的 100 条。 */
    void timelineHistory(String sessionId, long beforeOrderSeq, Callback callback) {
        request("GET", "/sessions/" + urlEncode(sessionId) + "/timeline?mode=history&beforeOrderSeq=" + beforeOrderSeq + "&limit=100",
                null, true, callback);
    }

    /** 运行状态（轮询会话页用）。 */
    void runtimeState(String sessionId, Callback callback) {
        request("GET", "/sessions/" + urlEncode(sessionId) + "/runtime/state", null, true, callback);
    }

    /** 待处理的运行时通知（提问、审批）。 */
    void notices(String sessionId, Callback callback) {
        request("GET", "/sessions/" + urlEncode(sessionId) + "/runtime/notices", null, true, callback);
    }

    /** 发一条消息。clientMessageId 便于服务器端去重。 */
    void send(String sessionId, String content, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("content", content);
            body.put("clientMessageId", UUID.randomUUID().toString());
        } catch (Exception ignored) {
            // 字符串不会失败
        }
        request("POST", "/sessions/" + urlEncode(sessionId) + "/runtime/messages", body, true,
                result -> deliver(callback, rpcResult(result)));
    }

    /** 停止当前生成。 */
    void interrupt(String sessionId, Callback callback) {
        request("POST", "/sessions/" + urlEncode(sessionId) + "/runtime/interrupt", new JSONObject(), true,
                result -> deliver(callback, rpcResult(result)));
    }

    /** 回复一条通知（答题 / 审批）。input 为 null 表示该动作不需要输入。 */
    void respond(String sessionId, String noticeId, String actionId, JSONObject input, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("actionId", actionId);
            if (input != null) body.put("input", input);
        } catch (Exception ignored) {
            // 不会失败
        }
        request("POST", "/sessions/" + urlEncode(sessionId) + "/runtime/notices/" + urlEncode(noticeId) + "/respond",
                body, true, result -> deliver(callback, rpcResult(result)));
    }

    /** 模型目录（含推理档位）。响应包在 `catalog` 键里，这里直接解包。 */
    void models(String sessionId, Callback callback) {
        request("GET", "/sessions/" + urlEncode(sessionId) + "/runtime/catalogs/model", null, true, result -> {
            if (!result.ok || result.value == null) {
                deliver(callback, result);
                return;
            }
            JSONObject catalog = result.value.optJSONObject("catalog");
            deliver(callback, Result.success(catalog == null ? result.value : catalog));
        });
    }

    /** 切换模型 / 推理强度。selections 形如 {"model": "sel_...", "reasoning": "sel_..."}。 */
    void applySelections(String sessionId, JSONObject selections, Callback callback) {
        pool.execute(() -> {
            // 改选择要先取得控制权（手机端同样如此），再提交 PATCH。
            Result takeoverResult = requestOnce("POST", "/sessions/" + urlEncode(sessionId) + "/takeover",
                    new JSONObject(), true, TIMEOUT_MS);
            if (!takeoverResult.ok) {
                deliver(callback, takeoverResult);
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("selections", selections);
            } catch (Exception ignored) {
                // 不会失败
            }
            Result patch = requestOnce("PATCH", "/sessions/" + urlEncode(sessionId) + "/runtime/selections",
                    body, true, TIMEOUT_MS);
            deliver(callback, rpcResult(patch));
        });
    }

    /** 新建会话并发送第一条消息。 */
    void createSession(String connectorId, String projectId, String runtimeType, String runtimeId,
                       String content, String title, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("connectorId", connectorId);
            body.put("projectId", projectId);
            body.put("runtime", runtimeType);
            if (runtimeId != null && !runtimeId.isEmpty()) body.put("runtimeId", runtimeId);
            body.put("content", content);
            if (title != null && !title.isEmpty()) body.put("title", title);
            body.put("clientMessageId", UUID.randomUUID().toString());
        } catch (Exception ignored) {
            // 不会失败
        }
        // 创建要等服务器冷启动运行时，读取超时放宽到 75 秒（与 AA 手机端一致）。
        requestWithTimeout("POST", "/sessions/create-and-start", body, true, 75_000,
                result -> deliver(callback, rpcResult(result)));
    }

    /** 把会话标为已读（进入会话页时调用，清掉列表上的未读标记）。 */
    void markRead(String sessionId) {
        // /sessions/read 的请求体是裸数组 ["id"]，不是 {"ids": ...}。
        JSONArray ids = new JSONArray();
        ids.put(sessionId);
        request("POST", "/sessions/read", ids, true, result -> {
            // 结果无所谓：未读标记只是提示。
        });
    }

    // ---------- 下面是把 JSON 转成界面模型的解析 ----------

    /** 会话摘要。 */
    static final class Session {
        String id = "";
        String title = "";
        boolean running;
        boolean waiting;
        boolean subagent;
        long updatedAt;
        /** 项目名（由列表页按 projectId 联上项目后填入），列表按它分组。 */
        String project = "";
        String projectPath = "";
        String projectId = "";
        String connectorId = "";
        /** 未读：服务器记了新内容还没读。 */
        boolean unread;
        String status = "idle";
        String runtime = "";
        String cwd = "";

        /** 列表页的副标题：状态 + 相对时间。 */
        String subtitle() {
            StringBuilder builder = new StringBuilder();
            if (waiting) builder.append("待回答");
            else if (running) builder.append("运行中");
            else builder.append("空闲");
            if (updatedAt > 0) builder.append(" · ").append(relativeTime(updatedAt));
            if (unread) builder.append(" · 未读");
            return builder.toString();
        }
    }

    /** 对话消息。 */
    static final class Message {
        String role = "assistant";
        String text = "";
        final List<String> tools = new ArrayList<>();
    }

    /** 选项。 */
    static final class Option {
        /** 选项在服务器侧的 id（AA 表单里答题要按 id 回传）。 */
        String id = "";
        String label = "";
        String description = "";
    }

    /** 单个问题。 */
    static final class Question {
        String id = "";
        String header = "";
        String question = "";
        boolean multiSelect;
        /** 是否允许打字自由作答（审批类问题不允许）。 */
        boolean allowCustom = true;
        final List<Option> options = new ArrayList<>();
    }

    /**
     * 一组待答问题（一条 AA 通知）。
     *
     * callId 现在装的是通知 id；审批类通知（模型改名的确认之类）没有表单，
     * 动作列表直接变成选项。
     */
    static final class Ask {
        String sessionId = "";
        String sessionTitle = "";
        String callId = "";
        /** 服务器要求的提交动作 id（表单类是 submit，审批类是所选动作自己的 id）。 */
        String actionId = "";
        /** input_request（带表单）或 approval（按钮直接回）。 */
        String kind = "input_request";
        String state = "";
        final List<Question> questions = new ArrayList<>();
    }

    /** 会话中的一个步骤：用户消息、助手回复，或一次工具调用。 */
    static final class Step {
        /** message / tool / thinking。 */
        String kind = "message";
        /** 时间线条目的服务器 id，去重与合并用。 */
        String id = "";
        /** 事件序号；同一个会话里唯一，界面用它认出「还是那一组工具调用」。 */
        long seq;
        int revision = 1;
        String role = "assistant";
        String text = "";
        /** AI 的思考内容；为空表示这条没有思考。 */
        String thinking = "";
        final List<String> tools = new ArrayList<>();
        /** 工具内部名（如 pwsh），用于图标化显示。 */
        String name = "";
        /** 工具显示名（如 命令）。 */
        String label = "工具";
        /** 参数摘要：命令或文件路径等最有信息量的一句。 */
        String detail = "";
        /** running / done / error。 */
        String status = "done";
        String result = "";
        final List<String> files = new ArrayList<>();
        boolean isError;
    }

    /** 一个可选的模型。 */
    static final class ModelInfo {
        /** 展示名（界面用）。 */
        String id = "";
        String name = "";
        /** 服务器侧选择 id（提交切换用）。 */
        String selectionId = "";
        final List<String> efforts = new ArrayList<>();
        /** 与 efforts 一一对应的推理档位选择 id。 */
        final List<String> effortIds = new ArrayList<>();
    }

    /** 一个模型供应商及其模型（AA 的目录按运行时给，界面上只有一个供应商）。 */
    static final class ProviderInfo {
        String provider = "";
        String name = "";
        final List<ModelInfo> models = new ArrayList<>();
    }

    /** 模型目录里记的当前选择；会话还没产生回复时用它当作「当前模型」。 */
    static final class ModelChoice {
        String provider = "";
        String model = "";
        String effort = "";
        String modelSelectionId = "";
        String reasoningSelectionId = "";
    }

    /** 服务器上的一个项目。 */
    static final class Project {
        /** 服务器侧项目 id（会话按它挂到项目上）。 */
        String projectId = "";
        String project = "";
        String path = "";
        int running;
        int waiting;
        long updatedAt;
        final List<Session> sessions = new ArrayList<>();
    }

    /** 一台连接器（跑 Agent 的电脑）。 */
    static final class Device {
        String connectorId = "";
        String name = "";
        String status = "";
    }

    /** 一台连接器上的一个运行时。 */
    static final class Runtime {
        String runtimeId = "";
        String runtimeType = "";
        String name = "";
        boolean available;
        boolean active;
        String status = "";
    }

    /**
     * 读一个字符串字段，缺失或为 null 时给空串。
     *
     * org.json 的 optString 在字段显式为 null 时会返回 null（而不是默认值），
     * 手表上的文案字段大量来自服务端 JSON，这里必须统一兜底，否则会变成
     * 界面上的 NullPointerException。
     *
     * 包级可见：{@link Store} 解析账号存档时也要用它。
     */
    static String text(JSONObject object, String key) {
        String value = object.optString(key, "");
        return value == null ? "" : value;
    }

    /** 数组元素版本的同一兜底。 */
    static String text(JSONArray array, int index) {
        String value = array.optString(index, "");
        return value == null ? "" : value;
    }

    /** ISO-8601 时间转毫秒；解析不了给 0（界面上就不显示时间）。 */
    static long parseTime(String value) {
        if (value == null || value.isEmpty()) return 0L;
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 有些字段是纯 UTC 的 `...Z`，Instant 也能直接读。
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException error) {
            return 0L;
        }
    }

    // ------------------------------------------------------------ 列表解析

    static List<Session> parseSessions(JSONObject root) {
        List<Session> sessions = new ArrayList<>();
        JSONArray array = root.optJSONArray("sessions");
        if (array == null) return sessions;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Session session = new Session();
            session.id = text(item, "id");
            if (session.id.isEmpty()) continue;
            session.title = text(item, "title");
            if (session.title.isEmpty()) session.title = shortCwd(text(item, "cwd"));
            if (session.title.isEmpty()) session.title = "未命名会话";
            session.projectId = text(item, "projectId");
            session.connectorId = text(item, "connectorId");
            session.runtime = text(item, "runtimeName");
            if (session.runtime.isEmpty()) session.runtime = text(item, "runtime");
            session.cwd = text(item, "cwd");
            session.status = text(item, "status");
            if (session.status.isEmpty()) session.status = "idle";
            session.running = "running".equals(session.status) || "stopping".equals(session.status);
            session.waiting = "waiting".equals(session.status) || "waiting_approval".equals(session.status)
                    || "pending".equals(session.status) || "blocked".equals(session.status);
            session.unread = item.optBoolean("unread", false);
            session.updatedAt = parseTime(text(item, "sortAt"));
            if (session.updatedAt == 0) session.updatedAt = parseTime(text(item, "lastActivityAt"));
            sessions.add(session);
        }
        return sessions;
    }

    static List<Project> parseProjects(JSONObject root) {
        List<Project> projects = new ArrayList<>();
        JSONArray array = root.optJSONArray("projects");
        if (array == null) return projects;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Project project = new Project();
            project.projectId = text(item, "id");
            project.project = text(item, "name");
            if (project.project.isEmpty()) project.project = "未命名项目";
            project.path = text(item, "workspacePath");
            projects.add(project);
        }
        return projects;
    }

    static List<Device> parseDevices(JSONObject root) {
        List<Device> devices = new ArrayList<>();
        JSONArray array = root.optJSONArray("connectors");
        if (array == null) return devices;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Device device = new Device();
            device.connectorId = text(item, "id");
            device.name = text(item, "name");
            device.status = text(item, "status");
            if (!device.connectorId.isEmpty()) devices.add(device);
        }
        return devices;
    }

    static List<Runtime> parseRuntimes(JSONObject root) {
        List<Runtime> runtimes = new ArrayList<>();
        JSONArray array = root.optJSONArray("runtimes");
        if (array == null) return runtimes;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Runtime runtime = new Runtime();
            runtime.runtimeId = text(item, "runtimeId");
            runtime.runtimeType = text(item, "runtimeType");
            runtime.name = text(item, "displayName");
            if (runtime.name.isEmpty()) runtime.name = text(item, "name");
            runtime.available = item.optBoolean("available", false);
            runtime.active = item.optBoolean("active", false);
            runtime.status = text(item, "status");
            if (!runtime.runtimeType.isEmpty()) runtimes.add(runtime);
        }
        return runtimes;
    }

    /** cwd 只留最后一段当会话标题的兜底。 */
    private static String shortCwd(String cwd) {
        if (cwd == null || cwd.isEmpty()) return "";
        String normalized = cwd.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    // ------------------------------------------------------------ 时间线解析

    /**
     * 把 AA 时间线条目数组解析成界面步骤。
     *
     * 映射关系（与 AA 安卓客户端一致）：
     * - message → kind=message，user/assistant 气泡；空文本且无附件的助手条目跳过；
     * - system(kind=reasoning) → kind=thinking，折叠的思考块；
     * - tool(kind=…) → kind=tool，卡片显示命令 / 改文件 / 搜索等；
     * - marker / artifact → 折叠成一条说明或一张文件卡片；
     * - turn.start / turn.end / hidden → 跳过（纯内部记号）。
     */
    static List<Step> parseTimelineItems(JSONArray array) {
        List<Step> steps = new ArrayList<>();
        if (array == null) return steps;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            Step step = parseTimelineItem(item);
            if (step != null) steps.add(step);
        }
        return steps;
    }

    private static Step parseTimelineItem(JSONObject item) {
        String type = text(item, "type");
        String status = text(item, "status");
        if ("hidden".equals(status)) return null;
        if ("turn.start".equals(type) || "turn.end".equals(type)) return null;

        Step step = new Step();
        step.id = text(item, "id");
        step.seq = item.optLong("orderSeq", 0L);
        step.revision = Math.max(1, item.optInt("revision", 1));
        String role = text(item, "role");
        JSONObject content = item.optJSONObject("content");
        if (content == null) content = new JSONObject();

        if ("message".equals(type)) {
            return messageStep(step, role, status, item, content);
        }
        if ("thinking".equals(type) || ("system".equals(type) && "reasoning".equals(text(content, "kind")))) {
            step.kind = "thinking";
            step.role = "assistant";
            step.text = "";
            step.thinking = reasoningText(content);
            step.status = mapStatus(status);
            return step.thinking.isEmpty() ? null : step;
        }
        if ("tool".equals(type)) {
            step.kind = "tool";
            step.role = "assistant";
            step.status = mapStatus(status);
            applyToolContent(step, content);
            return step;
        }
        if ("marker".equals(type)) {
            String label = text(content, "label");
            String kind = text(content, "kind");
            if (label.isEmpty()) {
                if ("compact".equals(kind)) label = "对话已压缩";
                else if ("error".equals(kind)) label = "出现错误";
                else label = kind.isEmpty() ? "系统标记" : kind;
            }
            step.kind = "message";
            step.role = "assistant";
            step.text = label;
            step.status = mapStatus(status);
            step.isError = "error".equals(kind);
            return step;
        }
        if ("artifact".equals(type)) {
            String path = firstText(content, "path", "filePath", "file", "uri");
            if (path.isEmpty()) return null;
            step.kind = "tool";
            step.role = "assistant";
            step.status = mapStatus(status);
            step.label = "产物";
            step.name = "artifact";
            step.detail = path;
            String diff = firstText(content, "patch", "text");
            if (!diff.isEmpty()) step.result = diff;
            return step;
        }
        if ("system".equals(type)) {
            String message = firstText(content, "message", "text");
            if (message.isEmpty()) return null;
            step.kind = "message";
            step.role = "assistant";
            step.text = message;
            step.status = mapStatus(status);
            step.isError = "error".equals(text(content, "kind")) || "error".equals(text(content, "severity"));
            return step;
        }
        return null;
    }

    /** 消息条目：文本或附件；两者都没有的助手条目不显示。 */
    private static Step messageStep(Step step, String role, String status, JSONObject item, JSONObject content) {
        step.kind = "message";
        step.role = role.isEmpty() ? "assistant" : role;
        step.status = mapStatus(status);
        String body = text(item, "text");
        if (body.isEmpty()) body = platformText(content);
        List<String> attachments = attachmentNames(item, content);
        if (body.isEmpty() && !attachments.isEmpty()) {
            body = "（附件：" + join(attachments, "、") + "）";
        } else if (body.isEmpty() && "user".equals(step.role) && !attachments.isEmpty()) {
            body = join(attachments, "、");
        }
        step.text = body;
        if (step.text.isEmpty() && "assistant".equals(step.role)) return null;
        if (!attachments.isEmpty() && !step.text.startsWith("（附件")) {
            step.tools.addAll(attachments);
        }
        return step;
    }

    /**
     * 工具条目：按 content.kind 分派显示。
     *
     * 字段名与 AA 安卓客户端的投影逻辑一致（命令、文件改动、子任务、搜索、MCP、
     * 通用 tool_call 等），读不到的字段自然留空。
     */
    private static void applyToolContent(Step step, JSONObject content) {
        String kind = text(content, "kind");
        step.name = kind.isEmpty() ? "tool" : kind;
        JSONObject input = content.optJSONObject("input");
        switch (kind) {
            case "command": {
                step.label = "命令";
                String command = commandText(content, input);
                step.detail = shorten(command);
                step.result = outputText(content);
                int exit = content.optInt("exitCode", Integer.MIN_VALUE);
                if (exit != Integer.MIN_VALUE && !step.result.isEmpty()) {
                    step.result = step.result + "\n（退出码 " + exit + "）";
                }
                if (!text(content, "error").isEmpty()) {
                    step.result = step.result.isEmpty() ? text(content, "error") : step.result + "\n" + text(content, "error");
                    step.isError = true;
                }
                break;
            }
            case "file_change": {
                step.label = "改文件";
                JSONArray changes = content.optJSONArray("changes");
                if (changes != null) {
                    for (int i = 0; i < changes.length(); i++) {
                        JSONObject change = changes.optJSONObject(i);
                        if (change == null) continue;
                        String path = firstText(change, "path", "filePath", "file", "uri");
                        if (path.isEmpty()) continue;
                        step.files.add(path);
                        String diff = text(change, "diff");
                        if (step.result.isEmpty() && !diff.isEmpty()) step.result = diff;
                    }
                }
                step.detail = step.files.isEmpty() ? "" : shorten(step.files.get(0))
                        + (step.files.size() > 1 ? " 等 " + step.files.size() + " 个文件" : "");
                break;
            }
            case "agent_call": {
                step.label = "子任务";
                String detail = firstText(content, "description", "prompt", "title");
                step.detail = shorten(detail);
                step.result = outputText(content);
                break;
            }
            case "web_search": {
                step.label = "搜索";
                String query = firstText(content, "query");
                if (query.isEmpty() && input != null) query = text(input, "query");
                step.detail = shorten(query);
                step.result = outputText(content);
                break;
            }
            case "mcp": {
                String server = text(content, "server");
                if (server.isEmpty() && input != null) server = text(input, "server");
                String tool = text(content, "tool");
                if (tool.isEmpty() && input != null) tool = text(input, "tool");
                step.label = (server.isEmpty() ? "mcp" : server) + " / " + (tool.isEmpty() ? "tool" : tool);
                step.detail = "";
                step.result = outputText(content);
                break;
            }
            default: {
                // tool_call / tool_result / permission / input_request / unknown
                String title = firstText(content, "toolName", "name", "tool", "title", "function");
                step.label = title.isEmpty() ? "工具" : title;
                step.detail = shorten(toolTargetText(content, input));
                step.result = outputText(content);
                if (!text(content, "error").isEmpty()) {
                    step.result = step.result.isEmpty() ? text(content, "error") : step.result + "\n" + text(content, "error");
                    step.isError = true;
                }
                break;
            }
        }
        if ("failed".equals(text(content, "status"))) step.isError = true;
    }

    /** AA 状态 → 界面三态。 */
    private static String mapStatus(String status) {
        if ("failed".equals(status)) return "error";
        if ("pending".equals(status) || "running".equals(status) || "waiting_approval".equals(status)
                || "inProgress".equals(status)) return "running";
        return "done";
    }

    /** 思考内容：分段摘要优先，退回原文。 */
    private static String reasoningText(JSONObject content) {
        StringBuilder builder = new StringBuilder();
        JSONArray summaries = content.optJSONArray("summaries");
        if (summaries != null) {
            for (int i = 0; i < summaries.length(); i++) {
                JSONObject summary = summaries.optJSONObject(i);
                if (summary == null) continue;
                String piece = text(summary, "text");
                if (!piece.isEmpty()) {
                    if (builder.length() > 0) builder.append('\n');
                    builder.append(piece);
                }
            }
        }
        if (builder.length() > 0) return builder.toString();
        String raw = firstText(content, "rawText", "text");
        return raw == null ? "" : raw;
    }

    /** 消息正文兜底：content 里多个字段名都可能装正文（多模态等）。 */
    private static String platformText(JSONObject content) {
        String value = firstText(content, "text", "message", "description", "rawText");
        if (value != null && !value.isEmpty()) return value;
        Object nested = content.opt("content");
        if (nested instanceof String) return (String) nested;
        if (nested instanceof JSONObject) {
            String inner = firstText((JSONObject) nested, "text", "message", "description", "rawText");
            return inner == null ? "" : inner;
        }
        if (nested instanceof JSONArray) {
            JSONArray array = (JSONArray) nested;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                Object part = array.opt(i);
                String piece = null;
                if (part instanceof String) piece = (String) part;
                else if (part instanceof JSONObject) {
                    piece = firstText((JSONObject) part, "text", "message", "description", "rawText");
                }
                if (piece != null && !piece.isEmpty()) {
                    if (builder.length() > 0) builder.append('\n');
                    builder.append(piece);
                }
            }
            return builder.toString();
        }
        return "";
    }

    private static List<String> attachmentNames(JSONObject item, JSONObject content) {
        List<String> names = new ArrayList<>();
        collectAttachments(content, names);
        JSONObject nested = content.optJSONObject("content");
        if (nested != null) collectAttachments(nested, names);
        collectAttachments(item, names);
        return names;
    }

    private static void collectAttachments(JSONObject object, List<String> names) {
        if (object == null) return;
        JSONArray array = object.optJSONArray("attachments");
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject attachment = array.optJSONObject(i);
            if (attachment == null) continue;
            String name = text(attachment, "name");
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
    }

    /** 命令字段：字符串或数组都能读。 */
    private static String commandText(JSONObject content, JSONObject input) {
        Object value = content.opt("command");
        if (value == null && input != null) value = input.opt("command");
        if (value == null && input != null) value = input.opt("cmd");
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String piece = text(array, i);
                if (!piece.isEmpty()) {
                    if (builder.length() > 0) builder.append(' ');
                    builder.append(piece);
                }
            }
            return builder.toString();
        }
        if (value instanceof String) return (String) value;
        return "";
    }

    /** 输出字段：字符串或对象都压成文本。 */
    private static String outputText(JSONObject content) {
        String value = firstText(content, "output", "outputPreview", "outputText");
        if (value != null && !value.isEmpty()) return value;
        Object output = content.opt("output");
        if (output instanceof JSONObject) {
            JSONObject object = (JSONObject) output;
            String inner = firstText(object, "text", "output", "message");
            if (inner != null && !inner.isEmpty()) return inner;
            return flatten(object).toString();
        }
        return "";
    }

    /** 通用工具的参数摘要：优先文件路径类键，否则整个 input 压成一行。 */
    private static String toolTargetText(JSONObject content, JSONObject input) {
        if (input != null) {
            String target = firstText(input, "file_path", "filePath", "notebook_path", "notebookPath",
                    "path", "file", "uri", "query", "url");
            if (target != null && !target.isEmpty()) return target;
        }
        if (input == null) return "";
        StringBuilder builder = new StringBuilder();
        java.util.Iterator<String> keys = input.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = input.opt(key);
            if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) continue;
            if (builder.length() > 0) builder.append(", ");
            builder.append(key).append('=').append(value);
        }
        return builder.toString();
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    /** 参数摘要里把长路径压成末两段，一行放得下更多信息。 */
    private static String shorten(String value) {
        if (value == null) return "";
        String[] words = value.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (i > 0) builder.append(' ');
            if (word.length() > 24 && (word.indexOf('\\') >= 0 || word.indexOf('/') >= 0)) {
                builder.append(shortPath(word));
            } else {
                builder.append(word);
            }
        }
        return builder.toString();
    }

    private static String shortPath(String path) {
        String normalized = path.replace('/', '\\');
        String[] parts = normalized.split("\\\\");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) kept.add(part);
        }
        if (kept.size() <= 2) return normalized;
        return kept.get(kept.size() - 2) + "\\" + kept.get(kept.size() - 1);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(separator);
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    /**
     * 合并时间线：按条目 id 去重，保留版本号更高的一份，按出现顺序排好。
     *
     * 增量轮询与整页快照都过这里，保证同一份时间线在界面上顺序稳定。
     */
    static List<Step> mergeSteps(List<Step> base, List<Step> incoming) {
        Map<String, Step> merged = new LinkedHashMap<>();
        for (Step step : base) {
            if (!step.id.isEmpty()) merged.put(step.id, step);
        }
        for (Step step : incoming) {
            if (step.id.isEmpty()) {
                merged.put(UUID.randomUUID().toString(), step);
                continue;
            }
            Step existing = merged.get(step.id);
            if (existing == null || step.revision >= existing.revision) {
                merged.put(step.id, step);
            }
        }
        List<Step> ordered = new ArrayList<>(merged.values());
        ordered.sort((a, b) -> Long.compare(a.seq, b.seq));
        return ordered;
    }

    // ------------------------------------------------------------ 通知解析

    /**
     * 把 AA 通知数组解析成待答问题。
     *
     * 只有「交互类 + 必须回复 + 状态 open」的通知需要用户动手：
     * - interactionType=input_request：表单在动作的 uiSchema 里，逐问渲染；
     * - 其余（审批、确认、执行失败）：把动作列表当成选项，点哪个就回哪个。
     */
    static List<Ask> parseNotices(JSONArray array) {
        List<Ask> asks = new ArrayList<>();
        if (array == null) return asks;
        for (int i = 0; i < array.length(); i++) {
            JSONObject notice = array.optJSONObject(i);
            if (notice == null) continue;
            if (!"interaction".equals(text(notice, "type"))) continue;
            if (!notice.optBoolean("responseRequired", false)) continue;
            String status = text(notice, "status");
            if (!"open".equals(status) && !"responding".equals(status)) continue;
            Ask ask = noticeToAsk(notice, status);
            if (ask != null && !ask.questions.isEmpty()) asks.add(ask);
        }
        return asks;
    }

    private static Ask noticeToAsk(JSONObject notice, String status) {
        Ask ask = new Ask();
        ask.callId = text(notice, "noticeId");
        ask.state = status;
        ask.sessionId = text(notice, "sessionId");
        String interactionType = text(notice, "interactionType");
        String title = text(notice, "title");
        String message = text(notice, "message");
        if ("input_request".equals(interactionType)) {
            JSONObject form = findInputForm(notice);
            if (form == null) return null;
            ask.kind = "input_request";
            ask.actionId = text(form, "actionId");
            JSONArray questions = form.optJSONObject("uiSchema") == null
                    ? null : form.optJSONObject("uiSchema").optJSONArray("questions");
            if (questions == null) return null;
            for (int i = 0; i < questions.length(); i++) {
                JSONObject question = questions.optJSONObject(i);
                if (question == null) continue;
                Question parsed = new Question();
                parsed.id = text(question, "id");
                parsed.header = text(question, "header");
                parsed.question = text(question, "prompt");
                if (parsed.question.isEmpty()) parsed.question = title;
                parsed.multiSelect = question.optBoolean("multiple", false);
                parsed.allowCustom = question.optBoolean("allowCustom", true);
                JSONArray options = question.optJSONArray("options");
                if (options != null) {
                    for (int j = 0; j < options.length(); j++) {
                        JSONObject option = options.optJSONObject(j);
                        if (option == null) continue;
                        Option parsedOption = new Option();
                        parsedOption.id = text(option, "id");
                        parsedOption.label = text(option, "label");
                        parsedOption.description = text(option, "description");
                        if (!parsedOption.label.isEmpty()) parsed.options.add(parsedOption);
                    }
                }
                if (parsed.id.isEmpty() || parsed.question.isEmpty()) continue;
                ask.questions.add(parsed);
            }
        } else {
            // 审批 / 确认 / 执行失败：动作即选项。
            ask.kind = "approval";
            ask.actionId = "";
            Question parsed = new Question();
            parsed.id = ask.callId.isEmpty() ? "action" : ask.callId;
            parsed.header = "";
            parsed.question = title.isEmpty() ? "请确认" : title;
            if (!message.isEmpty() && !message.equals(title)) parsed.question = parsed.question + "\n" + message;
            parsed.multiSelect = false;
            parsed.allowCustom = false;
            JSONArray actions = notice.optJSONArray("actions");
            if (actions != null) {
                for (int j = 0; j < actions.length(); j++) {
                    JSONObject action = actions.optJSONObject(j);
                    if (action == null) continue;
                    Option option = new Option();
                    option.id = text(action, "actionId");
                    option.label = text(action, "label");
                    if (option.label.isEmpty()) option.label = option.id;
                    parsed.options.add(option);
                }
            }
            if (parsed.options.isEmpty()) return null;
            ask.questions.add(parsed);
        }
        return ask;
    }

    /** 找到带 inputRequest 表单的动作（表单装在它的 uiSchema 里）。 */
    private static JSONObject findInputForm(JSONObject notice) {
        JSONArray actions = notice.optJSONArray("actions");
        if (actions == null) return null;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) continue;
            JSONObject input = action.optJSONObject("input");
            if (input == null) continue;
            JSONObject uiSchema = input.optJSONObject("uiSchema");
            if (uiSchema != null && "inputRequest".equals(text(uiSchema, "component"))) {
                // 表单动作要原样回传它的 actionId。
                try {
                    JSONObject wrapper = new JSONObject();
                    wrapper.put("actionId", text(action, "actionId"));
                    wrapper.put("uiSchema", uiSchema);
                    return wrapper;
                } catch (Exception ignored) {
                    // 不会失败
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 模型目录解析

    /**
     * 解析模型目录。AA 的目录按运行时给（没有供应商层级），界面上把它包成
     * 一个「供应商」以便沿用原来的三级选择页（第一层只有一个入口）。
     */
    static List<ProviderInfo> parseModels(JSONObject catalog) {
        List<ProviderInfo> providers = new ArrayList<>();
        if (catalog == null) return providers;
        ProviderInfo provider = new ProviderInfo();
        provider.provider = text(catalog, "runtime");
        provider.name = "模型";
        JSONArray models = catalog.optJSONArray("models");
        if (models != null) {
            for (int i = 0; i < models.length(); i++) {
                JSONObject model = models.optJSONObject(i);
                if (model == null) continue;
                ModelInfo info = new ModelInfo();
                info.name = text(model, "displayName");
                if (info.name.isEmpty()) info.name = text(model, "id");
                info.id = info.name;
                info.selectionId = text(model, "selectionId");
                if (info.selectionId.isEmpty()) info.selectionId = text(model, "id");
                JSONArray reasonings = model.optJSONArray("reasoningItems");
                if (reasonings != null) {
                    for (int j = 0; j < reasonings.length(); j++) {
                        JSONObject reasoning = reasonings.optJSONObject(j);
                        if (reasoning == null) continue;
                        if (!reasoning.optBoolean("enabled", true)) continue;
                        info.efforts.add(text(reasoning, "displayName"));
                        info.effortIds.add(text(reasoning, "selectionId"));
                    }
                }
                if (!info.name.isEmpty()) provider.models.add(info);
            }
        }
        if (!provider.models.isEmpty()) providers.add(provider);
        return providers;
    }

    /** 目录里的默认选择；会话还没有选择记录时充当「当前模型」。 */
    static ModelChoice parseModelDefault(JSONObject catalog) {
        ModelChoice choice = new ModelChoice();
        if (catalog == null) return choice;
        JSONArray models = catalog.optJSONArray("models");
        if (models == null) return choice;
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model == null) continue;
            if (model.optBoolean("default", false) || i == 0) {
                choice.model = text(model, "displayName");
                if (choice.model.isEmpty()) choice.model = text(model, "id");
                choice.modelSelectionId = text(model, "selectionId");
                if (choice.modelSelectionId.isEmpty()) choice.modelSelectionId = text(model, "id");
                JSONArray reasonings = model.optJSONArray("reasoningItems");
                if (reasonings != null) {
                    for (int j = 0; j < reasonings.length(); j++) {
                        JSONObject reasoning = reasonings.optJSONObject(j);
                        if (reasoning == null) continue;
                        if (!reasoning.optBoolean("enabled", true)) continue;
                        if (reasoning.optBoolean("default", false) || choice.effort.isEmpty()) {
                            choice.effort = text(reasoning, "displayName");
                            choice.reasoningSelectionId = text(reasoning, "selectionId");
                        }
                    }
                }
                if (model.optBoolean("default", false)) break;
            }
        }
        return choice;
    }

    /** 从运行状态的选择表里反查显示名（快照载入时用）。 */
    static ModelChoice choiceFromSelections(JSONObject state, List<ProviderInfo> providers) {
        ModelChoice choice = new ModelChoice();
        if (state == null || providers.isEmpty()) return choice;
        JSONObject selections = state.optJSONObject("selections");
        if (selections == null) return choice;
        String modelSelection = text(selections, "model");
        String reasoningSelection = text(selections, "reasoning");
        for (ProviderInfo provider : providers) {
            for (ModelInfo model : provider.models) {
                if (modelSelection.isEmpty() || model.selectionId.equals(modelSelection)) {
                    choice.model = model.name;
                    choice.modelSelectionId = model.selectionId;
                    for (int i = 0; i < model.effortIds.size(); i++) {
                        if (reasoningSelection.isEmpty() || model.effortIds.get(i).equals(reasoningSelection)) {
                            choice.effort = model.efforts.get(i);
                            choice.reasoningSelectionId = model.effortIds.get(i);
                            break;
                        }
                    }
                    return choice;
                }
            }
        }
        return choice;
    }

    // ------------------------------------------------------------ 工具

    /** 相对时间：手表上空间紧张，用最短的表达。 */
    static String relativeTime(long epochMs) {
        long delta = System.currentTimeMillis() - epochMs;
        if (delta < 0) delta = 0;
        long minutes = delta / 60_000L;
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        long days = hours / 24;
        if (days < 30) return days + " 天前";
        return (days / 30) + " 个月前";
    }

    static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    /** 供设置页展示用：把 JSON 对象里的布尔/字符串字段读成 map。 */
    static Map<String, String> flatten(JSONObject object) {
        Map<String, String> map = new LinkedHashMap<>();
        if (object == null) return map;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            map.put(key, String.valueOf(object.opt(key)));
        }
        return map;
    }
}
