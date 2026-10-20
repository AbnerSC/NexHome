package com.nexhome.module.proxy;

import com.nexhome.core.AppConfig;
import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSL 证书自动适应仓库：扫描「SSL 证书管理」已签发且未过期的证书任务，
 * 构建 域名（含通配）-> Netty SslContext 映射，供 HTTPS 监听经 SNI 自动选证。
 * <p>
 * 证书文件布局与面板 HTTPS 一致：{@code data/certs/task-{id}/} 下
 * {@code domain.key.pem}（PKCS8 私钥）+ {@code fullchain.pem}（缺失回退 cert.pem）。
 * 站点 cert_mode=TASK 时优先使用指定任务的证书；AUTO 按 SNI 域名精确 > 通配匹配。
 * 均未命中时不终止握手，兜底选用剩余有效期最长的证书（域名不符仅表现为浏览器告警）。
 * 证书签发/续期成功后调用 {@link #reload()} 热更新（SslContext 懒构建，重载即失效重建）。
 */
public final class SslStore {

    /** 证书任务 ID -> 已加载的证书元信息（reload 全量替换） */
    private static volatile Map<Long, CertEntry> entries = Map.of();
    /** 域名（精确或 *.父域）-> 证书任务 ID（reload 全量替换） */
    private static volatile Map<String, Long> domainMap = Map.of();
    /** 「证书任务 + 是否通告 h2」-> 懒构建的 SslContext 缓存（reload 时清空）。
     *  ALPN 是 per-SslContext 的，故按站点 frontProtocol 分两套并存；站点改开关后下次握手
     *  直接落到另一个 key，无需清缓存即生效 */
    private static final ConcurrentHashMap<String, SslContext> contexts = new ConcurrentHashMap<>();

    /** 一张已签发证书的元信息 */
    public record CertEntry(long taskId, String name, List<String> domains, Instant notAfter) {
    }

    private SslStore() {
    }

    /** 全量重载：重新扫描证书任务并解析域名映射（证书文件缺失/解析失败的任务跳过并告警） */
    public static synchronized void reload() {
        Map<Long, CertEntry> newEntries = new LinkedHashMap<>();
        Map<String, Long> newMap = new LinkedHashMap<>();
        List<Map<String, Object>> tasks;
        try {
            tasks = Database.query("SELECT * FROM cert_task WHERE status='ISSUED'");
        } catch (SQLException e) {
            Logs.error(Logs.PROXY, "SslStore 加载证书任务失败: " + e.getMessage());
            return;
        }
        Instant now = Instant.now();
        for (Map<String, Object> t : tasks) {
            long id = ((Number) t.get("id")).longValue();
            try {
                CertEntry entry = loadEntry(id, String.valueOf(t.get("name")));
                if (entry.notAfter().isBefore(now)) continue; // 已过期不挂载
                newEntries.put(id, entry);
                for (String d : entry.domains()) newMap.putIfAbsent(d, id);
            } catch (Exception e) {
                Logs.warn(Logs.PROXY, "证书任务 #" + id + " 不可用于代理 HTTPS（已跳过）: " + e.getMessage());
            }
        }
        entries = newEntries;
        domainMap = newMap;
        contexts.clear(); // 证书变化 -> 懒构建缓存失效，下次握手重建
        Logs.info(Logs.PROXY, "代理证书库已重载：" + newEntries.size() + " 张有效证书，覆盖 "
                + newMap.size() + " 个域名");
    }

    /** 是否有可挂载 HTTPS 的证书 */
    public static boolean hasCert() {
        return !entries.isEmpty();
    }

    /** 证书任务详情列表（供 /api/proxy/certs 展示） */
    public static List<Map<String, Object>> apiView() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CertEntry e : entries.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", e.taskId());
            m.put("name", e.name());
            m.put("domains", String.join(", ", e.domains()));
            m.put("notAfter", e.notAfter().toString());
            list.add(m);
        }
        return list;
    }

    /**
     * SNI 握手选证：先按当前站点列表解析（cert_mode=TASK 指定优先），
     * 再按域名映射自动匹配（精确 > 逐级父域通配）；均未命中时兜底选用
     * 剩余有效期最长的证书。返回 null 仅当证书库为空（握手终止）。
     * <p>
     * ALPN 通告按命中站点的 {@code frontProtocol} 决定：站点关闭 h2 时只通告 http/1.1，
     * 浏览器即回退 h1（用于绕开 h2 流控窗口对大文件下载的限制）。
     */
    public static SslContext forSni(String hostname, List<ProxySite> sites) {
        if (hostname == null || hostname.isEmpty()) return longestValidContext(true);
        String host = hostname.toLowerCase(Locale.ROOT);
        boolean h2 = frontH2(host, sites);
        // 1. 站点指定证书任务优先
        for (ProxySite s : sites) {
            if (!s.enabled() || !s.matchDomain(host)) continue;
            if ("TASK".equals(s.certMode()) && s.certTaskId() > 0 && entries.containsKey(s.certTaskId()))
                return context(s.certTaskId(), h2);
        }
        // 2. 域名自动匹配（精确 > 逐级父域通配）：未被站点 TASK 证书锁定即尝试，
        //    覆盖三种情形——AUTO 站点、证书先行站点后建、以及未配置站点的域名复用通配证书
        //    （如访问 npm.pi.mmxx.fun 无对应站点，仍应命中 *.pi.mmxx.fun 而非兜底错证）
        Long taskId = lookupDomain(host);
        if (taskId != null) return context(taskId, h2);
        // 3. 未匹配到域名证书：兜底剩余有效期最长的证书，避免直接中断握手
        return longestValidContext(h2);
    }

    /** 前端是否通告 h2：命中该域名的启用站点只要有一个关了 http1 就关（无站点则保持默认 h2） */
    private static boolean frontH2(String host, List<ProxySite> sites) {
        for (ProxySite s : sites) {
            if (s.enabled() && s.matchDomain(host) && !s.adv().frontH2()) return false;
        }
        return true;
    }

    /** 域名查表：精确 -> *.一级父域 -> *.二级父域 ...（nginx 通配最长匹配语义） */
    private static Long lookupDomain(String host) {
        Long id = domainMap.get(host);
        if (id != null) return id;
        int dot = host.indexOf('.');
        while (dot > 0) {
            String parent = host.substring(dot + 1);
            id = domainMap.get("*." + parent);
            if (id != null) return id;
            dot = parent.indexOf('.');
            if (dot <= 0) break;
            host = parent;
        }
        return null;
    }

    /** 任务 -> SslContext（懒构建：SNI 首握手或预热时创建，reload 后失效重建） */
    private static SslContext context(long taskId, boolean h2) {
        return contexts.computeIfAbsent(taskId + (h2 ? "|h2" : "|h1"), key -> {
            try {
                return buildContext(taskId, h2);
            } catch (Exception e) {
                Logs.error(Logs.PROXY, "构建证书任务 #" + taskId + " 的 TLS 上下文失败: " + e.getMessage());
                throw new IllegalStateException(e);
            }
        });
    }

    /** 兜底选证：剩余有效期最长的一张（无 SNI 或域名未命中时使用，后续按 HTTP 层再校验域名归属） */
    private static SslContext longestValidContext(boolean h2) {
        Long bestId = null;
        Instant bestNotAfter = null;
        for (CertEntry e : entries.values()) {
            if (bestId == null || e.notAfter().isAfter(bestNotAfter)) {
                bestId = e.taskId();
                bestNotAfter = e.notAfter();
            }
        }
        return bestId == null ? null : context(bestId, h2);
    }

    private static SslContext buildContext(long taskId, boolean h2) throws Exception {
        Path dir = AppConfig.DATA_DIR.resolve("certs").resolve("task-" + taskId);
        Path keyFile = dir.resolve("domain.key.pem");
        Path certFile = Files.exists(dir.resolve("fullchain.pem"))
                ? dir.resolve("fullchain.pem") : dir.resolve("cert.pem");
        if (!Files.exists(keyFile) || !Files.exists(certFile))
            throw new IllegalArgumentException("证书文件缺失: " + dir);
        // Netty 直接读 PEM（支持 PKCS8 RSA/EC 私钥），ALPN 走 JDK 内置能力（JDK9+）
        // 站点关 h2 时只通告 http/1.1：客户端（Chrome/curl --http2）协商不到 h2 即回退 h1
        String[] protos = h2
                ? new String[]{ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1}
                : new String[]{ApplicationProtocolNames.HTTP_1_1};
        return SslContextBuilder.forServer(certFile.toFile(), keyFile.toFile())
                .protocols("TLSv1.2", "TLSv1.3")
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        protos))
                .build();
    }

    /** 加载并解析单个任务的证书（叶子证书 SAN DNS 名单 + 到期时间） */
    private static CertEntry loadEntry(long taskId, String name) throws Exception {
        Path dir = AppConfig.DATA_DIR.resolve("certs").resolve("task-" + taskId);
        Path certFile = Files.exists(dir.resolve("fullchain.pem"))
                ? dir.resolve("fullchain.pem") : dir.resolve("cert.pem");
        if (!Files.exists(certFile)) throw new IllegalArgumentException("证书文件缺失: " + certFile);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        var certs = cf.generateCertificates(new ByteArrayInputStream(Files.readAllBytes(certFile)));
        java.security.cert.X509Certificate leaf = null;
        for (Object c : certs) {
            if (c instanceof java.security.cert.X509Certificate x && !isCA(x)) {
                leaf = x;
                break;
            }
        }
        if (leaf == null && !certs.isEmpty()) leaf = (java.security.cert.X509Certificate) certs.iterator().next();
        if (leaf == null) throw new IllegalArgumentException("证书文件中没有 X.509 证书");
        List<String> domains = new ArrayList<>();
        try {
            for (List<?> san : leaf.getSubjectAlternativeNames()) {
                if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0)) && san.get(1) instanceof String s)
                    domains.add(s.toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {
            // 无 SAN 的证书无法按域名匹配，跳过
        }
        if (domains.isEmpty()) throw new IllegalArgumentException("证书不含 SAN 域名");
        return new CertEntry(taskId, name, List.copyOf(domains), leaf.getNotAfter().toInstant());
    }

    private static boolean isCA(java.security.cert.X509Certificate x) {
        return x.getBasicConstraints() >= 0;
    }
}
