package com.nexhome.module.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nexhome.core.AppConfig;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 反向代理中心管理服务（管理面）。
 * <p>
 * 职责：全局开关/端口设置（存 app_config，前缀 {@code proxy.}）、站点 CRUD 与校验、
 * 状态与证书清单查询、缓存清理；站点/证书变更后触发 {@link ProxyCenter} 热更新，
 * 端口/开关变更重启监听引擎。转发数据面全部在 {@link ProxyCenter}。
 */
public final class ProxyService {

    /** 全局开关（"1"=启用监听），默认关闭：关闭时不绑定任何端口 */
    private static final String KEY_ENABLED = "proxy.enabled";
    private static final String KEY_HTTP_PORT = "proxy.http.port";
    private static final String KEY_HTTPS_PORT = "proxy.https.port";
    /** 最近一次绑定/降级提示（页头告警条展示），空=正常 */
    private static final String KEY_LAST_ERROR = "proxy.last_error";

    static final int DEFAULT_HTTP_PORT = 80;
    static final int DEFAULT_HTTPS_PORT = 443;

    private static final Set<String> CERT_MODES = Set.of("AUTO", "TASK", "NONE");

    private ProxyService() {
    }

    // ==================== REST 注册 ====================

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/proxy/settings", ProxyService::getSettings);
        WebServer.route("POST", "/api/proxy/settings", ProxyService::saveSettings);
        WebServer.route("GET", "/api/proxy/sites",
                ctx -> ctx.ok(Database.query("SELECT * FROM proxy_site ORDER BY id")));
        WebServer.route("POST", "/api/proxy/sites", ProxyService::create);
        WebServer.route("PUT", "/api/proxy/sites/{id}", ProxyService::update);
        WebServer.route("DELETE", "/api/proxy/sites/{id}", ProxyService::delete);
        WebServer.route("POST", "/api/proxy/sites/toggle", ProxyService::toggle);
        // 新建站点表单预填：返回后端默认高级配置（与保存时规范化一致）
        WebServer.route("GET", "/api/proxy/defaults",
                ctx -> ctx.ok(Map.of("advanced", ProxyAdvanced.defaults().toJson())));
        WebServer.route("GET", "/api/proxy/status", ProxyService::status);
        WebServer.route("GET", "/api/proxy/certs", ctx -> ctx.ok(SslStore.apiView()));
        WebServer.route("POST", "/api/proxy/certs/reload", ctx -> {
            SslStore.reload();
            ctx.ok(Map.of("certs", SslStore.apiView()));
        });
        WebServer.route("POST", "/api/proxy/cache/purge", ProxyService::purgeCache);
    }

    /**
     * 启动接线：初始化文件缓存（重建索引 + 周期清理），并按开关拉起监听。
     * 绑定失败不抛出（不拖垮进程），写入 {@code proxy.last_error} 供前端展示。
     */
    public static void init() {
        GeoResolver.load(); // IP 归属地离线库（ip2region xdb，外部可覆盖，均缺失时降级留空）
        ProxyCache.init(ProxyService::siteExists);
        VisitStats.init(); // 访问记录周期落库（独立于监听开关，关停期间不丢已累积增量）
        try {
            applyListener();
        } catch (Exception e) {
            Logs.error(Logs.PROXY, "启动代理监听失败: " + e.getMessage());
        }
    }

    static boolean siteExists(long id) {
        try {
            return Database.queryOne("SELECT id FROM proxy_site WHERE id=?", id) != null;
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 全局设置 ====================

    private static void getSettings(Ctx ctx) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        ProxyCenter.Cfg c = readCfg();
        m.put("enabled", c.enabled());
        m.put("httpPort", c.httpPort());
        m.put("httpsPort", c.httpsPort());
        m.put("lastError", orEmpty(Database.getConfig(KEY_LAST_ERROR)));
        ctx.ok(m);
    }

    private static void saveSettings(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        boolean enabled = JsonUtils.bool(b, "enabled", false);
        int http = JsonUtils.num(b, "httpPort", DEFAULT_HTTP_PORT);
        int https = JsonUtils.num(b, "httpsPort", DEFAULT_HTTPS_PORT);
        validatePorts(enabled, http, https);
        Database.setConfig(KEY_ENABLED, enabled ? "1" : "0");
        Database.setConfig(KEY_HTTP_PORT, String.valueOf(http));
        Database.setConfig(KEY_HTTPS_PORT, String.valueOf(https));
        String warn = applyListener();
        Logs.info(Logs.PROXY, "代理设置已更新: enabled=" + enabled + " HTTP:" + http + " HTTPS:" + https);
        Map<String, Object> r = new LinkedHashMap<>(ProxyCenter.listenView());
        r.put("lastError", orEmpty(Database.getConfig(KEY_LAST_ERROR)));
        r.put("warning", warn == null ? "" : warn);
        ctx.ok(r);
    }

    /** 端口校验：范围、两代理口不互撞、均不与面板端口冲突（面板端口见 AppConfig.port()） */
    private static void validatePorts(boolean enabled, int http, int https) {
        if (!enabled) return; // 关闭时不绑定端口，无需校验
        if (http < 1 || http > 65535) throw new IllegalArgumentException("HTTP 端口需在 1-65535 之间");
        if (https < 1 || https > 65535) throw new IllegalArgumentException("HTTPS 端口需在 1-65535 之间");
        if (http == https) throw new IllegalArgumentException("HTTP 与 HTTPS 端口不能相同: " + http);
        int panel = AppConfig.port();
        if (http == panel || https == panel)
            throw new IllegalArgumentException("代理端口不能与面板 Web 端口相同: " + panel);
    }

    /** 读取全局配置快照 */
    static ProxyCenter.Cfg readCfg() throws SQLException {
        boolean enabled = "1".equals(Database.getConfig(KEY_ENABLED));
        return new ProxyCenter.Cfg(enabled,
                intCfg(KEY_HTTP_PORT, DEFAULT_HTTP_PORT),
                intCfg(KEY_HTTPS_PORT, DEFAULT_HTTPS_PORT));
    }

    /**
     * 依当前配置重启监听：成功返回降级提示（可能为空串）并刷新 last_error；
     * HTTP 绑定失败抛异常（调用方捕获记日志），last_error 写入失败原因。
     */
    private static synchronized String applyListener() throws Exception {
        ProxyCenter.Cfg c = readCfg();
        String warn;
        try {
            warn = ProxyCenter.start(c);
        } catch (Exception e) {
            Database.setConfig(KEY_LAST_ERROR, e.getMessage());
            throw e;
        }
        Database.setConfig(KEY_LAST_ERROR, warn == null ? "" : warn);
        return warn;
    }

    // ==================== 站点 CRUD ====================

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        Values v = validate(b);
        long id = Database.insert("""
                INSERT INTO proxy_site(name, domains, paths, target, force_https, cert_mode,
                    cert_task_id, advanced, enabled)
                VALUES(?,?,?,?,?,?,?,?,?)""",
                v.name, v.domains, v.paths, v.target, v.forceHttps ? 1 : 0, v.certMode,
                v.certTaskId, v.advanced, v.enabled ? 1 : 0);
        ProxyCenter.reloadSites();
        Logs.info(Logs.PROXY, "新增代理站点: " + v.name + " -> " + v.target);
        ctx.ok(mustGet(id));
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        mustGet(id);
        JsonObject b = ctx.body();
        Values v = validate(b);
        Database.update("""
                UPDATE proxy_site SET name=?, domains=?, paths=?, target=?, force_https=?,
                    cert_mode=?, cert_task_id=?, advanced=?, enabled=? WHERE id=?""",
                v.name, v.domains, v.paths, v.target, v.forceHttps ? 1 : 0, v.certMode,
                v.certTaskId, v.advanced, v.enabled ? 1 : 0, id);
        ProxyCenter.reloadSites();
        Logs.info(Logs.PROXY, "更新代理站点 #" + id + ": " + v.name);
        ctx.ok(mustGet(id));
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> row = mustGet(id);
        Database.update("DELETE FROM proxy_site WHERE id=?", id);
        ProxyCache.purgeSite(id); // 站点删除即清空其磁盘缓存
        ProxyCenter.reloadSites();
        Logs.info(Logs.PROXY, "删除代理站点 #" + id + ": " + row.get("name"));
        ctx.ok("已删除");
    }

    private static void toggle(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        long id = JsonUtils.num(b, "id", 0);
        if (id <= 0) throw new IllegalArgumentException("缺少站点 id");
        boolean enabled = JsonUtils.bool(b, "enabled", false);
        mustGet(id);
        Database.update("UPDATE proxy_site SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
        ProxyCenter.reloadSites();
        Logs.info(Logs.PROXY, (enabled ? "启用" : "停用") + "代理站点 #" + id);
        ctx.ok(mustGet(id));
    }

    // ==================== 状态 / 缓存 ====================

    private static void status(Ctx ctx) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("listen", ProxyCenter.listenView());
        m.put("sites", ProxyCenter.sitesStatusView());
        m.put("cache", ProxyCache.statusView());
        m.put("lastError", orEmpty(Database.getConfig(KEY_LAST_ERROR)));
        ctx.ok(m);
    }

    private static void purgeCache(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        long siteId = JsonUtils.num(b, "siteId", 0);
        if (siteId > 0) {
            ProxyCache.purgeSite(siteId);
            Logs.info(Logs.PROXY, "清理站点 #" + siteId + " 缓存");
            ctx.ok("已清理该站点缓存");
        } else {
            for (Map<String, Object> row : Database.query("SELECT id FROM proxy_site"))
                ProxyCache.purgeSite(((Number) row.get("id")).longValue());
            Logs.info(Logs.PROXY, "清理全部代理缓存");
            ctx.ok("已清理全部缓存");
        }
    }

    // ==================== 校验 ====================

    /** 校验后的站点列值（advanced 为规范化后的 JSON 串） */
    private record Values(String name, String domains, String paths, String target,
                          boolean forceHttps, String certMode, Long certTaskId,
                          String advanced, boolean enabled) {
    }

    private static Values validate(JsonObject b) throws SQLException {
        String name = JsonUtils.str(b, "name").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("站点名称不能为空");
        String domains = JsonUtils.str(b, "domains").trim();
        ProxySite.validateDomains(domains);
        String paths = JsonUtils.str(b, "paths").trim();
        ProxySite.parsePaths(paths); // 校验格式（非法抛错），空串=整站
        String target = JsonUtils.str(b, "target").trim();
        ProxySite.validateTarget(target);

        String certMode = JsonUtils.str(b, "certMode").trim().toUpperCase();
        if (certMode.isEmpty()) certMode = "AUTO";
        Long certTaskId = null;
        if (!CERT_MODES.contains(certMode))
            throw new IllegalArgumentException("证书模式只能是 AUTO / TASK / NONE: " + certMode);
        if (certMode.equals("TASK")) {
            long tid = JsonUtils.num(b, "certTaskId", 0);
            if (tid <= 0) throw new IllegalArgumentException("证书模式为 TASK 时必须指定证书任务");
            Map<String, Object> task = Database.queryOne(
                    "SELECT status FROM cert_task WHERE id=?", tid);
            if (task == null) throw new IllegalArgumentException("证书任务不存在: #" + tid);
            if (!"ISSUED".equals(str(task, "status")))
                throw new IllegalArgumentException("证书任务 #" + tid + " 尚未签发成功，无法用于 HTTPS");
            certTaskId = tid;
        }

        String advJson = readAdvanced(b);
        String advanced = ProxyAdvanced.parse(advJson).toJson().toString(); // 校验 + 规范化回写

        boolean forceHttps = JsonUtils.bool(b, "forceHttps", false);
        if (forceHttps && certMode.equals("NONE"))
            throw new IllegalArgumentException("强制 HTTPS 需要可用证书，证书模式不能为 NONE");
        boolean enabled = JsonUtils.bool(b, "enabled", true);
        return new Values(name, domains, paths, target, forceHttps, certMode, certTaskId,
                advanced, enabled);
    }

    /** advanced 允许为 JSON 对象或 JSON 字符串，统一取出文本再解析 */
    private static String readAdvanced(JsonObject b) {
        if (!b.has("advanced") || b.get("advanced").isJsonNull()) return "";
        JsonElement el = b.get("advanced");
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> row = Database.queryOne("SELECT * FROM proxy_site WHERE id=?", id);
        if (row == null) throw new IllegalArgumentException("代理站点不存在: #" + id);
        return row;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static int intCfg(String key, int def) throws SQLException {
        String v = Database.getConfig(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 关停接线：冲刷访问记录、释放离线库与监听线程组（供 shutdown hook 调用） */
    public static void shutdown() {
        ProxyCenter.shutdown();
        VisitStats.flushNow(); // 落库最后不足一个周期的访问增量，避免丢失
        GeoResolver.close(); // 释放 ip2region 库句柄
    }

    /** 供 CertService 续期成功时热加载证书 */
    public static void onCertRenewed() {
        try {
            SslStore.reload();
        } catch (Exception e) {
            Logs.warn(Logs.PROXY, "证书续期后热加载失败: " + e.getMessage());
        }
    }

    /** 站点列表（前端下拉等），仅暴露必要字段 */
    public static List<Map<String, Object>> allSites() throws SQLException {
        return Database.query("SELECT id, name, domains, paths, target, enabled FROM proxy_site ORDER BY id");
    }
}
