package com.nexhome.web;

import com.google.gson.JsonObject;
import com.nexhome.core.AppConfig;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访问与安全配置（HTTP 端口 / HTTPS / 安全入口）。
 * <p>
 * 配置持久化在 SQLite 的 app_config 表（key 前缀 {@code web.}），
 * 优先级高于 nexhome.properties 中的引导配置；由系统设置页修改，
 * 保存后经 {@link WebServer#restart()} 进程内重启生效，绑定失败自动回滚旧快照。
 * <p>
 * HTTPS 证书复用「SSL 证书管理」中已签发成功的任务证书（PEM）：
 * 可指定任务，或在全部有效证书中自动挑选有效期最长的一张；证书续期后自动重启加载。
 */
public final class WebAccessConfig {

    public static final String KEY_PORT = "web.port";
    public static final String KEY_HTTPS_ENABLED = "web.https.enabled";
    public static final String KEY_HTTPS_PORT = "web.https.port";
    public static final String KEY_HTTPS_REDIRECT = "web.https.redirect";
    public static final String KEY_HTTPS_CERT_TASK = "web.https.cert_task_id";
    public static final String KEY_ENTRY = "web.entry";
    /** 最近一次应用新访问配置失败的回滚提示，或启动期端口自愈/降级的告警（设置页展示，成功后清空） */
    public static final String KEY_LAST_ERROR = "web.last_error";

    private WebAccessConfig() {
    }

    /**
     * 一份完整的生效配置快照，进程内重启与回滚均以此为单位。
     *
     * @param httpPort     HTTP 端口（HTTPS 开启时仍保留，用于跳转或并存访问）
     * @param httpsEnabled 是否启用 HTTPS
     * @param httpsPort    HTTPS 端口
     * @param httpsRedirect HTTP 请求是否强制跳转 HTTPS
     * @param certTaskId   指定的证书任务 ID（0 = 自动挑选）
     * @param entry        安全入口路径（空串 = 关闭；不含首尾斜杠）
     */
    public record Snapshot(int httpPort, boolean httpsEnabled, int httpsPort,
                           boolean httpsRedirect, long certTaskId, String entry) {

        /** 将快照写回 app_config（回滚时恢复旧配置） */
        public void persist() throws SQLException {
            set(KEY_PORT, String.valueOf(httpPort));
            set(KEY_HTTPS_ENABLED, httpsEnabled ? "1" : "0");
            set(KEY_HTTPS_PORT, String.valueOf(httpsPort));
            set(KEY_HTTPS_REDIRECT, httpsRedirect ? "1" : "0");
            set(KEY_HTTPS_CERT_TASK, certTaskId > 0 ? String.valueOf(certTaskId) : "");
            set(KEY_ENTRY, entry);
        }
    }

    /** 解析出的 HTTPS 证书上下文：任务 ID、名称与证书/私钥文件 */
    public record CertInfo(long taskId, String name, Path certFile, Path keyFile, Instant notAfter) {
    }

    /** 读取生效配置（数据库优先，缺失项回退 nexhome.properties 引导默认） */
    public static Snapshot load() throws SQLException {
        int port = parseInt(Database.getConfig(KEY_PORT), AppConfig.port());
        boolean enabled = "1".equals(Database.getConfig(KEY_HTTPS_ENABLED));
        int httpsPort = parseInt(Database.getConfig(KEY_HTTPS_PORT), 8443);
        boolean redirect = "1".equals(Database.getConfig(KEY_HTTPS_REDIRECT));
        long certTaskId = parseLong(Database.getConfig(KEY_HTTPS_CERT_TASK));
        String entry = Database.getConfig(KEY_ENTRY);
        entry = entry == null ? "" : entry.trim().replaceAll("^/+|/+$", "");
        return new Snapshot(port, enabled, httpsPort, redirect, certTaskId, entry);
    }

    /** 仅供日志等轻量场景读取当前 HTTP 端口（读取失败回退引导配置） */
    public static int httpPortQuiet() {
        try {
            return load().httpPort();
        } catch (Exception e) {
            return AppConfig.port();
        }
    }

    /**
     * 校验并保存访问配置（系统设置页提交）。
     * 校验失败抛 IllegalArgumentException；HTTPS 启用时同步校验证书可解析，避免重启后降级。
     */
    public static Snapshot apply(JsonObject b) throws Exception {
        Snapshot old = load();
        int port = JsonUtils.num(b, "port", old.httpPort());
        boolean httpsEnabled = JsonUtils.bool(b, "httpsEnabled", old.httpsEnabled());
        int httpsPort = JsonUtils.num(b, "httpsPort", old.httpsPort());
        boolean redirect = JsonUtils.bool(b, "httpsRedirect", old.httpsRedirect());
        long certTaskId = parseLong(JsonUtils.str(b, "certTaskId"));
        String entry = JsonUtils.str(b, "entryPath").trim().replaceAll("^/+|/+$", "");

        if (port < 1 || port > 65535) throw new IllegalArgumentException("HTTP 端口必须在 1-65535 之间");
        if (httpsPort < 1 || httpsPort > 65535) throw new IllegalArgumentException("HTTPS 端口必须在 1-65535 之间");
        if (httpsEnabled && httpsPort == port) throw new IllegalArgumentException("HTTPS 端口不能与 HTTP 端口相同");
        if (!entry.isEmpty() && !entry.matches("[A-Za-z0-9_-]{4,64}"))
            throw new IllegalArgumentException("安全入口须为 4-64 位字母/数字/下划线/中划线，且不含 /");
        if (httpsEnabled) resolveCertFile(certTaskId); // 无可用证书直接拒绝保存

        Snapshot snap = new Snapshot(port, httpsEnabled, httpsPort, redirect, certTaskId, entry);
        snap.persist();
        set(KEY_LAST_ERROR, "");
        Logs.info(Logs.SYS, "访问配置已更新: " + describe(snap));
        return snap;
    }

    /**
     * 解析 HTTPS 使用的证书：certTaskId > 0 时用指定任务，否则在全部已签发且未过期的
     * 证书中自动挑选有效期最长的一张（自动适应已申请的证书，续期后文件原位覆盖）。
     */
    public static CertInfo resolveCertFile(long certTaskId) throws Exception {
        if (certTaskId > 0) {
            Map<String, Object> task = Database.queryOne("SELECT * FROM cert_task WHERE id=?", certTaskId);
            if (task == null) throw new IllegalArgumentException("HTTPS 证书任务不存在: #" + certTaskId);
            return certInfoOf(task);
        }
        List<Map<String, Object>> tasks = Database.query(
                "SELECT * FROM cert_task WHERE status='ISSUED' AND not_after IS NOT NULL");
        Instant now = Instant.now();
        Map<String, Object> best = null;
        Instant bestTime = null;
        for (Map<String, Object> t : tasks) {
            Instant na;
            try {
                na = Instant.parse(str(t, "not_after"));
            } catch (Exception e) {
                continue;
            }
            if (na.isBefore(now)) continue;
            if (bestTime == null || na.isAfter(bestTime)) {
                best = t;
                bestTime = na;
            }
        }
        if (best == null) throw new IllegalArgumentException(
                "未找到已签发且未过期的证书，请先在「SSL 证书管理」中申请，或指定证书任务");
        return certInfoOf(best);
    }

    /** 任务记录 -> 证书上下文（校验状态、文件完整性与 PEM 可解析性，提前暴露问题避免重启降级） */
    private static CertInfo certInfoOf(Map<String, Object> task) throws Exception {
        long id = ((Number) task.get("id")).longValue();
        if (!"ISSUED".equals(str(task, "status")))
            throw new IllegalArgumentException("证书任务 #" + id + " 尚未签发成功");
        Path dir = AppConfig.DATA_DIR.resolve("certs").resolve("task-" + id);
        Path keyFile = dir.resolve("domain.key.pem");
        Path certFile = Files.exists(dir.resolve("fullchain.pem")) ? dir.resolve("fullchain.pem") : dir.resolve("cert.pem");
        if (!Files.exists(certFile) || !Files.exists(keyFile))
            throw new IllegalArgumentException("证书任务 #" + id + " 的证书文件缺失，请重新申请");
        buildSslContext(certFile, keyFile); // 解析校验
        return new CertInfo(id, str(task, "name"), certFile, keyFile, Instant.parse(str(task, "not_after")));
    }

    /**
     * 从 PEM（PKCS8 私钥 + X.509 证书链）构建 TLS SSLContext，供 Jetty SSL 连接器使用。
     * 证书链含中间证书（fullchain.pem），浏览器信任链完整。
     */
    public static SSLContext buildSslContext(Path certFile, Path keyFile) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(pemDer(Files.readString(keyFile), "PRIVATE KEY")));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> chain = new ArrayList<>(
                cf.generateCertificates(new ByteArrayInputStream(Files.readAllBytes(certFile))));
        if (chain.isEmpty()) throw new IllegalArgumentException("证书文件中没有 X.509 证书: " + certFile);
        // 以 PKCS12 作为内存 KeyStore 载体（JDK 内置，无需额外依赖）
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("nexhome", key, "nexhome".toCharArray(), chain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "nexhome".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** PEM 文本 -> DER 字节 */
    private static byte[] pemDer(String text, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int s = text.indexOf(begin), e = text.indexOf(end);
        if (s < 0 || e < 0) throw new IllegalArgumentException("PEM 格式错误，缺少 " + type + " 段: " + type);
        String b64 = text.substring(s + begin.length(), e).replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }

    /** 设置页视图：当前配置 + HTTPS 实际生效状态 + 最近一次重启回滚提示 */
    public static Map<String, Object> apiView() throws SQLException {
        Snapshot s = load();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("port", s.httpPort());
        // 实际监听的 HTTP 端口：启动期端口自愈（期望端口被占用）时与上面的配置值不一致
        m.put("boundPort", WebServer.httpPort());
        m.put("httpsEnabled", s.httpsEnabled());
        m.put("httpsPort", s.httpsPort());
        m.put("httpsRedirect", s.httpsRedirect());
        m.put("certTaskId", s.certTaskId());
        m.put("entryPath", s.entry());
        m.put("lastError", str_cfg(KEY_LAST_ERROR));
        long active = WebServer.activeCertTaskId();
        m.put("httpsActive", WebServer.httpsActive());
        m.put("activeCertTaskId", active);
        if (active > 0) {
            try {
                Map<String, Object> t = Database.queryOne("SELECT name, not_after FROM cert_task WHERE id=?", active);
                if (t != null) {
                    m.put("activeCertName", str(t, "name"));
                    m.put("activeCertNotAfter", str(t, "not_after"));
                }
            } catch (Exception ignored) {
                // 状态展示为附加信息，读取失败不阻塞设置页
            }
        }
        return m;
    }

    /** 配置摘要（用于日志） */
    private static String describe(Snapshot s) {
        StringBuilder sb = new StringBuilder("HTTP:").append(s.httpPort());
        if (s.httpsEnabled()) {
            sb.append(" HTTPS:").append(s.httpsPort());
            if (s.httpsRedirect()) sb.append("(强制跳转)");
        }
        if (!s.entry().isEmpty()) sb.append(" 安全入口:/").append(s.entry());
        return sb.toString();
    }

    private static void set(String key, String value) throws SQLException {
        Database.setConfig(key, value);
    }

    private static String str_cfg(String key) throws SQLException {
        String v = Database.getConfig(key);
        return v == null ? "" : v;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
