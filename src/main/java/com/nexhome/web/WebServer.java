package com.nexhome.web;

import com.nexhome.auth.AuthService;
import com.nexhome.core.AppConfig;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.module.cert.CertService;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpResponseException;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * 各业务模块在启动前通过 {@link #route(String, String, Handler)} 声明接口，
 * {@link #start(int)} 创建 Javalin 实例时统一回放注册；处理器统一使用 {@link Ctx}
 * 门面，与底层 Web 框架解耦。
 */
public final class WebServer {

    /** REST 接口处理器 */
    @FunctionalInterface
    public interface Handler {
        void handle(Ctx ctx) throws Exception;
    }

    private record Route(String method, String pattern, Handler handler) {
    }

    /** 路由缓冲区：模块在 start 前注册，start 时统一回放到 Javalin 实例 */
    private static final List<Route> ROUTES = new ArrayList<>();

    private WebServer() {
    }

    /** 注册 REST 路由，pattern 支持 {name} 路径参数 */
    public static void route(String method, String pattern, Handler handler) {
        ROUTES.add(new Route(method.toUpperCase(), pattern, handler));
    }

    /** 启动 HTTP 服务 */
    public static void start(int port) {
        Path webDir = resolveWebDir();
        Javalin app = Javalin.create(config -> {
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

            // 鉴权前置：除登录与登录状态检查外，/api/** 全部需要 token
            routes.before(ctx -> {
                String path = ctx.path();
                if (!path.startsWith("/api/")) return;
                if (path.equals("/api/auth/login") || path.equals("/api/auth/check")) return;
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
            routes.get("/", ctx -> {
                byte[] idx = webDir != null
                        ? readFile(webDir.resolve("index.html"))
                        : readResource("/web/index.html");
                if (idx != null) {
                    ctx.header("Cache-Control", "no-cache").contentType("text/html; charset=utf-8").result(idx);
                } else {
                    ctx.status(404).result("页面不存在");
                }
            });

            // ACME http-01 校验文件（免登录）
            routes.get("/.well-known/acme-challenge/{token}",
                    ctx -> CertService.serveChallenge(new Ctx(ctx)));

            // 回放业务模块注册的路由
            for (Route r : ROUTES) {
                routes.addHttpHandler(toHandlerType(r.method()), r.pattern(),
                        ctx -> r.handler().handle(new Ctx(ctx)));
            }
        });

        app.start(port);
        Logs.info(Logs.SYS, "Web 服务已启动，访问地址: http://localhost:" + port);
        if (webDir != null) {
            Logs.info(Logs.SYS, "前端静态资源使用外部目录: " + webDir + "，改动后刷新浏览器即生效");
        }
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
