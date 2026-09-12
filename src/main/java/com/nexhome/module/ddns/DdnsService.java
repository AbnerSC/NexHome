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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 正在同步中的任务 id，同一任务同一时刻仅允许一个同步（防并发重复写入） */
    private static final Set<Long> SYNCING = ConcurrentHashMap.newKeySet();

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
        WebServer.route("POST", "/api/ddns/preview-ip", ctx -> {
            JsonObject b = ctx.body();
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("ip_mode", JsonUtils.str(b, "ip_mode"));
            cfg.put("manual_ip", JsonUtils.str(b, "manual_ip"));
            cfg.put("local_nic", JsonUtils.str(b, "local_nic"));
            ctx.ok(Map.of("ip", IpResolver.resolve(cfg)));
        });
        WebServer.route("POST", "/api/ddns/tasks", DdnsService::create);
        WebServer.route("PUT", "/api/ddns/tasks/{id}", DdnsService::update);
        WebServer.route("DELETE", "/api/ddns/tasks/{id}", DdnsService::delete);
        WebServer.route("POST", "/api/ddns/tasks/{id}/sync", ctx -> {
            long id = ctx.paramLong("id");
            Map<String, Object> task = mustGet(id);
            if (SYNCING.contains(id)) {
                ctx.ok("该任务正在同步中，请稍候刷新列表查看结果");
                return;
            }
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
        Map<String, Object> task = mustGet(id);
        schedule(task);
        if (intVal(task, "enabled") == 1) Tasks.run(() -> sync(id)); // 创建后立即同步一次
        ctx.ok(task);
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
        Map<String, Object> task = mustGet(id);
        schedule(task);
        if (intVal(task, "enabled") == 1) Tasks.run(() -> sync(id)); // 更新后立即同步一次
        ctx.ok(task);
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> task = mustGet(id);
        // 默认同步删除远程解析记录，可通过 ?remote=false 仅删除本地任务
        boolean remote = !"false".equalsIgnoreCase(ctx.query("remote"));
        String remoteMsg = remote ? deleteRemoteRecord(task) : "（未删除远程解析记录）";
        cancel(id);
        Database.update("DELETE FROM ddns_task WHERE id=?", id);
        Logs.info(Logs.DDNS, "删除同步任务 #" + id + ": " + task.get("name") + " " + remoteMsg);
        ctx.ok("已删除 " + remoteMsg);
    }

    /**
     * 删除任务对应的远程解析记录（尽力而为，失败不阻断本地删除）。
     * <p>
     * 优先使用缓存的 RecordId，缺失时回查线上记录；记录已不存在视为删除成功。
     *
     * @return 面向用户的删除结果描述
     */
    private static String deleteRemoteRecord(Map<String, Object> task) {
        String fullDomain = fullRecordName(str(task, "rr"), str(task, "domain"));
        try {
            String recordId = str(task, "record_id");
            if (recordId.isBlank()) {
                // 缓存缺失（如从未同步成功）：回查线上记录定位 RecordId
                JsonObject online = findOnlineRecord(task, fullDomain);
                if (online == null) return "（远程无 " + fullDomain + " 解析记录，无需删除）";
                recordId = recordIdOf(task, online);
            }
            String ak = str(task, "access_key_id"), sk = str(task, "access_key_secret");
            if ("ALIYUN_DNS".equals(str(task, "provider"))) {
                AliyunClient.dnsDeleteRecord(ak, sk, recordId);
            } else {
                AliyunClient.esaDeleteRecord(ak, sk, recordId);
            }
            return "（已删除远程解析记录 " + fullDomain + "）";
        } catch (Exception e) {
            // 记录已不存在（如被手动删除）视为删除成功
            if (e instanceof AliyunApiException ae && ae.code.contains("NotFound")) {
                return "（远程解析记录已不存在）";
            }
            Logs.error(Logs.DDNS, "删除远程解析记录失败[" + fullDomain + "]: " + e);
            return "（远程解析记录删除失败: " + e.getMessage() + "）";
        }
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

    /** 执行一次同步：先查询线上记录，再依据查询结果决策新增/更新/跳过 */
    public static void sync(long taskId) {
        if (!SYNCING.add(taskId)) {
            Logs.info(Logs.DDNS, "任务 #" + taskId + " 正在同步中，本次触发已跳过");
            return;
        }
        try {
            Map<String, Object> task = mustGet(taskId);
            String fullDomain = fullRecordName(str(task, "rr"), str(task, "domain"));
            String ip = IpResolver.resolve(task);

            // 先查询：阿里云 DNS/ESA 均提供查询接口，添加前先确认线上状态，避免提交必然失败的请求
            JsonObject online = findOnlineRecord(task, fullDomain);
            String onlineIp = recordValue(task, online);

            if (onlineIp != null && onlineIp.equals(ip)) {
                markStatus(taskId, ip, "SUCCESS(无变化)");
                Logs.info(Logs.DDNS, "任务[" + task.get("name") + "] IP 无变化: " + ip + "，跳过更新");
                return;
            }

            writeRecord(taskId, task, fullDomain, ip, online);
            markStatus(taskId, ip, "SUCCESS");
            Logs.info(Logs.DDNS, "任务[" + task.get("name") + "] 已同步 " + fullDomain + " -> " + ip);
        } catch (Exception e) {
            try {
                markStatus(taskId, null, "FAIL: " + e.getMessage());
            } catch (Exception ignored) {
            }
            Logs.error(Logs.DDNS, "任务 #" + taskId + " 同步失败: " + e);
        } finally {
            SYNCING.remove(taskId);
        }
    }

    /** 将解析写入线上：存在则更新，不存在则新增；查询未匹配但缓存过 RecordId 时按 id 兜底更新 */
    private static void writeRecord(long taskId, Map<String, Object> task, String fullDomain,
                                    String ip, JsonObject online) throws Exception {
        if (online != null) {
            String rid = recordIdOf(task, online);
            if (!str(task, "record_id").equals(rid)) {
                // 缓存回查到的真实 RecordId，避免每次更新前先查询
                Database.update("UPDATE ddns_task SET record_id=? WHERE id=?", rid, taskId);
            }
            updateRecord(task, rid, ip);
            return;
        }
        String cached = str(task, "record_id");
        if (cached.isBlank()) {
            addRecord(task, fullDomain, ip);   // 查询确认不存在，安全新增
            return;
        }
        try {
            // 查询未匹配但缓存过 RecordId：按 id 直接更新，规避查询盲区导致的重复新增
            updateRecord(task, cached, ip);
        } catch (AliyunApiException e) {
            if (!e.code.contains("NotFound")) throw e;
            // 缓存的记录已不存在（如被手动删除）：清除失效缓存后新增
            Database.update("UPDATE ddns_task SET record_id=NULL WHERE id=?", taskId);
            addRecord(task, fullDomain, ip);
        }
    }

    /** 查询线上与任务主机记录/类型匹配的解析记录，不存在返回 null */
    private static JsonObject findOnlineRecord(Map<String, Object> task, String fullDomain) throws Exception {
        String rr = str(task, "rr"), type = str(task, "type");
        JsonArray records;
        if ("ALIYUN_DNS".equals(str(task, "provider"))) {
            records = AliyunClient.dnsDescribeRecords(
                    str(task, "access_key_id"), str(task, "access_key_secret"),
                    str(task, "domain"), rr);
            for (var el : records) {
                JsonObject r = el.getAsJsonObject();
                if (r.get("RR").getAsString().equalsIgnoreCase(rr)
                        && r.get("Type").getAsString().equalsIgnoreCase(type)) {
                    return r;
                }
            }
        } else {
            // ESA
            records = AliyunClient.esaListRecords(
                    str(task, "access_key_id"), str(task, "access_key_secret"),
                    str(task, "esa_site_id"), fullDomain);
            for (var el : records) {
                JsonObject r = el.getAsJsonObject();
                // 部分记录类型（如 NS）无 Data.Value 结构，跳过避免 NPE
                if (r.get("RecordName").getAsString().equalsIgnoreCase(fullDomain)
                        && r.get("Type").getAsString().equalsIgnoreCase(type)
                        && r.has("Data") && r.get("Data").isJsonObject()) {
                    return r;
                }
            }
        }
        // 未匹配到目标记录：输出接口返回摘要，便于定位查询盲区
        Logs.info(Logs.DDNS, "任务[" + task.get("name") + "] 线上未匹配到 " + fullDomain + "/" + type
                + "（接口返回 " + records.size() + " 条: " + summarize(records) + "）");
        return null;
    }

    /** 解析记录列表摘要：RR/Type 列表（最多 10 条） */
    private static String summarize(JsonArray records) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (var el : records) {
            JsonObject r = el.getAsJsonObject();
            if (i++ >= 10) {
                sb.append(" …");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.has("RR") ? r.get("RR").getAsString() : r.get("RecordName").getAsString())
                    .append('/').append(r.get("Type").getAsString());
        }
        return sb.toString();
    }

    /** 提取线上记录的解析值，记录为 null 时返回 null */
    private static String recordValue(Map<String, Object> task, JsonObject r) {
        if (r == null) return null;
        return "ALIYUN_DNS".equals(str(task, "provider"))
                ? r.get("Value").getAsString()
                : r.getAsJsonObject("Data").get("Value").getAsString();
    }

    /** 提取线上记录的 RecordId */
    private static String recordIdOf(Map<String, Object> task, JsonObject r) {
        return "ALIYUN_DNS".equals(str(task, "provider"))
                ? r.get("RecordId").getAsString()
                : String.valueOf(r.get("RecordId").getAsLong());
    }

    /** 新增线上解析记录（仅在查询确认不存在后调用），并缓存 RecordId */
    private static void addRecord(Map<String, Object> task, String fullDomain, String ip) throws Exception {
        String ak = str(task, "access_key_id"), sk = str(task, "access_key_secret");
        String newId = "ALIYUN_DNS".equals(str(task, "provider"))
                ? AliyunClient.dnsAddRecord(ak, sk, str(task, "domain"), str(task, "rr"),
                        str(task, "type"), ip, intVal(task, "ttl"))
                : AliyunClient.esaCreateRecord(ak, sk, str(task, "esa_site_id"), fullDomain,
                        str(task, "type"), ip, intVal(task, "ttl"));
        Database.update("UPDATE ddns_task SET record_id=? WHERE id=?", newId, task.get("id"));
    }

    /** 更新线上解析记录 */
    private static void updateRecord(Map<String, Object> task, String recordId, String ip) throws Exception {
        String ak = str(task, "access_key_id"), sk = str(task, "access_key_secret");
        if ("ALIYUN_DNS".equals(str(task, "provider"))) {
            AliyunClient.dnsUpdateRecord(ak, sk, recordId, str(task, "rr"),
                    str(task, "type"), ip, intVal(task, "ttl"));
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
