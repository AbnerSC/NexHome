package com.nexhome.web;

import com.nexhome.auth.AuthService;
import com.nexhome.core.AppConfig;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;
import com.nexhome.module.cert.CertService;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpResponseException;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置 Web 服务器（基于 Javalin）。
 * <p>
 * 单端口同时提供：
 * <ul>
 *   <li>/api/** REST 接口（JSON，除登录/登录检查外需 X-Token 鉴权）</li>
 *   <li>前端静态资源：开发环境直接读 src/main/resources/web（改动刷新浏览器即生效），
 *       生产环境读 jar 内置的 /web/ 目录，亦可通过 server.web.dir 外挂目录覆盖</li>
 *   <li>/.well-known/acme-challenge/** ACME http-01 证书校验文件</li>
 * </ul>
 * 访问配置（端口 / HTTPS / 安全入口）持久化在数据库，由系统设置页修改：
 * <ul>
 *   <li>HTTPS：通过 Jetty 扩展连接器在同进程提供 HTTPS 端口，证书复用已签发的 SSL 证书任务</li>
 *   <li>安全入口：未携带 {@code /{入口}} 前缀的请求一律 404（ACME 校验除外），
 *       业务路由与静态资源同时注册原始与前缀化副本</li>
 *   <li>修改后 {@link #restart()} 进程内重启生效，绑定失败自动回滚旧配置，避免面板失联</li>
 * </ul>
 * 各业务模块在启动前通过 {@link #route(String, String, Handler)} 声明接口，
 * 启动时统一回放注册；处理器统一使用 {@link Ctx} 门面，与底层 Web 框架解耦。
 */
public final class WebServer {

    /** REST 接口处理器 */
    @FunctionalInterface
    public interface Handler {
        void handle(Ctx ctx) throws Exception;
    }

    private record Route(String method, String pattern, Handler handler) {
    }

    /** 路由缓冲区：模块在 start 前注册，start（含每次重启）时统一回放到 Javalin 实例 */
    private static final List<Route> ROUTES = new ArrayList<>();

    /** 当前运行的 Javalin 实例（进程内重启时替换） */
    private static volatile Javalin app;
    /** 当前生效的访问配置快照 */
    private static volatile WebAccessConfig.Snapshot cfg;
    /** HTTPS 当前实际使用的证书任务 ID（未启用或降级为 -1） */
    private static volatile long activeCertTaskId = -1;

    private WebServer() {
    }

    /** 注册 REST 路由，pattern 支持 {name} 路径参数 */
    public static void route(String method, String pattern, Handler handler) {
        ROUTES.add(new Route(method.toUpperCase(), pattern, handler));
    }

    /** HTTPS 是否实际生效（已成功挂载证书连接器） */
    public static boolean httpsActive() {
        return activeCertTaskId > 0;
    }

    /** HTTPS 当前使用的证书任务 ID（未启用返回 -1） */
    public static long activeCertTaskId() {
        return activeCertTaskId;
    }

    /** 证书续期成功后的热加载入口：续期任务为当前 HTTPS 所用证书时自动重启加载新证书 */
    public static void onCertRenewed(long taskId) {
        if (activeCertTaskId == taskId) {
            Logs.info(Logs.SYS, "HTTPS 证书 #" + taskId + " 已续期，即将重启 Web 服务加载新证书");
            Tasks.delay(1, WebServer::restart);
        }
    }

