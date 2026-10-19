package com.nexhome.web;

import com.google.gson.JsonObject;
import com.nexhome.auth.AuthService;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 系统级 REST 接口：登录鉴权、全局日志分页查询、系统信息。
 */
public final class SystemRoutes {

    /** 应用版本号：构建期由 Maven 从 pom.xml 注入到 app.properties */
    private static final String VERSION = readVersion();

    /** 测速默认时长（毫秒）：延迟/下载/上传三个阶段统一按时长测量 */
    private static final long SPEED_TEST_DEFAULT_MS = 10_000L;

    /** 下载流单请求最长存活时间（毫秒）：浏览器主动中断之外的服务端兜底，防止请求线程被长期占用 */
    private static final long SPEED_DOWN_MAX_MS = 60_000L;

    /**
     * 下载流单请求字节兜底上限：只靠时长封顶时，超快链路（localhost / 同机房反向代理）
     * 下实测单流可达 870MB/s，16GB 相当于给最长存活时间再套一层量级上限；
     * 取这个数是为了不截断任何真实硬件上的合法测量窗口（10 秒窗口连 loopback 也跑不到）。
     */
    private static final long SPEED_DOWN_MAX_BYTES = 16L << 30;

    /** 预热定长响应字节上限（预热只需跑通握手与慢启动，给几百 KB 足够） */
    private static final long SPEED_DOWN_MAX_FIXED_BYTES = 32L << 20;

    /** 上传流单请求最长读取时间（毫秒）：防御异常客户端无限推流 */
    private static final long SPEED_UP_MAX_MS = 60_000L;

    /**
     * 测速高熵负载块（1MB 随机数据，只读复用）：循环拷贝即可持续输出不可压缩负载。
     * 边生成边写会把吞吐压在伪随机数生成器上（java.util.Random 每 4 字节一次 nextInt，
     * 实测仅 ~50MB/s，正好卡在千兆链路以下），而 memcpy 不构成瓶颈。
     * 周期 1MB 远大于 deflate 的 32KB 回溯窗口，重复不会让中间层压缩出虚高速率。
     */
    private static final byte[] SPEED_BLOCK = new byte[1 << 20];

    static {
        new java.util.Random().nextBytes(SPEED_BLOCK);
    }

    /** 延迟探测响应体（单字节，避免每请求重复分配） */
    private static final byte[] PING_BODY = {'P'};

    private SystemRoutes() {
    }

