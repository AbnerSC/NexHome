package com.nexhome.module.provider;

import com.google.gson.JsonObject;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务商凭证配置：集中维护阿里云 AccessKey，供 DDNS 同步（云解析 / ESA）与
 * SSL 证书（DNS01 自动验证）按名称选择引用，避免在每个任务中重复填写。
 * <p>
 * 任务通过 ddns_task / cert_task.provider_config_id 引用本表：
 * <ul>
 *   <li>DDNS 同步时实时读取引用配置的凭证，修改配置后所有引用任务自动生效；</li>
 *   <li>删除配置时将当前凭证回填到引用的 DDNS 任务（转为任务内手动凭证，保证继续可用），
 *       证书任务解除引用回到手动添加 TXT 模式。</li>
 * </ul>
 * esa_site_id 为可选项：仅阿里云 ESA 使用，DDNS 任务表单选择配置后自动带出，仍可按任务覆盖；
 * SSL 证书 DNS01 在云解析定位不到域名（解析托管在 ESA）时自动用该站点写入验证 TXT 记录。
 */
public final class ProviderConfigService {

    /** 支持的服务商类型（code -> 显示名，保持登记顺序），新增服务商时在此登记并扩展对应 API 客户端 */
    private static final Map<String, String> PROVIDER_TYPES = new LinkedHashMap<>();

    static {
        PROVIDER_TYPES.put("ALIYUN", "阿里云（云解析 / ESA）");
    }

    private ProviderConfigService() {
    }

    /** 启动初始化：旧库补充服务商类型列（存量配置默认阿里云） */
    public static void init() throws SQLException {
        ensureColumn("provider_type", "TEXT NOT NULL DEFAULT 'ALIYUN'");
    }

    /** 旧库升级：补充缺失列（新库建表脚本已包含） */
    private static void ensureColumn(String name, String type) throws SQLException {
        boolean exists = Database.query("PRAGMA table_info(provider_config)").stream()
                .anyMatch(col -> name.equals(col.get("name")));
        if (!exists) {
            Database.update("ALTER TABLE provider_config ADD COLUMN " + name + " " + type);
        }
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/provider/types", ctx -> ctx.ok(PROVIDER_TYPES));
        WebServer.route("GET", "/api/provider/configs", ctx -> ctx.ok(Database.query(
                "SELECT * FROM provider_config ORDER BY id")));
        WebServer.route("POST", "/api/provider/configs", ProviderConfigService::create);
        WebServer.route("PUT", "/api/provider/configs/{id}", ProviderConfigService::update);
        WebServer.route("DELETE", "/api/provider/configs/{id}", ProviderConfigService::delete);
    }

    // ---------- 供其他模块调用的工具 ----------

    /** 从请求体解析可选的配置 id（未选择 / 空 / 非法返回 null） */
    public static Long parseId(JsonObject b, String key) {
        String s = JsonUtils.str(b, key).trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 读取配置的阿里云凭证 [accessKeyId, accessKeySecret]，配置不存在时抛出异常 */
    public static String[] credentials(long id) throws SQLException {
        Map<String, Object> c = Database.queryOne("SELECT * FROM provider_config WHERE id=?", id);
        if (c == null) {
            throw new IllegalArgumentException("服务商凭证配置不存在: #" + id + "，请重新选择凭证配置");
        }
        return new String[]{str(c, "access_key_id"), str(c, "access_key_secret")};
    }

    /** 读取配置的 ESA 站点 SiteId（未填写返回空串；供证书 DNS01 在域名托管于 ESA 时降级使用） */
    public static String esaSiteId(long id) throws SQLException {
        Map<String, Object> c = Database.queryOne("SELECT esa_site_id FROM provider_config WHERE id=?", id);
        if (c == null) {
            throw new IllegalArgumentException("服务商凭证配置不存在: #" + id + "，请重新选择凭证配置");
        }
        return str(c, "esa_site_id");
    }

    // ---------- 增删改 ----------

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        validate(b);
        long id = Database.insert("""
                INSERT INTO provider_config(name, provider_type, access_key_id, access_key_secret, esa_site_id)
                VALUES(?,?,?,?,?)""",
                JsonUtils.str(b, "name"), providerType(b), JsonUtils.str(b, "access_key_id"),
                JsonUtils.str(b, "access_key_secret"), JsonUtils.str(b, "esa_site_id"));
        Logs.info(Logs.PROVIDER, "新增服务商凭证配置[" + providerType(b) + "]: " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        mustGet(id);
        JsonObject b = ctx.body();
        validate(b);
        Database.update("""
                UPDATE provider_config SET name=?, provider_type=?, access_key_id=?, access_key_secret=?, esa_site_id=? WHERE id=?""",
                JsonUtils.str(b, "name"), providerType(b), JsonUtils.str(b, "access_key_id"),
                JsonUtils.str(b, "access_key_secret"), JsonUtils.str(b, "esa_site_id"), id);
        Logs.info(Logs.PROVIDER, "更新服务商凭证配置 #" + id + "[" + providerType(b) + "]: " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    /**
     * 删除配置：引用该配置的 DDNS 任务回填当前凭证转为任务内手动凭证（保证继续可用），
     * 证书任务解除引用回到手动添加 TXT 模式。
     */
    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> cfg = mustGet(id);
        Database.update("""
                UPDATE ddns_task SET provider_config_id=NULL, access_key_id=?, access_key_secret=?
                WHERE provider_config_id=?""",
                cfg.get("access_key_id"), cfg.get("access_key_secret"), id);
        Database.update("UPDATE cert_task SET provider_config_id=NULL WHERE provider_config_id=?", id);
        Database.update("DELETE FROM provider_config WHERE id=?", id);
        Logs.info(Logs.PROVIDER, "删除服务商凭证配置 #" + id + ": " + cfg.get("name") + "（引用任务已回填凭证）");
        ctx.ok("已删除，引用该配置的任务已回填当前凭证");
    }

    /** 表单校验 */
    private static void validate(JsonObject b) {
        if (JsonUtils.str(b, "name").isBlank()) throw new IllegalArgumentException("配置名称不能为空");
        if (JsonUtils.str(b, "access_key_id").isBlank() || JsonUtils.str(b, "access_key_secret").isBlank()) {
            throw new IllegalArgumentException("AccessKey ID / Secret 不能为空");
        }
    }

    /** 服务商类型（缺省阿里云，兼容旧表单；必须在支持清单内） */
    private static String providerType(JsonObject b) {
        String type = JsonUtils.str(b, "provider_type");
        if (type.isBlank()) type = "ALIYUN";
        if (!PROVIDER_TYPES.containsKey(type)) {
            throw new IllegalArgumentException("不支持的服务商类型: " + type);
        }
        return type;
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> c = Database.queryOne("SELECT * FROM provider_config WHERE id=?", id);
        if (c == null) throw new IllegalArgumentException("服务商凭证配置不存在: #" + id);
        return c;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
