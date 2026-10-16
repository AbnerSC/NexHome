package com.nexhome.module.stun;

import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.module.ddns.AliyunClient;
import com.nexhome.module.provider.ProviderConfigService;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.DatagramSocket;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STUN 穿透任务管理服务：增删改查 + 启停控制 + 状态持久化（重启自动加载），
 * 并提供 ESA（边缘安全加速）站点/解析记录/回源规则的线上列表拉取与手动同步入口。
 * <p>
 * 穿透结果有三个可独立启用的联动出口（触发点同一：首次穿透成功、外网映射地址变化）：
 * {@link StunWebhook}（任务内自定义 HTTP 回调）、{@link StunEsaSync}（写阿里云 ESA 解析/回源）、
 * {@link StunNotify}（投递到统一告警通知中心，渠道由任务多选勾选）。
 */
public final class StunService {

    /** 任务 id -> 运行中的任务实例 */
    private static final Map<Long, StunRunner> RUNNERS = new ConcurrentHashMap<>();
    /** 任务 id -> 启停互斥锁：并发启动（重复点击/自动恢复重叠）会导致新旧实例竞争同一端口，启停须串行 */
    private static final Map<Long, Object> LOCKS = new ConcurrentHashMap<>();

    private StunService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/stun/tasks", StunService::list);
        // 单任务流量明细：总量 + 按小时/天/月归档（连接重建不清零，落库+内存实时增量合并）
        WebServer.route("GET", "/api/stun/tasks/{id}/traffic", ctx ->
                ctx.ok(TrafficStats.detail(ctx.paramLong("id"))));
        WebServer.route("POST", "/api/stun/tasks", StunService::create);
        WebServer.route("PUT", "/api/stun/tasks/{id}", StunService::update);
        WebServer.route("DELETE", "/api/stun/tasks/{id}", StunService::delete);
        WebServer.route("POST", "/api/stun/tasks/{id}/start", ctx -> {
            start(ctx.paramLong("id"));
            ctx.ok(mustGet(ctx.paramLong("id")));
        });
        WebServer.route("POST", "/api/stun/tasks/{id}/stop", ctx -> {
            stop(ctx.paramLong("id"));
            ctx.ok(mustGet(ctx.paramLong("id")));
        });
        // 可用性自测：验证外网能否经映射地址主动连入（TCP 连接外网IP:穿透端口，UDP 发探测包）
        WebServer.route("POST", "/api/stun/tasks/{id}/verify", ctx -> {
            StunRunner runner = RUNNERS.get(ctx.paramLong("id"));
            if (runner == null) throw new IllegalArgumentException("任务未运行，无法自测");
            ctx.ok(runner.verifyNow());
        });
        // 手动立即同步一次 ESA（按当前外网映射地址更新解析记录与回源端口，返回逐条明细）
        WebServer.route("POST", "/api/stun/tasks/{id}/esa-sync", ctx ->
                ctx.ok(StunEsaSync.syncNow(mustGet(ctx.paramLong("id")))));
        // 手动试发一次通知：按任务勾选的渠道投递测试通知（异步投递，结果见「告警通知」页发送记录）
        WebServer.route("POST", "/api/stun/tasks/{id}/notify-test", ctx -> {
            int n = StunNotify.testSend(mustGet(ctx.paramLong("id")));
            ctx.ok("已按 " + n + " 个勾选渠道投递测试通知，发送结果见「告警通知」页的最近发送记录");
        });
        // ESA 站点/解析记录/回源规则列表：供任务表单勾选（只读拉取，不改动云端）
        WebServer.route("POST", "/api/stun/esa/sites", StunService::esaSites);
        WebServer.route("POST", "/api/stun/esa/records", StunService::esaRecords);
        WebServer.route("POST", "/api/stun/esa/origin-rules", StunService::esaOriginRules);
        // 单独探测一次：不启动任务，仅检测 NAT 类型与映射地址（TCP 任务另探测 TCP 映射）
        WebServer.route("POST", "/api/stun/tasks/{id}/test", ctx -> {
            long taskId = ctx.paramLong("id");
            Map<String, Object> task = mustGet(taskId);
            StunRunner runner = RUNNERS.get(taskId);
            boolean live = runner != null && runner.isRunning();
            String liveMapped = str(task, "mapped_addr");
            String liveNat = str(task, "nat_type");
            try (DatagramSocket s = new DatagramSocket()) {
                // NAT 类型是网络路径属性，新开 socket 探测仍有效；映射地址则不同：临时端口的
                // 映射在对称型 NAT 下与任务端口映射必然不同，任务运行中必须以运行器登记的
                // 实际映射为准，否则探测结果不代表任务通道（误导用户排查方向）
                StunClient.Result r = StunClient.detectNatType(s, str(task, "stun_host"),
                        intVal(task, "stun_port"), 3000);
                String natType = r == null || r.mappedAddress() == null
                        ? (liveNat.isBlank() ? "Unknown(STUN服务器无响应)" : liveNat + "(本次探测无响应，取任务记录)")
                        : r.natType();
                String mapped;
                String tcpMapped = "";
                if (live) {
                    mapped = liveMapped; // 任务运行中：实际映射（权威）
                    if ("TCP".equalsIgnoreCase(str(task, "protocol"))) tcpMapped = liveMapped;
                } else {
                    mapped = r == null || r.mappedAddress() == null ? "" : r.mappedAddress();
                    if ("TCP".equalsIgnoreCase(str(task, "protocol")) && intVal(task, "bind_port") > 0) {
                        // 仅未运行时才做同端口 TCP 探测：运行中监听与出站链路占用该本地端口，
                        // 再从该端口出站是对称型 CGNAT 下的另一条映射（外部端口可能不同），
                        // 探测结果不代表任务通道；绑定时才能探测到对入站有效的 TCP 映射，
                        // 配置服务器不支持 TCP 时尝试维护列表与内置兑底
                        int bp = intVal(task, "bind_port");
                        String m = StunClient.bindOverTcp(str(task, "stun_host"), intVal(task, "stun_port"), bp, 3000);
                        // 内置列表按电信 CGNAT 实测可达排序优先，维护列表（历史种子多为被封的 3478 端口）兑底
                        List<String[]> fallback = new ArrayList<>(Arrays.asList(StunClient.TCP_STUN_SERVERS));
                        fallback.addAll(StunServerService.tcpServers());
                        for (int i = 0; m == null && i < fallback.size(); i++) {
                            m = StunClient.bindOverTcp(fallback.get(i)[0], Integer.parseInt(fallback.get(i)[1]), bp, 3000);
                        }
                        if (m != null) tcpMapped = m;
                    }
                }
                Logs.info(Logs.STUN, "手动探测任务[" + task.get("name") + "] 结果: "
                        + (live ? "任务运行中，取实际映射 " + liveMapped
                                : (r == null ? "无响应" : r.natType() + " / " + r.mappedAddress()))
                        + (tcpMapped.isEmpty() ? "" : " / TCP映射: " + tcpMapped));
                ctx.ok(Map.of(
                        "live", live,
                        "mapped", mapped == null ? "" : mapped,
                        "natType", natType,
                        "tcpMapped", tcpMapped));
            }
        });
    }

    /** 启动时自动加载：恢复启用了的穿透任务 */
    public static void init() throws SQLException {
        ensureColumns();
        for (Map<String, Object> task : Database.query("SELECT * FROM stun_task WHERE enabled=1")) {
            try {
                start(((Number) task.get("id")).longValue());
            } catch (Exception e) {
                Logs.error(Logs.STUN, "自动恢复穿透任务 #" + task.get("id") + " 失败: " + e);
            }
        }
    }

    /** 旧库升级：补充缺失列（新库建表脚本已包含） */
    private static void ensureColumns() throws SQLException {
        ensureColumn("peer_addr", "TEXT");
        ensureColumn("punched_at", "TEXT");
        ensureColumn("check_time", "TEXT");
        ensureColumn("check_result", "TEXT");
        ensureColumn("upnp_enabled", "INTEGER NOT NULL DEFAULT 1");
        ensureColumn("webhook_config", "TEXT");
        ensureColumn("webhook_status", "TEXT");
        ensureColumn("esa_sync_enabled", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn("provider_config_id", "INTEGER");
        ensureColumn("esa_site_id", "TEXT");
        ensureColumn("esa_records", "TEXT");
        ensureColumn("esa_rules", "TEXT");
        ensureColumn("esa_sync_status", "TEXT");
        ensureColumn("notify_enabled", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn("notify_channels", "TEXT");
    }

    private static void ensureColumn(String name, String type) throws SQLException {
        boolean exists = Database.query("PRAGMA table_info(stun_task)").stream()
                .anyMatch(col -> name.equals(col.get("name")));
        if (!exists) {
            Database.update("ALTER TABLE stun_task ADD COLUMN " + name + " " + type);
        }
    }

    // ---------- 增删改 ----------

    /** 任务列表：在原始行基础上附带每个任务的累计总流量 total_bytes（含内存未落库增量） */
    private static void list(Ctx ctx) throws Exception {
        List<Map<String, Object>> rows = Database.query("SELECT * FROM stun_task ORDER BY id");
        for (Map<String, Object> r : rows) {
            if (r.get("id") instanceof Number n) r.put("total_bytes", TrafficStats.totalBytes(n.longValue()));
        }
        ctx.ok(rows);
    }

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        validate(b);
        long id = Database.insert("""
                INSERT INTO stun_task(name, protocol, target_ip, target_port, bind_port,
                    stun_host, stun_port, keepalive_sec, peer_addr, upnp_enabled, webhook_config,
                    esa_sync_enabled, provider_config_id, esa_site_id, esa_records, esa_rules,
                    notify_enabled, notify_channels, enabled)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "protocol"), JsonUtils.str(b, "target_ip"),
                JsonUtils.num(b, "target_port", 0), JsonUtils.num(b, "bind_port", 0),
                JsonUtils.str(b, "stun_host"), JsonUtils.num(b, "stun_port", 19302),
                JsonUtils.num(b, "keepalive_sec", 25), JsonUtils.str(b, "peer_addr"),
                JsonUtils.bool(b, "upnp_enabled", true) ? 1 : 0,
                serializeWebhooks(b),
                JsonUtils.bool(b, "esa_sync_enabled", false) ? 1 : 0,
                ProviderConfigService.parseId(b, "provider_config_id"),
                JsonUtils.str(b, "esa_site_id").trim(),
                serializePicked(b, "esa_records"), serializePicked(b, "esa_rules"),
                JsonUtils.bool(b, "notify_enabled", false) ? 1 : 0, serializeNotifyChannels(b),
                JsonUtils.bool(b, "enabled", false) ? 1 : 0);
        Logs.info(Logs.STUN, "新增穿透任务: " + JsonUtils.str(b, "name"));
        // 创建即要求启动的场景：前端传 enabled=1 时自动启动
        if (JsonUtils.bool(b, "enabled", false)) {
            start(id);
        }
        ctx.ok(mustGet(id));
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        boolean wasRunning = RUNNERS.containsKey(id);
        JsonObject b = ctx.body();
        validate(b);
        Database.update("""
                UPDATE stun_task SET name=?, protocol=?, target_ip=?, target_port=?, bind_port=?,
                    stun_host=?, stun_port=?, keepalive_sec=?, peer_addr=?, upnp_enabled=?, webhook_config=?,
                    esa_sync_enabled=?, provider_config_id=?, esa_site_id=?, esa_records=?, esa_rules=?,
                    notify_enabled=?, notify_channels=? WHERE id=?""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "protocol"), JsonUtils.str(b, "target_ip"),
                JsonUtils.num(b, "target_port", 0), JsonUtils.num(b, "bind_port", 0),
                JsonUtils.str(b, "stun_host"), JsonUtils.num(b, "stun_port", 19302),
                JsonUtils.num(b, "keepalive_sec", 25), JsonUtils.str(b, "peer_addr"),
                JsonUtils.bool(b, "upnp_enabled", true) ? 1 : 0, serializeWebhooks(b),
                JsonUtils.bool(b, "esa_sync_enabled", false) ? 1 : 0,
                ProviderConfigService.parseId(b, "provider_config_id"),
                JsonUtils.str(b, "esa_site_id").trim(),
                serializePicked(b, "esa_records"), serializePicked(b, "esa_rules"),
                JsonUtils.bool(b, "notify_enabled", false) ? 1 : 0, serializeNotifyChannels(b), id);
        Logs.info(Logs.STUN, "更新穿透任务 #" + id + ": " + JsonUtils.str(b, "name"));
        // 运行中修改配置 -> 重启任务使配置生效
        if (wasRunning) {
            stop(id);
            start(id);
        }
        ctx.ok(mustGet(id));
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> task = mustGet(id);
        stop(id);
        Database.update("DELETE FROM stun_task WHERE id=?", id);
        TrafficStats.clear(id); // 清理流量统计（内存计数 + stun_traffic 落库行）
        Logs.info(Logs.STUN, "删除穿透任务 #" + id + ": " + task.get("name"));
        ctx.ok("已删除");
    }

    // ---------- 启停 ----------

    /** 启动任务（同任务串行：并发启动会竞争同一出站/监听端口，是端口冲突启动失败的诱因之一） */
    public static void start(long id) throws Exception {
        synchronized (LOCKS.computeIfAbsent(id, k -> new Object())) {
            Map<String, Object> task = mustGet(id);
            stopLocked(id); // 幂等：先停止旧实例（内联调用避免嵌套加锁，逻辑更直白）
            StunRunner runner = new StunRunner(task);
            runner.start();
            RUNNERS.put(id, runner);
            Database.update("UPDATE stun_task SET enabled=1 WHERE id=?", id);
        }
    }

    /** 停止任务（与 {@link #start} 同锁串行，避免停止释放端口与新实例绑定交错） */
    public static void stop(long id) throws SQLException {
        synchronized (LOCKS.computeIfAbsent(id, k -> new Object())) {
            stopLocked(id);
        }
    }

    /** 停止本体：调用方必须已持有该任务的 {@link #LOCKS} 锁 */
    private static void stopLocked(long id) throws SQLException {
        StunRunner runner = RUNNERS.remove(id);
        if (runner != null) {
            runner.stop();
        }
        Database.update("UPDATE stun_task SET enabled=0, status='STOPPED' WHERE id=?", id);
    }

    /** 关停时停止全部任务并释放路由器 UPnP 映射（防止映射条目残留浪费路由器资源） */
    public static void stopAll() {
        RUNNERS.values().forEach(runner -> {
            try {
                runner.stopSilently();
            } catch (Exception e) {
                Logs.warn(Logs.STUN, "关停停止穿透任务异常: " + e.getMessage());
            }
        });
        RUNNERS.clear();
    }

    /** 表单校验 */
    private static void validate(JsonObject b) throws SQLException {
        if (JsonUtils.str(b, "name").isBlank()) throw new IllegalArgumentException("任务名称不能为空");
        String protocol = JsonUtils.str(b, "protocol");
        if (!"TCP".equals(protocol) && !"UDP".equals(protocol)) {
            throw new IllegalArgumentException("协议必须为 TCP 或 UDP");
        }
        if (JsonUtils.str(b, "target_ip").isBlank()) throw new IllegalArgumentException("内网目标 IP 不能为空");
        int port = JsonUtils.num(b, "target_port", 0);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("内网目标端口需在 1-65535 之间");
        if (JsonUtils.str(b, "stun_host").isBlank()) throw new IllegalArgumentException("STUN 服务器地址不能为空");
        int sp = JsonUtils.num(b, "stun_port", 19302);
        if (sp < 1 || sp > 65535) throw new IllegalArgumentException("STUN 端口需在 1-65535 之间");
        int bp = JsonUtils.num(b, "bind_port", 0);
        if (bp < 0 || bp > 65535) throw new IllegalArgumentException("本地绑定端口需在 0-65535 之间（0=随机）");
        String peer = JsonUtils.str(b, "peer_addr").trim();
        if (!peer.isBlank()) {
            int ci = peer.lastIndexOf(':');
            if (ci <= 0 || ci == peer.length() - 1) {
                throw new IllegalArgumentException("对端公网地址格式需为 ip:端口");
            }
            try {
                int pp = Integer.parseInt(peer.substring(ci + 1));
                if (pp < 1 || pp > 65535) throw new IllegalArgumentException("对端端口需在 1-65535 之间");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("对端公网地址端口需为数字");
            }
        }
        validateWebhooks(b);
        validateEsaSync(b);
        validateNotify(b);
    }

    /** Webhook 配置校验：地址须为 http/https，请求方式仅 GET/POST（空地址忽略） */
    private static void validateWebhooks(JsonObject b) {
        if (!b.has("webhooks") || !b.get("webhooks").isJsonArray()) return;
        for (JsonElement el : b.getAsJsonArray("webhooks")) {
            if (!el.isJsonObject()) continue;
            JsonObject w = el.getAsJsonObject();
            String url = JsonUtils.str(w, "url").trim();
            if (url.isEmpty()) continue;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("Webhook 地址必须以 http:// 或 https:// 开头: " + url);
            }
            String method = JsonUtils.str(w, "method").trim().toUpperCase();
            if (!method.isEmpty() && !"GET".equals(method) && !"POST".equals(method)) {
                throw new IllegalArgumentException("Webhook 请求方式只能为 GET 或 POST");
            }
        }
    }

    /** 将前端提交的 webhooks 数组规范化为存储用 JSON 串（过滤空地址、默认 POST、params 统一为字符串值、保留 body 字段） */
    private static String serializeWebhooks(JsonObject b) {
        if (!b.has("webhooks") || !b.get("webhooks").isJsonArray()) return "";
        JsonArray in = b.getAsJsonArray("webhooks");
        JsonArray out = new JsonArray();
        for (JsonElement el : in) {
            if (!el.isJsonObject()) continue;
            JsonObject w = el.getAsJsonObject();
            String url = JsonUtils.str(w, "url").trim();
            if (url.isEmpty()) continue;
            String method = JsonUtils.str(w, "method").trim().toUpperCase();
            if (!"GET".equals(method)) method = "POST";
            JsonObject o = new JsonObject();
            o.addProperty("url", url);
            o.addProperty("method", method);
            o.add("headers", normalizeStringMap(w, "headers"));
            o.add("params", normalizeStringMap(w, "params"));
            // 保留用户自定义 JSON 请求体（POST 模式）
            if (w.has("body") && !w.get("body").isJsonNull()) {
                String bodyVal = w.get("body").getAsString().trim();
                if (!bodyVal.isEmpty()) o.addProperty("body", bodyVal);
            }
            out.add(o);
        }
        return out.size() == 0 ? "" : out.toString();
    }

    // ---------- 通知中心（渠道勾选校验/序列化） ----------

    /** 通知配置校验：启用时必须至少勾选一个渠道，且渠道必须存在（已被删除的渠道给出可读数错） */
    private static void validateNotify(JsonObject b) throws SQLException {
        if (!JsonUtils.bool(b, "notify_enabled", false)) return;
        String channels = serializeNotifyChannels(b);
        List<Long> ids = StunNotify.parseChannelIds(channels);
        if (ids.isEmpty()) throw new IllegalArgumentException("启用穿透通知需至少选择一个通知渠道");
        for (Long id : ids) {
            if (Database.queryOne("SELECT id FROM notify_channel WHERE id=?", id) == null) {
                throw new IllegalArgumentException("通知渠道不存在: #" + id + "，请重新选择通知渠道");
            }
        }
    }

    /** 将前端勾选的 notify_channels 数组规范化为存储用 JSON 数字串（过滤非法项与重复 id） */
    private static String serializeNotifyChannels(JsonObject b) {
        if (!b.has("notify_channels") || !b.get("notify_channels").isJsonArray()) return "";
        List<Long> ids = StunNotify.parseChannelIds(b.getAsJsonArray("notify_channels").toString());
        JsonArray out = new JsonArray();
        ids.forEach(out::add);
        return out.size() == 0 ? "" : out.toString();
    }

    // ---------- ESA 同步（配置校验/序列化 + 线上列表拉取） ----------

    /** ESA 同步校验：启用时必须有凭证配置与站点，且至少勾选一条解析记录或回源规则 */
    private static void validateEsaSync(JsonObject b) {
        if (!JsonUtils.bool(b, "esa_sync_enabled", false)) return;
        if (ProviderConfigService.parseId(b, "provider_config_id") == null) {
            throw new IllegalArgumentException("启用 ESA 同步需选择服务商凭证配置");
        }
        if (JsonUtils.str(b, "esa_site_id").trim().isEmpty()) {
            throw new IllegalArgumentException("启用 ESA 同步需选择 ESA 站点");
        }
        if (serializePicked(b, "esa_records").isEmpty() && serializePicked(b, "esa_rules").isEmpty()) {
            throw new IllegalArgumentException("启用 ESA 同步需至少勾选一条解析记录或回源规则");
        }
    }

    /**
     * 将前端勾选的 ESA 记录/规则数组规范化为存储用 JSON 串（元素 {id,name}，无 id 项丢弃）：
     * id 为线上 RecordId/ConfigId（定位为主键），name 为名称快照（id 失效时按名称回退）。
     */
    private static String serializePicked(JsonObject b, String key) {
        if (!b.has(key) || !b.get(key).isJsonArray()) return "";
        JsonArray out = new JsonArray();
        for (JsonElement el : b.getAsJsonArray(key)) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            long id;
            try {
                id = Long.parseLong(JsonUtils.str(o, "id").trim());
            } catch (NumberFormatException e) {
                continue; // 非线上真实 id（如占位项）直接丢弃
            }
            if (id <= 0) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("name", JsonUtils.str(o, "name").trim());
            out.add(item);
        }
        return out.size() == 0 ? "" : out.toString();
    }

    /** 拉取 ESA 站点列表（表单下拉选择，不手填 SiteId） */
    private static void esaSites(Ctx ctx) throws Exception {
        String[] cred = esaCredentials(ctx.body());
        JsonArray out = new JsonArray();
        for (JsonElement el : AliyunClient.esaListSites(cred[0], cred[1])) {
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();
            JsonObject o = new JsonObject();
            o.addProperty("site_id", s.has("SiteId") ? String.valueOf(s.get("SiteId").getAsLong()) : "");
            o.addProperty("site_name", JsonUtils.str(s, "SiteName"));
            o.addProperty("status", JsonUtils.str(s, "Status"));
            out.add(o);
        }
        ctx.ok(Map.of("sites", out));
    }

    /** 拉取站点下全部解析记录（含当前记录值，供勾选与回显） */
    private static void esaRecords(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        String[] cred = esaCredentials(b);
        String siteId = JsonUtils.str(b, "site_id").trim();
        if (siteId.isEmpty()) throw new IllegalArgumentException("请先选择 ESA 站点");
        JsonArray out = new JsonArray();
        for (JsonElement el : AliyunClient.esaListAllRecords(cred[0], cred[1], siteId)) {
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject();
            JsonObject o = new JsonObject();
            o.addProperty("record_id", r.has("RecordId") ? String.valueOf(r.get("RecordId").getAsLong()) : "");
            o.addProperty("record_name", JsonUtils.str(r, "RecordName"));
            o.addProperty("record_type", JsonUtils.str(r, "RecordType"));
            o.addProperty("value", r.has("Data") && r.get("Data").isJsonObject()
                    ? JsonUtils.str(r.getAsJsonObject("Data"), "Value") : "");
            o.addProperty("ttl", r.has("Ttl") && !r.get("Ttl").isJsonNull() ? r.get("Ttl").getAsString() : "");
            o.addProperty("proxied", r.has("Proxied") && !r.get("Proxied").isJsonNull()
                    && r.get("Proxied").getAsBoolean());
            o.addProperty("comment", JsonUtils.str(r, "Comment"));
            out.add(o);
        }
        ctx.ok(Map.of("records", out));
    }

    /** 拉取站点下全部回源规则（含当前回源协议与端口；全局配置由前端过滤不展示） */
    private static void esaOriginRules(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        String[] cred = esaCredentials(b);
        String siteId = JsonUtils.str(b, "site_id").trim();
        if (siteId.isEmpty()) throw new IllegalArgumentException("请先选择 ESA 站点");
        JsonArray out = new JsonArray();
        for (JsonElement el : AliyunClient.esaListOriginRules(cred[0], cred[1], siteId)) {
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject();
            JsonObject o = new JsonObject();
            o.addProperty("config_id", r.has("ConfigId") ? String.valueOf(r.get("ConfigId").getAsLong()) : "");
            o.addProperty("rule_name", JsonUtils.str(r, "RuleName"));
            o.addProperty("config_type", JsonUtils.str(r, "ConfigType"));
            o.addProperty("origin_scheme", JsonUtils.str(r, "OriginScheme"));
            o.addProperty("origin_https_port", JsonUtils.str(r, "OriginHttpsPort"));
            o.addProperty("origin_http_port", JsonUtils.str(r, "OriginHttpPort"));
            o.addProperty("rule_enable", JsonUtils.str(r, "RuleEnable"));
            out.add(o);
        }
        ctx.ok(Map.of("rules", out));
    }

    /** 拉取线上列表用的凭证：ESA 同步只支持引用服务商凭证配置（与 DDNS 的实时读取一致） */
    private static String[] esaCredentials(JsonObject b) throws SQLException {
        Long cfgId = ProviderConfigService.parseId(b, "provider_config_id");
        if (cfgId == null) throw new IllegalArgumentException("请先选择服务商凭证配置");
        return ProviderConfigService.credentials(cfgId);
    }

    /** 将 webhook 项内的对象字段（headers/params）规范化为字符串值对象，缺失时返回空对象 */
    private static JsonObject normalizeStringMap(JsonObject w, String key) {
        JsonObject out = new JsonObject();
        if (w.has(key) && w.get(key).isJsonObject()) {
            for (Map.Entry<String, JsonElement> pe : w.getAsJsonObject(key).entrySet()) {
                JsonElement pv = pe.getValue();
                out.addProperty(pe.getKey(), pv == null || pv.isJsonNull() ? "" : pv.getAsString());
            }
        }
        return out;
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> task = Database.queryOne("SELECT * FROM stun_task WHERE id=?", id);
        if (task == null) throw new IllegalArgumentException("STUN 任务不存在: #" + id);
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
}
