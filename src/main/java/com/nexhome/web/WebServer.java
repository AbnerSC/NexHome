package com.nexhome.web;

import com.nexhome.auth.AuthService;
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
 *   <li>内嵌于 jar 的前端静态资源（classpath 的 /web/ 目录）</li>
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
        Javalin app = Javalin.create(config -> {
            // 前端静态资源：classpath 的 /web 目录映射到根路径
            config.staticFiles.add(sf -> {
                sf.hostedPath = "/";
                sf.directory = "/web";
                sf.location = Location.CLASSPATH;
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
                byte[] idx = readResource("/web/index.html");
                if (idx != null) {
                    ctx.contentType("text/html; charset=utf-8").result(idx);
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
