package com.nexhome.web;

import com.google.gson.JsonObject;
import com.nexhome.auth.AuthService;
import com.nexhome.core.AppConfig;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 系统级 REST 接口：登录鉴权、全局日志分页查询、系统信息。
 */
public final class SystemRoutes {

    /** 应用版本号：构建期由 Maven 从 pom.xml 注入到 app.properties */
    private static final String VERSION = readVersion();

    private SystemRoutes() {
    }

    public static void register() {
        // ---------- 鉴权 ----------
        WebServer.route("POST", "/api/auth/login", ctx -> {
            JsonObject b = ctx.body();
            String password = JsonUtils.str(b, "password");
            if (password.isBlank()) throw new IllegalArgumentException("密码不能为空");
            String token = AuthService.login(password);
            if (token == null) {
                ctx.fail(401, "密码错误");
                return;
            }
            ctx.ok(Map.of("token", token));
        });
        WebServer.route("GET", "/api/auth/check", ctx -> {
            String token = ctx.header("X-Token");
            if (token == null) token = ctx.query("token");
            ctx.ok(Map.of("loggedIn", AuthService.check(token)));
        });
        WebServer.route("POST", "/api/auth/logout", ctx -> {
            AuthService.logout(ctx.header("X-Token"));
            ctx.ok("已退出");
        });
        WebServer.route("POST", "/api/auth/change-password", ctx -> {
            JsonObject b = ctx.body();
            String oldPwd = JsonUtils.str(b, "old_password");
            String newPwd = JsonUtils.str(b, "new_password");
            if (newPwd.length() < 4) throw new IllegalArgumentException("新密码长度至少 4 位");
            if (!AuthService.changePassword(oldPwd, newPwd)) {
                ctx.fail(400, "原密码不正确");
                return;
            }
            ctx.ok("密码已修改，请重新登录");
        });

        // ---------- 全局日志（分页 + 模块过滤） ----------
        WebServer.route("GET", "/api/logs", ctx -> {
            int page = parsePage(ctx.query("page"));
            int size = parsePage(ctx.query("size"));
            ctx.ok(Logs.page(page, size == 0 ? 50 : size, ctx.query("module")));
        });

        // ---------- 系统信息 ----------
        WebServer.route("GET", "/api/system/info", ctx -> {
            Runtime rt = Runtime.getRuntime();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", "NexHome 联枢");
            info.put("version", VERSION);
            info.put("javaVersion", System.getProperty("java.version"));
            info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            info.put("port", AppConfig.port());
            info.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
            info.put("usedMemoryMB", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
            info.put("maxMemoryMB", rt.maxMemory() / 1024 / 1024);
            ctx.ok(info);
        });
    }

    private static int parsePage(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 从 classpath 的 app.properties 读取注入的版本号，读取失败时回退为 unknown */
    private static String readVersion() {
        try (InputStream in = SystemRoutes.class.getResourceAsStream("/app.properties")) {
            if (in == null) return "unknown";
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("app.version", "unknown").trim();
            return v.isEmpty() || v.contains("${") ? "unknown" : v;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
