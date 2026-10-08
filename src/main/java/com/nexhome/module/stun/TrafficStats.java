package com.nexhome.module.stun;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 穿透通道流量统计：按任务累积转发字节数（双向合计），连接重建不清零。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>不影响转发性能</b>：热路径 {@link #record} 仅做无锁 {@link LongAdder} 累加，
 *       不逐包写库；时间桶键（时/天/月）每任务缓存 1 秒，避免逐块计算时间字符串</li>
 *   <li><b>重建不清零</b>：计数以 task_id 为维度累积，与 socket 生命周期无关，
 *       链路轮换/弹跳重建/任务重启（同 id）均延续</li>
 *   <li><b>周期落库</b>：{@link #flush} 每 30 秒把内存增量以单事务批量 upsert 到 stun_traffic，
 *       按 TOTAL/HOUR/DAY/MONTH 归档；落库失败退回内存下轮重试，不丢统计</li>
 *   <li><b>实时展示</b>：查询把已落库值与内存未落库增量合并，展示接近实时</li>
 * </ul>
 */
public final class TrafficStats {

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 落库周期（秒）：足够低频以不影响转发/数据库，又让展示保持较新 */
    private static final int FLUSH_SEC = 30;

    /** 待落库增量：key = taskId|periodType|periodKey -> 该桶自上次落库以来累计的新增字节 */
    private static final ConcurrentHashMap<String, LongAdder> PENDING = new ConcurrentHashMap<>();
    /** 每任务的时间桶键缓存：避免逐块计算时间字符串，键最多每 1 秒刷新一次 */
    private static final ConcurrentHashMap<Long, Buckets> BUCKETS = new ConcurrentHashMap<>();

    private TrafficStats() {
    }

    /** 启动周期落库任务（进程启动时调用一次） */
    public static void init() {
        Tasks.every(FLUSH_SEC, FLUSH_SEC, TrafficStats::flush);
    }

    /**
     * 记录一次转发的字节数（热路径）：按任务的 TOTAL/HOUR/DAY/MONTH 四个桶无锁累加。
     * 由 UDP 转发与 TCP 管道在每块数据发送后调用，成本仅数次 LongAdder.add。
     */
    public static void record(long taskId, long bytes) {
        if (bytes <= 0) return;
        Buckets b = BUCKETS.computeIfAbsent(taskId, Buckets::new);
        long now = System.currentTimeMillis();
        if (now >= b.keysUntil) b.roll(now); // 时间桶滚动（最多每秒一次，synchronized 内重算键）
        add(b.totalKey, bytes);
        add(b.hourKey, bytes);
        add(b.dayKey, bytes);
        add(b.monthKey, bytes);
    }

    private static void add(String key, long bytes) {
        PENDING.computeIfAbsent(key, k -> new LongAdder()).add(bytes);
    }

    /** 任务累计总流量（已落库 + 内存实时增量），单位字节 */
    public static long totalBytes(long taskId) {
        long db = 0;
        try {
            Object v = Database.scalar("SELECT bytes FROM stun_traffic WHERE task_id=? AND period_type='TOTAL' AND period_key='all'", taskId);
            if (v instanceof Number n) db = n.longValue();
        } catch (Exception ignored) {
            // 查询失败：仅返回内存增量
        }
        LongAdder a = PENDING.get(taskId + "|TOTAL|all");
        return db + (a == null ? 0 : a.sum());
    }

    /** 任务流量明细：总量 + 按小时/天/月归档序列（均合并内存未落库增量，按时间倒序） */
    public static Map<String, Object> detail(long taskId) {
        Map<String, Long> pend = pendingFor(taskId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", totalBytes(taskId));
        res.put("hours", series(taskId, "HOUR", pend, 48));
        res.put("days", series(taskId, "DAY", pend, 60));
        res.put("months", series(taskId, "MONTH", pend, 24));
        return res;
    }

    /** 删除任务时清理其内存计数与落库数据 */
    public static void clear(long taskId) {
        BUCKETS.remove(taskId);
        String prefix = taskId + "|";
        PENDING.keySet().removeIf(k -> k.startsWith(prefix));
        try {
            Database.update("DELETE FROM stun_traffic WHERE task_id=?", taskId);
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "清理任务流量统计失败: " + e.getMessage());
        }
    }

    /** 立即落库（进程关停时调用，保留最后不足一个周期的增量） */
    public static void flushNow() {
        flush();
    }

    // ---------- 内部实现 ----------

    /** 每任务缓存的当前时间桶键：TOTAL 键恒定，HOUR/DAY/MONTH 键随时间滚动（缓存 1s 降低计算频率） */
    private static final class Buckets {
        final long taskId;
        final String totalKey;
        volatile String hourKey;
        volatile String dayKey;
        volatile String monthKey;
        volatile long keysUntil;

        Buckets(long taskId) {
            this.taskId = taskId;
            this.totalKey = taskId + "|TOTAL|all";
            roll(System.currentTimeMillis());
        }

        synchronized void roll(long now) {
            LocalDateTime t = LocalDateTime.now();
            this.hourKey = taskId + "|HOUR|" + t.format(HOUR_FMT);
            this.dayKey = taskId + "|DAY|" + t.format(DAY_FMT);
            this.monthKey = taskId + "|MONTH|" + t.format(MONTH_FMT);
            this.keysUntil = now + 1000;
        }
    }

    /** 收集本任务当前全部内存增量，键为 periodType|periodKey */
    private static Map<String, Long> pendingFor(long taskId) {
        Map<String, Long> m = new HashMap<>();
        String idStr = String.valueOf(taskId);
        for (Map.Entry<String, LongAdder> e : PENDING.entrySet()) {
            String[] k = splitKey(e.getKey());
            if (k[0].equals(idStr)) m.merge(k[1] + "|" + k[2], e.getValue().sum(), Long::sum);
        }
        return m;
    }

    /** 查询某维度时间序列（DB 已落库 + 内存增量合并，按 period_key 倒序取前 limit 条） */
    private static List<Map<String, Object>> series(long taskId, String type, Map<String, Long> pend, int limit) {
        Map<String, Long> map = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : Database.query(
                    "SELECT period_key, bytes FROM stun_traffic WHERE task_id=? AND period_type=? ORDER BY period_key DESC LIMIT ?",
                    taskId, type, limit)) {
                Object key = r.get("period_key");
                Object bytes = r.get("bytes");
                map.put(String.valueOf(key), bytes instanceof Number n ? n.longValue() : 0L);
            }
        } catch (Exception ignored) {
            // 查询失败：仅展示内存增量
        }
        String tp = type + "|";
        for (Map.Entry<String, Long> e : pend.entrySet()) {
            if (e.getKey().startsWith(tp)) map.merge(e.getKey().substring(tp.length()), e.getValue(), Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByKey().reversed())
                .limit(limit)
                .forEach(en -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", en.getKey());
                    m.put("bytes", en.getValue());
                    out.add(m);
                });
        return out;
    }

    /** 批量落库：排空内存增量单事务 upsert 到 stun_traffic；失败退回内存下轮重试 */
    private static void flush() {
        if (PENDING.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (Iterator<Map.Entry<String, LongAdder>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, LongAdder> e = it.next();
            long v = e.getValue().sumThenReset();
            if (v <= 0) {
                it.remove(); // 旧时间桶已无新增：移除，防止 PENDING 无限增长
                continue;
            }
            String[] k = splitKey(e.getKey());
            rows.add(new Object[]{Long.parseLong(k[0]), k[1], k[2], v});
        }
        if (rows.isEmpty()) return;
        try {
            Database.updateBatch("""
                    INSERT INTO stun_traffic(task_id, period_type, period_key, bytes, updated_at)
                    VALUES(?,?,?,?,datetime('now','localtime'))
                    ON CONFLICT(task_id, period_type, period_key)
                    DO UPDATE SET bytes=bytes+excluded.bytes, updated_at=excluded.updated_at""", rows);
        } catch (Exception ex) {
            for (Object[] r : rows) { // 落库失败：退回内存，下周期重试，不丢统计
                String key = r[0] + "|" + r[1] + "|" + r[2];
                PENDING.computeIfAbsent(key, x -> new LongAdder()).add((Long) r[3]);
            }
            Logs.warn(Logs.STUN, "流量统计落库失败，将在下周期重试: " + ex.getMessage());
        }
    }

    /** 解析复合键 taskId|periodType|periodKey（periodKey 含空格/冒号但不含 '|'） */
    private static String[] splitKey(String key) {
        int i1 = key.indexOf('|');
        int i2 = key.indexOf('|', i1 + 1);
        return new String[]{key.substring(0, i1), key.substring(i1 + 1, i2), key.substring(i2 + 1)};
    }
}
