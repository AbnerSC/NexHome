package com.nexhome.module.wol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WOL（Wake-on-LAN）网络唤醒服务。
 * <p>
 * 底层实现：向目标广播地址发送 UDP 魔术包（Magic Packet）：
 * 6 字节 0xFF + 目标 MAC 地址重复 16 遍，连续发送 3 次提高成功率。
 * 被唤醒主机需在网卡/BIOS 中开启 WOL 支持。
 */
public final class WolService {

    private WolService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/wol/devices", ctx -> ctx.ok(Database.query(
                "SELECT * FROM wol_device ORDER BY id")));
        WebServer.route("POST", "/api/wol/devices", WolService::create);
        WebServer.route("PUT", "/api/wol/devices/{id}", WolService::update);
        WebServer.route("DELETE", "/api/wol/devices/{id}", WolService::delete);
        // 单台唤醒
        WebServer.route("POST", "/api/wol/devices/{id}/wake", ctx -> {
            wake(ctx.paramLong("id"));
            ctx.ok("唤醒魔术包已发送");
        });
        // 批量唤醒
        WebServer.route("POST", "/api/wol/wake-batch", ctx -> {
            JsonObject b = ctx.body();
            JsonArray ids = b.has("ids") ? b.getAsJsonArray("ids") : new JsonArray();
            List<String> results = new ArrayList<>();
            for (var el : ids) {
                long id = el.getAsLong();
                try {
                    wake(id);
                    results.add("#" + id + " 已发送");
                } catch (Exception e) {
                    results.add("#" + id + " 失败: " + e.getMessage());
                }
            }
            ctx.ok(Map.of("results", results));
        });
    }

    // ---------- 增删改 ----------

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        validate(b);
        long id = Database.insert(
                "INSERT INTO wol_device(name, mac, broadcast, port) VALUES(?,?,?,?)",
                JsonUtils.str(b, "name"), normalizeMac(JsonUtils.str(b, "mac")),
                JsonUtils.str(b, "broadcast"), JsonUtils.num(b, "port", 9));
        Logs.info(Logs.WOL, "新增唤醒设备: " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        mustGet(id);
        JsonObject b = ctx.body();
        validate(b);
        Database.update("UPDATE wol_device SET name=?, mac=?, broadcast=?, port=? WHERE id=?",
                JsonUtils.str(b, "name"), normalizeMac(JsonUtils.str(b, "mac")),
                JsonUtils.str(b, "broadcast"), JsonUtils.num(b, "port", 9), id);
        Logs.info(Logs.WOL, "更新唤醒设备 #" + id + ": " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> dev = mustGet(id);
        Database.update("DELETE FROM wol_device WHERE id=?", id);
        Logs.info(Logs.WOL, "删除唤醒设备 #" + id + ": " + dev.get("name"));
        ctx.ok("已删除");
    }

    // ---------- 唤醒 ----------

    /** 向指定设备发送魔术包唤醒 */
    public static void wake(long deviceId) throws Exception {
        Map<String, Object> dev = mustGet(deviceId);
        String name = str(dev, "name");
        byte[] mac = parseMac(str(dev, "mac"));
        String broadcast = str(dev, "broadcast");
        int port = intVal(dev, "port");

        // 魔术包：6 字节 0xFF + MAC 重复 16 次，共 102 字节
        byte[] packet = new byte[102];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
        for (int i = 0; i < 16; i++) {
            System.arraycopy(mac, 0, packet, 6 + i * 6, 6);
        }

        InetAddress addr = InetAddress.getByName(broadcast);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            // 连发 3 次，应对 UDP 丢包
            for (int i = 0; i < 3; i++) {
                socket.send(new DatagramPacket(packet, packet.length, addr, port));
            }
        }
        Logs.info(Logs.WOL, "唤醒魔术包已发送 -> " + name + " (MAC=" + str(dev, "mac")
                + ", 广播=" + broadcast + ":" + port + ")");
    }

    // ---------- 校验与工具 ----------

    private static void validate(JsonObject b) {
        if (JsonUtils.str(b, "name").isBlank()) throw new IllegalArgumentException("设备名称不能为空");
        String mac = JsonUtils.str(b, "mac");
        if (!mac.replaceAll("[:-]", "").matches("[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("MAC 地址格式不正确，例如 00:11:22:33:44:55");
        }
        if (JsonUtils.str(b, "broadcast").isBlank()) {
            throw new IllegalArgumentException("广播地址不能为空，常用 255.255.255.255 或子网广播地址");
        }
    }

    /** 统一 MAC 格式为冒号分隔小写 */
    private static String normalizeMac(String mac) {
        String hex = mac.replaceAll("[:-]", "").toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(hex, i * 2, i * 2 + 2);
        }
        return sb.toString();
    }

    private static byte[] parseMac(String mac) {
        String hex = mac.replaceAll("[:-]", "");
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> dev = Database.queryOne("SELECT * FROM wol_device WHERE id=?", id);
        if (dev == null) throw new IllegalArgumentException("唤醒设备不存在: #" + id);
        return dev;
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
