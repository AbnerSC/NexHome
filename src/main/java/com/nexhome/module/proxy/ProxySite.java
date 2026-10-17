package com.nexhome.module.proxy;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;

import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 代理站点（proxy_site 表一行的解析结果，转发期只读使用）。
 * <p>
 * 路由匹配语义对齐 nginx server_name / location 前缀：
 * domains 支持 {@code *.example.com} 通配（nginx 语义：同时匹配 example.com 本身与任意子域）；
 * paths 为逗号分隔的路径前缀，空 = 该域名全部路径。
 */
public record ProxySite(long id, String name, List<String> domains, List<String> paths,
                        String scheme, String backendHost, int backendPort, String basePath,
                        boolean forceHttps, String certMode, long certTaskId,
                        ProxyAdvanced adv, boolean enabled) {

    /** 从数据库加载全部站点（单行解析失败仅告警跳过，不阻塞其他站点） */
    public static List<ProxySite> loadAll() {
        List<ProxySite> list = new ArrayList<>();
        try {
            for (Map<String, Object> row : Database.query("SELECT * FROM proxy_site ORDER BY id")) {
                try {
                    list.add(of(row));
                } catch (Exception e) {
                    Logs.error(Logs.PROXY, "代理站点 #" + row.get("id") + " 配置解析失败，本次加载已跳过: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            Logs.error(Logs.PROXY, "加载代理站点失败: " + e.getMessage());
        }
        return list;
    }

    /** 数据库行 -> 站点模型（含 target/advanced 校验解析），非法抛 IllegalArgumentException */
    public static ProxySite of(Map<String, Object> row) {
        String adv = row.get("advanced") == null ? "" : row.get("advanced").toString();
        ProxySite base = new ProxySite(
                ((Number) row.get("id")).longValue(),
                str(row, "name"),
                splitList(str(row, "domains")),
                splitList(str(row, "paths")),
                null, null, 0, null,
                intVal(row, "force_https") == 1,
                str(row, "cert_mode").isEmpty() ? "AUTO" : str(row, "cert_mode"),
                row.get("cert_task_id") instanceof Number n ? n.longValue() : 0,
                ProxyAdvanced.parse(adv),
                intVal(row, "enabled") == 1);
        return base.withTarget(str(row, "target"));
    }

    /** 解析后端地址（创建/更新站点与加载时校验；record 中 scheme/host/port/basePath 由此填充） */
    public ProxySite withTarget(String target) {
        URI u;
        try {
            u = URI.create(target.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("后端地址格式不正确: " + target);
        }
        String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!s.equals("http") && !s.equals("https"))
            throw new IllegalArgumentException("后端地址必须以 http:// 或 https:// 开头: " + target);
        String host = u.getHost();
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("后端地址缺少主机名: " + target);
        int port = u.getPort();
        if (port < 0) port = s.equals("https") ? 443 : 80;
        if (port < 1 || port > 65535) throw new IllegalArgumentException("后端端口非法: " + port);
        String base = u.getPath() == null ? "" : u.getPath();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (u.getQuery() != null || u.getFragment() != null)
            throw new IllegalArgumentException("后端地址不能携带查询参数或片段: " + target);
        return new ProxySite(id, name, domains, paths, s, host, port, base,
                forceHttps, certMode, certTaskId, adv, enabled);
    }

    /** 域名匹配：精确或 *.父域（含父域本身），host 不带端口、已小写 */
    public boolean matchDomain(String host) {
        for (String d : domains) {
            if (d.startsWith("*.")) {
                String parent = d.substring(2);
                if (host.equals(parent) || host.endsWith("." + parent)) return true;
            } else if (host.equals(d)) return true;
        }
        return false;
    }

    /** 路径前缀匹配（无 paths 时匹配全部路径） */
    public boolean matchPath(String path) {
        if (paths.isEmpty()) return true;
        for (String p : paths) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    /** 最长路径前缀（路由排序用，无 paths 视为整站匹配） */
    public int pathPrefixLen() {
        int max = 0;
        for (String p : paths) max = Math.max(max, p.length());
        return paths.isEmpty() ? 0 : max;
    }

    /** 后端地址合法性校验（创建/更新接口用），非法抛 IllegalArgumentException */
    public static void validateTarget(String target) {
        if (target == null || target.isBlank())
            throw new IllegalArgumentException("后端地址不能为空");
        new ProxySite(0, "", List.of(), List.of(), null, null, 0, "", false, "AUTO", 0,
                ProxyAdvanced.defaults(), true).withTarget(target);
    }

    /** 域名合法性校验（创建/更新接口用），非法抛 IllegalArgumentException */
    public static void validateDomains(String csv) {
        List<String> list = splitList(csv);
        if (list.isEmpty()) throw new IllegalArgumentException("匹配域名不能为空");
        for (String d : list) {
            String body = d.startsWith("*.") ? d.substring(2) : d;
            if (!body.matches("(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*"))
                throw new IllegalArgumentException("域名格式不正确: " + d);
        }
    }

    /** 路径前缀列表校验，空串返回空列表（= 整站） */
    public static List<String> parsePaths(String csv) {
        List<String> list = new ArrayList<>();
        for (String p : splitList(csv)) {
            if (!p.startsWith("/")) throw new IllegalArgumentException("路径前缀必须以 / 开头: " + p);
            list.add(p);
        }
        return list;
    }

    private static List<String> splitList(String csv) {
        List<String> list = new ArrayList<>();
        if (csv == null) return List.of();
        for (String part : csv.split("[,;\\s]+")) {
            String v = part.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) list.add(v);
        }
        return List.copyOf(list);
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
