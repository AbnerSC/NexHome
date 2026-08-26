package com.nexhome.module.ddns;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * DDNS 域名同步服务。
 * <p>
 * 负责同步任务的增删改查、定时/手动同步调度，并对接阿里云云解析与 ESA。
 * 同步逻辑：解析当前 IP -> 与线上解析记录比对 -> 不一致时调用 OpenAPI 更新 -> 记录状态与日志。
 */
public final class DdnsService {

    /** 任务 id -> 定时句柄，用于增删改时重建调度 */
    private static final Map<Long, ScheduledFuture<?>> SCHEDULES = new ConcurrentHashMap<>();

    private DdnsService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/ddns/tasks", ctx -> ctx.ok(Database.query(
                "SELECT * FROM ddns_task ORDER BY id")));
        WebServer.route("GET", "/api/ddns/nics", ctx -> ctx.ok(Map.of(
                "nics", IpResolver.listNics())));
        WebServer.route("GET", "/api/ddns/public-ip", ctx -> ctx.ok(Map.of(
                "ip", IpResolver.publicIp())));
        WebServer.route("POST", "/api/ddns/tasks", DdnsService::create);
        WebServer.route("PUT", "/api/ddns/tasks/{id}", DdnsService::update);
        WebServer.route("DELETE", "/api/ddns/tasks/{id}", DdnsService::delete);
        WebServer.route("POST", "/api/ddns/tasks/{id}/sync", ctx -> {
            long id = ctx.paramLong("id");
            Map<String, Object> task = mustGet(id);
            Logs.info(Logs.DDNS, "手动触发同步: " + task.get("name"));
            Tasks.run(() -> sync(id));
            ctx.ok("已触发同步，请在列表中查看结果");
        });
    }

    /** 启动时加载全部启用的任务并建立定时调度（重启自动加载） */
    public static void init() throws SQLException {
        for (Map<String, Object> task : Database.query("SELECT * FROM ddns_task")) {
            schedule(task);
        }
        Logs.info(Logs.DDNS, "DDNS 任务已加载，共 " + SCHEDULES.size() + " 个定时任务");
    }

    // ---------- 增删改 ----------

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        validate(b, true);
        long id = Database.insert("""
                INSERT INTO ddns_task(name, provider, domain, rr, type, ttl, ip_mode, manual_ip, local_nic,
                    access_key_id, access_key_secret, esa_site_id, interval_sec, enabled)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "provider"), JsonUtils.str(b, "domain"),
                JsonUtils.str(b, "rr"), JsonUtils.str(b, "type"), JsonUtils.num(b, "ttl", 600),
                JsonUtils.str(b, "ip_mode"), JsonUtils.str(b, "manual_ip"), JsonUtils.str(b, "local_nic"),
                JsonUtils.str(b, "access_key_id"), JsonUtils.str(b, "access_key_secret"),
                JsonUtils.str(b, "esa_site_id"), JsonUtils.num(b, "interval_sec", 300),
                JsonUtils.bool(b, "enabled", true) ? 1 : 0);
        Logs.info(Logs.DDNS, "新增同步任务: " + JsonUtils.str(b, "name"));
        schedule(mustGet(id));
        ctx.ok(mustGet(id));
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        mustGet(id);
        JsonObject b = ctx.body();
        validate(b, false);
        Database.update("""
                UPDATE ddns_task SET name=?, provider=?, domain=?, rr=?, type=?, ttl=?, ip_mode=?,
                    manual_ip=?, local_nic=?, access_key_id=?, access_key_secret=?, esa_site_id=?,
                    interval_sec=?, enabled=? WHERE id=?""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "provider"), JsonUtils.str(b, "domain"),
                JsonUtils.str(b, "rr"), JsonUtils.str(b, "type"), JsonUtils.num(b, "ttl", 600),
                JsonUtils.str(b, "ip_mode"), JsonUtils.str(b, "manual_ip"), JsonUtils.str(b, "local_nic"),
                JsonUtils.str(b, "access_key_id"), JsonUtils.str(b, "access_key_secret"),
                JsonUtils.str(b, "esa_site_id"), JsonUtils.num(b, "interval_sec", 300),
                JsonUtils.bool(b, "enabled", true) ? 1 : 0, id);
        Logs.info(Logs.DDNS, "更新同步任务 #" + id + ": " + JsonUtils.str(b, "name"));
        schedule(mustGet(id));
        ctx.ok(mustGet(id));
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> task = mustGet(id);
        cancel(id);
        Database.update("DELETE FROM ddns_task WHERE id=?", id);
        Logs.info(Logs.DDNS, "删除同步任务 #" + id + ": " + task.get("name"));
        ctx.ok("已删除");
    }

    /** 表单必填与取值合法性校验 */
    private static void validate(JsonObject b, boolean isCreate) {
        if (!isCreate) return; // 更新时前端已回传完整表单，此处仅校验创建
        if (JsonUtils.str(b, "name").isBlank()) throw new IllegalArgumentException("任务名称不能为空");
        if (JsonUtils.str(b, "domain").isBlank()) throw new IllegalArgumentException("域名不能为空");
        if (JsonUtils.str(b, "rr").isBlank()) throw new IllegalArgumentException("主机记录不能为空");
        if (JsonUtils.str(b, "access_key_id").isBlank() || JsonUtils.str(b, "access_key_secret").isBlank()) {
            throw new IllegalArgumentException("阿里云 AccessKey ID / Secret 不能为空");
        }
        if ("ALIYUN_ESA".equals(JsonUtils.str(b, "provider")) && JsonUtils.str(b, "esa_site_id").isBlank()) {
            throw new IllegalArgumentException("ESA 服务商需要填写站点 SiteId");
        }
        if ("MANUAL".equals(JsonUtils.str(b, "ip_mode")) && JsonUtils.str(b, "manual_ip").isBlank()) {
            throw new IllegalArgumentException("手动模式必须填写 IP 地址");
        }
    }

    // ---------- 同步执行 ----------

    /** 执行一次同步：解析 IP -> 比对线上记录 -> 必要时更新 */
    public static void sync(long taskId) {
        try {
            Map<String, Object> task = mustGet(taskId);
            String provider = str(task, "provider");
            String fullDomain = fullRecordName(str(task, "rr"), str(task, "domain"));
            String ip = IpResolver.resolve(task);

            String recordId = str(task, "record_id");
            String onlineIp = findOnlineIp(task, fullDomain, recordId);

            if (ip.equals(onlineIp)) {
                markStatus(taskId, ip, "SUCCESS(无变化)");
                Logs.info(Logs.DDNS, "任务[" + task.get("name") + "] IP 无变化: " + ip + "，跳过更新");
                return;
            }

            if ("ALIYUN_DNS".equals(provider)) {
                syncAliyunDns(task, ip, onlineIp, recordId);
            } else {
                syncAliyunEsa(task, ip, fullDomain, onlineIp, recordId);
            }
            markStatus(taskId, ip, "SUCCESS");
            Logs.info(Logs.DDNS, "任务[" + task.get("name") + "] 已同步 " + fullDomain + " -> " + ip);
        } catch (Exception e) {
            try {
                markStatus(taskId, null, "FAIL: " + e.getMessage());
            } catch (Exception ignored) {
            }
            Logs.error(Logs.DDNS, "任务 #" + taskId + " 同步失败: " + e);
        }
    }

    /** 查询线上当前解析值，不存在返回 null */
    private static String findOnlineIp(Map<String, Object> task, String fullDomain, String recordId) throws Exception {
        String provider = str(task, "provider");
        if ("ALIYUN_DNS".equals(provider)) {
            JsonArray records = AliyunClient.dnsDescribeRecords(
                    str(task, "access_key_id"), str(task, "access_key_secret"),
                    str(task, "domain"), str(task, "rr"));
            for (var el : records) {
                JsonObject r = el.getAsJsonObject();
                if (r.get("RR").getAsString().equals(str(task, "rr"))
                        && r.get("Type").getAsString().equals(str(task, "type"))) {
                    if (recordId.isBlank() || !recordId.equals(r.get("RecordId").getAsString())) {
                        // 缓存 RecordId，避免每次更新前先查询
                        Database.update("UPDATE ddns_task SET record_id=? WHERE id=?",
                                r.get("RecordId").getAsString(), task.get("id"));
                    }
                    return r.get("Value").getAsString();
                }
            }
            return null;
        }
        // ESA
        JsonArray records = AliyunClient.esaListRecords(
                str(task, "access_key_id"), str(task, "access_key_secret"),
                str(task, "esa_site_id"), fullDomain);
        for (var el : records) {
            JsonObject r = el.getAsJsonObject();
            if (r.get("RecordName").getAsString().equals(fullDomain)
                    && r.get("Type").getAsString().equals(str(task, "type"))) {
                if (recordId.isBlank() || !recordId.equals(String.valueOf(r.get("RecordId").getAsLong()))) {
                    Database.update("UPDATE ddns_task SET record_id=? WHERE id=?",
                            String.valueOf(r.get("RecordId").getAsLong()), task.get("id"));
                }
                return r.get("Data").getAsJsonObject().get("Value").getAsString();
            }
        }
        return null;
    }

    /** 云解析：新增或更新记录 */
    private static void syncAliyunDns(Map<String, Object> task, String ip, String onlineIp, String recordId) throws Exception {
        String ak = str(task, "access_key_id"), sk = str(task, "access_key_secret");
        if (onlineIp == null) {
            String newId = AliyunClient.dnsAddRecord(ak, sk, str(task, "domain"), str(task, "rr"),
                    str(task, "type"), ip, intVal(task, "ttl"));
            Database.update("UPDATE ddns_task SET record_id=? WHERE id=?", newId, task.get("id"));
        } else {
            AliyunClient.dnsUpdateRecord(ak, sk, recordId, str(task, "rr"),
                    str(task, "type"), ip, intVal(task, "ttl"));
        }
    }

    /** ESA：新增或更新记录 */
    private static void syncAliyunEsa(Map<String, Object> task, String ip, String fullDomain,
                                      String onlineIp, String recordId) throws Exception {
        String ak = str(task, "access_key_id"), sk = str(task, "access_key_secret");
        if (onlineIp == null) {
            String newId = AliyunClient.esaCreateRecord(ak, sk, str(task, "esa_site_id"), fullDomain,
                    str(task, "type"), ip, intVal(task, "ttl"));
            Database.update("UPDATE ddns_task SET record_id=? WHERE id=?", newId, task.get("id"));
        } else {
            AliyunClient.esaUpdateRecord(ak, sk, recordId, str(task, "type"), ip, intVal(task, "ttl"));
        }
    }

    /** 记录同步结果到数据库 */
    private static void markStatus(long taskId, String ip, String status) throws SQLException {
        if (ip == null) {
            Database.update("UPDATE ddns_task SET last_sync=?, last_status=? WHERE id=?",
                    Database.now(), status, taskId);
        } else {
            Database.update("UPDATE ddns_task SET last_ip=?, last_sync=?, last_status=? WHERE id=?",
                    ip, Database.now(), status, taskId);
        }
    }

    // ---------- 调度 ----------

    /** 根据任务配置建立或重建定时调度 */
    private static void schedule(Map<String, Object> task) {
        long id = ((Number) task.get("id")).longValue();
        cancel(id);
        if (intVal(task, "enabled") != 1) return;
        long interval = Math.max(60, intVal(task, "interval_sec"));
        SCHEDULES.put(id, Tasks.every(5, interval, () -> sync(id)));
    }

    private static void cancel(long id) {
        ScheduledFuture<?> f = SCHEDULES.remove(id);
        if (f != null) f.cancel(false);
    }

    // ---------- 工具 ----------

    /** 主机记录 + 域名拼成完整记录名，@ 表示主域名本身 */
    static String fullRecordName(String rr, String domain) {
        return "@".equals(rr) ? domain : rr + "." + domain;
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> task = Database.queryOne("SELECT * FROM ddns_task WHERE id=?", id);
        if (task == null) throw new IllegalArgumentException("DDNS 任务不存在: #" + id);
        return task;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 查询全部任务（供系统信息页统计） */
    public static List<Map<String, Object>> listAll() throws SQLException {
        return Database.query("SELECT id, name, provider, domain, rr, enabled, last_status FROM ddns_task");
    }
}