    /** 启动服务：读取数据库中的访问配置并构建实例 */
    public static synchronized void start() {
        WebAccessConfig.Snapshot snapshot;
        try {
            snapshot = WebAccessConfig.load();
        } catch (Exception e) {
            // 数据库读取异常的极端情况：回退引导配置，保证面板可访问
            Logs.error(Logs.SYS, "读取访问配置失败，已回退引导配置: " + e.getMessage());
            snapshot = new WebAccessConfig.Snapshot(AppConfig.port(), false, 8443, false, 0, "");
        }
        cfg = snapshot;

        // HTTPS 证书解析前置：失败降级为仅 HTTP（HTTPS 可能因证书过期被关闭），避免服务无法启动
        boolean httpsOn = snapshot.httpsEnabled();
        WebAccessConfig.CertInfo certInfo = null;
        if (httpsOn) {
            try {
                certInfo = WebAccessConfig.resolveCertFile(snapshot.certTaskId());
                activeCertTaskId = certInfo.taskId();
            } catch (Exception e) {
                Logs.error(Logs.SYS, "HTTPS 证书解析失败，已降级为仅 HTTP 访问: " + e.getMessage());
                httpsOn = false;
            }
        }
        if (!httpsOn) activeCertTaskId = -1;

        final Path webDir = resolveWebDir();
        final WebAccessConfig.Snapshot snap = snapshot;
        final boolean https = httpsOn;
        final WebAccessConfig.CertInfo cert = certInfo;

        Javalin created = Javalin.create(config -> {
            // 图标上传（multipart）需突破框架默认 1MB 请求体限制；业务层另有 2MB 上限并友好报错
            config.http.maxRequestSize = 8_000_000L;
            // HTTPS：向 Jetty 追加 SSL 连接器（与 Javalin 默认 HTTP 连接器同属一个 Server，共享全部路由）
            if (https) addHttpsConnector(config.jetty, snap.httpsPort(), cert);

            // 前端静态资源：外部目录（开发热更新）或 classpath 的 /web 目录映射到根路径
            config.staticFiles.add(sf -> {
                sf.hostedPath = "/";
                if (webDir != null) {
                    sf.directory = webDir.toString();
                    sf.location = Location.EXTERNAL;
                } else {
                    sf.directory = "/web";
                    sf.location = Location.CLASSPATH;
                }
                // 静态资源不做浏览器缓存：外部目录模式下改动刷新即生效，
                // 生产升级 jar 后客户端也能立即拿到新版本（资源未做指纹命名）
                sf.headers = Map.of("Cache-Control", "no-cache");
            });

            RoutesConfig routes = config.routes;

            // 安全入口网关：启用后未携带 /{入口} 前缀的请求一律 404（ACME 校验除外），防端口扫描与爆破定位
            final String prefix = "/" + snap.entry();
            final boolean entryOn = !snap.entry().isEmpty();
            routes.before(ctx -> {
                if (!entryOn) return;
                String path = ctx.path();
                boolean allowed = path.equals(prefix) || path.startsWith(prefix + "/")
                        || path.startsWith("/.well-known/acme-challenge/");
                if (!allowed) throw new NotFoundResponse("404 Not Found");
            });

            // HTTPS 强制跳转：ACME http-01 校验走原协议（CA 需按申请协议访问），反代场景尊重 X-Forwarded-Proto
            if (https && snap.httpsRedirect()) {
                routes.before(ctx -> {
                    if (ctx.path().startsWith("/.well-known/acme-challenge/")) return;
                    String xfp = ctx.header("X-Forwarded-Proto");
                    boolean secure = xfp != null ? xfp.equalsIgnoreCase("https") : ctx.fullUrl().startsWith("https");
                    if (!secure) ctx.redirect(toHttpsUrl(ctx, snap.httpsPort()));
                });
            }

            // 鉴权前置：除登录与登录状态检查外，/api/** 全部需要 token
            // （安全入口模式下业务路由存在前缀化副本，先剥离前缀再判定是否属于 API）
            routes.before(ctx -> {
                String path = ctx.path();
                String check = entryOn && path.startsWith(prefix + "/") ? path.substring(prefix.length()) : path;
                if (!check.startsWith("/api/")) return;
                if (check.equals("/api/auth/login") || check.equals("/api/auth/check")) return;
                String token = ctx.header("X-Token");
                if (token == null) token = ctx.queryParam("token");
                if (!AuthService.check(token)) {
                    throw new UnauthorizedResponse("未登录或登录已过期");
                }
            });

            // 统一异常处理，保持 {ok:false, error:...} 响应约定
            // 业务校验错误（IllegalArgumentException）-> 400，前端直接展示
            routes.exception(IllegalArgumentException.class, (e, ctx) -> {
                Logs.warn(Logs.SYS, "参数错误 " + ctx.method().name() + " " + ctx.path() + ": " + e.getMessage());
                fail(ctx, 400, e.getMessage());
            });
            // Javalin 内置响应异常（401 未登录、404 未匹配路由等）
            routes.exception(HttpResponseException.class, (e, ctx) -> fail(ctx, e.getStatus(), e.getMessage()));
            // 兜底 500
            routes.exception(Exception.class, (e, ctx) -> {
                Logs.error(Logs.SYS, "接口异常 " + ctx.method().name() + " " + ctx.path() + ": " + e);
                fail(ctx, 500, "操作失败: " + e.getMessage());
            });

            // 首页（GET 端点优先级高于静态资源，显式返回 index.html）
            // 安全入口启用后根路径已被网关拦截，面板仅经 /{入口}/ 访问
            routes.get("/", ctx -> serveIndex(ctx, webDir));

            // ACME http-01 校验文件（免登录，且不受安全入口与 HTTPS 跳转影响）
            routes.get("/.well-known/acme-challenge/{token}",
                    ctx -> CertService.serveChallenge(new Ctx(ctx)));

            // 回放业务模块注册的路由（安全入口模式下同步注册前缀化副本，
            // 面板内相对请求 /{入口}/api/** 可正常匹配）
            for (Route r : ROUTES) {
                routes.addHttpHandler(toHandlerType(r.method()), r.pattern(),
                        ctx -> r.handler().handle(new Ctx(ctx)));
                if (entryOn) {
                    routes.addHttpHandler(toHandlerType(r.method()), prefix + r.pattern(),
                            ctx -> r.handler().handle(new Ctx(ctx)));
                }
            }

            // 安全入口面板入口：/entry 301 补全斜杠（保证相对资源解析到前缀下），
            // /entry/ 返回首页，其余前缀路径手动分发静态资源（置于业务路由之后，避免抢占精确匹配）
            if (entryOn) {
                routes.get(prefix, ctx -> ctx.redirect(prefix + "/"));
                routes.get(prefix + "/", ctx -> serveIndex(ctx, webDir));
                routes.get(prefix + "/*", ctx -> servePrefixedStatic(ctx, webDir, snap.entry()));
            }
        });

        // HTTP 连接器始终绑定（HTTPS 开启时用于跳转或并存访问）
        created.start(snap.httpPort());
        app = created;

        StringBuilder addr = new StringBuilder("Web 服务已启动: http://localhost:").append(snap.httpPort());
        if (!snap.entry().isEmpty()) addr.append('/').append(snap.entry()).append('/');
        if (https) {
            addr.append("（HTTPS: https://localhost:").append(snap.httpsPort());
            if (snap.httpsRedirect()) addr.append("，HTTP 强制跳转");
            addr.append("）");
        }
        Logs.info(Logs.SYS, addr.toString());
        if (webDir != null) {
            Logs.info(Logs.SYS, "前端静态资源使用外部目录: " + webDir + "，改动后刷新浏览器即生效");
        }
    }

