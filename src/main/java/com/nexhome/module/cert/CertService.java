package com.nexhome.module.cert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nexhome.core.AppConfig;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;
import com.nexhome.module.ddns.AliyunApiException;
import com.nexhome.module.ddns.AliyunClient;
import com.nexhome.module.provider.ProviderConfigService;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.util.CSRBuilder;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSL 证书申请与自动续期服务（ACME 协议，基于 acme4j）。
 * <p>
 * 服务商：Let's Encrypt（无需额外凭证）与 ZeroSSL（需要在其控制台生成
 * EAB 凭证：EAB KID 与 EAB HMAC Key，在"设置"页配置，存于 app_config 表）。
 * <p>
 * 域名验证方式：
 * <ul>
 *   <li>HTTP01：全自动。CA 会访问 {@code http://域名/.well-known/acme-challenge/token}，
 *       由本程序内置 Web 服务器应答，因此要求本服务 80 端口可被公网访问（或路由器 80 端口转发到本服务端口）。</li>
 *   <li>DNS01：引用服务商凭证配置时全自动写入 TXT（支持云解析与 ESA 托管的域名）；
 *       未引用时为半自动——程序生成需要添加的 DNS TXT 记录，用户在域名服务商处添加后点击"完成验证"。</li>
 * </ul>
 * 证书文件保存在 {@code data/certs/task-{id}/}：私钥、证书、完整证书链（PEM）。
 */
public final class CertService {

    /** ACME 服务商目录地址 */
    private static final Map<String, String> PROVIDERS = Map.of(
            "LETSENCRYPT", "acme://letsencrypt.org",
            "ZEROSSL", "acme://zerossl.com");

    /** http-01 校验：token -> keyAuthorization（由内置 Web 服务器对外应答） */
    private static final Map<String, String> CHALLENGES = new ConcurrentHashMap<>();

    /** 续期提前天数：到期前 21 天自动续期 */
    private static final int RENEW_AHEAD_DAYS = 21;

    /** 默认证书保存目录（与 Docker 部署的数据目录一致）：任务未填写 save_dir 时使用，不存在时自动新建 */
    public static final String DEFAULT_SAVE_DIR = "/app/data/ssl";

    /** 证书同步 webhook 使用的共享 HTTP 客户端 */
    private static final HttpClient WEBHOOK_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private CertService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/cert/tasks", ctx -> ctx.ok(Database.query(
                "SELECT * FROM cert_task ORDER BY id")));
        WebServer.route("POST", "/api/cert/tasks", CertService::create);
        WebServer.route("PUT", "/api/cert/tasks/{id}", CertService::update);
        WebServer.route("DELETE", "/api/cert/tasks/{id}", CertService::delete);
        // 发起申请/续期
        WebServer.route("POST", "/api/cert/tasks/{id}/issue", ctx -> {
            long id = ctx.paramLong("id");
            mustGet(id);
            Logs.info(Logs.CERT, "手动发起证书申请 #" + id);
            Tasks.run(() -> issue(id, "手动申请"));
            ctx.ok("申请已发起，请刷新列表查看进度");
        });
        // DNS01 手动模式：用户添加 TXT 记录后点击"完成验证"
        WebServer.route("POST", "/api/cert/tasks/{id}/validate", ctx -> {
            long id = ctx.paramLong("id");
            mustGet(id);
            Tasks.run(() -> validateDns(id));
            ctx.ok("已提交验证，等待 CA 校验 DNS 记录");
        });
        // 证书详情（有效期/域名/指纹等）
        WebServer.route("GET", "/api/cert/tasks/{id}/detail", ctx -> ctx.ok(certDetail(ctx.paramLong("id"))));
        // 证书文件下载：?file=key|cert|fullchain
        WebServer.route("GET", "/api/cert/tasks/{id}/download", ctx -> {
            long id = ctx.paramLong("id");
            Map<String, Object> task = mustGet(id);
            String file = ctx.query("file");
            Path p = switch (file == null ? "" : file) {
                case "key" -> taskDir(id).resolve("domain.key.pem");
                case "cert" -> taskDir(id).resolve("cert.pem");
                case "fullchain" -> taskDir(id).resolve("fullchain.pem");
                default -> throw new IllegalArgumentException("不支持的文件类型: " + file);
            };
            if (!Files.exists(p)) throw new IllegalArgumentException("文件尚未生成，请先成功申请证书");
            ctx.file(p, task.get("name") + "-" + file + ".pem");
            Logs.info(Logs.CERT, "下载证书文件: 任务#" + id + " " + file);
        });
        // 服务商凭证设置（ZeroSSL EAB / 联系邮箱）；default_save_dir 供前端新增任务时预填默认保存目录
        WebServer.route("GET", "/api/cert/settings", ctx -> ctx.ok(Map.of(
                "email", cfg("acme.email", ""),
                "zerossl_eab_kid", cfg("zerossl.eab_kid", ""),
                "zerossl_eab_hmac", cfg("zerossl.eab_hmac", ""),
                "default_save_dir", DEFAULT_SAVE_DIR)));
        WebServer.route("PUT", "/api/cert/settings", ctx -> {
            JsonObject b = ctx.body();
            Database.setConfig("acme.email", JsonUtils.str(b, "email"));
            Database.setConfig("zerossl.eab_kid", JsonUtils.str(b, "zerossl_eab_kid"));
            Database.setConfig("zerossl.eab_hmac", JsonUtils.str(b, "zerossl_eab_hmac"));
            Logs.info(Logs.CERT, "更新证书服务设置（邮箱/ZeroSSL EAB）");
            ctx.ok("已保存");
        });
    }

    /** 启动初始化：注册 BouncyCastle 提供者 + 启动自动续期检查 */
    public static void init() throws SQLException {
        Security.addProvider(new BouncyCastleProvider());
        ensureColumn("provider_config_id", "INTEGER"); // 旧库升级：DNS01 自动验证引用凭证配置
        ensureColumn("save_dir", "TEXT");             // 旧库升级：可选的证书保存目录
        ensureColumn("webhook_url", "TEXT");          // 旧库升级：可选的证书同步 webhook
        // 重启恢复：ISSUING 是纯内存的在途状态，进程重启后不会有线程继续。
        // 若残留说明上次签发被中断（容器重启/异常退出/Error 绕过捕获），置为 ERROR 提示重新申请，
        // 避免前端永久停留在“正在签发证书...”的假象。
        int stale = Database.update(
                "UPDATE cert_task SET status='ERROR', message=? WHERE status='ISSUING'",
                "上次签发未完成（程序可能已重启或异常退出），请重新点击「申请」");
        if (stale > 0) Logs.warn(Logs.CERT, "已重置 " + stale + " 个中断的证书签发任务（ISSUING → ERROR）");
        // 每小时检查一次证书有效期
        Tasks.every(60, 3600, CertService::autoRenewCheck);
        Logs.info(Logs.CERT, "证书自动续期检查已启动（每小时，到期前 " + RENEW_AHEAD_DAYS + " 天续期）");
    }

    /** 旧库升级：补充缺失列（新库建表脚本已包含） */
    private static void ensureColumn(String name, String type) throws SQLException {
        boolean exists = Database.query("PRAGMA table_info(cert_task)").stream()
                .anyMatch(col -> name.equals(col.get("name")));
        if (!exists) {
            Database.update("ALTER TABLE cert_task ADD COLUMN " + name + " " + type);
        }
    }

    /** 供 WebServer 分发 /.well-known/acme-challenge/{token}（安全入口模式下兼容带前缀的路径） */
    public static void serveChallenge(Ctx ctx) throws Exception {
        String marker = "/.well-known/acme-challenge/";
        int i = ctx.path().indexOf(marker);
        String token = i < 0 ? ctx.path().substring(ctx.path().lastIndexOf('/') + 1)
                : ctx.path().substring(i + marker.length());
        String auth = CHALLENGES.get(token);
        if (auth == null) {
            ctx.text(404, "challenge not found", "text/plain");
        } else {
            ctx.text(200, auth, "text/plain");
        }
    }

    // ---------- 增删改 ----------

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        validate(b);
        long id = Database.insert("""
                INSERT INTO cert_task(name, provider, domains, challenge_type, provider_config_id, auto_renew, save_dir, webhook_url)
                VALUES(?,?,?,?,?,?,?,?)""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "provider"),
                normalizeDomains(JsonUtils.str(b, "domains")),
                JsonUtils.str(b, "challenge_type"),
                ProviderConfigService.parseId(b, "provider_config_id"),
                JsonUtils.bool(b, "auto_renew", true) ? 1 : 0,
                resolveSaveDir(b),
                JsonUtils.str(b, "webhook_url").trim());
        Logs.info(Logs.CERT, "新增证书任务: " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        mustGet(id);
        JsonObject b = ctx.body();
        validate(b);
        Database.update("""
                UPDATE cert_task SET name=?, provider=?, domains=?, challenge_type=?, provider_config_id=?, auto_renew=?, save_dir=?, webhook_url=? WHERE id=?""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "provider"),
                normalizeDomains(JsonUtils.str(b, "domains")),
                JsonUtils.str(b, "challenge_type"),
                ProviderConfigService.parseId(b, "provider_config_id"),
                JsonUtils.bool(b, "auto_renew", true) ? 1 : 0,
                resolveSaveDir(b),
                JsonUtils.str(b, "webhook_url").trim(), id);
        Logs.info(Logs.CERT, "更新证书任务 #" + id + ": " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> task = mustGet(id);
        Database.update("DELETE FROM cert_task WHERE id=?", id);
        // 同时清理磁盘上的证书文件
        Path dir = taskDir(id);
        if (Files.exists(dir)) {
            try (var stream = Files.list(dir)) {
                stream.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
        Logs.info(Logs.CERT, "删除证书任务 #" + id + ": " + task.get("name"));
        ctx.ok("已删除");
    }

    private static void validate(JsonObject b) {
        if (JsonUtils.str(b, "name").isBlank()) throw new IllegalArgumentException("任务名称不能为空");
        String provider = JsonUtils.str(b, "provider");
        if (!PROVIDERS.containsKey(provider)) throw new IllegalArgumentException("服务商必须为 LETSENCRYPT 或 ZEROSSL");
        String ct = JsonUtils.str(b, "challenge_type");
        if (!"HTTP01".equals(ct) && !"DNS01".equals(ct)) throw new IllegalArgumentException("验证方式必须为 HTTP01 或 DNS01");
        List<String> domains = domainList(JsonUtils.str(b, "domains"));
        if (domains.isEmpty()) throw new IllegalArgumentException("域名不能为空");
        for (String d : domains) {
            if (!d.matches("(\\*\\.)?[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+")) {
                throw new IllegalArgumentException("域名格式不正确: " + d);
            }
        }
        String webhook = JsonUtils.str(b, "webhook_url").trim();
        if (!webhook.isEmpty() && !webhook.startsWith("http://") && !webhook.startsWith("https://")) {
            throw new IllegalArgumentException("Webhook 地址必须以 http:// 或 https:// 开头");
        }
    }

    // ---------- ACME 申请主流程 ----------

    /**
     * 发起证书申请/续期。
     * HTTP01：全自动完成；DNS01：创建订单后写入 TXT 提示，等待用户添加记录再调用 {@link #validateDns(long)}。
     */
    public static void issue(long taskId, String trigger) {
        try {
            Map<String, Object> task = mustGet(taskId);
            String name = str(task, "name");
            String provider = str(task, "provider");
            String challengeType = str(task, "challenge_type");
            List<String> domains = domainList(str(task, "domains"));
            setStatus(taskId, "ISSUING", trigger + "：正在创建订单...", null);
            Logs.info(Logs.CERT, "任务[" + name + "] 发起证书申请(" + provider + "/" + challengeType + "): " + domains);

            Session session = new Session(PROVIDERS.get(provider));
            Account account = getOrCreateAccount(session, provider);
            Order order = account.newOrder().domains(domains).create();
            Database.setConfig("cert." + taskId + ".order", order.getLocation().toString());

            if ("HTTP01".equals(challengeType)) {
                // 以实际监听端口为准（启动期端口自愈后可能与配置值不同），否则转发提示会误导
                int httpPort = WebServer.httpPort();
                if (httpPort != 80) {
                    Logs.warn(Logs.CERT, "任务[" + name + "] 当前服务端口非 80，http-01 验证需保证 CA 能访问 80 端口" +
                            "（可配置路由器端口转发 80 -> " + httpPort + "）");
                }
                CHALLENGES.clear();
                for (Authorization auth : order.getAuthorizations()) {
                    if (auth.getStatus() == Status.VALID) continue;
                    Http01Challenge ch = auth.findChallenge(Http01Challenge.class)
                            .orElseThrow(() -> new IllegalStateException("CA 未提供 http-01 验证方式"));
                    // 先注册应答内容，再通知 CA 来校验
                    CHALLENGES.put(ch.getToken(), ch.getAuthorization());
                    ch.trigger();
                    Logs.info(Logs.CERT, "任务[" + name + "] http-01 已应答: "
                            + auth.getIdentifier().getDomain() + "/.well-known/acme-challenge/" + ch.getToken());
                }
                waitAuthorizations(order, Duration.ofSeconds(120));
                finalizeAndDownload(taskId, task, order, domains);
            } else {
                // DNS01：引用服务商凭证配置时全自动（写入 DNS TXT -> 触发校验 -> 签发后清理），
                // 未引用时收集提示等待用户手动添加 TXT 后点「完成验证」
                DnsCred cred = dnsCredentials(task);
                List<TxtRecord> addedTxt = new ArrayList<>();
                try {
                    if (cred != null) {
                        for (Authorization auth : order.getAuthorizations()) {
                            if (auth.getStatus() == Status.VALID) continue;
                            Dns01Challenge ch = auth.findChallenge(Dns01Challenge.class)
                                    .orElseThrow(() -> new IllegalStateException("CA 未提供 dns-01 验证方式"));
                            String domain = auth.getIdentifier().getDomain();
                            TxtRecord txt = autoApplyTxt(cred, domain, ch.getDigest());
                            addedTxt.add(txt);
                            ch.trigger();
                            Logs.info(Logs.CERT, "任务[" + name + "] dns-01 已自动写入"
                                    + txt.channelName() + " TXT: _acme-challenge." + domain);
                        }
                        waitAuthorizations(order, Duration.ofSeconds(180));
                        finalizeAndDownload(taskId, task, order, domains);
                    } else {
                        StringBuilder hint = new StringBuilder();
                        for (Authorization auth : order.getAuthorizations()) {
                            if (auth.getStatus() == Status.VALID) continue;
                            Dns01Challenge ch = auth.findChallenge(Dns01Challenge.class)
                                    .orElseThrow(() -> new IllegalStateException("CA 未提供 dns-01 验证方式"));
                            String domain = auth.getIdentifier().getDomain();
                            hint.append("记录类型: TXT\n主机记录: _acme-challenge.")
                                    .append(auth.isWildcard() ? domain : domain)
                                    .append("\n记录值: ").append(ch.getDigest()).append("\n\n");
                        }
                        Database.update("UPDATE cert_task SET status='PENDING_VALIDATION', dns_hint=?, message=? WHERE id=?",
                                hint.toString(),
                                "请前往域名服务商添加以上 TXT 记录（等待生效后）点击\"完成验证\"", taskId);
                        Logs.info(Logs.CERT, "任务[" + name + "] 等待用户添加 DNS TXT 记录");
                    }
                } finally {
                    cleanupTxt(cred, addedTxt); // 验证完成/失败后均清理自动写入的 TXT
                }
            }
        } catch (Throwable e) {
            // 捕获 Throwable（含 Error）：签发链路任何未预期错误都要落库为 ERROR，
            // 否则任务会永久卡在 ISSUING（如 NoClassDefFoundError、OOM 等）
            fail(taskId, trigger + "失败: " + e);
        }
    }

    /** DNS01 第二步：用户添加 TXT 记录后触发验证并完成签发 */
    public static void validateDns(long taskId) {
        try {
            Map<String, Object> task = mustGet(taskId);
            setStatus(taskId, "ISSUING", "正在请求 CA 校验 DNS 记录...", null);
            Login login = bindLogin(task);
            String orderUrl = Database.getConfig("cert." + taskId + ".order");
            if (orderUrl == null) throw new IllegalStateException("订单不存在，请先发起申请");
            Order order = login.bindOrder(URI.create(orderUrl).toURL());

            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() == Status.VALID) continue;
                auth.findChallenge(Dns01Challenge.class).ifPresent(ch -> {
                    try {
                        ch.trigger(); // 通知 CA 开始校验 TXT 记录
                    } catch (Exception e) {
                        throw new IllegalStateException("触发验证失败: " + e.getMessage(), e);
                    }
                });
            }
            waitAuthorizations(order, Duration.ofSeconds(180));
            finalizeAndDownload(taskId, task, order, domainList(str(task, "domains")));
        } catch (Throwable e) {
            fail(taskId, "DNS验证失败: " + e);
        }
    }

    /** 等待全部授权完成 */
    private static void waitAuthorizations(Order order, Duration timeout) throws Exception {
        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) continue;
            Status status = auth.waitForCompletion(timeout);
            if (status != Status.VALID) {
                throw new IllegalStateException("域名 " + auth.getIdentifier().getDomain()
                        + " 验证未通过(" + status + ")，请检查验证配置后重试");
            }
        }
    }

    /** 生成私钥与 CSR，提交签发并保存证书文件 */
    private static void finalizeAndDownload(long taskId, Map<String, Object> task,
                                            Order order, List<String> domains) throws Exception {
        setStatus(taskId, "ISSUING", "验证通过，正在签发证书...", null);
        Path dir = taskDir(taskId);
        Files.createDirectories(dir);

        // 域名私钥：RSA 2048（兼容性最好）
        KeyPair domainKeyPair = generateRsaKeyPair();
        byte[] keyDer = domainKeyPair.getPrivate().getEncoded();
        String keyPem = pem("PRIVATE KEY", keyDer);
        Files.writeString(dir.resolve("domain.key.pem"), keyPem);

        // 构造 CSR 并提交订单
        CSRBuilder csrb = new CSRBuilder();
        csrb.addDomains(domains);
        csrb.sign(domainKeyPair);
        order.execute(csrb.getEncoded());
        order.waitForCompletion(Duration.ofSeconds(60));
        if (order.getStatus() != Status.VALID) {
            throw new IllegalStateException("签发失败，订单状态: " + order.getStatus());
        }

        // 保存证书与完整证书链
        Certificate cert = order.getCertificate();
        X509Certificate leaf = cert.getCertificate();
        byte[] leafDer = leaf.getEncoded();
        String certPem = pem("CERTIFICATE", leafDer);
        Files.writeString(dir.resolve("cert.pem"), certPem);
        StringBuilder chain = new StringBuilder();
        for (X509Certificate c : cert.getCertificateChain()) {
            chain.append(pem("CERTIFICATE", c.getEncoded()));
        }
        String fullchainPem = chain.toString();
        Files.writeString(dir.resolve("fullchain.pem"), fullchainPem);

        Database.update("""
                UPDATE cert_task SET status='ISSUED', message=?, dns_hint=NULL, not_after=?, cert_dir=? WHERE id=?""",
                "证书签发成功", leaf.getNotAfter().toInstant().toString(), dir.toString(), taskId);
        Logs.info(Logs.CERT, "任务[" + task.get("name") + "] 证书签发成功，域名: " + domains
                + "，有效期至 " + leaf.getNotAfter());

        // 可选：按任务配置同步到自定义保存目录、推送到 webhook（任一失败不影响已签发证书，仅记日志）
        saveToCustomDir(str(task, "save_dir"), domains, keyDer, leafDer, fullchainPem);
        pushWebhook(str(task, "webhook_url"), task, domains, keyPem, certPem, fullchainPem,
                leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant());
        // HTTPS 正在使用该证书时自动重启 Web 服务，加载续期后的新证书
        WebServer.onCertRenewed(taskId);
    }

    /** 定时自动续期：到期前 RENEW_AHEAD_DAYS 天自动重新申请 */
    public static void autoRenewCheck() {
        try {
            for (Map<String, Object> task : Database.query(
                    "SELECT * FROM cert_task WHERE auto_renew=1 AND status='ISSUED' AND not_after IS NOT NULL")) {
                Instant notAfter = Instant.parse(str(task, "not_after"));
                if (notAfter.isBefore(Instant.now().plus(Duration.ofDays(RENEW_AHEAD_DAYS)))) {
                    Logs.info(Logs.CERT, "任务[" + task.get("name") + "] 即将到期(" + notAfter + ")，自动续期");
                    issue(((Number) task.get("id")).longValue(), "自动续期");
                }
            }
        } catch (Exception e) {
            Logs.error(Logs.CERT, "自动续期检查异常: " + e.getMessage());
        }
    }

    // ---------- 证书详情 ----------

    /** 读取已签发证书的详细信息 */
    private static Map<String, Object> certDetail(long taskId) throws Exception {
        Map<String, Object> task = mustGet(taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task);
        Path certFile = taskDir(taskId).resolve("cert.pem");
        if (!Files.exists(certFile)) {
            result.put("hasCert", false);
            return result;
        }
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile)));
        List<String> sans = new ArrayList<>();
        if (cert.getSubjectAlternativeNames() != null) {
            for (List<?> san : cert.getSubjectAlternativeNames()) {
                if (san.size() >= 2 && (Integer) san.get(0) == 2) sans.add(String.valueOf(san.get(1)));
            }
        }
        result.put("hasCert", true);
        result.put("subject", cert.getSubjectX500Principal().getName());
        result.put("issuer", cert.getIssuerX500Principal().getName());
        result.put("domains", sans);
        result.put("notBefore", cert.getNotBefore().toInstant().toString());
        result.put("notAfter", cert.getNotAfter().toInstant().toString());
        result.put("serial", cert.getSerialNumber().toString(16));
        result.put("sha256", hexSha256(cert.getEncoded()));
        return result;
    }

    // ---------- DNS01 自动验证（引用服务商凭证配置） ----------

    /** DNS01 自动验证凭证上下文：阿里云 AK/SK + 凭证配置中可选的 ESA 站点（域名托管在 ESA 时降级使用） */
    private record DnsCred(String ak, String sk, String esaSiteId) {
    }

    /** 自动写入的 TXT 记录：channel 标识写入通道（DNS=云解析 / ESA=边缘安全加速），验证后按原通道删除 */
    private record TxtRecord(String channel, String recordId) {
        String channelName() {
            return "ESA".equals(channel) ? "阿里云ESA" : "阿里云云解析";
        }
    }

    /** 任务引用的服务商凭证（DNS01 自动模式）；未引用配置返回 null */
    private static DnsCred dnsCredentials(Map<String, Object> task) throws SQLException {
        if (!(task.get("provider_config_id") instanceof Number n)) return null;
        long cfgId = n.longValue();
        String[] cred = ProviderConfigService.credentials(cfgId);
        return new DnsCred(cred[0], cred[1], ProviderConfigService.esaSiteId(cfgId));
    }

    /**
     * 向阿里云写入 dns-01 TXT 记录并返回写入结果，供验证结束后按原通道清理。
     * <p>
     * 授权域可能是主域或其子域（泛域名授权的 identifier 为主域），而云解析接口的
     * DomainName 参数必须是账户内的主域：优先整域探测，失败逐级剥离左段重试（最多三层）。
     * 已存在相同值的 TXT 直接复用（如上次失败残留），避免重复添加。
     * 云解析全部探测失败且凭证配置填写了 ESA 站点时，说明域名解析托管在 ESA，降级改走 ESA 记录接口。
     */
    private static TxtRecord autoApplyTxt(DnsCred cred, String domain, String value) throws Exception {
        String[] parts = domain.split("\\.");
        int maxStrip = Math.min(2, parts.length - 2); // 主域至少保留两段
        AliyunApiException last = null;
        for (int strip = 0; strip <= maxStrip; strip++) {
            String zone = String.join(".", List.of(parts).subList(strip, parts.length));
            String rr = strip == 0 ? "_acme-challenge"
                    : "_acme-challenge." + String.join(".", List.of(parts).subList(0, strip));
            try {
                for (var el : AliyunClient.dnsDescribeRecords(cred.ak(), cred.sk(), zone, "_acme-challenge")) {
                    JsonObject r = el.getAsJsonObject();
                    if (r.get("RR").getAsString().equalsIgnoreCase(rr)
                            && "TXT".equalsIgnoreCase(r.get("Type").getAsString())
                            && value.equals(r.get("Value").getAsString())) {
                        return new TxtRecord("DNS", null); // 已有相同值记录，无需重复添加
                    }
                }
                return new TxtRecord("DNS", AliyunClient.dnsAddRecord(cred.ak(), cred.sk(), zone, rr, "TXT", value, 600));
            } catch (AliyunApiException e) {
                last = e; // 域名不在当前账户/不存在：剥离左段继续探测主域
            }
        }
        if (!cred.esaSiteId().isBlank()) {
            return esaApplyTxt(cred, domain, value);
        }
        throw new IllegalStateException("自动写入 TXT 失败（无法定位 " + domain
                + " 的主域；若域名解析托管在阿里云 ESA，请在所引用的凭证配置中填写 ESA 站点 SiteId）: "
                + (last == null ? "" : last.getMessage()), last);
    }

    /** ESA 托管域名：向站点写入 _acme-challenge TXT（ESA 记录名为完整记录名） */
    private static TxtRecord esaApplyTxt(DnsCred cred, String domain, String value) throws Exception {
        String fullName = "_acme-challenge." + domain;
        try {
            for (var el : AliyunClient.esaListRecords(cred.ak(), cred.sk(), cred.esaSiteId(), fullName)) {
                JsonObject r = el.getAsJsonObject();
                if ("TXT".equalsIgnoreCase(r.get("Type").getAsString())
                        && r.has("Data") && r.get("Data").isJsonObject()
                        && value.equals(r.getAsJsonObject("Data").get("Value").getAsString())) {
                    return new TxtRecord("ESA", null); // 已有相同值记录，无需重复添加
                }
            }
            return new TxtRecord("ESA", AliyunClient.esaCreateRecord(
                    cred.ak(), cred.sk(), cred.esaSiteId(), fullName, "TXT", value, 600));
        } catch (Exception e) {
            throw new IllegalStateException("ESA 写入 TXT 失败（请检查凭证配置的 ESA 站点 SiteId 是否为 "
                    + domain + " 所在站点）: " + e.getMessage(), e);
        }
    }

    /** 验证结束后清理自动写入的 TXT 记录（CA 已缓存验证结果，删除不影响签发；失败仅记日志） */
    private static void cleanupTxt(DnsCred cred, List<TxtRecord> addedTxt) {
        for (TxtRecord t : addedTxt) {
            if (t.recordId() == null) continue; // 复用的已有记录，无需清理
            try {
                if ("ESA".equals(t.channel())) {
                    AliyunClient.esaDeleteRecord(cred.ak(), cred.sk(), t.recordId());
                } else {
                    AliyunClient.dnsDeleteRecord(cred.ak(), cred.sk(), t.recordId());
                }
            } catch (Exception e) {
                Logs.warn(Logs.CERT, "清理 dns-01 TXT 记录失败(" + t.channel() + " RecordId=" + t.recordId() + "): " + e.getMessage());
            }
        }
    }

    // ---------- ACME 账号管理 ----------

    /** 获取或创建 ACME 账号（按服务商独立，密钥持久化在 data/certs/acme/ 下） */
    private static Account getOrCreateAccount(Session session, String provider) throws Exception {
        Path dir = AppConfig.DATA_DIR.resolve("certs").resolve("acme").resolve(provider.toLowerCase());
        Files.createDirectories(dir);
        Path keyFile = dir.resolve("account.pem");
        String locKey = "cert.account." + provider;

        if (Files.exists(keyFile)) {
            KeyPair kp = readKeyPair(keyFile);
            String loc = Database.getConfig(locKey);
            if (loc != null) {
                // 已有账号，直接绑定
                return new Login(URI.create(loc).toURL(), kp, session).getAccount();
            }
            // 密钥在但账号位置丢失：尝试按已有密钥找回
            AccountBuilder b = new AccountBuilder().agreeToTermsOfService().useKeyPair(kp).onlyExisting();
            applyEab(b, provider);
            Account a = b.create(session);
            Database.setConfig(locKey, a.getLocation().toString());
            return a;
        }

        // 首次注册新账号
        KeyPair kp = generateRsaKeyPair();
        AccountBuilder b = new AccountBuilder().agreeToTermsOfService().useKeyPair(kp);
        String email = Database.getConfig("acme.email");
        if (email != null && !email.isBlank()) {
            b.addEmail(email);
        }
        applyEab(b, provider);
        Account a = b.create(session);
        writePem(keyFile, "PRIVATE KEY", kp.getPrivate().getEncoded());
        Database.setConfig(locKey, a.getLocation().toString());
        Logs.info(Logs.CERT, "已注册 " + provider + " ACME 账号");
        return a;
    }

    /** ZeroSSL 需要外部账号绑定（EAB），凭证在"设置"中配置 */
    private static void applyEab(AccountBuilder b, String provider) throws SQLException {
        if (!"ZEROSSL".equals(provider)) return;
        String kid = Database.getConfig("zerossl.eab_kid");
        String hmac = Database.getConfig("zerossl.eab_hmac");
        if (kid == null || kid.isBlank() || hmac == null || hmac.isBlank()) {
            throw new IllegalArgumentException("ZeroSSL 需要 EAB 凭证：请先在 设置 -> 证书设置 中填写 EAB KID 与 EAB HMAC Key" +
                    "（ZeroSSL 控制台 Developer 页面生成）");
        }
        b.withKeyIdentifier(kid, hmac);
    }

    /** 按任务保存的账号与订单信息重建登录会话 */
    private static Login bindLogin(Map<String, Object> task) throws Exception {
        String provider = str(task, "provider");
        Session session = new Session(PROVIDERS.get(provider));
        Path keyFile = AppConfig.DATA_DIR.resolve("certs").resolve("acme")
                .resolve(provider.toLowerCase()).resolve("account.pem");
        if (!Files.exists(keyFile)) throw new IllegalStateException("ACME 账号不存在，请先发起一次申请");
        KeyPair kp = readKeyPair(keyFile);
        String loc = Database.getConfig("cert.account." + provider);
        if (loc == null) throw new IllegalStateException("ACME 账号位置丢失，请重新申请");
        return new Login(URI.create(loc).toURL(), kp, session);
    }

    // ---------- 密钥与工具 ----------

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    /** 从 PKCS8 私钥 PEM 恢复密钥对（RSA CRT 可推导出公钥） */
    private static KeyPair readKeyPair(Path file) throws Exception {
        byte[] der = parsePem(file, "PRIVATE KEY");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
        PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        return new KeyPair(pub, priv);
    }

    private static void writePem(Path file, String type, byte[] der) throws Exception {
        Files.writeString(file, pem(type, der));
    }

    /** DER 编码转 PEM 文本（64 字符折行） */
    private static String pem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    private static byte[] parsePem(Path file, String type) throws Exception {
        String text = Files.readString(file);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int s = text.indexOf(begin), e = text.indexOf(end);
        if (s < 0 || e < 0) throw new IllegalStateException("PEM 格式错误: " + file);
        String b64 = text.substring(s + begin.length(), e).replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }

    private static String hexSha256(byte[] data) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 域名串解析：支持逗号/分号/空白分隔 */
    static List<String> domainList(String domains) {
        List<String> list = new ArrayList<>();
        for (String d : domains.split("[,;\\s]+")) {
            if (!d.isBlank()) list.add(d.trim().toLowerCase());
        }
        return list;
    }

    private static String normalizeDomains(String domains) {
        return String.join(",", domainList(domains));
    }

    private static Path taskDir(long id) {
        return AppConfig.DATA_DIR.resolve("certs").resolve("task-" + id);
    }

    /** 主域名（作为保存文件名）：取第一个域名，泛域名去除 *. 前缀，非安全字符替换为下划线 */
    static String primaryDomain(List<String> domains) {
        String d = domains.isEmpty() ? "cert" : domains.get(0);
        if (d.startsWith("*.")) d = d.substring(2);
        return d.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    /** 解析任务保存目录：留空时回退到默认目录 {@link #DEFAULT_SAVE_DIR} */
    private static String resolveSaveDir(JsonObject b) {
        String dir = JsonUtils.str(b, "save_dir").trim();
        return dir.isEmpty() ? DEFAULT_SAVE_DIR : dir;
    }

    /**
     * 将证书以主域名作为文件名写入保存目录（save_dir）。
     * 生成 {domain}.key / {domain}.pem / {domain}.fullchain.pem 三个文件；目录不存在时自动新建。
     * save_dir 为空时回退到默认目录 {@link #DEFAULT_SAVE_DIR}。
     * 失败不阻断签发主流程（文件已存于默认任务目录），仅记日志。
     */
    private static void saveToCustomDir(String saveDir, List<String> domains, byte[] keyDer, byte[] leafDer, String fullchainPem) {
        if (saveDir == null || saveDir.isBlank()) saveDir = DEFAULT_SAVE_DIR;
        String base = primaryDomain(domains);
        try {
            Path dir = Path.of(saveDir.trim());
            Files.createDirectories(dir);
            writePem(dir.resolve(base + ".key"), "PRIVATE KEY", keyDer);
            writePem(dir.resolve(base + ".pem"), "CERTIFICATE", leafDer);
            Files.writeString(dir.resolve(base + ".fullchain.pem"), fullchainPem);
            Logs.info(Logs.CERT, "证书已保存到自定义目录: " + dir + "/" + base + ".*");
        } catch (Exception e) {
            Logs.warn(Logs.CERT, "保存证书到自定义目录失败(" + saveDir + "): " + e.getMessage());
        }
    }

    /**
     * 可选：签发成功后将证书完整内容（证书/私钥/证书链 PEM）POST 推送到 webhook，用于同步到其他系统。
     * 失败不阻断签发主流程，仅记日志。
     */
    private static void pushWebhook(String url, Map<String, Object> task, List<String> domains,
                                    String keyPem, String certPem, String fullchainPem,
                                    Instant notBefore, Instant notAfter) {
        if (url == null || url.isBlank()) return;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("event", "certificate.issued");
            payload.addProperty("taskId", str(task, "id"));
            payload.addProperty("name", str(task, "name"));
            JsonArray arr = new JsonArray();
            domains.forEach(arr::add);
            payload.add("domains", arr);
            payload.addProperty("notBefore", notBefore.toString());
            payload.addProperty("notAfter", notAfter.toString());
            payload.addProperty("keyPem", keyPem);
            payload.addProperty("certPem", certPem);
            payload.addProperty("fullchainPem", fullchainPem);
            String json = JsonUtils.GSON.toJson(payload);

            HttpRequest req = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = WEBHOOK_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                Logs.info(Logs.CERT, "证书已推送 webhook: " + url + " (HTTP " + resp.statusCode() + ")");
            } else {
                Logs.warn(Logs.CERT, "推送 webhook 返回非 2xx: HTTP " + resp.statusCode()
                        + " " + truncate(resp.body()));
            }
        } catch (Exception e) {
            Logs.warn(Logs.CERT, "推送 webhook 失败(" + url + "): " + e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static void setStatus(long taskId, String status, String message, String notAfter) {
        try {
            if (notAfter == null) {
                Database.update("UPDATE cert_task SET status=?, message=? WHERE id=?", status, message, taskId);
            } else {
                Database.update("UPDATE cert_task SET status=?, message=?, not_after=? WHERE id=?",
                        status, message, notAfter, taskId);
            }
        } catch (Exception e) {
            Logs.error(Logs.CERT, "更新证书任务状态失败: " + e.getMessage());
        }
    }

    private static void fail(long taskId, String message) {
        setStatus(taskId, "ERROR", message, null);
        Logs.error(Logs.CERT, "任务 #" + taskId + ": " + message);
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> task = Database.queryOne("SELECT * FROM cert_task WHERE id=?", id);
        if (task == null) throw new IllegalArgumentException("证书任务不存在: #" + id);
        return task;
    }

    private static String cfg(String key, String def) {
        try {
            String v = Database.getConfig(key);
            return v == null ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
