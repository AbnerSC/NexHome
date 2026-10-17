package com.nexhome.module.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nexhome.core.AppConfig;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;
import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 代理文件缓存（模仿 nginx proxy_cache 的磁盘缓存，突破内存容量限制）。
 * <p>
 * 布局：{@code data/proxy-cache/{siteId}/{hash前2位}/{hash}.data} 存响应体，
 * 同名 {@code .meta} 存状态码/响应头/过期时间等元数据；写入先落 {@code .tmp} 临时文件，
 * 完成后原子 rename，避免半份缓存。内存索引（key -> Entry）启动时扫描 .meta 重建，缓存跨重启有效。
 * <p>
 * 过期与容量：TTL（上游 Cache-Control 优先或站点 ttlSec 兜底）+ nginx inactive 语义
 * （长期未命中清理）+ 站点总容量 LRU 驱逐；后台周期任务统一执行。
 * key = 站点id + method + 完整 URL（含查询串）；带 Authorization 头的请求不读不写（安全默认）。
 */
public final class ProxyCache {

    /** 缓存根目录 */
    public static final Path ROOT = AppConfig.DATA_DIR.resolve("proxy-cache");

    /** 一条缓存元数据（响应体在 file 中） */
    public static final class Entry {
        public final long siteId;
        public final String key;
        public final Path file;
        public final int status;
        /** 存储的响应头（已剥离逐跳头/Cookie，不含 Content-Length，发送时按文件大小补） */
        public final List<String[]> headers;
        public final long size;
        public volatile long expiresAt;
        public volatile long lastAccess;