    public static void register() {
        // ---------- 鉴权 ----------
        WebServer.route("POST", "/api/auth/login", ctx -> {
            JsonObject b = ctx.body();
            String password = JsonUtils.str(b, "password");
            if (password.isBlank()) throw new IllegalArgumentException("密码不能为空");
            String ip = ctx.clientIp();
            long remain = AuthService.lockRemainingMs(ip);
            if (remain > 0) {
                long minutes = (remain + 59_999) / 60_000;
                ctx.fail(429, "尝试过于频繁，已临时锁定，请约 " + minutes + " 分钟后再试");
                return;
            }
            String token = AuthService.login(password, ip);
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
            if (newPwd.length() < 12) throw new IllegalArgumentException("新密码长度至少 12 位");
            if (!AuthService.changePassword(oldPwd, newPwd)) {
                ctx.fail(400, "原密码不正确");
                return;
            }
            ctx.ok("密码已修改，请重新登录");
        });

        // ---------- 登录安全配置（失败锁定阈值与时长） ----------
        WebServer.route("GET", "/api/auth/security", ctx -> ctx.ok(Map.of(
                "maxFails", AuthService.getMaxFails(),
                "lockMinutes", AuthService.getLockMinutes())));
        WebServer.route("PUT", "/api/auth/security", ctx -> {
            JsonObject b = ctx.body();
            AuthService.setSecurity(JsonUtils.num(b, "maxFails", 3), JsonUtils.num(b, "lockMinutes", 30));
            Logs.info(Logs.AUTH, "登录安全参数已更新：最大失败 " + AuthService.getMaxFails()
                    + " 次，锁定 " + AuthService.getLockMinutes() + " 分钟");
            ctx.ok("已保存登录安全设置");
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
            // 实际监听端口（启动期可端口自愈，与配置值不一致时以此为准）
            info.put("port", WebServer.httpPort());
            info.put("httpsEnabled", WebServer.httpsActive());
            info.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
            info.put("usedMemoryMB", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
            info.put("maxMemoryMB", rt.maxMemory() / 1024 / 1024);
            ctx.ok(info);
        });

        // ---------- 系统资源监控（CPU / 物理内存 / 虚拟内存，供顶栏实时展示） ----------
        WebServer.route("GET", "/api/system/metrics", ctx -> {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long memTotal = os.getTotalMemorySize();
            long memUsed = memTotal - os.getFreeMemorySize();
            long swapTotal = os.getTotalSwapSpaceSize();
            long swapUsed = swapTotal - os.getFreeSwapSpaceSize();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cores", os.getAvailableProcessors());
            m.put("cpuLoad", toPct(os.getCpuLoad()));           // JVM 采样未就绪时为 -1
            m.put("memTotalMB", memTotal / 1024 / 1024);
            m.put("memUsedMB", memUsed / 1024 / 1024);
            m.put("swapTotalMB", swapTotal / 1024 / 1024);
            m.put("swapUsedMB", swapUsed / 1024 / 1024);
            ctx.ok(m);
        });

        // ---------- 访问与安全设置（端口 / HTTPS / 安全入口，保存后进程内重启生效） ----------
        WebServer.route("GET", "/api/system/web-settings", ctx -> ctx.ok(WebAccessConfig.apiView()));
        WebServer.route("PUT", "/api/system/web-settings", ctx -> {
            WebAccessConfig.apply(ctx.body());
            Logs.info(Logs.SYS, "访问配置已更新，服务即将重启应用新配置");
            // 延迟重启：确保本次响应送达客户端后再停服（非守护线程承载，避免 JVM 随之退出）
            WebServer.scheduleRestart(1);
            ctx.ok("已保存，服务正在以新配置重启");
        });

        // ---------- 配置备份：VACUUM INTO 生成一致性快照后下载（仅提供导出，不提供导入恢复） ----------
        WebServer.route("GET", "/api/system/backup", ctx -> {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path tmp = Files.createTempFile("nexhome-backup-", ".db");
            try {
                // SQLite VACUUM INTO 要求目标文件不存在，临时文件已创建，先删除再交给 SQLite 生成
                Files.deleteIfExists(tmp);
                String target = tmp.toAbsolutePath().toString().replace('\\', '/');
                Database.backupTo(target);
                ctx.file(tmp, "nexhome-" + stamp + ".db");
                Logs.info(Logs.SYS, "配置备份已导出: nexhome-" + stamp + ".db");
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        });

        // ---------- 统一状态仪表盘（聚合各模块关键状态） ----------
        WebServer.route("GET", "/api/dashboard", ctx -> ctx.ok(DashboardService.summary()));

        // ---------- 网络测速（浏览器 <-> 本系统链路，不经外部测速服务器） ----------
        // 延迟/丢包探测：空载荷小响应，前端 fetch 计时多次往返取平均与失败率
        WebServer.route("GET", "/api/system/speedtest/ping", ctx ->
                ctx.raw(200, PING_BODY, "application/octet-stream"));
        // 下载测速：测量窗口由浏览器控制（到点主动中断请求），服务端只负责持续输出高熵流并做兜底封顶。
        // 不能反过来让服务端“按时长写满再收尾”：服务端与浏览器之间只要隔着反向代理或有较大的
        // 内核/中间层缓冲（远程访问面板的常见链路），写出就不会被真实链路背压阻塞——几百毫秒就灌完
        // 几 MB 并正常收尾，浏览器还要按链路真实速率慢慢收，表现为测速一分多钟不结束且速率严重偏低。
        // 窗口计时交给客户端后，结果与链路上的缓冲深度无关。
        // ?bytes= 定长小响应：预热连接专用，显式声明 Content-Length，写完即收尾，不参与计时；
        // ?ms=    单请求最长存活时间（默认 10 秒，上限 60 秒）：纯兜底，正常测速由浏览器先到点中断。
        // 不声明 Content-Length 的流式响应由 Jetty 分块传输，客户端中断在写出时抛 IOException，
        // 属正常收尾，在 speedWrite 内静默处理，不冒泡成 500 与错误日志。
        WebServer.route("GET", "/api/system/speedtest/down", ctx -> {
            long fixed = clamp(parseLong(ctx.query("bytes"), 0L), 0L, SPEED_DOWN_MAX_FIXED_BYTES);
            long ms = clamp(parseLong(ctx.query("ms"), SPEED_TEST_DEFAULT_MS), 500L, SPEED_DOWN_MAX_MS);
            io.javalin.http.Context j = ctx.javalin();
            j.status(200)
                    .contentType("application/octet-stream")
                    .header("Cache-Control", "no-store");
            if (fixed > 0) j.res().setContentLengthLong(fixed);
            speedWrite(j.outputStream(), fixed, ms);
        });
        // 上传测速：流式读取并丢弃请求体直到 EOF，返回实收字节数（前端多路并发持续推流到时关闭流，
        // 或兜底中断请求）。
        // 大缓冲减少 TLS/输入流的读取次数，读后即弃不占内存；直接读 Servlet 裸输入流，
        // 不经 Javalin 请求体缓存（maxRequestSize 只限制 bodyAsBytes 缓存路径），
        // 单请求可达数百 MB 不受全局 8MB 限制。
        // 60 秒封顶：防御异常客户端无限推流长期占用请求线程（正常测量单路不超过测速时长）
        WebServer.route("POST", "/api/system/speedtest/up", ctx -> {
            HttpServletRequest req = ctx.javalin().req();
            long total = 0;
            final long deadline = System.nanoTime() + SPEED_UP_MAX_MS * 1_000_000L;
            byte[] buf = new byte[256 * 1024];
            try (InputStream in = req.getInputStream()) {
                while (System.nanoTime() < deadline) {
                    int n = in.read(buf);
                    if (n < 0) break; // 客户端推完收尾（EOF）：正常结束
                    total += n;
                }
            } catch (IOException clientGone) {
                // 客户端中途断连（兜底 abort / 页面切走）：已收字节数仍是有效计量，照常返回而非抛 500
            }
            ctx.ok(Map.of("bytes", total));
        });
        // ---------- 访问来源识别（首页导航内外网智能切换依据） ----------
        // 内外网用两套域名时前端 hostname 无法区分，但 TCP 连接的源 IP 不会骗人：
        // 内网来源必然是私网/环回地址，外网来源必然是公网地址（经反代时取 XFF 首跳）
        WebServer.route("GET", "/api/net/source", ctx -> {
            String ip = ctx.clientIp();
            ctx.ok(Map.of("lan", isPrivateSource(ip), "ip", ip));
        });
    }

    /** 是否内网来源：环回/私网/链路本地/组播及运营商 CGNAT(100.64.0.0/10) 均视为内网，其余视为公网 */
    private static boolean isPrivateSource(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress()
                    || a.isLinkLocalAddress() || a.isMulticastAddress()) return true;
            byte[] b = a.getAddress();
            return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xFF) >= 64 && (b[1] & 0xFF) <= 127;
        } catch (Exception e) {
            return false;
        }
    }

    /** 0~1 的负载比例转百分比（保留 1 位小数）；负值表示尚未完成采样，原样返回 -1 */
    private static double toPct(double ratio) {
        if (ratio < 0) return -1;
        return Math.round(ratio * 1000.0) / 10.0;
    }

    /** 长整型参数夹到 [min,max] 区间 */
    private static long clamp(long v, long min, long max) {
        return Math.min(Math.max(v, min), max);
    }

    private static int parsePage(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 测速时长等长整型参数解析，缺省/非法回退默认值 */
    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 向响应输出流持续写出高熵负载：{@code fixed > 0} 时写满该字节数即收尾（预热）；
     * 否则一直写到 {@code maxMs} 超时或触到字节兜底上限（正常测速由浏览器到点中断）。
     * <p>
     * 逐块 flush：把在途数据压到最小，浏览器中断后服务端能下一次写出即感知，
     * 负载也不会被提前堆进中间代理的缓冲。只读共享 {@link #SPEED_BLOCK}，
     * 不随请求分配内存（多路并发测速亦仅一份负载块）。
     */
    private static void speedWrite(OutputStream out, long fixed, long maxMs) {
        final long cap = fixed > 0 ? fixed : SPEED_DOWN_MAX_BYTES;
        final long deadline = System.nanoTime() + maxMs * 1_000_000L;
        long sent = 0;
        try {
            while (sent < cap && (fixed > 0 || System.nanoTime() < deadline)) {
                int n = (int) Math.min(SPEED_BLOCK.length, cap - sent);
                out.write(SPEED_BLOCK, 0, n);
                out.flush();
                sent += n;
            }
        } catch (IOException clientGone) {
            // 浏览器测速窗口到点中断请求，或用户中途切走页面：链路已断，静默收尾
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
