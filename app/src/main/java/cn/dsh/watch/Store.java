package cn.dsh.watch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 手表侧的本地状态：多个 Agents Anywhere 账号的登录信息，以及当前在用的那一个。
 *
 * 设计要点：
 * - **记住多个账号**。同一块手表可能在「官方云端」「自建服务器」之间来回切，
 *   每个账号单独存一条（服务器地址 + 邮箱 + 密码 + 令牌 + 备注 + 最后使用时间），
 *   可随时切换。
 * - **base() / token() 保持原有语义**：返回「当前账号」的值，调用点不用关心
 *   账号切换的细节。
 * - 邮箱与密码必须保存：AA 的令牌 7 天过期且没有刷新接口，过期后要靠它们
 *   静默重新登录。存档与本机 SharedPreferences 同级（单用户设备）。
 */
final class Store {
    private static final String FILE = "dsh-watch";
    /** 账号列表（JSON 数组）。 */
    private static final String KEY_ACCOUNTS = "accounts";
    /** 当前账号在列表里的下标。 */
    private static final String KEY_CURRENT = "currentAccount";
    private static final String KEY_LAST_SESSION = "lastSession";
    private static final String KEY_LAST_SESSION_TITLE = "lastSessionTitle";

    /** 一个已记住的 AA 账号。 */
    static final class Account {
        /** 服务器根地址，形如 https://web.agents-anywhere.com。 */
        String base = "";
        String email = "";
        String password = "";
        String token = "";
        /** 令牌过期时间（毫秒）；AA 签发 7 天，过期前靠静默重登续期。 */
        long tokenExpiresAt = 0L;
        String userId = "";
        String displayName = "";
        /** 用户可读的备注；为空时界面用邮箱与服务器地址取名。 */
        String label = "";
        long lastUsed = 0L;

        boolean valid() {
            return base != null && !base.isEmpty()
                    && email != null && !email.isEmpty()
                    && password != null && !password.isEmpty();
        }

        /** 令牌是否还在有效期内（过期时间未知时按可用处理，交给 401 重登兜底）。 */
        boolean tokenUsable() {
            return token != null && !token.isEmpty()
                    && (tokenExpiresAt <= 0 || tokenExpiresAt > System.currentTimeMillis());
        }

        boolean isPublic() {
            return base != null && base.startsWith("https://");
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("base", base == null ? "" : base);
                object.put("email", email == null ? "" : email);
                object.put("password", password == null ? "" : password);
                object.put("token", token == null ? "" : token);
                object.put("tokenExpiresAt", tokenExpiresAt);
                object.put("userId", userId == null ? "" : userId);
                object.put("displayName", displayName == null ? "" : displayName);
                object.put("label", label == null ? "" : label);
                object.put("lastUsed", lastUsed);
            } catch (Exception ignored) {
                // 值都是字符串与数字，不会失败。
            }
            return object;
        }