        Entry(long siteId, String key, Path file, int status, List<String[]> headers,
              long size, long expiresAt) {
            this.siteId = siteId;
            this.key = key;
            this.file = file;
            this.status = status;
            this.headers = headers;
            this.size = size;
            this.expiresAt = expiresAt;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    /** 一次响应的旁路写入器（流式中继的同时 tee 到缓存文件） */
    public final class Writer {
        private final long siteId;
        private final String key;
        private final Path tmp;
        private FileChannel channel;
        private long written;
        private boolean failed;

        Writer(long siteId, String key, Path tmp) {
            this.siteId = siteId;
            this.key = key;
            this.tmp = tmp;
        }

        /** 追加一段响应体；写失败自动废弃缓存（已中继部分不受影响） */
        public void append(ByteBuf buf) {
            if (failed || !buf.isReadable()) return;
            try {
                if (channel == null) channel = FileChannel.open(tmp,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                // nioBuffer() 不改动读写指针，同一 buf 继续用于向客户端中继
                ByteBuffer bb = buf.nioBuffer();
                while (bb.hasRemaining()) {
                    long n = channel.write(bb);
                    if (n <= 0) throw new IOException("缓存写入停滞");
                }
                written += buf.readableBytes();
            } catch (Exception e) {
                failed = true;
                closeQuietly();
                deleteQuietly(tmp);
            }
        }

        /** 响应正常结束：原子落盘并登记索引。返回是否成功入缓存 */
        public boolean finish(int status, List<String[]> headers, long expiresAt) {
            closeQuietly();
            if (failed || written <= 0) {
                deleteQuietly(tmp);
                return false;
            }
            try {
                Path data = tmp.resolveSibling(keyHash(key) + ".data");
                Files.move(tmp, data, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Entry entry = new Entry(siteId, key, data, status, headers, written, expiresAt);
                writeMeta(entry);
                index.put(keyHash(key), entry);
                siteBytes.computeIfAbsent(siteId, k -> new AtomicLong()).addAndGet(written);
                return true;
            } catch (Exception e) {
                deleteQuietly(tmp);
                Logs.warn(Logs.PROXY, "缓存落盘失败: " + e.getMessage());
                return false;
            }
        }

        /** 响应异常/放弃缓存：清理临时文件 */
        public void abort() {
            closeQuietly();
            deleteQuietly(tmp);
        }

        public long written() {
            return written;
        }

        private void closeQuietly() {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
                channel = null;
            }
        }
    }

    // keyHash -> 内存索引；key 原文存于 Entry/元数据，重启扫描重建
    private static final ConcurrentHashMap<String, Entry> index = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, AtomicLong> siteBytes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, AtomicLong> hits = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, AtomicLong> misses = new ConcurrentHashMap<>();
    /** 站点缓存策略（store 时登记，清理任务读取；缺省 256MB / 7 天 inactive） */
    private static final ConcurrentHashMap<Long, long[]> sitePolicy = new ConcurrentHashMap<>();

    private ProxyCache() {
    }

    /** 启动初始化：扫描缓存目录重建内存索引，孤儿站点目录清理，并挂周期清理任务 */
    public static void init(Predicate<Long> siteExists) {
        try {
            if (!Files.isDirectory(ROOT)) return;
            long now = System.currentTimeMillis();
            try (var dirs = Files.list(ROOT)) {
                for (Path siteDir : dirs.toList()) {
                    long siteId;
                    try {
                        siteId = Long.parseLong(siteDir.getFileName().toString());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (!siteExists.test(siteId)) {
                        purgeSite(siteId); // 站点已删除：清掉整个缓存目录
                        continue;
                    }
                    try (var walk = Files.walk(siteDir)) {
                        for (Path p : walk.filter(f -> f.toString().endsWith(".meta")).toList()) {
                            try {
                                Entry e = readMeta(p, siteId);
                                if (e == null || e.expiresAt < now || !Files.exists(e.file)) {
                                    deleteEntryFiles(e);
                                    continue;
                                }
                                index.put(keyHash(e.key), e);
                                siteBytes.computeIfAbsent(siteId, k -> new AtomicLong()).addAndGet(e.size);
                            } catch (Exception ex) {
                                Logs.warn(Logs.PROXY, "缓存元数据解析失败（跳过）: " + p + " " + ex.getMessage());
                            }
                        }
                    }
                }
            }
            Logs.info(Logs.PROXY, "代理文件缓存已加载：" + index.size() + " 项，共 "
                    + (totalBytes() / 1024 / 1024) + " MB");
        } catch (IOException e) {
            Logs.error(Logs.PROXY, "代理缓存初始化失败: " + e.getMessage());
        }
        Tasks.every(60, 60, ProxyCache::cleanupOnce); // 每分钟：过期/inactive/容量清理
    }

    /** 查缓存：命中返回 Entry（并刷新 lastAccess），未命中/过期返回 null 并计数 */
    public static Entry lookup(long siteId, String key, boolean withAuth) {
        if (withAuth) return null; // 带 Authorization 不读缓存（安全默认）
        Entry e = index.get(keyHash(key));
        if (e == null || e.siteId != siteId) { // 站点路由切换后的陈旧条目按未命中处理
            misses.computeIfAbsent(siteId, k -> new AtomicLong()).incrementAndGet();
            return null;
        }
        if (e.expiresAt < System.currentTimeMillis()) {
            remove(e);
            misses.computeIfAbsent(siteId, k -> new AtomicLong()).incrementAndGet();
            return null;
        }
        e.lastAccess = System.currentTimeMillis();
        hits.computeIfAbsent(siteId, k -> new AtomicLong()).incrementAndGet();
        return e;
    }

    /** 新建旁路写入器（.tmp 临时文件，finish 时原子改名入索引）；失败返回 null */
    public static Writer newWriter(long siteId, String key, ProxyAdvanced adv) {
        try {
            Path dir = dirFor(siteId, key);
            Files.createDirectories(dir);
            String hash = keyHash(key);
            Path tmp = dir.resolve(hash + "." + UUID.randomUUID() + ".tmp");
            sitePolicy.put(siteId, new long[]{(long) adv.cacheMaxMB * 1024 * 1024, adv.cacheInactiveSec});
            return new ProxyCache().new Writer(siteId, key, tmp);
        } catch (IOException e) {
            Logs.warn(Logs.PROXY, "创建缓存写入器失败: " + e.getMessage());
            return null;
        }
    }

    /** 缓存 key：method + 完整 URL（含查询串，scheme+host 由客户端请求 authority 提供） */
    public static String buildKey(String method, String url) {
        return method + " " + url;
    }

    /** 清理某站点全部缓存（删除站点/手动 purge） */
    public static void purgeSite(long siteId) {
        index.values().removeIf(e -> {
            if (e.siteId == siteId) {
                deleteEntryFiles(e);
                return true;
            }
            return false;
        });
        siteBytes.remove(siteId);
        hits.remove(siteId);
        misses.remove(siteId);
        try {
            Path dir = ROOT.resolve(String.valueOf(siteId));
            if (Files.isDirectory(dir)) {
                try (var walk = Files.walk(dir)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            Logs.warn(Logs.PROXY, "清理站点 " + siteId + " 缓存目录失败: " + e.getMessage());
        }
    }

    /** 周期清理：过期删除 -> inactive 驱逐 -> 站点容量 LRU 驱逐 */
    public static void cleanupOnce() {
        long now = System.currentTimeMillis();
        List<Entry> expired = new ArrayList<>();
        index.values().forEach(e -> {
            if (e.expiresAt < now) expired.add(e);
        });
        expired.forEach(ProxyCache::remove);
        Map<Long, List<Entry>> bySite = new ConcurrentHashMap<>();
        index.values().forEach(e -> bySite.computeIfAbsent(e.siteId, k -> new ArrayList<>()).add(e));
        bySite.forEach((siteId, list) -> {
            long[] policy = sitePolicy.getOrDefault(siteId, new long[]{256L * 1024 * 1024, 604800});
            long inactiveMs = policy[1] * 1000;
            List<Entry> inactive = new ArrayList<>();
            for (Entry e : list) {
                if (now - e.lastAccess > inactiveMs) inactive.add(e);
            }
            inactive.forEach(ProxyCache::remove);
            AtomicLong bytes = siteBytes.get(siteId);
            if (bytes == null) return;
            // 容量超限：按 lastAccess 升序 LRU 驱逐
            List<Entry> alive = new ArrayList<>(list);
            alive.removeAll(inactive);
            alive.sort(Comparator.comparingLong(e -> e.lastAccess));
            for (Entry e : alive) {
                if (bytes.get() <= policy[0]) break;
                remove(e);
            }
            if (bytes.get() < 0) siteBytes.put(siteId, new AtomicLong(0));
        });
    }

    /** 状态视图：总占用、条目数、各站点命中/未命中 */
    public static Map<String, Object> statusView() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("totalBytes", totalBytes());
        m.put("entries", index.size());
        List<Map<String, Object>> perSite = new ArrayList<>();
        Map<Long, long[]> agg = new java.util.TreeMap<>();
        hits.forEach((k, v) -> agg.computeIfAbsent(k, x -> new long[2])[0] = v.get());
        misses.forEach((k, v) -> agg.computeIfAbsent(k, x -> new long[2])[1] = v.get());
        agg.forEach((siteId, hv) -> {
            Map<String, Object> s = new java.util.LinkedHashMap<>();
            s.put("siteId", siteId);
            s.put("hits", hv[0]);
            s.put("misses", hv[1]);
            s.put("bytes", siteBytes.containsKey(siteId) ? siteBytes.get(siteId).get() : 0);
            perSite.add(s);
        });
        m.put("sites", perSite);
        return m;
    }

    public static long totalBytes() {
        return siteBytes.values().stream().mapToLong(AtomicLong::get).sum();
    }

    // ---------- 内部 ----------

    private static void remove(Entry e) {
        // 以索引移除为准：并发方已删则跳过，避免容量双扣与重复删文件
        if (index.remove(keyHash(e.key)) == null) return;
        AtomicLong b = siteBytes.get(e.siteId);
        if (b != null) b.addAndGet(-e.size);
        deleteEntryFiles(e);
    }

    private static void deleteEntryFiles(Entry e) {
        if (e == null) return;
        deleteQuietly(e.file);
        deleteQuietly(siblingMeta(e.file));
    }

    private static Path dirFor(long siteId, String key) {
        String hash = keyHash(key);
        return ROOT.resolve(String.valueOf(siteId)).resolve(hash.substring(0, 2));
    }

    private static String keyHash(String key) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static String stripSuffix(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static Path siblingMeta(Path data) {
        return data.resolveSibling(stripSuffix(data.getFileName().toString()) + ".meta");
    }

    private static void writeMeta(Entry e) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("siteId", e.siteId);
        o.addProperty("key", e.key);
        o.addProperty("status", e.status);
        o.addProperty("size", e.size);
        o.addProperty("expiresAt", e.expiresAt);
        JsonArray hs = new JsonArray();
        for (String[] h : e.headers) {
            JsonArray pair = new JsonArray();
            pair.add(h[0]);
            pair.add(h[1]);
            hs.add(pair);
        }
        o.add("headers", hs);
        Files.writeString(siblingMeta(e.file), o.toString(), StandardCharsets.UTF_8);
    }

    private static Entry readMeta(Path meta, long siteId) throws IOException {
        JsonObject o = JsonUtils.parse(Files.readString(meta, StandardCharsets.UTF_8));
        List<String[]> headers = new ArrayList<>();
        if (o.has("headers")) {
            for (JsonElement el : o.getAsJsonArray("headers")) {
                JsonArray pair = el.getAsJsonArray();
                headers.add(new String[]{pair.get(0).getAsString(), pair.get(1).getAsString()});
            }
        }
        Path data = meta.resolveSibling(stripSuffix(meta.getFileName().toString()) + ".data");
        if (!Files.exists(data)) return null;
        return new Entry(siteId, JsonUtils.str(o, "key"), data,
                JsonUtils.num(o, "status", 200), List.copyOf(headers),
                o.has("size") ? o.get("size").getAsLong() : Files.size(data),
                o.has("expiresAt") ? o.get("expiresAt").getAsLong() : 0);
    }

    /** URL 的缓存扩展名（去查询串），无扩展名返回空串 */
    public static String extensionOf(String pathAndQuery) {
        String path = pathAndQuery;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot <= slash + 1) return "";
        String ext = path.substring(dot + 1);
        return ext.length() <= 10 ? ext.toLowerCase(Locale.ROOT) : "";
    }
}
