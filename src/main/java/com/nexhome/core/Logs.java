package com.nexhome.core;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 全局操作日志。
 * <p>
 * 所有模块统一调用 {@link #info(String, String)} / {@link #error(String, String)} /
 * {@link #warn(String, String)} 写入日志：同时输出到控制台与 SQLite op_log 表，
 * Web 端日志面板通过分页接口查询。
 */
public final class Logs {

    /** 模块常量，保证日志可按模块过滤 */
    public static final String SYS  = "SYSTEM";
    public static final String AUTH = "AUTH";
    public static final String DDNS = "DDNS";
    public static final String STUN = "STUN";
    public static final String WOL  = "WOL";
    public static final String CERT = "CERT";
    public static final String NAV  = "NAV";

    private Logs() {
    }

    public static void info(String module, String message) {
        write(module, "INFO", message);
    }

    public static void warn(String module, String message) {
        write(module, "WARN", message);
    }

    public static void error(String module, String message) {
        write(module, "ERROR", message);
    }

    private static void write(String module, String level, String message) {
        String time = Database.now();
        System.out.printf("[%s] [%s] [%s] %s%n", time, module, level, message);
        try {
            Database.insert("INSERT INTO op_log(time, module, level, message) VALUES(?,?,?,?)",
                    time, module, level, message);
        } catch (SQLException e) {
            // 日志落库失败不影响业务，仅输出到控制台
            System.err.println("写入日志表失败: " + e.getMessage());
        }
    }

    /**
     * 分页查询日志。
     *
     * @param page   页码，从 1 开始
     * @param size   每页条数
     * @param module 模块过滤，null 表示全部
     */
    public static Map<String, Object> page(int page, int size, String module) throws SQLException {
        if (page < 1) page = 1;
        if (size < 1 || size > 500) size = 50;
        int offset = (page - 1) * size;
        String where = (module == null || module.isBlank()) ? "" : " WHERE module=?";
        Object[] params = where.isEmpty()
                ? new Object[]{size, offset}
                : new Object[]{module, size, offset};
        List<Map<String, Object>> rows = Database.query(
                "SELECT id, time, module, level, message FROM op_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", params);
        Object total = Database.scalar("SELECT COUNT(*) FROM op_log" + where,
                where.isEmpty() ? new Object[]{} : new Object[]{module});
        return Map.of("list", rows, "total", total == null ? 0 : total, "page", page, "size", size);
    }

    /** 清理过期日志，防止表无限增长（保留最近 20000 条） */
    public static void truncateOld() {
        try {
            Database.update("DELETE FROM op_log WHERE id <= (SELECT MAX(id) - 20000 FROM op_log)");
        } catch (SQLException ignored) {
        }
    }
}
