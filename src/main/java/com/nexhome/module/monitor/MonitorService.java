package com.nexhome.module.monitor;

import com.google.gson.JsonObject;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;
import com.nexhome.module.notify.NotifyService;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 服务/端口可用性监控。
 * <p>
 * 支持 HTTP/HTTPS（URL 状态码 + 关键字判定）、TCP（连接可达）、UDP（尽力探测）三类探测。
 * UDP 中 53 端口按标准 DNS 查询探测（有应答才判可用），其余端口收不到回包且未收到 ICMP 端口不可达差错时按可达处理。
 * 探测按任务间隔周期调度，<b>实际探测提交到独立虚拟线程池</b>，绝不占用 {@link Tasks} 的 3 线程调度池
 * （慢速网络 IO 会饥饿 DDNS 同步 / 证书续期 / 穿透流量落库等关键周期任务，遵循既有线程隔离规范）。
 * <p>
 * <b>抗抖动</b>（监控误报「一会儿异常、一会儿恢复」的根治点）：
 * <ul>
 *   <li>单个周期内对<b>传输层瞬时故障</b>（连接/读超时、DNS 解析失败、TLS 握手失败、连接被拒）自动重试
 *       {@value #PROBE_ATTEMPTS} 次，任一成功即判可用；应用层判定（状态码/关键字不符）不做无意义重试。</li>
 *   <li>单次尝试超时不低于 {@value #MIN_ATTEMPT_TIMEOUT_SEC} 秒——HTTPS 握手叠加 VPN/overlay 链路 RTT 时，
 *       1~2 秒的预算极易把健康目标判成故障。</li>
 *   <li>各任务按 ID 确定性<b>错峰</b>触发，避免同一秒齐发冷连接互相放大时延（编辑任务也不会重新对齐）。</li>
 *   <li>DOWN→UP 需连续多个周期成功才确认恢复，避免链路抖动时状态与通知来回翻转。</li>
 * </ul>
 * <p>
 * 状态机（通知去重，保证一个故障周期内异常/恢复各只发一次）：
 * <ul>
 *   <li>UP→DOWN：连续失败刚达到 {@code fail_threshold} 时触发一次 {@code monitor.down} 告警；
 *       持续宕机期间 {@code fail_count} 继续累加，<b>不再重复发送</b>异常通知。</li>
 *   <li>DOWN→UP：仅当本故障周期确实发过告警（{@code fail_count} 曾达阈值）时，
 *       且连续成功数达到恢复确认次数，触发一次 {@code monitor.up} 恢复通知。</li>
 * </ul>
 * 结果写回 {@code monitor_task}。
 */
public final class MonitorService {

    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();
    private static final Map<Long, ScheduledFuture<?>> SCHEDULES = new ConcurrentHashMap<>();
    private static final Set<Long> PROBING = ConcurrentHashMap.newKeySet();
    /** 各任务本故障周期的连续成功计数（内存态，仅用于 DOWN→UP 恢复确认，重启后自然归零） */
    private static final Map<Long, Integer> SUCCESS_STREAK = new ConcurrentHashMap<>();

    // 探测固定用 HTTP/1.1：JDK HttpClient 默认按 HTTP/2 协商，明文请求会带 Upgrade: h2c 头，
    // 路由器/NAS 这类嵌入式 Web 服务常处理不了该升级而直接挂起，表现为偶发连接超时。
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    /** DNS（UDP 53）探测用的查询域名：任意 A 记录查询能拿到应答即证明解析服务存活 */
    private static final String DNS_PROBE_NAME = "example.com";

    /** UDP 尽力探测：首包无回包后，补发一包给 ICMP 端口不可达差错留的短等待窗口（毫秒） */
    private static final int UDP_ICMP_GRACE_MS = 500;

    /** 单个周期内的探测尝试次数：吸收一次链路抖动，不至于一抖就告警 */
    private static final int PROBE_ATTEMPTS = 3;

    /** 两次尝试之间的间隔（毫秒） */
    private static final long RETRY_GAP_MS = 800L;

    /** 单次尝试的最小超时（秒）：低于此值的配置对 HTTPS/overlay 链路不现实，按下限保护 */
    private static final int MIN_ATTEMPT_TIMEOUT_SEC = 3;

    /** DOWN→UP 恢复至少需连续成功的周期数（实际取它与 fail_threshold 的较大值） */
    private static final int RECOVER_CONFIRM_MIN = 2;

    /** 任务启用/编辑后的首个探测延迟基准（秒），叠加按 ID 的错峰偏移 */
    private static final int PROBE_FIRST_DELAY_SEC = 3;

    private MonitorService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/monitor/tasks", ctx -> ctx.ok(Database.query(
                "SELECT * FROM monitor_task ORDER BY id")));
        WebServer.route("POST", "/api/monitor/tasks", MonitorService::create);
        WebServer.route("PUT", "/api/monitor/tasks/{id}", MonitorService::update);
        WebServer.route("DELETE", "/api/monitor/tasks/{id}", MonitorService::delete);
        WebServer.route("POST", "/api/monitor/tasks/{id}/run", ctx -> {
            long id = ctx.paramLong("id");
            mustGet(id);
            if (PROBING.contains(id)) {
                ctx.ok("该任务正在探测中，请稍候刷新查看结果");
                return;
            }
            POOL.submit(() -> probe(id, true));
            ctx.ok("已触发一次探测，请在列表中查看结果");
        });
    }

    /** 启动时加载启用任务并建立调度 */
    public static void init() throws SQLException {
        for (Map<String, Object> task : Database.query("SELECT * FROM monitor_task")) {
            schedule(task);
        }
        Logs.info(Logs.MONITOR, "监控任务已加载，共 " + SCHEDULES.size() + " 个定时探测");
    }

    // ---------- 增删改 ----------

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        validate(b);
        long id = insert(b);
        Logs.info(Logs.MONITOR, "新增监控任务: " + JsonUtils.str(b, "name"));
        Map<String, Object> task = mustGet(id);
        schedule(task);
        POOL.submit(() -> probe(id));
        ctx.ok(task);
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        mustGet(id);
        JsonObject b = ctx.body();
        validate(b);
        Database.update("""
                UPDATE monitor_task SET name=?, type=?, target=?, method=?, keyword=?, expect_status=?,
                    interval_sec=?, timeout_sec=?, fail_threshold=?, enabled=?, notify_on_recovery=? WHERE id=?""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "type").toUpperCase(), JsonUtils.str(b, "target").trim(),
                normalizeMethod(JsonUtils.str(b, "method")), JsonUtils.str(b, "keyword"),
                JsonUtils.num(b, "expect_status", 200), JsonUtils.num(b, "interval_sec", 60),
                JsonUtils.num(b, "timeout_sec", 10), JsonUtils.num(b, "fail_threshold", 1),
                JsonUtils.bool(b, "enabled", true) ? 1 : 0, JsonUtils.bool(b, "notify_on_recovery", true) ? 1 : 0, id);
        Logs.info(Logs.MONITOR, "更新监控任务 #" + id + ": " + JsonUtils.str(b, "name"));
        Map<String, Object> task = mustGet(id);
        schedule(task);
        ctx.ok(task);
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> task = mustGet(id);
        cancel(id);
        Database.update("DELETE FROM monitor_task WHERE id=?", id);
        Logs.info(Logs.MONITOR, "删除监控任务 #" + id + ": " + task.get("name"));
        ctx.ok("已删除");
    }

    private static long insert(JsonObject b) throws SQLException {
        return Database.insert("""
                INSERT INTO monitor_task(name, type, target, method, keyword, expect_status,
                    interval_sec, timeout_sec, fail_threshold, enabled, notify_on_recovery)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "type").toUpperCase(), JsonUtils.str(b, "target").trim(),
                normalizeMethod(JsonUtils.str(b, "method")), JsonUtils.str(b, "keyword"),
                JsonUtils.num(b, "expect_status", 200), JsonUtils.num(b, "interval_sec", 60),
                JsonUtils.num(b, "timeout_sec", 10), JsonUtils.num(b, "fail_threshold", 1),
                JsonUtils.bool(b, "enabled", true) ? 1 : 0, JsonUtils.bool(b, "notify_on_recovery", true) ? 1 : 0);
    }

    private static void validate(JsonObject b) {
        if (JsonUtils.str(b, "name").isBlank()) throw new IllegalArgumentException("任务名称不能为空");
        String type = JsonUtils.str(b, "type").toUpperCase();
        if (!List.of("HTTP", "HTTPS", "TCP", "UDP").contains(type)) {
            throw new IllegalArgumentException("不支持的探测类型: " + JsonUtils.str(b, "type"));
        }
        String target = JsonUtils.str(b, "target").trim();
        if (target.isEmpty()) throw new IllegalArgumentException("探测目标不能为空");
        if ("HTTP".equals(type) || "HTTPS".equals(type)) {
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                throw new IllegalArgumentException("HTTP(S) 探测目标需为完整 URL（含 http:// 或 https://）");
            }
        } else {
            String[] hp = splitHostPort(target);
            if (hp == null) {
                throw new IllegalArgumentException("TCP/UDP 探测目标需为 host:port 格式");
            }
            int port;
            try {
                port = Integer.parseInt(hp[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("TCP/UDP 探测端口非法: " + hp[1]);
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("TCP/UDP 探测端口需在 1-65535");
        }
        if (JsonUtils.num(b, "timeout_sec", 10) <= 0) throw new IllegalArgumentException("超时时间需大于 0");
    }

    private static String normalizeMethod(String m) {
        String v = m == null ? "" : m.trim().toUpperCase();
        return "POST".equals(v) ? "POST" : "GET";
    }

    // ---------- 探测执行 ----------

    /** 执行一个探测周期并做状态机告警判定（调度入口） */
    public static void probe(long taskId) {
        probe(taskId, false);
    }

    /**
     * 执行一个探测周期（内含瞬时故障重试）并做状态机告警判定。
     *
     * @param manual 手动「立即探测」传 true：跳过 DOWN→UP 的连续成功确认，结果立即反映到状态，
     *               便于用户改完配置马上验证
     */
    public static void probe(long taskId, boolean manual) {
        if (!PROBING.add(taskId)) return;
        try {
            Map<String, Object> task = mustGet(taskId);
            String prev = str(task, "last_status");
            int prevFail = intVal(task, "fail_count");
            int threshold = Math.max(1, intVal(task, "fail_threshold"));
            int timeout = Math.max(MIN_ATTEMPT_TIMEOUT_SEC, intVal(task, "timeout_sec"));
            // 本周期时间预算：不超过约半个探测间隔，避免重试把后续调度周期整个顶掉
            long budgetMs = Math.min(60, Math.max(15, intVal(task, "interval_sec"))) * 500L;

            long cycleStart = System.currentTimeMillis();
            boolean up;
            String detail;
            long latencyMs;
            for (int attempt = 1; ; attempt++) {
                String[] d = new String[1];
                boolean retryable;
                try {
                    up = doProbe(task, timeout, d);
                    retryable = false;   // 应用层判定（状态码/关键字不符、端口明确不可达）重试无意义
                } catch (IOException e) {
                    up = false;
                    retryable = true;    // 连接/读超时、DNS 解析失败、TLS 握手失败、连接被拒：瞬时故障
                    d[0] = msg(e);
                } catch (Exception e) {
                    up = false;
                    retryable = false;   // 配置/格式类错误，重试只会白耗时间
                    d[0] = msg(e);
                }
                latencyMs = System.currentTimeMillis() - cycleStart;
                detail = attempt > 1 ? d[0] + "（已重试 " + (attempt - 1) + " 次）" : d[0];
                if (up || !retryable || attempt >= PROBE_ATTEMPTS) break;
                if (latencyMs + timeout * 1000L + RETRY_GAP_MS > budgetMs) break; // 超出预算，留给下个周期
                try {
                    Thread.sleep(RETRY_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            String time = Database.now();

            if (up) {
                // DOWN→UP 恢复确认：告警已发出的故障周期内，单次成功不改状态、不发恢复通知，
                // 需连续 recoverConfirm 个周期成功才确认，避免链路抖动时“异常/恢复”来回翻转。
                boolean hadAlerted = "DOWN".equals(prev) && prevFail >= threshold;
                int streak = hadAlerted ? SUCCESS_STREAK.merge(taskId, 1, Integer::sum) : 0;
                if (hadAlerted && !manual && streak < recoverConfirm(threshold)) {
                    Database.update("UPDATE monitor_task SET last_check=?, last_latency_ms=? WHERE id=?",
                            time, latencyMs, taskId);
                    Logs.info(Logs.MONITOR, "监控[" + task.get("name") + "] 探测已恢复，等待连续确认（"
                            + streak + "/" + recoverConfirm(threshold) + "）");
                    return;
                }
                SUCCESS_STREAK.remove(taskId);
                Database.update("UPDATE monitor_task SET last_status='UP', last_check=?, last_latency_ms=?, fail_count=0 WHERE id=?",
                        time, latencyMs, taskId);
                // 恢复通知只在“本故障周期确实发过告警”时发送一次：
                // 已告警 <=> 上次状态为 DOWN 且连续失败数已达阈值（fail_count 跨阈值后仍会累加，故用 >=）。
                // 这样可避免未达阈值的瞬时抖动（失败后很快恢复）凭空发出恢复通知。
                if (hadAlerted) {
                    notifyIfRecovery(task, latencyMs);
                }
            } else {
                SUCCESS_STREAK.remove(taskId);
                int fail = prevFail + 1;
                Database.update("UPDATE monitor_task SET last_status='DOWN', last_check=?, last_latency_ms=?, fail_count=? WHERE id=?",
                        time, latencyMs, fail, taskId);
                // 异常告警只在连续失败“刚跨过阈值”的那一次发出（fail 精确等于 threshold）；
                // 之后服务持续宕机时 fail 继续累加（> threshold），不再重复发送异常通知，直到恢复后进入新周期。
                if (fail == threshold) {
                    alertDown(task, detail, latencyMs, fail);
                }
            }
        } catch (Exception e) {
            Logs.error(Logs.MONITOR, "监控任务 #" + taskId + " 探测异常: " + e);
        } finally {
            PROBING.remove(taskId);
        }
    }

    /** 恢复确认所需连续成功周期数：与告警阈值对称，至少 {@value #RECOVER_CONFIRM_MIN} 次 */
    private static int recoverConfirm(int failThreshold) {
        return Math.max(RECOVER_CONFIRM_MIN, failThreshold);
    }

    /** 发起一次探测尝试，返回是否可用；outDetail 写回详情（耗时由调用方计时） */
    private static boolean doProbe(Map<String, Object> task, int timeout, String[] outDetail) throws Exception {
        String type = str(task, "type");
        return switch (type) {
            case "HTTP", "HTTPS" -> probeHttp(task, timeout, outDetail);
            case "TCP" -> probeTcp(task, timeout, outDetail);
            case "UDP" -> probeUdp(task, timeout, outDetail);
            default -> throw new IllegalArgumentException("未知探测类型: " + type);
        };
    }

    private static boolean probeHttp(Map<String, Object> task, int timeout, String[] outDetail) throws Exception {
        String url = str(task, "target");
        String method = str(task, "method");
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout));
        if ("POST".equals(method)) {
            rb.header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.noBody());
        } else {
            rb.GET();
        }
        HttpResponse<String> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        int expect = intVal(task, "expect_status");
        boolean codeOk = expect > 0 ? code == expect : (code >= 200 && code < 400);
        String keyword = str(task, "keyword");
        boolean kwOk = keyword.isEmpty() || (resp.body() != null && resp.body().contains(keyword));
        outDetail[0] = "HTTP " + code + (keyword.isEmpty() ? "" : (kwOk ? " 命中关键字" : " 未命中关键字"));
        return codeOk && kwOk;
    }

    private static boolean probeTcp(Map<String, Object> task, int timeout, String[] outDetail) throws IOException {
        String[] hp = hostPort(task);
        int port;
        try {
            port = Integer.parseInt(hp[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("TCP 探测端口非法: " + hp[1]);
        }
        // 先显式解析地址：UnknownHostException 属 IOException，能被上层当作瞬时故障重试
        // （new InetSocketAddress(host, port) 解析失败时抛的是不可重试的 UnresolvedAddressException）
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(hp[0]), port);
        try (Socket s = new Socket()) {
            s.connect(addr, timeout * 1000);
            outDetail[0] = "TCP 连接成功";
            return true;
        } catch (IOException e) {
            // 向上抛出让周期内重试：连接超时/被拒在链路抖动、目标端 backlog 满时多为一次性
            throw new IOException("TCP 连接失败: " + msg(e), e);
        }
    }

    private static boolean probeUdp(Map<String, Object> task, int timeout, String[] outDetail) throws Exception {
        String[] hp = hostPort(task);
        InetAddress addr = InetAddress.getByName(hp[0]);
        int port = Integer.parseInt(hp[1].trim());
        // DNS 服务器对无关随机报文根本不回包，必须发标准查询，否则永远判故障
        if (port == 53) return probeDns(addr, timeout, outDetail);
        return probeUdpBestEffort(addr, port, timeout, outDetail);
    }

    /**
     * DNS 探测（UDP 53）：发送标准 A 记录查询，收到 ID 匹配且 QR=1 的应答即判可用
     * （NXDOMAIN 等错误码同样证明解析服务存活）；DNS 是有应答服务，超时无应答以 IOException
     * 抛出，交由周期内重试吸收上游递归的偶发慢应答，不在此直接判故障。
     */
    private static boolean probeDns(InetAddress addr, int timeout, String[] outDetail) throws Exception {
        byte[] query = dnsQuery(DNS_PROBE_NAME, ThreadLocalRandom.current().nextInt(0x10000));
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeout * 1000);
            socket.send(new DatagramPacket(query, query.length, addr, 53));
            byte[] buf = new byte[1024];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(resp);
            } catch (SocketTimeoutException e) {
                throw new IOException("DNS 查询 " + DNS_PROBE_NAME + " 超时无应答", e);
            }
            int len = resp.getLength();
            if (len < 12 || (buf[2] & 0x80) == 0
                    || (buf[0] & 0xFF) != (query[0] & 0xFF) || (buf[1] & 0xFF) != (query[1] & 0xFF)) {
                throw new IOException("DNS 应答报文非法（" + len + " 字节）");
            }
            int rcode = buf[3] & 0x0F;
            int answers = ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
            outDetail[0] = "DNS 应答 " + rcodeName(rcode) + "，" + answers + " 条记录";
            return true;
        }
    }

    /**
     * 尽力型 UDP 探测：UDP 无连接，多数服务不会对未知报文回包，因此
     * “收到回包”与“无回包但未收到 ICMP 端口不可达差错”均判可达，仅端口明确不可达时判故障。
     * ICMP 差错只会投递给 connected socket，故先 connect 再收发；首包无回包时补发一包等短窗口。
     */
    private static boolean probeUdpBestEffort(InetAddress addr, int port, int timeout, String[] outDetail) throws Exception {
        byte[] payload = "NEXHOME-UDP-PING\n".getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(addr, port));
            byte[] buf = new byte[256];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            socket.setSoTimeout(timeout * 1000);
            socket.send(new DatagramPacket(payload, payload.length, addr, port));
            for (int round = 0; round < 2; round++) {
                try {
                    socket.receive(reply);
                    outDetail[0] = "UDP 收到回包 " + reply.getLength() + " 字节";
                    return true;
                } catch (PortUnreachableException e) {
                    outDetail[0] = "UDP 端口不可达（ICMP port unreachable）";
                    return false;
                } catch (SocketTimeoutException e) {
                    if (round == 1) break;
                    // 无回包：补发一包并短等，给首包可能延迟到达的 ICMP 差错一次机会
                    socket.send(new DatagramPacket(payload, payload.length, addr, port));
                    socket.setSoTimeout(UDP_ICMP_GRACE_MS);
                }
            }
            outDetail[0] = "UDP 无回包且未收到端口不可达差错，按可达处理";
            return true;
        }
    }

    /** 拼装标准 DNS 查询报文：1 条 A 记录问题，无选项 */
    private static byte[] dnsQuery(String name, int id) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((id >> 8) & 0xFF);
        out.write(id & 0xFF);
        out.write(0x01);  // flags: 期望递归
        out.write(0x00);
        out.write(0x00);  // QDCOUNT = 1
        out.write(0x01);
        out.write(0x00);  // ANCOUNT / NSCOUNT / ARCOUNT = 0
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        for (String label : name.split("\\.")) {
            byte[] lb = label.getBytes(StandardCharsets.US_ASCII);
            if (lb.length == 0 || lb.length > 63) continue;
            out.write(lb.length);
            out.write(lb, 0, lb.length);
        }
        out.write(0);     // 根标签
        out.write(0x00);
        out.write(0x01);  // QTYPE = A
        out.write(0x00);
        out.write(0x01);  // QCLASS = IN
        return out.toByteArray();
    }

    private static String rcodeName(int rcode) {
        return switch (rcode) {
            case 0 -> "NOERROR";
            case 1 -> "FORMERR";
            case 2 -> "SERVFAIL";
            case 3 -> "NXDOMAIN";
            case 4 -> "NOTIMP";
            case 5 -> "REFUSED";
            default -> "RCODE" + rcode;
        };
    }

    private static void alertDown(Map<String, Object> task, String detail, long latencyMs, int fail) {
        Logs.warn(Logs.MONITOR, "监控[" + task.get("name") + "] 不可用: " + detail);
        String msg = "目标: " + str(task, "target") + "\n类型: " + str(task, "type")
                + "\n原因: " + detail + "\n连续失败: " + fail + " 次\n时间: " + Database.now();
        NotifyService.broadcast("monitor.down", "监控告警：" + str(task, "name") + " 不可用", msg);
    }

    private static void notifyIfRecovery(Map<String, Object> task, long latencyMs) {
        Logs.info(Logs.MONITOR, "监控[" + task.get("name") + "] 已恢复");
        if (intVal(task, "notify_on_recovery") != 1) return;
        String msg = "目标: " + str(task, "target") + "\n类型: " + str(task, "type")
                + "\n延迟: " + latencyMs + " ms\n时间: " + Database.now();
        NotifyService.broadcast("monitor.up", "监控恢复：" + str(task, "name") + " 已恢复", msg);
    }

    // ---------- 调度 ----------

    private static void schedule(Map<String, Object> task) {
        long id = ((Number) task.get("id")).longValue();
        cancel(id);
        if (intVal(task, "enabled") != 1) return;
        long interval = Math.max(15, intVal(task, "interval_sec"));
        // 按任务 ID 确定性错峰：否则所有任务都在“保存后 3 秒 + 整间隔”上触发，同一秒齐发冷连接
        // （DNS 解析 + TCP 握手 + TLS 叠在一起）会互相放大时延，一个链路毛刺就把全部任务同时带倒。
        // 偏移只由 id 与 interval 决定，所以编辑任务不会把相位重新拉齐。
        long spread = Math.max(1, interval - PROBE_FIRST_DELAY_SEC);
        long initial = PROBE_FIRST_DELAY_SEC + Math.floorMod(id * 13, spread);
        SCHEDULES.put(id, Tasks.every(initial, interval, () -> POOL.submit(() -> probe(id))));
    }

    private static void cancel(long id) {
        ScheduledFuture<?> f = SCHEDULES.remove(id);
        if (f != null) f.cancel(false);
        SUCCESS_STREAK.remove(id);
    }

    // ---------- 工具 ----------

    private static String[] splitHostPort(String target) {
        // IPv6 字面量带括号：[::1]:53
        if (target.startsWith("[")) {
            int c = target.indexOf("]:");
            if (c < 0 || c == target.length() - 2) return null;
            return new String[]{target.substring(1, c), target.substring(c + 2)};
        }
        int c = target.lastIndexOf(':');
        if (c <= 0 || c == target.length() - 1) return null;
        return new String[]{target.substring(0, c), target.substring(c + 1)};
    }

    /** 取任务的 host/port，格式非法（存量脏数据）时抛出可读异常 */
    private static String[] hostPort(Map<String, Object> task) {
        String[] hp = splitHostPort(str(task, "target"));
        if (hp == null) throw new IllegalArgumentException("探测目标需为 host:port 格式: " + str(task, "target"));
        return hp;
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> task = Database.queryOne("SELECT * FROM monitor_task WHERE id=?", id);
        if (task == null) throw new IllegalArgumentException("监控任务不存在: #" + id);
        return task;
    }

    /** 供仪表盘统计：全部任务的状态摘要 */
    public static List<Map<String, Object>> listAll() throws SQLException {
        return Database.query("SELECT id, name, type, target, enabled, last_status, last_check FROM monitor_task");
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 取异常可读信息，无 message 时退回类名（避免告警详情出现 "null"） */
    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