    /**
     * 进程内重启：停止旧实例后按数据库最新配置重建。
     * 新配置绑定失败（端口占用等）时自动回滚旧配置并重启，保证面板不失联；
     * 回滚提示写入 web.last_error 供设置页展示。
     */
    public static synchronized void restart() {
        WebAccessConfig.Snapshot old = cfg;
        stopQuietly();
        try {
            start();
            setLastError("");
            Logs.info(Logs.SYS, "Web 服务已按新访问配置重启完成");
        } catch (Exception e) {
            Logs.error(Logs.SYS, "应用新访问配置失败，正在回滚: " + e);
            String msg = "应用新访问配置失败，已回滚旧配置: " + e.getMessage();
            try {
                if (old != null) old.persist();
                stopQuietly();
                start();
            } catch (Exception e2) {
                msg = "应用新访问配置失败且回滚异常，请检查端口占用后重启进程: " + e2.getMessage();
                Logs.error(Logs.SYS, "回滚旧访问配置失败: " + e2);
            }
            setLastError(msg);
            Logs.warn(Logs.SYS, msg);
        }
    }

    /** 停止当前实例（静默），实例不存在时忽略 */
    private static void stopQuietly() {
        Javalin a = app;
        if (a != null) {
            try {
                a.stop();
            } catch (Exception e) {
                Logs.warn(Logs.SYS, "停止旧 Web 服务异常: " + e.getMessage());
            }
            app = null;
        }
    }

    private static void setLastError(String msg) {
        try {
            Database.setConfig(WebAccessConfig.KEY_LAST_ERROR, msg);
        } catch (Exception ignored) {
            // 仅影响设置页提示展示
        }
    }

    /** 向 Jetty 追加 HTTPS 连接器：SSL 上下文由已签发证书（PEM）构建 */
    private static void addHttpsConnector(io.javalin.config.JettyConfig jetty, int httpsPort,
                                          WebAccessConfig.CertInfo cert) {
        jetty.modifyServer(server -> {
            try {
                SslContextFactory.Server sslFactory = new SslContextFactory.Server();
                sslFactory.setSslContext(WebAccessConfig.buildSslContext(cert.certFile(), cert.keyFile()));
                sslFactory.setIncludeProtocols("TLSv1.2", "TLSv1.3");
                HttpConfiguration httpsConfig = new HttpConfiguration();
                httpsConfig.addCustomizer(new SecureRequestCustomizer());
                ServerConnector sslConnector = new ServerConnector(server,
                        new SslConnectionFactory(sslFactory, HttpVersion.HTTP_1_1.asString()),
                        new HttpConnectionFactory(httpsConfig));
                sslConnector.setPort(httpsPort);
                server.addConnector(sslConnector);
                Logs.info(Logs.SYS, "HTTPS 连接器已挂载，端口: " + httpsPort
                        + "，证书任务 #" + cert.taskId() + " " + cert.name());
            } catch (Exception e) {
                throw new IllegalStateException("初始化 HTTPS 失败: " + e.getMessage(), e);
            }
        });
    }

