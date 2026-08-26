package com.nexhome.web;

import com.nexhome.auth.AuthService;
import com.nexhome.core.Logs;
import com.nexhome.module.cert.CertService;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置 Web 服务器（基于 JDK 自带 com.sun.net.httpserver，零第三方依赖）。
 * <p>
 * 单端口同时提供：
 * <ul>
 *   <li>/api/** REST 接口（JSON，除登录外需 X-Token 鉴权）</li>
 *   <li>内嵌于 jar 的前端静态资源（/web/ 目录）</li>
 *   <li>/.well-known/acme-challenge/** ACME http-01 证书校验文件</li>
 * </ul>
 */
public final class WebServer {

    /** REST 接口处理器 */
    @FunctionalInterface
    public interface Handler {
        void handle(Ctx ctx) throws Exception;
    }

    private record Route(String method, String[] pattern, Handler handler) {
    }

    private static final List<Route> ROUTES = new ArrayList<>();

    /** 静态资源 MIME 映射 */
    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon");

    private WebServer() {
    }

    /** 注册 REST 路由，pattern 支持 {name} 路径参数 */
    public static void route(String method, String pattern, Handler handler) {
        ROUTES.add(new Route(method.toUpperCase(), pattern.split("/"), handler));
    }

    /** 启动 HTTP 服务 */
    public static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // 轻量线程池：家庭场景并发极低，8 个守护线程足够且省内存
        ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "http-worker");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", WebServer::dispatch);
        server.start();
        Logs.info(Logs.SYS, "Web 服务已启动，访问地址: http://localhost:" + port);
    }

    /** 统一请求分发入口 */
    private static void dispatch(com.sun.net.httpserver.HttpExchange ex) {
        Ctx ctx = new Ctx(ex, Map.of());
        try {
            String path = ctx.path();
            if (path.startsWith("/.well-known/acme-challenge/")) {
                // ACME http-01 校验文件，不需要登录
                CertService.serveChallenge(ctx);
                return;
            }
            if (path.startsWith("/api/")) {
                handleApi(ctx);
                return;
            }
            serveStatic(ctx);
        } catch (Exception e) {
            try {
                ctx.fail(500, "服务器内部错误: " + e.getMessage());
            } catch (IOException ignored) {
            }
            Logs.error(Logs.SYS, "请求处理异常 " + ctx.method() + " " + ctx.path() + ": " + e);
        } finally {
            ctx.close();
        }
    }

    private static void handleApi(Ctx ctx) throws Exception {
        String path = ctx.path();
        boolean isLogin = path.equals("/api/auth/login");
        boolean isCheck = path.equals("/api/auth/check");

        // 除登录与登录状态检查外，全部接口需要 token
        if (!isLogin && !isCheck) {
            String token = ctx.header("X-Token");
            if (token == null) token = ctx.query("token");
            if (!AuthService.check(token)) {
                ctx.fail(401, "未登录或登录已过期");
                return;
            }
        }

        for (Route r : ROUTES) {
            Map<String, String> params = match(r.pattern, path.split("/"));
            if (params != null && r.method().equals(ctx.method())) {
                try {
                    r.handler().handle(new Ctx(ctx.exchange(), params));
                } catch (IllegalArgumentException e) {
                    // 业务校验错误统一返回 400，前端直接展示
                    ctx.fail(400, e.getMessage());
                } catch (Exception e) {
                    Logs.error(Logs.SYS, "接口异常 " + ctx.method() + " " + path + ": " + e);
                    ctx.fail(500, "操作失败: " + e.getMessage());
                }
                return;
            }
        }
        ctx.notFound();
    }

    /** 路径段匹配，成功返回路径参数映射 */
    private static Map<String, String> match(String[] pattern, String[] segs) {
        if (pattern.length != segs.length) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                params.put(p.substring(1, p.length() - 1), segs[i]);
            } else if (!p.equals(segs[i])) {
                return null;
            }
        }
        return params;
    }

    /** 从 jar 内 /web/ 目录提供前端静态资源 */
    private static void serveStatic(Ctx ctx) throws IOException {
        String path = ctx.path();
        if (path.equals("/")) path = "/index.html";
        // 防止路径穿越
        if (path.contains("..")) {
            ctx.fail(400, "非法路径");
            return;
        }
        byte[] data = readResource("/web" + path);
        if (data == null) {
            // SPA 兜底：未命中的路径返回首页
            data = readResource("/web/index.html");
            if (data == null) {
                ctx.fail(404, "页面不存在");
                return;
            }
            path = "/index.html";
        }
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        ctx.raw(200, data, MIME.getOrDefault(ext, "application/octet-stream"));
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
