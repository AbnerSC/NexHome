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
                "SELECT * FROM stun_server ORDER BY sort_order, id")));
        WebServer.route("POST", "/api/stun/servers", StunServerService::create);
        WebServer.route("POST", "/api/stun/servers/probe-tcp", StunServerService::probeTcp);
        WebServer.route("POST", "/api/stun/servers/{id}/move", StunServerService::move);
        WebServer.route("PUT", "/api/stun/servers/{id}", StunServerService::update);
        WebServer.route("DELETE", "/api/stun/servers/{id}", StunServerService::delete);
    }

    /** 实测可用的公共 STUN 服务器（name, host, port, tcp_support），按展示顺序播种 */
    private static final String[][] SEED_SERVERS = {
            {"谷歌", "stun.l.google.com", "19302", "0"},
            {"Cloudflare", "stun.cloudflare.com", "3478", "0"},
            {"小米", "stun.miwifi.com", "3478", "0"},
            {"Twilio", "global.stun.twilio.com", "3478", "0"},
            {"VoipStunt", "stun.voipstunt.com", "3478", "0"},
            {"Antisip(TCP)", "stun.antisip.com", "3478", "1"},
            {"Nextcloud(TCP)", "stun.nextcloud.com", "3478", "1"},
            {"CloudflareTURN(TCP)", "turn.cloudflare.com", "3478", "1"},
            {"FreeSwitch(TCP)", "stun.freeswitch.org", "3478", "1"},
    };

    /** 旧版本内置默认服务器（已停服/不支持TCP），升级时自动清除对应默认行 */
    private static final String[][] LEGACY_SEED_ROWS = {
            {"小米", "stun.miwifi.com", "3478", "0"},
            {"谷歌", "stun.l.google.com", "19302", "0"},
            {"Antisip", "stun.antisip.com", "3478", "1"},
            {"STUNProtocol", "stun.stunprotocol.org", "3478", "1"},
            {"Sipgate", "stun.sipgate.net", "3478", "1"},
    };

    /**
     * 启动播种/刷新：旧库补充排序列；清除旧版停服默认服务器；补入实测可用列表。
     * 仅删除与旧默认（name/host/port/tcp_support）完全一致的默认行，用户改动过的行保留。
     */
    public static void init() throws SQLException {
        ensureColumn("sort_order", "INTEGER NOT NULL DEFAULT 0");
        for (String[] l : LEGACY_SEED_ROWS) {
            Database.update("DELETE FROM stun_server WHERE name=? AND host=? AND port=? AND tcp_support=?",
                    l[0], l[1], Integer.parseInt(l[2]), Integer.parseInt(l[3]));
        }
        int maxOrder = 0;
        Object max = Database.scalar("SELECT COALESCE(MAX(sort_order), 0) FROM stun_server");
        if (max instanceof Number n) maxOrder = n.intValue();
        for (String[] s : SEED_SERVERS) {
            Map<String, Object> row = Database.queryOne("SELECT id FROM stun_server WHERE host=? AND port=?",
                    s[1], Integer.parseInt(s[2]));
            if (row == null) {
                Database.update("INSERT INTO stun_server(name, host, port, tcp_support, sort_order) VALUES(?,?,?,?,?)",
                        s[0], s[1], Integer.parseInt(s[2]), Integer.parseInt(s[3]), ++maxOrder);
            } else {
                // 已存在（用户/历史数据）：保留用户顺序与名称，仅校正 tcp_support 实测标记
                Database.update("UPDATE stun_server SET tcp_support=? WHERE id=?",
                        Integer.parseInt(s[3]), ((Number) row.get("id")).longValue());
            }
        }
        Logs.info(Logs.STUN, "STUN 服务器列表已刷新（清除停服默认项，补入实测可用服务器）");
    }

    /** 旧库升级：补充缺失列（新库建表脚本已包含） */
    private static void ensureColumn(String name, String type) throws SQLException {
        boolean exists = Database.query("PRAGMA table_info(stun_server)").stream()
                .anyMatch(col -> name.equals(col.get("name")));
        if (!exists) {
            Database.update("ALTER TABLE stun_server ADD COLUMN " + name + " " + type);
        }
    }

    /** 支持 STUN-over-TCP 的服务器列表（host/port），供 TCP 映射探测兜底 */
    public static List<String[]> tcpServers() {
        try {
            List<String[]> list = new ArrayList<>();
            for (Map<String, Object> m : Database.query(
                    "SELECT host, port FROM stun_server WHERE tcp_support=1 ORDER BY sort_order, id")) {
                list.add(new String[]{String.valueOf(m.get("host")),
                        String.valueOf(((Number) m.get("port")).intValue())});
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 全部 STUN 服务器（host/port，按维护排序），供 UDP/TCP 保活候选兜底 */
    public static List<String[]> allServers() {
        try {
            List<String[]> list = new ArrayList<>();
            for (Map<String, Object> m : Database.query(
                    "SELECT host, port FROM stun_server ORDER BY sort_order, id")) {
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
                INSERT INTO stun_server(name, host, port, tcp_support, sort_order)
                VALUES(?,?,?,?,(SELECT COALESCE(MAX(sort_order),0)+1 FROM stun_server))""",
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

    /** 上移/下移调整顺序（交换相邻行，先按当前顺序归一化序号再交换） */
    private static void move(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        String dir = JsonUtils.str(ctx.body(), "dir");
        List<Map<String, Object>> ordered = Database.query("SELECT id FROM stun_server ORDER BY sort_order, id");
        int idx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (((Number) ordered.get(i).get("id")).longValue() == id) {
                idx = i;
                break;
            }
        }
        if (idx < 0) throw new IllegalArgumentException("STUN 服务器不存在: #" + id);
        int swap = "up".equals(dir) ? idx - 1 : idx + 1;
        if (swap < 0 || swap >= ordered.size()) {
            ctx.ok(Map.of("moved", false)); // 已到边界
            return;
        }
        for (int i = 0; i < ordered.size(); i++) { // 归一化：避免多行 sort_order 相同导致交换无效
            Database.update("UPDATE stun_server SET sort_order=? WHERE id=?", i,
                    ((Number) ordered.get(i).get("id")).longValue());
        }
        long other = ((Number) ordered.get(swap).get("id")).longValue();
        Database.update("UPDATE stun_server SET sort_order=? WHERE id=?", swap, id);
        Database.update("UPDATE stun_server SET sort_order=? WHERE id=?", idx, other);
        ctx.ok(Map.of("moved", true));
    }

    /** 手动探测服务器是否支持 STUN-over-TCP（新增/编辑表单「探测」按钮） */
    private static void probeTcp(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        String host = JsonUtils.str(b, "host").trim();
        int port = JsonUtils.num(b, "port", 3478);
        if (host.isBlank()) throw new IllegalArgumentException("服务器地址不能为空");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口需在 1-65535 之间");
        StunClient.TcpProbe p = StunClient.probeOverTcp(host, port, 0, 3000);
        if (p != null) {
            try {
                p.socket().close(); // 手动探测仅验证支持性，用后即闭（长期保活由任务运行器管理）
            } catch (Exception ignored) {
                // 探测连接关闭失败不影响探测结果
            }
        }
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