        static Account fromJson(JSONObject object) {
            if (object == null) return null;
            Account account = new Account();
            account.base = Api.text(object, "base");
            account.email = Api.text(object, "email");
            account.password = Api.text(object, "password");
            account.token = Api.text(object, "token");
            account.tokenExpiresAt = object.optLong("tokenExpiresAt", 0L);
            account.userId = Api.text(object, "userId");
            account.displayName = Api.text(object, "displayName");
            account.label = Api.text(object, "label");
            account.lastUsed = object.optLong("lastUsed", 0L);
            return account;
        }
    }

    private final SharedPreferences prefs;

    Store(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 账号列表

    /** 读全部已记住的账号（按最后使用时间倒序，最近的在前）。 */
    List<Account> accounts() {
        List<Account> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_ACCOUNTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Account account = Account.fromJson(array.optJSONObject(i));
                if (account != null) list.add(account);
            }
        } catch (Exception ignored) {
            // 存档损坏时从空开始，用户重新登录即可。
        }
        list.sort((a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        return list;
    }

    /** 当前账号；没有登录过时返回 null。 */
    Account current() {
        List<Account> list = accounts();
        if (list.isEmpty()) return null;
        int index = prefs.getInt(KEY_CURRENT, 0);
        if (index < 0 || index >= list.size()) index = 0;
        return list.get(index);
    }

    /** 记录一次登录成功（同一 服务器+邮箱 会覆盖，不会堆重复项），并切换为当前账号。 */
    void saveLogin(String base, String email, String password, String token,
                   long tokenExpiresAt, String userId, String displayName, String label) {
        List<Account> list = accounts();
        Account account = null;
        for (Account item : list) {
            if (item.base.equals(base) && item.email.equalsIgnoreCase(email)) {
                account = item;
                break;
            }
        }
        if (account == null) {
            account = new Account();
            list.add(account);
        }
        account.base = base;
        account.email = email;
        account.password = password;
        account.token = token == null ? "" : token;
        account.tokenExpiresAt = tokenExpiresAt;
        account.userId = userId == null ? "" : userId;
        account.displayName = displayName == null ? "" : displayName;
        if (label != null && !label.isEmpty()) account.label = label;
        account.lastUsed = System.currentTimeMillis();
        persist(list, list.indexOf(account));
    }

    /** 静默重登成功后刷新令牌（不动密码与备注）。 */
    void updateToken(String base, String email, String token, long tokenExpiresAt) {
        List<Account> list = accounts();
        for (int i = 0; i < list.size(); i++) {
            Account account = list.get(i);
            if (account.base.equals(base) && account.email.equalsIgnoreCase(email)) {
                account.token = token == null ? "" : token;
                account.tokenExpiresAt = tokenExpiresAt;
                account.lastUsed = System.currentTimeMillis();
                persist(list, i);
                return;
            }
        }
    }

    /** 切换当前账号（换账号等同于换了一个世界，调用方要清空会话状态）。 */
    void useAccount(Account account) {
        if (account == null || !account.valid()) return;
        List<Account> list = accounts();
        for (int i = 0; i < list.size(); i++) {
            Account item = list.get(i);
            if (item.base.equals(account.base) && item.email.equalsIgnoreCase(account.email)) {
                item.lastUsed = System.currentTimeMillis();
                persist(list, i);
                return;
            }
        }
    }

    /** 删除一个账号。 */
    void forgetAccount(Account account) {
        List<Account> list = accounts();
        for (int i = 0; i < list.size(); i++) {
            Account item = list.get(i);
            if (item.base.equals(account.base) && item.email.equalsIgnoreCase(account.email)) {
                list.remove(i);
                break;
            }
        }
        persist(list, 0);
    }

    private void persist(List<Account> list, int currentIndex) {
        JSONArray array = new JSONArray();
        for (Account account : list) array.put(account.toJson());
        prefs.edit().putString(KEY_ACCOUNTS, array.toString())
                .putInt(KEY_CURRENT, Math.max(0, currentIndex))
                .apply();
    }

    // ------------------------------------------------------------ 当前账号的快捷读取

    /** 当前账号的服务器根地址（形如 https://host[:port]）。 */
    String base() {
        Account account = current();
        return account == null ? "" : account.base;
    }

    /** 当前账号的访问令牌。 */
    String token() {
        Account account = current();
        return account == null ? "" : account.token;
    }

    String email() {
        Account account = current();
        return account == null ? "" : account.email;
    }

    String password() {
        Account account = current();
        return account == null ? "" : account.password;
    }

    /** 是否已经登录（有账号且密码在，令牌过期也没关系——会静默重登）。 */
    boolean paired() {
        Account account = current();
        return account != null && account.valid();
    }

    /** 清除当前账号的令牌（保留账号本身，用于「重新登录」场景）。 */
    void clearToken() {
        List<Account> list = accounts();
        int index = prefs.getInt(KEY_CURRENT, 0);
        if (index < 0 || index >= list.size()) return;
        list.get(index).token = "";
        list.get(index).tokenExpiresAt = 0L;
        persist(list, index);
    }

    // ------------------------------------------------------------ 最近会话

    void setLastSession(String sessionId, String title) {
        prefs.edit()
                .putString(KEY_LAST_SESSION, sessionId == null ? "" : sessionId)
                .putString(KEY_LAST_SESSION_TITLE, title == null ? "" : title)
                .apply();
    }

    String lastSession() {
        String value = prefs.getString(KEY_LAST_SESSION, "");
        return value == null ? "" : value;
    }

    String lastSessionTitle() {
        String value = prefs.getString(KEY_LAST_SESSION_TITLE, "");
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------ 地址解析

    /** 解析结果：规范化后的根地址 + 协议 + 主机 + 端口。 */
    static final class Endpoint {
        final String canonical;
        final String scheme;
        final String host;
        final int port;

        private Endpoint(String canonical, String scheme, String host, int port) {
            this.canonical = canonical;
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        /** 拼出以 path 开头的完整地址。 */
        String url(String path) {
            StringBuilder builder = new StringBuilder(scheme).append("://").append(host);
            if (!(("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80))) {
                builder.append(':').append(port);
            }
            if (path == null) return builder.toString();
            return path.startsWith("/") ? builder.append(path).toString() : builder.append('/').append(path).toString();
        }

        /** 展示用：带协议的完整地址。 */
        String display() {
            return url(null);
        }
    }

    /**
     * 解析用户输入的 AA 服务器地址。
     *
     * 接受 `https://host`、`http://host:port`、`host`（补 https:443）、`host:port`（补 http）。
     * 与旧中继不同，裸主机名默认按 https 处理：AA 服务器几乎总是走 TLS。
     * 返回 null 表示格式不合法。
     */
    static Endpoint parseServer(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return null;
        int schemeAt = value.indexOf("://");
        String scheme = "https";
        String rest = value;
        if (schemeAt >= 0) {
            scheme = value.substring(0, schemeAt).toLowerCase();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            rest = value.substring(schemeAt + 3);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int port = "https".equals(scheme) ? 443 : 80;
        String host = rest;
        if (!rest.isEmpty() && rest.charAt(0) != '[' && rest.indexOf(':') == rest.lastIndexOf(':')) {
            int colon = rest.lastIndexOf(':');
            if (colon > 0) {
                try {
                    int parsed = Integer.parseInt(rest.substring(colon + 1));
                    if (parsed <= 0 || parsed > 65535) return null;
                    port = parsed;
                    host = rest.substring(0, colon);
                } catch (NumberFormatException error) {
                    // 不是端口，按整体当主机名处理（IPv6 字面量等）。
                }
            }
        }
        if (host.isEmpty()) return null;

        String canonical = scheme + "://" + host
                + ((("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) ? "" : ":" + port);
        return new Endpoint(canonical, scheme, host, port);
    }
}
