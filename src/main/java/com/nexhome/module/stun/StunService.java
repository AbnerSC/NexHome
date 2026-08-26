package com.nexhome.module.stun;

import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import com.google.gson.JsonObject;

import java.net.DatagramSocket;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STUN 穿透任务管理服务：增删改查 + 启停控制 + 状态持久化（重启自动加载）。
 */
public final class StunService {

    /** 任务 id -> 运行中的任务实例 */
    private static final Map<Long, StunRunner> RUNNERS = new ConcurrentHashMap<>();

    private StunService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/stun/tasks", ctx -> ctx.ok(Database.query(
                "SELECT * FROM stun_task ORDER BY id")));
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
        // 单独探测一次：不启动任务，仅检测 NAT 类型与映射地址
        WebServer.route("POST", "/api/stun/tasks/{id}/test", ctx -> {
            Map<String, Object> task = mustGet(ctx.paramLong("id"));
            try (DatagramSocket s = new DatagramSocket()) {
                StunClient.Result r = StunClient.detectNatType(s, str(task, "stun_host"),
                        intVal(task, "stun_port"), 3000);
                Logs.info(Logs.STUN, "手动探测任务[" + task.get("name") + "] 结果: "
                        + (r == null ? "无响应" : r.natType() + " / " + r.mappedAddress()));
                ctx.ok(Map.of(
                        "mapped", r == null || r.mappedAddress() == null ? "" : r.mappedAddress(),
                        "natType", r == null ? "Unknown(STUN服务器无响应)" : r.natType()));
            }
        });
    }

    /** 启动时自动加载：恢复启用了的穿透任务 */
    public static void init() throws SQLException {
        for (Map<String, Object> task : Database.query("SELECT * FROM stun_task WHERE enabled=1")) {
            try {
                start(((Number) task.get("id")).longValue());
            } catch (Exception e) {
                Logs.error(Logs.STUN, "自动恢复穿透任务 #" + task.get("id") + " 失败: " + e);
            }
        }
    }

    // ---------- 增删改 ----------

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        validate(b);
        long id = Database.insert("""
                INSERT INTO stun_task(name, protocol, target_ip, target_port, bind_port,
                    stun_host, stun_port, keepalive_sec, enabled)
                VALUES(?,?,?,?,?,?,?,?,?)""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "protocol"), JsonUtils.str(b, "target_ip"),
                JsonUtils.num(b, "target_port", 0), JsonUtils.num(b, "bind_port", 0),
                JsonUtils.str(b, "stun_host"), JsonUtils.num(b, "stun_port", 19302),
                JsonUtils.num(b, "keepalive_sec", 25),
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
                    stun_host=?, stun_port=?, keepalive_sec=? WHERE id=?""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "protocol"), JsonUtils.str(b, "target_ip"),
                JsonUtils.num(b, "target_port", 0), JsonUtils.num(b, "bind_port", 0),
                JsonUtils.str(b, "stun_host"), JsonUtils.num(b, "stun_port", 19302),
                JsonUtils.num(b, "keepalive_sec", 25), id);
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
        Logs.info(Logs.STUN, "删除穿透任务 #" + id + ": " + task.get("name"));
        ctx.ok("已删除");
    }

    // ---------- 启停 ----------

    /** 启动任务 */
    public static void start(long id) throws Exception {
        Map<String, Object> task = mustGet(id);
        stop(id); // 幂等：先停止旧实例
        StunRunner runner = new StunRunner(task);
        runner.start();
        RUNNERS.put(id, runner);
        Database.update("UPDATE stun_task SET enabled=1 WHERE id=?", id);
    }

    /** 停止任务 */
    public static void stop(long id) throws SQLException {
        StunRunner runner = RUNNERS.remove(id);
        if (runner != null) {
            runner.stop();
        }
        Database.update("UPDATE stun_task SET enabled=0, status='STOPPED' WHERE id=?", id);
    }

    /** 表单校验 */
    private static void validate(JsonObject b) {
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