    /** 构造 HTTPS 跳转地址：同 host，端口替换为 HTTPS 端口，保留路径与查询参数 */
    private static String toHttpsUrl(Context ctx, int httpsPort) {
        try {
            URI u = URI.create(ctx.fullUrl());
            String portPart = httpsPort == 443 ? "" : ":" + httpsPort;
            String query = u.getRawQuery();
            return "https://" + u.getHost() + portPart + u.getRawPath() + (query != null ? "?" + query : "");
        } catch (Exception e) {
            return "https://" + ctx.host() + (httpsPort == 443 ? "" : ":" + httpsPort) + "/";
        }
    }

    /** 返回面板首页 index.html */
    private static void serveIndex(Context ctx, Path webDir) throws IOException {
        byte[] idx = webDir != null
                ? readFile(webDir.resolve("index.html"))
                : readResource("/web/index.html");
        if (idx != null) {
            ctx.header("Cache-Control", "no-cache").contentType("text/html; charset=utf-8").result(idx);
        } else {
            ctx.status(404).result("页面不存在");
        }
    }

    /**
     * 安全入口模式下的前缀化静态资源分发：/{入口}/xxx -> web 目录下 xxx。
     * 相对路径请求（style.css / js/app.js 等）会自动带上前缀到达此处；
     * 拒绝 .. 目录穿越，根路径回退 index.html。
     */
    private static void servePrefixedStatic(Context ctx, Path webDir, String entry) throws IOException {
        String path = ctx.path();
        String rel = path.length() > entry.length() + 1 ? path.substring(entry.length() + 1) : "index.html";
        if (rel.isEmpty() || rel.endsWith("/")) rel = rel + "index.html";
        if (rel.contains("..")) {
            ctx.status(404).result("404 Not Found");
            return;
        }
        byte[] data = webDir != null ? readFile(webDir.resolve(rel)) : readResource("/web/" + rel);
        if (data == null) {
            ctx.status(404).result("404 Not Found");
            return;
        }
        ctx.header("Cache-Control", "no-cache").contentType(contentType(rel)).result(data);
    }

    /** 按扩展名推断静态资源 Content-Type */
    private static String contentType(String file) {
        String f = file.toLowerCase();
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".js") || f.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (f.endsWith(".json")) return "application/json; charset=utf-8";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".gif")) return "image/gif";
        if (f.endsWith(".svg")) return "image/svg+xml";
        if (f.endsWith(".ico")) return "image/x-icon";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".woff2")) return "font/woff2";
        if (f.endsWith(".woff")) return "font/woff";
        if (f.endsWith(".ttf")) return "font/ttf";
        if (f.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    /** HTTP 方法字符串 -> Javalin HandlerType */
    private static HandlerType toHandlerType(String method) {
        return switch (method) {
            case "GET" -> HandlerType.GET;
            case "POST" -> HandlerType.POST;
            case "PUT" -> HandlerType.PUT;
            case "DELETE" -> HandlerType.DELETE;
            case "PATCH" -> HandlerType.PATCH;
            case "HEAD" -> HandlerType.HEAD;
            case "OPTIONS" -> HandlerType.OPTIONS;
            default -> throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
        };
    }

    /** 输出错误 JSON：{ok:false, error:...} */
    private static void fail(Context ctx, int code, String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", false);
        r.put("error", message);
        ctx.status(code).contentType("application/json; charset=utf-8").result(JsonUtils.GSON.toJson(r));
    }

    /**
     * 解析前端静态资源目录：
     * <ol>
     *   <li>配置项 server.web.dir 指定的外部目录（存在时最优先）</li>
     *   <li>开发环境自动探测：以非 jar 方式运行（IDE 直接跑 main）且存在
     *       src/main/resources/web 时，直接读源码目录，前端改动无需重新构建/重启</li>
     *   <li>均不满足则返回 null，回退为 jar 内置 classpath 资源</li>
     * </ol>
     */
    private static Path resolveWebDir() {
        Path configured = AppConfig.webDir();
        if (configured != null && Files.isDirectory(configured)) return configured;
        if (!runningFromJar()) {
            Path dev = AppConfig.WORK_DIR.resolve("src/main/resources/web");
            if (Files.isDirectory(dev)) return dev;
        }
        return null;
    }

    /** 当前代码是否位于 jar 包内（fat-jar 运行） */
    private static boolean runningFromJar() {
        try {
            return WebServer.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI().getPath().endsWith(".jar");
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取外部文件，失败返回 null */
    private static byte[] readFile(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readResource(String path) {
        try (InputStream in = WebServer.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
