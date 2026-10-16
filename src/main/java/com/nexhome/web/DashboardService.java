package com.nexhome.web;

import com.nexhome.core.Database;
import com.nexhome.module.docker.DockerService;

import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一状态仪表盘聚合：把分散在各模块的关键状态汇总成一次请求，供首页总览面板展示。
 * <p>
 * 全部为只读统计；Docker 概览失败（未挂载 sock 等）容忍降级，不影响其余板块。
 */
public final class DashboardService {

    /** 证书到期告警阈值（天），与自动续期窗口一致 */
    private static final int CERT_WARN_DAYS = 21;

    private DashboardService() {
    }

    public static Map<String, Object> summary() throws SQLException {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("system", system());
        d.put("ddns", count("ddns_task",
                "SUM(CASE WHEN last_status LIKE 'FAIL%' THEN 1 ELSE 0 END)", "enabled"));
        d.put("stun", stun());
        d.put("cert", cert());
        d.put("monitor", monitor());
        d.put("docker", DockerService.dashboardSummary());
        d.put("alerts", alerts());
        return d;
    }

    private static Map<String, Object> system() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("port", WebServer.httpPort());
        m.put("httpsActive", WebServer.httpsActive());
        return m;
    }

    /** 通用计数：total + enabled + 失败数（failedExpr 为统计失败的 SQL 表达式） */
    private static Map<String, Object> count(String table, String failedExpr, String enabledCol) throws SQLException {
        Map<String, Object> row = Database.queryOne("SELECT COUNT(*) total, "
                + "SUM(CASE WHEN " + enabledCol + "=1 THEN 1 ELSE 0 END) enabled, "
                + failedExpr + " failed FROM " + table);
        return numMap(row, "total", "enabled", "failed");
    }

    private static Map<String, Object> stun() throws SQLException {
        Map<String, Object> row = Database.queryOne("SELECT COUNT(*) total, "
                + "SUM(CASE WHEN status='RUNNING' THEN 1 ELSE 0 END) running, "
                + "SUM(CASE WHEN status='ERROR' THEN 1 ELSE 0 END) failed "
                + "FROM stun_task");
        return numMap(row, "total", "running", "failed");
    }

    private static Map<String, Object> monitor() throws SQLException {
        Map<String, Object> row = Database.queryOne("SELECT COUNT(*) total, "
                + "SUM(CASE WHEN enabled=1 THEN 1 ELSE 0 END) enabled, "
                + "SUM(CASE WHEN last_status='DOWN' THEN 1 ELSE 0 END) down, "
                + "SUM(CASE WHEN last_status='UP' THEN 1 ELSE 0 END) up "
                + "FROM monitor_task");
        return numMap(row, "total", "enabled", "down", "up");
    }

    /** 证书：总数、已签发、临期（<=21 天）、临期明细（含剩余天数） */
    private static Map<String, Object> cert() throws SQLException {
        List<Map<String, Object>> tasks = Database.query(
                "SELECT id, name, status, not_after FROM cert_task ORDER BY id");
        List<Map<String, Object>> expiring = new ArrayList<>();
        int issued = 0, soon = 0;
        LocalDate today = LocalDate.now();
        for (Map<String, Object> t : tasks) {
            if ("ISSUED".equals(str(t, "status"))) issued++;
            long days = daysRemaining(str(t, "not_after"), today);
            if ("ISSUED".equals(str(t, "status")) && days >= 0 && days <= CERT_WARN_DAYS) {
                soon++;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", t.get("id"));
                e.put("name", str(t, "name"));
                e.put("not_after", str(t, "not_after"));
                e.put("daysLeft", days);
                expiring.add(e);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", tasks.size());
        m.put("issued", issued);
        m.put("expiringSoon", soon);
        m.put("warnDays", CERT_WARN_DAYS);
        m.put("expiringList", expiring);
        return m;
    }

    /** 最近告警/错误日志摘要（op_log ERROR/WARN，最多 10 条） */
    private static List<Map<String, Object>> alerts() throws SQLException {
        return Database.query("SELECT time, module, level, message FROM op_log "
                + "WHERE level IN ('ERROR','WARN') ORDER BY id DESC LIMIT 10");
    }

    private static long daysRemaining(String notAfter, LocalDate today) {
        if (notAfter == null || notAfter.length() < 10) return -1;
        try {
            return ChronoUnit.DAYS.between(today, LocalDate.parse(notAfter.substring(0, 10)));
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    private static Map<String, Object> numMap(Map<String, Object> row, String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, row == null ? 0 : toLong(row.get(k)));
        return m;
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
