package com.nexhome.web;

import com.nexhome.core.Database;
import com.nexhome.module.docker.DockerService;
import com.nexhome.module.proxy.ProxyCenter;

import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
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

    /** 访问统计回看天数（与月分表策略配合，7 天至多命中 2 张月表） */
    private static final int VISIT_DAYS = 7;
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String VISIT_TABLE_PREFIX = "proxy_visit_stat_";

    /** 来源地区口径：国内取省份，外国取国家，缺省归为未知（与 GeoResolver 记录口径一致） */
    private static final String REGION_EXPR = "CASE WHEN COALESCE(country,'')='' THEN '未知' "
            + "WHEN country='中国' AND COALESCE(province,'')!='' THEN province "
            + "ELSE country END";

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
        d.put("proxy", proxy());
        d.put("visits", visits());
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

    /** 代理站点：启用/总数 + 监听状态（有站点但未监听视为异常，前端橙色提示） */
    private static Map<String, Object> proxy() throws SQLException {
        Map<String, Object> row = Database.queryOne("SELECT COUNT(*) total, "
                + "SUM(CASE WHEN enabled=1 THEN 1 ELSE 0 END) enabled FROM proxy_site");
        Map<String, Object> m = numMap(row, "total", "enabled");
        m.put("listening", ProxyCenter.boundHttpPort() > 0);
        return m;
    }

    /** 代理访问统计（最近 7 天）：每日总访问、网站 TOP10、来源地区 TOP10 */
    private static Map<String, Object> visits() throws SQLException {
        LocalDate today = LocalDate.now();
        List<String> days = new ArrayList<>();
        for (int i = VISIT_DAYS - 1; i >= 0; i--) days.add(today.minusDays(i).format(DAY_FMT));
        String from = days.get(0), to = days.get(days.size() - 1);
        String union = visitUnion(from, to);

        List<Map<String, Object>> daily = new ArrayList<>();
        List<Map<String, Object>> topSites = new ArrayList<>();
        List<Map<String, Object>> topRegions = new ArrayList<>();
        long total = 0;
        if (!union.isEmpty()) {
            Map<String, Object> dayHits = new LinkedHashMap<>();
            for (Map<String, Object> r : Database.query("SELECT day, SUM(hits) hits FROM (" + union
                    + ") WHERE day BETWEEN '" + from + "' AND '" + to + "' GROUP BY day")) {
                dayHits.put(str(r, "day"), toLong(r.get("hits")));
            }
            for (String day : days) {
                long hits = ((Number) dayHits.getOrDefault(day, 0L)).longValue();
                total += hits;
                daily.add(Map.of("day", day, "hits", hits));
            }
            for (Map<String, Object> r : Database.query("SELECT host, SUM(hits) hits FROM (" + union
                    + ") WHERE day BETWEEN '" + from + "' AND '" + to
                    + "' GROUP BY host ORDER BY hits DESC LIMIT 10")) {
                topSites.add(Map.of("name", str(r, "host"), "hits", toLong(r.get("hits"))));
            }
            for (Map<String, Object> r : Database.query("SELECT " + REGION_EXPR + " region, SUM(hits) hits FROM ("
                    + union + ") WHERE day BETWEEN '" + from + "' AND '" + to
                    + "' GROUP BY region ORDER BY hits DESC LIMIT 10")) {
                topRegions.add(Map.of("name", str(r, "region"), "hits", toLong(r.get("hits"))));
            }
        } else {
            for (String day : days) daily.add(Map.of("day", day, "hits", 0L));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("days", daily);
        m.put("topSites", topSites);
        m.put("topRegions", topRegions);
        return m;
    }

    /**
     * 7 天窗口命中的访问月表 UNION 视图（day BETWEEN 的过滤在外层做）：
     * 仅拼接真实存在的表，跨月窗口至多 2 张；无任何数据表时返回空串（外层跳过查询）。
     */
    private static String visitUnion(String from, String to) throws SQLException {
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (Map<String, Object> r : Database.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '"
                        + VISIT_TABLE_PREFIX + "%'")) {
            existing.add(str(r, "name"));
        }
        StringBuilder sb = new StringBuilder();
        YearMonth m = YearMonth.parse(from.substring(0, 7));
        YearMonth end = YearMonth.parse(to.substring(0, 7));
        for (; !m.isAfter(end); m = m.plusMonths(1)) {
            String table = VISIT_TABLE_PREFIX + m.format(MONTH_FMT);
            if (!existing.contains(table)) continue;
            if (sb.length() > 0) sb.append(" UNION ALL ");
            sb.append("SELECT day, host, country, province, hits FROM ").append(table);
        }
        return sb.toString();
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
