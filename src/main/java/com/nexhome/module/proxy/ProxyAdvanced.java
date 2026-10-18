package com.nexhome.module.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nexhome.core.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 代理站点高级配置（proxy_site.advanced 列的解析结果）。
 * <p>
 * 全部为结构化字段，由程序解释执行（非 nginx 指令），JSON 形态：
 * <pre>{@code
 * {
 *   "reqHeaders":  [{"op":"set|delete","name":"X-A","value":"1"}],  // 回源请求头改写
 *   "respHeaders": [{"op":"set|delete","name":"X-B","value":"2"}],  // 响应头改写
 *   "pathRewrite": {"from":"/api","to":"/v2"},                      // 请求路径前缀替换
 *   "stripPrefix": "/portal",                                       // 剥离请求路径前缀
 *   "timeout":     {"connectSec":10,"idleSec":300},                 // 后端连接/空闲超时
 *   "maxBodyMB":   0,                                               // 请求体上限（0=不限）
 *   "rateLimitRps":0,                                               // 站点限流 QPS（0=不限）
 *   "basicAuth":   {"user":"","pass":""},                           // Basic 认证（user 空=关闭）
 *   "ipAllow":     ["192.168.1.", "10.0.0.5"],                      // 来源 IP 白名单：精确 IP 或以点结尾的网段前缀（空=不限）
 *   "backendInsecure": false,                                       // 后端 HTTPS 跳过证书校验
 *   "upstreamKeepalive": false,                                     // 回源长连接池（按后端 host:port 复用空闲连接）
 *   "upstreamIdleSec": 60,                                          // 池内空闲连接回收秒数
 *   "upstreamMaxIdleConns": 32,                                     // 每后端路由(每事件循环)保留的最大空闲连接数
 *   "cache":       {"enabled":false,"ttlSec":3600,"inactiveSec":604800,
 *                  "maxMB":256,"maxItemMB":8,"respectUpstream":true,
 *                  "extensions":["js","css","png"]}                 // 文件缓存（nginx 风格）
 * }
 * }</pre>
 */
public final class ProxyAdvanced {

    /** 请求/响应头改写操作 */
    public record HeaderOp(String op, String name, String value) {
    }

