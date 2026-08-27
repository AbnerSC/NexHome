package com.nexhome.module.stun;

import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import com.google.gson.JsonObject;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * STUN 服务器维护：常用服务器列表增删改查，穿透任务新增/编辑时下拉选择。
 * <p>
 * tcp_support 标记服务器是否支持 STUN-over-TCP：TCP 穿透任务必须借助支持 TCP 的
 * 服务器出站，才能在运营商 CGNAT 上建立真实 TCP 映射；配置的服务器不支持时，
 * 运行器按本表 tcp_support=1 的列表依次兜底。
 */
public final class StunServerService {

    private StunServerService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/stun/servers", ctx -> ctx.ok(Database.query(
                "SELECT * FROM stun_server ORDER BY id")));
        WebServer.route("POST", "/api/stun/servers", StunServerService::create);
        WebServer.route("POST", "/api/stun/servers/probe-tcp", StunServerService::probeTcp);
        WebServer.route("PUT", "/api/stun/servers/{id}", StunServerService::update);
        WebServer.route("DELETE", "/api/stun/servers/{id}", StunServerService::delete);
    }

    /** 首次启动播种常用公共 STUN 服务器 */
    public static void init() throws SQLException {
        Number count = (Number) Database.scalar("SELECT COUNT(*) FROM stun_server");
        if (count != null && count.intValue() > 0) return;
        Database.update("INSERT INTO stun_server(name, host, port, tcp_support) VALUES(?,?,?,?)", "小米", "stun.miwifi.com", 3478, 0);
        Database.update("INSERT INTO stun_server(name, host, port, tcp_support) VALUES(?,?,?,?)", "谷歌", "stun.l.google.com", 19302, 0);
        Database.update("INSERT INTO stun_server(name, host, port, tcp_support) VALUES(?,?,?,?)", "Antisip", "stun.antisip.com", 3478, 1);
        Database.update("INSERT INTO stun_server(name, host, port, tcp_support) VALUES(?,?,?,?)", "STUNProtocol", "stun.stunprotocol.org", 3478, 1);
        Database.update("INSERT INTO stun_server(name, host, port, tcp_support) VALUES(?,?,?,?)", "Sipgate", "stun.sipgate.net", 3478, 1);
        Logs.info(Logs.STUN, "已播种默认 STUN 服务器列表");
    }

    /** 支持 STUN-over-TCP 的服务器列表（host/port），供 TCP 映射探测兜底 */
    public static List<String[]> tcpServers() {
        try {
            List<String[]> list = new ArrayList<>();
            for (Map<String, Object> m : Database.query(
                    "SELECT host, port FROM stun_server WHERE tcp_support=1 ORDER BY id")) {
                list.add(new String[]{String.valueOf(m.get("host")),
                        String.valueOf(((Number) m.get("port")).intValue())});
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ---------- 增删改 ----------

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        validate(b);
        long id = Database.insert("""
                INSERT INTO stun_server(name, host, port, tcp_support)
                VALUES(?,?,?,?)""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "host"), JsonUtils.num(b, "port", 3478),
                JsonUtils.bool(b, "tcp_support", false) ? 1 : 0);
        Logs.info(Logs.STUN, "新增STUN服务器: " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        JsonObject b = ctx.body();
        validate(b);
        Database.update("UPDATE stun_server SET name=?, host=?, port=?, tcp_support=? WHERE id=?",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "host"), JsonUtils.num(b, "port", 3478),
                JsonUtils.bool(b, "tcp_support", false) ? 1 : 0, id);
        Logs.info(Logs.STUN, "更新STUN服务器 #" + id + ": " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Database.update("DELETE FROM stun_server WHERE id=?", id);
        Logs.info(Logs.STUN, "删除STUN服务器 #" + id);
        ctx.ok("已删除");
    }

    /** 手动探测服务器是否支持 STUN-over-TCP（新增/编辑表单「探测」按钮） */
    private static void probeTcp(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        String host = JsonUtils.str(b, "host").trim();
        int port = JsonUtils.num(b, "port", 3478);
        if (host.isBlank()) throw new IllegalArgumentException("服务器地址不能为空");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口需在 1-65535 之间");
        StunClient.TcpProbe p = StunClient.probeOverTcp(host, port, 0, 3000);
        Logs.info(Logs.STUN, "探测 STUN-over-TCP " + host + ":" + port + " -> "
                + (p == null ? "不支持/无响应" : "支持，映射地址 " + p.mapped()));
        ctx.ok(Map.of("supported", p != null, "mapped", p == null ? "" : p.mapped()));
    }

    /** 表单校验 */
    private static void validate(JsonObject b) {
        if (JsonUtils.str(b, "name").isBlank()) throw new IllegalArgumentException("服务器名称不能为空");
        if (JsonUtils.str(b, "host").isBlank()) throw new IllegalArgumentException("服务器地址不能为空");
        int port = JsonUtils.num(b, "port", 3478);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口需在 1-65535 之间");
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> s = Database.queryOne("SELECT * FROM stun_server WHERE id=?", id);
        if (s == null) throw new IllegalArgumentException("STUN 服务器不存在: #" + id);
        return s;
    }
}
