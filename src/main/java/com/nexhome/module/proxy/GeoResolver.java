package com.nexhome.module.proxy;

import com.nexhome.core.AppConfig;
import com.nexhome.core.Logs;
import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端 IP 归属地解析（代理访问记录用）：基于 ip2region 3.x 的 xdb 库文件离线解析，无在线接口依赖。
 * <p>
 * 库文件加载优先级：数据目录外部文件（{@code data/ip2region_v4.xdb}、{@code data/ip2region_v6.xdb}，
 * 便于自行更新库）> jar 内置资源（{@code db/ip2region_v4.xdb}、{@code db/ip2region_v6.xdb}，随 fat-jar
 * 打包）；v4 / v6 两个库独立可选，均采用 VIndexCache 策略：仅向量索引（约 512KB/库）常驻内存，
 * 数据段按需从文件读取，避免 37MB 级 v6 库整体进内存。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>同步即时返回</b>：离线检索为本地文件随机读（微秒级）+ 进程内结果缓存
 *       （同一 IP 仅首次检索），转发线程可直接调用，无需异步回填</li>
 *   <li><b>降级容错</b>：库文件全部缺失或损坏时降级为空实现（{@link #regionOf} 恒返回 null），
 *       不影响代理转发运行</li>
 *   <li><b>地区口径</b>：国内（中国）记录到省份/城市；外国仅记录国家（province/city 置空）；
 *       内网地址（RFC1918 私有段 / 回环 / 链路本地 / ULA / CGNAT）本机判定为「局域网」</li>
 * </ul>
 */
final class GeoResolver {

    /** 归属地：国内 country=中国 + 省/市；外国仅 country；内网 country=局域网 */
    record Region(String country, String province, String city) {
    }

    private static final Region LAN = new Region("局域网", "", "");
    /** 缓存容量上限：超限后仅检索不缓存，防止异常流量撑爆内存（结果 map 只增不减，计数代替 O(n) size） */
    private static final int CACHE_LIMIT = 5000;
    /** jar 内置 xdb 库资源路径：随 fat-jar 打包，外部文件缺失时兜底加载 */
    private static final String BUILTIN_V4 = "db/ip2region_v4.xdb";
    private static final String BUILTIN_V6 = "db/ip2region_v6.xdb";

    /** ip2region v4+v6 统一查询服务；两个库文件均不可用时为 null（降级为空实现） */
    private static volatile Ip2Region searcher;

    /** IP -> 归属地结果缓存；检索不到的 IP 缓存 LAN 哨兵，避免重复检索 */
    private static final Map<String, Region> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger cacheCount = new AtomicInteger();

    private GeoResolver() {
    }

    /** 由 {@code ProxyService.init()} 调用：加载 xdb 库（失败降级，不抛异常） */
    static void load() {
        Config v4 = buildConfig(AppConfig.DATA_DIR.resolve("ip2region_v4.xdb"), BUILTIN_V4, true);
        Config v6 = buildConfig(AppConfig.DATA_DIR.resolve("ip2region_v6.xdb"), BUILTIN_V6, false);
        if (v4 == null && v6 == null) {
            Logs.warn(Logs.PROXY, "未加载到任何 IP 归属地库文件（data/ip2region_v4.xdb 或 v6），归属地留空");
            return;
        }
        try {
            searcher = Ip2Region.create(v4, v6);
            Logs.info(Logs.PROXY, "IP 归属地库已加载: v4=" + (v4 != null) + ", v6=" + (v6 != null));
        } catch (Exception e) {
            Logs.warn(Logs.PROXY, "IP 归属地库初始化失败，归属地留空: " + e.getMessage());
        }
    }

    /** 关停释放（shutdown hook 调用） */
    static void close() {
        Ip2Region s = searcher;
        searcher = null;
        if (s != null) {
            try {
                s.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 查询 IP 归属地（同步即时返回）：内网即时判定；其余命中缓存直接返回；
     * 未命中做一次本地 xdb 检索（微秒级文件随机读）并缓存。库不可用或无记录时返回 null。
     */
    static Region regionOf(String ip) {
        if (isLan(ip)) return LAN;
        Ip2Region s = searcher;
        if (s == null || ip == null || ip.isBlank()) return null;
        String key = ip.trim();
        Region r = CACHE.get(key);
        if (r != null) return r == LAN ? null : r; // LAN 哨兵 = 检索无记录
        r = search(s, key);
        // putIfAbsent 保证并发下同一 key 只计数一次；达上限后仅检索不缓存
        if (cacheCount.get() < CACHE_LIMIT
                && CACHE.putIfAbsent(key, r == null ? LAN : r) == null) {
            cacheCount.incrementAndGet();
        }
        return r;
    }

    /** xdb 检索并归一化口径：国内保留省/市，外国仅国家；非法地址/无记录返回 null */
    private static Region search(Ip2Region s, String ip) {
        try {
            String region = s.search(ip); // “国家|区域|省份|城市|ISP”
            if (region == null || region.isBlank()) return null;
            String[] parts = region.split("\\|", -1);
            String country = clean(parts, 0);
            if (country.isEmpty()) return null;
            if ("中国".equals(country)) {
                return new Region("中国", clean(parts, 2), clean(parts, 3));
            }
            return new Region(country, "", "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null; // 非法地址或检索失败，按无记录处理
        }
    }

    /** 取分段值并过滤空串与 "0" 占位符 */
    private static String clean(String[] parts, int idx) {
        if (idx >= parts.length) return "";
        String v = parts[idx].trim();
        return v.isEmpty() || "0".equals(v) ? "" : v;
    }

    /**
     * 构造单侧库配置：优先数据目录外部文件（可自行更新库），缺失时回退 jar 内置资源，
     * 均不可用时返回 null（该侧跳过）。
     */
    private static Config buildConfig(Path xdbFile, String builtinResource, boolean v4) {
        String tag = v4 ? "v4" : "v6";
        boolean external = Files.exists(xdbFile);
        try {
            // VIndexCache：仅向量索引（约 512KB/库）进内存，数据段按需读取，
            // 避免 BufferCache 整体加载 37MB 级 v6 库；
            // 注意：ip2region 仅 BufferCache 支持 InputStream，故内置库需先释放为临时文件
            var builder = Config.custom().setCachePolicy(Config.VIndexCache);
            String source;
            if (external) {
                builder.setXdbFile(xdbFile.toFile());
                source = xdbFile.toAbsolutePath().toString();
            } else {
                Path tmp = extractBuiltin(builtinResource, tag);
                if (tmp == null) {
                    Logs.warn(Logs.PROXY, tag + " IP 归属地库既无外部文件也无内置资源 " + builtinResource
                            + "，" + tag + " 归属地留空");
                    return null;
                }
                builder.setXdbFile(tmp.toFile());
                source = "classpath:" + builtinResource;
            }
            Config config = v4 ? builder.asV4() : builder.asV6();
            Logs.info(Logs.PROXY, tag + " IP 归属地库已加载: " + source);
            return config;
        } catch (Exception e) {
            Logs.warn(Logs.PROXY, tag + " IP 库文件加载失败，" + tag + " 归属地留空: " + e.getMessage());
            return null;
        }
    }

    /**
     * 将 jar 内置 xdb 库释放到临时文件（ip2region 仅 BufferCache 支持流式加载，
     * VIndexCache 需要可随机读取的文件）。
     *
     * @return 临时文件路径（进程退出时自动删除）；内置资源不存在或释放失败时返回 null
     */
    private static Path extractBuiltin(String builtinResource, String tag) {
        byte[] content;
        try (InputStream in = GeoResolver.class.getResourceAsStream("/" + builtinResource)) {
            if (in == null) return null;
            content = in.readAllBytes();
        } catch (IOException e) {
            Logs.warn(Logs.PROXY, tag + " IP 内置库读取失败 " + builtinResource + ": " + e.getMessage());
            return null;
        }
        try {
            Path tmp = Files.createTempFile("ip2region-" + tag + "-", ".xdb");
            tmp.toFile().deleteOnExit();
            Files.write(tmp, content);
            return tmp;
        } catch (IOException e) {
            Logs.warn(Logs.PROXY, tag + " IP 内置库释放临时文件失败: " + e.getMessage());
            return null;
        }
    }

    /** 内网/非公网判定：IPv4 私有段、回环、链路本地、CGNAT；IPv6 回环、链路本地、ULA */
    private static boolean isLan(String ip) {
        if (ip == null || ip.isEmpty()) return true;
        String v = ip;
        if (v.startsWith("::ffff:") && v.indexOf('.') > 0) v = v.substring(7); // IPv4-mapped IPv6
        if (v.indexOf(':') >= 0) {
            String lower = v.toLowerCase();
            return lower.equals("::1") || lower.startsWith("fe80:")
                    || lower.startsWith("fc") || lower.startsWith("fd");
        }
        String[] p = v.split("\\.");
        if (p.length != 4) return true; // 非法格式不检索
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || a == 127 || a == 192 && b == 168
                    || a == 172 && b >= 16 && b <= 31
                    || a == 169 && b == 254
                    || a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