    /** 禁止改写的头部（逐跳/框架自控头，改写会破坏转发正确性） */
    static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-connection", "te", "trailer", "transfer-encoding",
            "upgrade", "content-length", "host", "date", "server", "x-cache");

    /** 默认缓存扩展名（静态资源类） */
    static final List<String> DEFAULT_CACHE_EXTENSIONS =
            List.of("js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf");

    public List<HeaderOp> reqHeaders = List.of();
    public List<HeaderOp> respHeaders = List.of();
    public String pathRewriteFrom, pathRewriteTo;
    public String stripPrefix;
    public int connectSec = 10;
    public int idleSec = 300;
    public int maxBodyMB = 0;
    public double rateLimitRps = 0;
    public String authUser = "", authPass = "";
    public List<String> ipAllow = List.of();
    public boolean backendInsecure = false;

    // ---------- 回源长连接池（upstream keepalive） ----------
    /** 是否复用回源连接（默认关闭：每请求短连接，对齐 nginx 默认不启用 upstream keepalive） */
    public boolean upstreamKeepalive = false;
    /** 池内空闲连接最长保留时间（秒），超时即回收，兼顾后端自身的 keep-alive 空闲关闭 */
    public int upstreamIdleSec = 60;
    /** 每个后端路由在每个事件循环上保留的最大空闲连接数 */
    public int upstreamMaxIdleConns = 32;

    // ---------- 文件缓存 ----------
    public boolean cacheEnabled = false;
    public int cacheTtlSec = 3600;
    public int cacheInactiveSec = 604800;   // 7 天未命中即清理（nginx inactive 语义）
    public int cacheMaxMB = 256;            // 站点缓存总容量上限
    public int cacheMaxItemMB = 8;          // 单文件上限
    public boolean cacheRespectUpstream = true;
    public List<String> cacheExtensions = DEFAULT_CACHE_EXTENSIONS;

    public static ProxyAdvanced defaults() {
        return new ProxyAdvanced();
    }

    /**
     * 解析并校验 advanced JSON。
     * 非法输入抛 {@link IllegalArgumentException}（由全局异常处理映射为 400，前端直接展示原因）。
     */
    public static ProxyAdvanced parse(String json) {
        ProxyAdvanced a = new ProxyAdvanced();
        if (json == null || json.isBlank()) return a;
        JsonObject o;
        try {
            // 严格解析：非法 JSON / 非 JSON 对象一律拒绝，避免坏配置被静默洗白为默认值
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) throw new IllegalArgumentException("advanced 必须是 JSON 对象");
            o = el.getAsJsonObject();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("advanced 不是合法 JSON: " + e.getMessage());
        }

        a.reqHeaders = parseHeaderOps(o, "reqHeaders");
        a.respHeaders = parseHeaderOps(o, "respHeaders");

        if (o.has("pathRewrite") && !o.get("pathRewrite").isJsonNull()) {
            JsonObject pr = o.getAsJsonObject("pathRewrite");
            a.pathRewriteFrom = requirePath(JsonUtils.str(pr, "from"), "pathRewrite.from");
            a.pathRewriteTo = requirePath(JsonUtils.str(pr, "to"), "pathRewrite.to");
            if (a.pathRewriteFrom.isEmpty() || a.pathRewriteTo.isEmpty())
                throw new IllegalArgumentException("pathRewrite 的 from/to 均不能为空");
        }
        if (o.has("stripPrefix") && !o.get("stripPrefix").isJsonNull()) {
            a.stripPrefix = requirePath(JsonUtils.str(o, "stripPrefix"), "stripPrefix");
            if (a.stripPrefix.isEmpty()) throw new IllegalArgumentException("stripPrefix 不能为空字符串");
        }
        if (o.has("stripPrefix") && o.has("pathRewrite"))
            throw new IllegalArgumentException("stripPrefix 与 pathRewrite 只能二选一");

        if (o.has("timeout") && !o.get("timeout").isJsonNull()) {
            JsonObject t = o.getAsJsonObject("timeout");
            a.connectSec = rangeInt(t, "connectSec", a.connectSec, 1, 120, "timeout.connectSec");
            a.idleSec = rangeInt(t, "idleSec", a.idleSec, 5, 86400, "timeout.idleSec");
        }
        a.maxBodyMB = rangeInt(o, "maxBodyMB", a.maxBodyMB, 0, 102400, "maxBodyMB");
        a.rateLimitRps = rangeDouble(o, "rateLimitRps", a.rateLimitRps, 0, 1000000, "rateLimitRps");

        if (o.has("basicAuth") && !o.get("basicAuth").isJsonNull()) {
            JsonObject ba = o.getAsJsonObject("basicAuth");
            a.authUser = JsonUtils.str(ba, "user").trim();
            a.authPass = JsonUtils.str(ba, "pass");
            if (!a.authUser.isEmpty()) {
                if (a.authPass.isEmpty()) throw new IllegalArgumentException("basicAuth 配置了 user 时 pass 不能为空");
                if (a.authUser.contains(":") || a.authUser.matches(".*\\p{Cntrl}.*")
                        || a.authPass.matches(".*\\p{Cntrl}.*"))
                    throw new IllegalArgumentException("basicAuth 的 user 不能含冒号/控制字符");
            }
        }
        if (o.has("ipAllow") && !o.get("ipAllow").isJsonNull()) {
            List<String> list = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("ipAllow")) {
                String v = el.getAsString().trim();
                if (v.isEmpty()) throw new IllegalArgumentException("ipAllow 存在空条目");
                if (v.matches(".*[\\s,].*") || v.matches(".*\\p{Cntrl}.*"))
                    throw new IllegalArgumentException("ipAllow 条目非法（应为 IP 或 IP 前缀，不能含空白/逗号）: " + v);
                list.add(v.toLowerCase(Locale.ROOT));
            }
            a.ipAllow = List.copyOf(list);
        }
        a.backendInsecure = JsonUtils.bool(o, "backendInsecure", a.backendInsecure);
        a.upstreamKeepalive = JsonUtils.bool(o, "upstreamKeepalive", a.upstreamKeepalive);
        a.upstreamIdleSec = rangeInt(o, "upstreamIdleSec", a.upstreamIdleSec, 1, 3600, "upstreamIdleSec");
        a.upstreamMaxIdleConns = rangeInt(o, "upstreamMaxIdleConns", a.upstreamMaxIdleConns, 1, 4096, "upstreamMaxIdleConns");

        if (o.has("cache") && !o.get("cache").isJsonNull()) {
            JsonObject c = o.getAsJsonObject("cache");
            a.cacheEnabled = JsonUtils.bool(c, "enabled", a.cacheEnabled);
            a.cacheTtlSec = rangeInt(c, "ttlSec", a.cacheTtlSec, 1, 31536000, "cache.ttlSec");
            a.cacheInactiveSec = rangeInt(c, "inactiveSec", a.cacheInactiveSec, 60, 31536000, "cache.inactiveSec");
            a.cacheMaxMB = rangeInt(c, "maxMB", a.cacheMaxMB, 1, 1048576, "cache.maxMB");
            a.cacheMaxItemMB = rangeInt(c, "maxItemMB", a.cacheMaxItemMB, 1, 1024, "cache.maxItemMB");
            a.cacheRespectUpstream = JsonUtils.bool(c, "respectUpstream", a.cacheRespectUpstream);
            if (c.has("extensions") && !c.get("extensions").isJsonNull()) {
                List<String> ext = new ArrayList<>();
                for (JsonElement el : c.getAsJsonArray("extensions")) {
                    String e = el.getAsString().trim().toLowerCase(Locale.ROOT);
                    if (!e.matches("[a-z0-9]{1,10}"))
                        throw new IllegalArgumentException("cache.extensions 条目非法（应为纯扩展名，如 js）: " + e);
                    ext.add(e);
                }
                a.cacheExtensions = List.copyOf(ext);
            }
        }
        return a;
    }

    /** 回写完整 JSON（含默认值），供前端预填与「自定义 JSON」页展示 */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("reqHeaders", headerOpsToJson(reqHeaders));
        o.add("respHeaders", headerOpsToJson(respHeaders));
        if (pathRewriteFrom != null) {
            JsonObject pr = new JsonObject();
            pr.addProperty("from", pathRewriteFrom);
            pr.addProperty("to", pathRewriteTo);
            o.add("pathRewrite", pr);
        } else if (stripPrefix != null) {
            o.addProperty("stripPrefix", stripPrefix);
        }
        JsonObject t = new JsonObject();
        t.addProperty("connectSec", connectSec);
        t.addProperty("idleSec", idleSec);
        o.add("timeout", t);
        o.addProperty("maxBodyMB", maxBodyMB);
        o.addProperty("rateLimitRps", rateLimitRps);
        JsonObject ba = new JsonObject();
        ba.addProperty("user", authUser);
        ba.addProperty("pass", authPass);
        o.add("basicAuth", ba);
        JsonArray ip = new JsonArray();
        ipAllow.forEach(ip::add);
        o.add("ipAllow", ip);
        o.addProperty("backendInsecure", backendInsecure);
        o.addProperty("upstreamKeepalive", upstreamKeepalive);
        o.addProperty("upstreamIdleSec", upstreamIdleSec);
        o.addProperty("upstreamMaxIdleConns", upstreamMaxIdleConns);
        JsonObject c = new JsonObject();
        c.addProperty("enabled", cacheEnabled);
        c.addProperty("ttlSec", cacheTtlSec);
        c.addProperty("inactiveSec", cacheInactiveSec);
        c.addProperty("maxMB", cacheMaxMB);
        c.addProperty("maxItemMB", cacheMaxItemMB);
        c.addProperty("respectUpstream", cacheRespectUpstream);
        JsonArray ext = new JsonArray();
        cacheExtensions.forEach(ext::add);
        c.add("extensions", ext);
        o.add("cache", c);
        return o;
    }

    // ---------- 内部工具 ----------

    private static List<HeaderOp> parseHeaderOps(JsonObject o, String key) {
        List<HeaderOp> list = new ArrayList<>();
        if (!o.has(key) || o.get(key).isJsonNull()) return List.of();
        for (JsonElement el : o.getAsJsonArray(key)) {
            JsonObject h = el.getAsJsonObject();
            String op = JsonUtils.str(h, "op").trim().toLowerCase(Locale.ROOT);
            String name = JsonUtils.str(h, "name").trim();
            String value = JsonUtils.str(h, "value").trim();
            if (!op.equals("set") && !op.equals("delete"))
                throw new IllegalArgumentException(key + ".op 只能是 set 或 delete: " + op);
            if (!name.matches("[A-Za-z0-9!#$%&'*+\\-.^_`|~]+"))
                throw new IllegalArgumentException(key + ".name 不是合法 HTTP 头名: " + name);
            if (FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException(key + " 不允许改写头: " + name + "（由代理框架自动控制）");
            if (op.equals("set") && !value.isEmpty() && value.matches(".*[\\r\\n].*"))
                throw new IllegalArgumentException(key + ".value 不能含换行（头注入风险）: " + name);
            list.add(new HeaderOp(op, name, value));
        }
        return List.copyOf(list);
    }

    private static JsonArray headerOpsToJson(List<HeaderOp> ops) {
        JsonArray arr = new JsonArray();
        for (HeaderOp h : ops) {
            JsonObject o = new JsonObject();
            o.addProperty("op", h.op());
            o.addProperty("name", h.name());
            o.addProperty("value", h.value());
            arr.add(o);
        }
        return arr;
    }

    private static String requirePath(String v, String field) {
        if (v != null && !v.isEmpty() && !v.startsWith("/"))
            throw new IllegalArgumentException(field + " 必须以 / 开头: " + v);
        if (v != null && (v.contains(" ") || v.matches(".*[\\r\\n].*")))
            throw new IllegalArgumentException(field + " 不能含空白/换行: " + v);
        return v;
    }

    private static int rangeInt(JsonObject o, String key, int def, int min, int max, String field) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        int v;
        try {
            v = o.get(key).getAsInt();
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
        if (v < min || v > max)
            throw new IllegalArgumentException(field + " 必须在 " + min + "-" + max + " 之间: " + v);
        return v;
    }

    private static double rangeDouble(JsonObject o, String key, double def, double min, double max, String field) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        double v;
        try {
            v = o.get(key).getAsDouble();
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " 必须是数字");
        }
        if (v < min || v > max)
            throw new IllegalArgumentException(field + " 必须在 " + min + "-" + max + " 之间: " + v);
        return v;
    }
}
