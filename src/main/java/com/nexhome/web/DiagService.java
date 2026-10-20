package com.nexhome.web;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;
import com.nexhome.module.proxy.ProxyCache;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * 内存诊断服务：轻量周期采样 JVM/进程/容器内存，构建时间线环形缓冲，
 * 并联动 {@code op_log} 做「内存跳变点 ↔ 日志事件」的时间线关联分析。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>只读、零依赖</b>：全部取自 JDK 管理 Bean（{@link MemoryMXBean}/{@link BufferPoolMXBean}/
 *       {@link com.sun.management.OperatingSystemMXBean} 等）与 {@code /proc/self/status} 的 VmRSS，
 *       不引入 agent；周期采样只给出 NIO 直接缓冲。若启动时开了 {@code -XX:NativeMemoryTracking}，
 *       可另经 {@code /api/diag/nmt} 「按需」读取 NMT 汇总把堆外（含 Netty 归入的 Internal）拆出来，
 *       该路径不常驻、带缓存，未开启或不可用时优雅降级。</li>
 *   <li><b>有界环形缓冲</b>：默认每 {@value #INTERVAL_SEC} 秒一个样本、保留 {@value #CAPACITY} 个（约 3 小时），
 *       样本量与内存占用恒定，不随运行时长增长。</li>
 *   <li><b>采样足够轻</b>：单次仅数次 MXBean 读取 + 一个小文件读，走 {@link Tasks} 虚拟线程执行，
 *       不占用调度池，符合既有线程隔离规范。</li>
 *   <li><b>关联分析</b>：在服务端扫描时间线求最大单周期涨幅（容器内存或堆），取该时刻前后窗口内的
 *       op_log 事件与每分钟日志密度，直接给出「内存是在哪一分钟、跟着哪条日志跳上去的」。</li>
 * </ul>
 * 局限：时间线自本次进程启动开始采集，进程启动前发生的内存跳变无法回溯（无历史内存序列），
 * 但对「装好后持续观察、复现下一次上涨并自动关联日志」完全够用。
 */
public final class DiagService {

    /** 采样间隔（秒） */
    private static final int INTERVAL_SEC = 10;
    /** 环形缓冲容量：10s × 1080 ≈ 3 小时 */
    private static final int CAPACITY = 1080;
    /** 关联分析取跳变点前后的日志窗口（秒） */
    private static final int LOG_WINDOW_SEC = 120;
    /** NMT 明细缓存有效期（毫秒）：按需生成、多次刷新不重复拿 NMT 锁 */
    private static final long NMT_TTL_MS = 60_000;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final ArrayDeque<Map<String, Object>> HISTORY = new ArrayDeque<>();
    private static volatile Map<String, Object> latest = Map.of();

    // NMT 明细按需缓存（仅在访问 /api/diag/nmt 时生成，正常采样/日常运行不触发）
    private static volatile Map<String, Object> nmtCache;
    private static volatile long nmtCacheAt;
    // 启动时的 NMT 基线（分类名 → committed MB）：与当前值作差即「哪一类原生内存长大了」
    private static volatile Map<String, Double> nmtBaseline;
    private static volatile String nmtBaselineTime;

    private DiagService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/diag/snapshot", ctx -> ctx.ok(snapshot()));
        WebServer.route("GET", "/api/diag/analysis", ctx -> ctx.ok(analysis(ctx.query("metric"))));
        // 堆外原生内存明细：仅在诊断页点击时按需调用（带缓存），不进入 10s 采样器
        WebServer.route("GET", "/api/diag/nmt", ctx -> ctx.ok(nmtView()));
    }

    /** 启动周期采样（先立即采一次，保证进页面即有数据） */
    public static void init() {
        sample();
        Tasks.every(INTERVAL_SEC, INTERVAL_SEC, DiagService::sample);
        if (nmtEnabled()) {
            // 启动即取一次 NMT 基线（一次性，几毫秒）：之后差值可直接定位原生内存增长类别
            Tasks.run(() -> {
                Map<String, Object> b = collectNmt();
                Map<String, Double> m = catCommittedMap(b);
                if (m != null && !m.isEmpty()) {
                    nmtBaseline = m;
                    nmtBaselineTime = TS.format(Instant.now());
                }
            });
        }
        Logs.info(Logs.SYS, "内存诊断采样已启动：间隔 " + INTERVAL_SEC + " 秒，保留 " + CAPACITY + " 个样本");
    }

    // ---------- 采样 ----------

    private static void sample() {
        try {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = mem.getHeapMemoryUsage();
            MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();

            long metaspace = poolUsed("Metaspace");
            long codecache = poolUsedPrefix("CodeHeap");

            long gcCount = 0, gcTime = 0;
            for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (g.getCollectionCount() > 0) gcCount += g.getCollectionCount();
                if (g.getCollectionTime() > 0) gcTime += g.getCollectionTime();
            }
            ThreadMXBean tb = ManagementFactory.getThreadMXBean();

            long dirUsed = 0, dirCap = 0, dirCount = 0, mapUsed = 0;
            for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if ("direct".equals(b.getName())) {
                    dirUsed = b.getMemoryUsed();
                    dirCap = b.getTotalCapacity();
                    dirCount = b.getCount();
                } else if ("mapped".equals(b.getName())) {
                    mapUsed = b.getMemoryUsed();
                }
            }

            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long memTotal = os.getTotalMemorySize();
            long memUsed = memTotal - os.getFreeMemorySize();
            long rss = readRssBytes();

            long cacheBytes = 0;
            int cacheEntries = 0;
            try {
                Map<String, Object> cv = ProxyCache.statusView();
                if (cv.get("totalBytes") instanceof Number n) cacheBytes = n.longValue();
                if (cv.get("entries") instanceof Number n) cacheEntries = n.intValue();
            } catch (Exception ignored) {
                // 代理未初始化时忽略
            }

            long now = System.currentTimeMillis();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("t", now);
            s.put("time", TS.format(Instant.ofEpochMilli(now)));
            s.put("heap", mb(heap.getUsed()));
            s.put("heapC", mb(heap.getCommitted()));
            s.put("heapM", mb(heap.getMax()));
            s.put("nonHeap", mb(nonHeap.getUsed()));
            s.put("meta", mb(metaspace));
            s.put("code", mb(codecache));
            s.put("gc", gcCount);
            s.put("gcMs", gcTime);
            s.put("thr", tb.getThreadCount());
            s.put("dir", mb(dirUsed));
            s.put("dirCap", mb(dirCap));
            s.put("dirCnt", dirCount);
            s.put("mapped", mb(mapUsed));
            s.put("rss", mb(rss));
            s.put("mem", mb(memUsed));
            s.put("memM", mb(memTotal));
            s.put("cacheN", cacheEntries);
            s.put("cacheMB", mb(cacheBytes));

            latest = s;
            synchronized (HISTORY) {
                HISTORY.addLast(s);
                while (HISTORY.size() > CAPACITY) HISTORY.removeFirst();
            }
        } catch (Exception e) {
            Logs.warn(Logs.SYS, "内存诊断采样失败: " + e.getMessage());
        }
    }

    // ---------- 接口数据 ----------

    private static Map<String, Object> snapshot() {
        List<Map<String, Object>> hist;
        synchronized (HISTORY) {
            hist = new ArrayList<>(HISTORY);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intervalSec", INTERVAL_SEC);
        out.put("capacity", CAPACITY);
        out.put("count", hist.size());
        out.put("latest", latest);
        out.put("history", hist);
        out.put("jvm", jvmInfo());
        return out;
    }

    private static Map<String, Object> jvmInfo() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        List<String> args = rt.getInputArguments();
        boolean nmt = args.stream().anyMatch(a -> a.contains("NativeMemoryTracking"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vm", rt.getVmName() + " " + rt.getVmVersion());
        m.put("uptimeSec", rt.getUptime() / 1000);
        m.put("maxHeapMB", mb(Runtime.getRuntime().maxMemory()));
        m.put("processors", Runtime.getRuntime().availableProcessors());
        m.put("args", args);
        m.put("nmt", nmt);
        return m;
    }

    // ---------- 堆外原生内存明细（按需 + 缓存 + 降级） ----------

    /** 对外入口：命中缓存直接返回，过期才重新生成（避免多次刷新反复拿 NMT 锁） */
    private static Map<String, Object> nmtView() {
        Map<String, Object> c = nmtCache;
        if (c != null && System.currentTimeMillis() - nmtCacheAt < NMT_TTL_MS) return c;
        Map<String, Object> r = collectNmt();
        nmtCache = r;
        nmtCacheAt = System.currentTimeMillis();
        return r;
    }

    /**
     * 通过进程内 JMX 的 DiagnosticCommand MBean 调用 {@code VM.native_memory summary}（与 jcmd 同一入口），
     * 不需 Attach API、不需额外启动参数、不起额外线程；不可用时无需中断其它指标。
     */
    private static Map<String, Object> collectNmt() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!nmtEnabled()) {
            out.put("available", false);
            out.put("reason", "未开启 NMT：需在 JVM 启动参数加 -XX:NativeMemoryTracking=summary 后重启");
            return out;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
            String text = (String) server.invoke(
                    name, "vmNativeMemory",
                    new Object[]{new String[]{"summary"}},
                    new String[]{"[Ljava.lang.String;"});
            parseNmt(text, out);
            out.put("available", true);
            appendBaselineDiff(out);
        } catch (Exception e) {
            // 精简 JRE / MBean 不可用 / 命令失败：优雅降级，仅提示不影响其它诊断数据
            // 带上异常类型，避免 getMessage() 为 null 时无法定位
            String why = e.getMessage();
            out.put("available", false);
            out.put("reason", "读取 NMT 失败：" + e.getClass().getSimpleName()
                    + (why == null || why.isBlank() ? "" : " - " + why));
        }
        return out;
    }

    private static boolean nmtEnabled() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments()
                .stream().anyMatch(a -> a.contains("NativeMemoryTracking"));
    }

    // 分类行形如：-      Java Heap (reserved=NNNKB, committed=MMMKB[, Max. Commit=KKKKB])
    private static final Pattern NMT_CAT =
            Pattern.compile("^-\\s+(.+?)\\s+\\(reserved=(\\d+)KB,\\s*committed=(\\d+)KB(?:,\\s*Max. Commit=(-?\\d+)KB)?\\)");
    private static final Pattern NMT_RES = Pattern.compile("reserved=(\\d+)KB");
    private static final Pattern NMT_COM = Pattern.compile("committed=(\\d+)KB");

    /** 解析 summary 文本：Total 取文中首个 reserved/committed（Total 行在所有分类之前），分类按 committed 降序 */
    private static void parseNmt(String text, Map<String, Object> out) {
        List<Map<String, Object>> cats = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher m = NMT_CAT.matcher(line);
            if (m.find()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", m.group(1).trim());
                c.put("reservedMB", kbToMb(Long.parseLong(m.group(2))));
                c.put("committedMB", kbToMb(Long.parseLong(m.group(3))));
                cats.add(c);
            }
        }
        cats.sort(Comparator.comparingDouble((Map<String, Object> c) ->
                ((Number) c.get("committedMB")).doubleValue()).reversed());
        Matcher tr = NMT_RES.matcher(text);
        if (tr.find()) out.put("totalReservedMB", kbToMb(Long.parseLong(tr.group(1))));
        Matcher tc = NMT_COM.matcher(text);
        if (tc.find()) out.put("totalCommittedMB", kbToMb(Long.parseLong(tc.group(1))));
        out.put("categories", cats);
    }

    private static double kbToMb(long kb) {
        return Math.round(kb / 102.4) / 10.0;
    }

    private static Map<String, Double> catCommittedMap(Map<String, Object> parsed) {
        Object cats = parsed.get("categories");
        if (!(cats instanceof List<?> list)) return null;
        Map<String, Double> m = new LinkedHashMap<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> c && c.get("name") instanceof String name
                    && c.get("committedMB") instanceof Number n) {
                m.put(name, n.doubleValue());
            }
        }
        return m;
    }

    /** 附加工具：当前各分类与启动基线的 committed 差值（按增幅降序），定位原生内存增长类别 */
    private static void appendBaselineDiff(Map<String, Object> out) {
        Map<String, Double> base = nmtBaseline;
        if (base == null || base.isEmpty()) return;
        Map<String, Double> now = catCommittedMap(out);
        if (now == null) return;
        List<Map<String, Object>> diff = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, Double> e : now.entrySet()) {
            double d = e.getValue() - base.getOrDefault(e.getKey(), 0.0);
            total += d;
            if (Math.abs(d) >= 0.05) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", e.getKey());
                row.put("baseMB", round1(base.getOrDefault(e.getKey(), 0.0)));
                row.put("nowMB", round1(e.getValue()));
                row.put("deltaMB", round1(d));
                diff.add(row);
            }
        }
        diff.sort(Comparator.comparingDouble((Map<String, Object> r) ->
                ((Number) r.get("deltaMB")).doubleValue()).reversed());
        out.put("baselineTime", nmtBaselineTime);
        out.put("baselineTotalMB", round1(base.values().stream().mapToDouble(Double::doubleValue).sum()));
        out.put("totalDeltaMB", round1(total));
        out.put("diff", diff);
    }

    /** 关联分析：定位时间线中的最大内存跳变，并取该时刻前后窗口的 op_log 事件 */
    private static Map<String, Object> analysis(String metric) {
        boolean byHeap = "heap".equals(metric);
        String key = byHeap ? "heap" : "mem";
        List<Map<String, Object>> hist;
        synchronized (HISTORY) {
            hist = new ArrayList<>(HISTORY);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", byHeap ? "heap" : "container");
        out.put("metricLabel", byHeap ? "JVM 堆" : "容器内存");
        out.put("count", hist.size());

        // 1) 增长概览 + 最大单周期跳变
        Map<String, Object> jump = null;
        if (hist.size() >= 2) {
            double first = num(hist.get(0), key), last = num(hist.get(hist.size() - 1), key);
            out.put("firstMB", round1(first));
            out.put("lastMB", round1(last));
            out.put("growthMB", round1(last - first));
            double maxDelta = 0;
            int maxIdx = -1;
            for (int i = 1; i < hist.size(); i++) {
                double d = num(hist.get(i), key) - num(hist.get(i - 1), key);
                if (d > maxDelta) {
                    maxDelta = d;
                    maxIdx = i;
                }
            }
            if (maxIdx > 0 && maxDelta > 0) {
                jump = new LinkedHashMap<>();
                jump.put("time", hist.get(maxIdx).get("time"));
                jump.put("ts", hist.get(maxIdx).get("t"));
                jump.put("fromMB", round1(num(hist.get(maxIdx - 1), key)));
                jump.put("toMB", round1(num(hist.get(maxIdx), key)));
                jump.put("deltaMB", round1(maxDelta));
            }
        }
        out.put("jump", jump);

        // 2) 日志密度（覆盖时间线跨度；无样本则回退最近 60 分钟）
        String start = hist.isEmpty()
                ? TS.format(Instant.now().minusSeconds(3600))
                : String.valueOf(hist.get(0).get("time"));
        out.put("density", query(
                "SELECT substr(time,1,16) AS minute, COUNT(*) AS c FROM op_log "
                        + "WHERE time>=? GROUP BY minute ORDER BY minute", start));

        // 3) 跳变点前后窗口的日志事件
        if (jump != null) {
            long ts = ((Number) jump.get("ts")).longValue();
            String from = TS.format(Instant.ofEpochMilli(ts - LOG_WINDOW_SEC * 1000L));
            String to = TS.format(Instant.ofEpochMilli(ts + LOG_WINDOW_SEC * 1000L));
            out.put("logsAround", query(
                    "SELECT time, module, level, message FROM op_log "
                            + "WHERE time BETWEEN ? AND ? ORDER BY time LIMIT 300", from, to));
        } else {
            out.put("logsAround", List.of());
        }

        // 4) 关键事件：缓存加载 / 重启 / 监听启动（这些是内存台阶上涨的高发点）
        out.put("notable", query(
                "SELECT time, module, message FROM op_log WHERE "
                        + "message LIKE '%缓存已加载%' OR message LIKE '%启动完成%' "
                        + "OR message LIKE '%即将重启%' OR message LIKE '%重启并应用%' "
                        + "OR message LIKE '%监听已启动%' ORDER BY id DESC LIMIT 50"));
        return out;
    }

    // ---------- 工具 ----------

    private static List<Map<String, Object>> query(String sql, Object... params) {
        try {
            return Database.query(sql, params);
        } catch (Exception e) {
            Logs.warn(Logs.SYS, "诊断查询失败: " + e.getMessage());
            return List.of();
        }
    }

    private static long poolUsed(String name) {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (name.equals(p.getName())) return p.getUsage().getUsed();
        }
        return 0;
    }

    private static long poolUsedPrefix(String prefix) {
        long s = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getName().startsWith(prefix)) s += p.getUsage().getUsed();
        }
        return s;
    }

    /** 读 /proc/self/status 的 VmRSS（进程常驻物理内存，kB）；非 Linux 或读不到返回 -1 */
    private static long readRssBytes() {
        try {
            Path p = Path.of("/proc/self/status");
            if (!Files.isReadable(p)) return -1;
            for (String line : Files.readAllLines(p)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Exception ignored) {
            // 非 Linux 环境无此文件
        }
        return -1;
    }

    private static double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** 字节转 MB（保留 1 位小数）；负值（不可用）原样返回 -1 */
    private static double mb(long bytes) {
        if (bytes < 0) return -1;
        return Math.round(bytes / 104857.6) / 10.0;
    }
}
