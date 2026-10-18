package com.nexhome.module.proxy;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 代理访问记录：按 天 × 域名 × 客户端IP 聚合落库（无逐请求明细，仅记录不做统计展示）。
 * <p>
 * 应对长期海量记录（上亿行级）的设计：
 * <ul>
 *   <li><b>按月分表</b>：写入 {@code proxy_visit_stat_yyyymm} 当月表——单表规模与索引深度
 *       可控，upsert 主键查找不随总量退化；将来按天统计时按日期区间定位少量月表，
 *       永不触碰全量大表</li>
 *   <li><b>保留期整表 DROP</b>：过期月表直接 DROP TABLE（元数据操作，瞬时完成、不产生
 *       空洞碎片），不做上亿行的大批量 DELETE；默认保留 12 个月，
 *       app_config {@code proxy.visit.retention_months} 可调</li>
 *   <li><b>不阻塞转发线程</b>：热路径 {@link #record} 仅无锁 {@link LongAdder} 内存累加；
 *       归属地由 {@link GeoResolver} 离线库同步解析（微秒级检索 + 进程缓存）</li>
 *   <li><b>周期落库</b>：每 30 秒把内存增量按目标月表分组、单事务批量 upsert；
 *       落库失败退回内存下轮重试，不丢记录</li>
 * </ul>
 */
final class VisitStats {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 落库周期（秒） */
    private static final int FLUSH_SEC = 30;
    /** 明细保留月数配置键（app_config），默认 {@link #DEFAULT_RETENTION} */
    private static final String KEY_RETENTION = "proxy.visit.retention_months";
    private static final int DEFAULT_RETENTION = 12;

    private static final String TABLE_PREFIX = "proxy_visit_stat_";

    /** 待落库增量：key = day|host|ip -> 自上次落库以来累计的新增访问次数 */
    private static final ConcurrentHashMap<String, LongAdder> PENDING = new ConcurrentHashMap<>();
    /** 已确认存在的月表后缀（yyyyMM）：跨月残留行 upsert 前确保目标表存在、回填限定近期表 */
    private static final Set<String> TABLES = ConcurrentHashMap.newKeySet();
    /** 上次月表巡检日（epochDay）：建新月表 + 清理超期表每日至多一次 */
    private static volatile long lastSweepDay = -1;

    private VisitStats() {
    }

    /** 启动周期落库任务（ProxyService.init 时调用） */
    static void init() {
        Tasks.every(FLUSH_SEC, FLUSH_SEC, VisitStats::flush);
    }

    /**
     * 记录一次代理访问（热路径）：路由命中即计一次（含被访问控制拒绝的请求），
     * 维度按请求 Host 域名 + 客户端 IP；归属地由离线库同步解析即时可得。
     */
    static void record(String host, String ip) {
        String key = LocalDate.now().format(DAY_FMT) + "|" + host + "|" + ip;
        PENDING.computeIfAbsent(key, k -> new LongAdder()).increment();
        GeoResolver.regionOf(ip); // 离线库微秒级检索 + 进程缓存，预热缓存供落库直接取
    }

    /** 立即落库（进程关停时调用，保留最后不足一个周期的增量） */
    static void flushNow() {
        flush();
    }

    // ---------- 内部实现 ----------

    /** 批量落库：先巡检月表，再排空内存增量按目标月表分组 upsert */
    private static synchronized void flush() {
        sweep();
        // 排空内存增量，按行 day 所属月表分组（跨月残留行各自落对应表）
        Map<String, List<Object[]>> byTable = new LinkedHashMap<>();
        if (!PENDING.isEmpty()) {
            for (Iterator<Map.Entry<String, LongAdder>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, LongAdder> e = it.next();
                long v = e.getValue().sumThenReset();
                if (v <= 0) {
                    it.remove(); // 无新增的旧桶：移除，防止 PENDING 无限增长
                    continue;
                }
                String[] k = splitKey(e.getKey());
                GeoResolver.Region g = GeoResolver.regionOf(k[2]); // 离线库同步检索（已缓存纯内存）
                byTable.computeIfAbsent(tableOf(k[0]), x -> new ArrayList<>())
                        .add(new Object[]{k[0], k[1], k[2],
                                g == null ? null : g.country(), g == null ? null : g.province(),
                                g == null ? null : g.city(), v});
            }
        }
        for (Map.Entry<String, List<Object[]>> en : byTable.entrySet()) {
            String suffix = en.getKey();
            try {
                ensureTable(suffix);
                Database.updateBatch("""
                        INSERT INTO %s(day, host, ip, country, province, city, hits, last_at)
                        VALUES(?,?,?,?,?,?,?,datetime('now','localtime'))
                        ON CONFLICT(day, host, ip) DO UPDATE SET
                            hits=hits+excluded.hits,
                            last_at=excluded.last_at,
                            country=COALESCE(NULLIF(%s.country,''), excluded.country),
                            province=COALESCE(NULLIF(%s.province,''), excluded.province),
                            city=COALESCE(NULLIF(%s.city,''), excluded.city)"""
                        .formatted(TABLE_PREFIX + suffix, TABLE_PREFIX + suffix,
                                TABLE_PREFIX + suffix, TABLE_PREFIX + suffix), en.getValue());
            } catch (Exception ex) {
                for (Object[] r : en.getValue()) { // 落库失败：退回内存，下周期重试，不丢记录
                    PENDING.computeIfAbsent(r[0] + "|" + r[1] + "|" + r[2], x -> new LongAdder()).add((Long) r[6]);
                }
                Logs.warn(Logs.PROXY, "访问记录落库失败，将在下周期重试: " + ex.getMessage());
            }
        }
    }

    /**
     * 月表巡检（每日至多一次）：确保当月表存在；登记现存月表；
     * DROP 早于保留期的整月表——元数据级操作瞬时完成，规避大批量 DELETE 的写放大与碎片。
     */
    private static void sweep() {
        LocalDate today = LocalDate.now();
        long epoch = today.toEpochDay();
        if (epoch == lastSweepDay) return;
        try {
            ensureTable(today.format(MONTH_FMT));
            String cutoff = today.minusMonths(retentionMonths()).format(MONTH_FMT);
            for (Map<String, Object> r : Database.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '"
                            + TABLE_PREFIX + "%'")) {
                String name = str(r.get("name"));
                String suffix = name.substring(TABLE_PREFIX.length());
                if (!suffix.matches("\\d{6}")) continue; // 非月表命名（如旧版静态表）不处理
                TABLES.add(suffix);
                if (suffix.compareTo(cutoff) < 0) {
                    Database.update("DROP TABLE IF EXISTS " + name);
                    TABLES.remove(suffix);
                    Logs.info(Logs.PROXY, "访问记录月表已超保留期，整表清理: " + name);
                }
            }
            lastSweepDay = epoch;
        } catch (Exception e) {
            Logs.warn(Logs.PROXY, "访问记录月表巡检失败: " + e.getMessage());
        }
    }

    /** 保留月数：app_config 可调，非法或缺省回退默认值 */
    private static int retentionMonths() {
        try {
            String v = Database.getConfig(KEY_RETENTION);
            if (v != null && !v.isBlank()) {
                int n = Integer.parseInt(v.trim());
                if (n >= 1) return Math.min(n, 120);
            }
        } catch (Exception ignored) {
            // 读取失败按默认值
        }
        return DEFAULT_RETENTION;
    }

    /** 确保月表存在（DDL 与原静态表结构一致，含缓存避免重复执行） */
    private static void ensureTable(String suffix) throws Exception {
        if (TABLES.contains(suffix)) return;
        Database.execScript("""
                CREATE TABLE IF NOT EXISTS %s (
                    day      TEXT NOT NULL,
                    host     TEXT NOT NULL,
                    ip       TEXT NOT NULL,
                    country  TEXT,
                    province TEXT,
                    city     TEXT,
                    hits     INTEGER NOT NULL DEFAULT 0,
                    last_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
                    PRIMARY KEY (day, host, ip)
                )""".formatted(TABLE_PREFIX + suffix));
        TABLES.add(suffix);
    }

    /** day(yyyy-MM-dd) -> 月表后缀 */
    private static String tableOf(String day) {
        return day.substring(0, 7).replace("-", "");
    }

    /** 解析复合键 day|host|ip（host/ip 不含 '|'） */
    private static String[] splitKey(String key) {
        int i1 = key.indexOf('|');
        int i2 = key.indexOf('|', i1 + 1);
        return new String[]{key.substring(0, i1), key.substring(i1 + 1, i2), key.substring(i2 + 1)};
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
