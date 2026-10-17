package com.nexhome.module.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.ReadTimeoutException;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * 后端响应中继（普通消息级路径）：改写响应头后流式回写前端连接；
 * 满足缓存条件时旁路 tee 写入 {@link ProxyCache} 文件缓存。
 * 后端连接与前端连接绑定在同一事件循环（ProxyCenter.connectBackend），
 * 全部读写在单线程内完成，无跨线程时序问题。
 */
final class BackendRelay extends SimpleChannelInboundHandler<Object> {

    private final ProxyHandler.Tx tx;
    private final boolean keepAlive;

    // 缓存旁路状态
    private boolean headSeen;
    private int status;
    private ProxyCache.Writer writer;
    private List<String[]> storeHeaders;
    private long expiresAt;
    private long maxItemBytes;

    BackendRelay(ProxyHandler.Tx tx, boolean keepAlive) {
        this.tx = tx;
        this.keepAlive = keepAlive;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        ProxySite site = tx.site;
        if (msg instanceof HttpResponse resp) {
            headSeen = true;
            status = resp.status().code();
            DefaultHttpResponse out = new DefaultHttpResponse(HttpVersion.HTTP_1_1, resp.status(),
                    new DefaultHttpHeaders());
            ProxyHandler.copyHeaders(resp.headers(), out.headers());
            ProxyHandler.applyHeaderOps(site.adv().respHeaders, out.headers());

            // 缓存判定：开启 + GET + 200 + 扩展名命中 + 上游策略允许；
            // Vary 变体响应不缓存（缓存 key 无变体维度，直存会导致不同客户端命中同一变体）
            ProxyAdvanced adv = site.adv();
            boolean store = false;
            if (adv.cacheEnabled && tx.methodGet && tx.cacheKey != null && status == 200
                    && !resp.headers().contains(HttpHeaderNames.SET_COOKIE)
                    && !resp.headers().contains(HttpHeaderNames.AUTHORIZATION)
                    && !resp.headers().contains(HttpHeaderNames.VARY)) {
                int ttl = cacheTtl(resp, adv);
                if (ttl > 0 && extCacheable(tx.pathQuery, adv)) {
                    writer = ProxyCache.newWriter(site.id(), tx.cacheKey, adv);
                    if (writer != null) {
                        store = true;
                        maxItemBytes = (long) adv.cacheMaxItemMB * 1024 * 1024;
                        expiresAt = System.currentTimeMillis() + ttl * 1000L;
                        storeHeaders = collectStoreHeaders(out.headers());
                    }
                }
            }
            String xc = adv.cacheEnabled ? (store ? "MISS" : "BYPASS") : null;
            if (xc != null) out.headers().set("X-Cache", xc);
            HttpUtil.setKeepAlive(out, keepAlive);
            // 上游 chunked / close 定界（无 Content-Length）时必须为下游重新声明分帧：
            // 否则前端编码器裸写 body 字节且 keep-alive 下不关连接，客户端无法判定结束而挂起。
            // 1xx/204/205/304 无 body，无需分帧。
            if (status >= 200 && status != 204 && status != 205 && status != 304
                    && HttpUtil.getContentLength(resp, -1) < 0) {
                HttpUtil.setTransferEncodingChunked(out, true);
            }
            tx.front.write(out);
        } else if (msg instanceof HttpContent c) {
            if (writer != null) {
                writer.append(c.content());
                if (writer.written() > maxItemBytes) { // 单文件超限：放弃缓存，中继不受影响
                    writer.abort();
                    writer = null;
                }
            }
            tx.front.write(c.retain());
            if (!tx.front.channel().isWritable()) {
                // 背压：前端出站缓冲积压超限，暂停读后端；待前端可写后由 ProxyHandler 恢复
                ctx.channel().config().setAutoRead(false);
            }
            if (c instanceof LastHttpContent) {
                if (writer != null) {
                    writer.finish(status, storeHeaders, expiresAt);
                    writer = null;
                }
                tx.front.flush();
                tx.finish(true);
                if (!keepAlive) tx.front.close(); // 客户端要求关闭：响应完毕即关
                ctx.close(); // 回源短连接（Connection: close）用完即关
            }
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // 每批读到即刷出：流式响应（SSE/大文件）不依赖 LastHttpContent 才下发，
        // 上游分帧异常时也不会把已到字节卡成黑洞
        if (headSeen) tx.front.flush();
        super.channelReadComplete(ctx);
    }

    /** 上游 Cache-Control 优先的有效期（秒）；0 = 不缓存 */
    private static int cacheTtl(HttpResponse resp, ProxyAdvanced adv) {
        if (!adv.cacheRespectUpstream) return adv.cacheTtlSec;
        String cc = resp.headers().get(HttpHeaderNames.CACHE_CONTROL);
        if (cc == null || cc.isBlank()) return adv.cacheTtlSec;
        String v = cc.toLowerCase(Locale.ROOT);
        if (v.contains("no-store") || v.contains("no-cache") || v.contains("private")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("max-age\\s*=\\s*(\\d+)").matcher(v);
        if (m.find()) {
            try {
                long s = Long.parseLong(m.group(1));
                if (s <= 0) return 0;
                return (int) Math.min(s, Integer.MAX_VALUE);
            } catch (NumberFormatException ignored) {
            }
        }
        return adv.cacheTtlSec;
    }

    /** 扩展名命中判定（未配置清单时缓存全部路径） */
    static boolean extCacheable(String pathAndQuery, ProxyAdvanced adv) {
        if (adv.cacheExtensions.isEmpty()) return true;
        String ext = ProxyCache.extensionOf(pathAndQuery);
        return !ext.isEmpty() && adv.cacheExtensions.contains(ext);
    }

    /** 待落盘响应头：剥离 Content-Length/Date/X-Cache（命中直出时重算） */
    private static List<String[]> collectStoreHeaders(io.netty.handler.codec.http.HttpHeaders h) {
        List<String[]> list = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : h) {
            String n = e.getKey().toLowerCase(Locale.ROOT);
            if (n.equals("content-length") || n.equals("date") || n.equals("x-cache")) continue;
            list.add(new String[]{e.getKey(), e.getValue()});
        }
        return list;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!headSeen) {
            int code = cause instanceof ReadTimeoutException ? 504 : 502;
            String msg = code == 504
                    ? "后端响应超时（" + tx.site.adv().idleSec + " 秒无响应头）"
                    : "后端连接失败: " + cause.getMessage();
            ProxyCenter.noteError(tx.site, msg);
            ProxyCenter.sendSimple(tx.front, code, "NexHome 代理：" + msg, null, null, false);
        }
        abortWriter();
        tx.finish(false);
        ctx.close();
        tx.front.close(); // 响应流中断无法续用 keep-alive
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!headSeen && !tx.done.get()) {
            ProxyCenter.noteError(tx.site, "后端在响应前关闭连接");
            ProxyCenter.sendSimple(tx.front, 502,
                    "NexHome 代理：后端在响应前关闭了连接，请检查后端服务与端口", null, null, false);
            tx.front.close();
        }
        abortWriter();
        tx.finish(false);
    }

    private void abortWriter() {
        if (writer != null) {
            writer.abort();
            writer = null;
        }
    }
}

/**
 * Upgrade 隧道桥（后端原始字节侧）：嗅探响应状态行，
 * 101 则摘除两端 HTTP codec 转入 {@link RawRelay} 双向原始泵；非 101 回 502。
 */
final class UpgradeBridge extends SimpleChannelInboundHandler<ByteBuf> {

    private final ProxyHandler.Tx tx;
    private final ProxyHandler handler;
    private final ByteBuf acc = Unpooled.buffer(1024);
    private boolean handoff;

    UpgradeBridge(ProxyHandler.Tx tx, ProxyHandler handler) {
        this.tx = tx;
        this.handler = handler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) {
        if (handoff) {
            if (tx.front.channel().isActive()) tx.front.writeAndFlush(in.retain());
            return;
        }
        acc.writeBytes(in);
        int idx = indexOfHeadEnd(acc);
        if (idx < 0) {
            if (acc.readableBytes() > 65536) fail(ctx, "升级响应头超长");
            return;
        }
        String head = acc.toString(0, idx + 4, StandardCharsets.US_ASCII);
        int code = parseStatus(head);
        if (code != 101) {
            fail(ctx, "后端未确认协议升级（HTTP " + code + "），WebSocket/升级请求需后端支持");
            return;
        }
        // 101：先做 pipeline 手术再写原始头——前端 HttpResponseEncoder 在初始状态收到裸
        // ByteBuf 会拒绝编码（101 头将写不出去），必须先摘除 codec；随后恢复读取进入原始泵
        Channel backend = ctx.channel();
        io.netty.channel.ChannelPipeline fp = tx.front.pipeline();
        try {
            fp.remove("codec");                 // HttpServerCodec：后续入站字节不再按 HTTP 解析
            fp.remove(handler);                 // 摘除请求处理器，改挂原始泵
        } catch (NoSuchElementException e) {
            fail(ctx, "升级切换时 pipeline 状态异常");
            return;
        }
        fp.addLast(new RawRelay(backend, tx));
        ByteBuf headBuf = acc.readRetainedSlice(idx + 4);
        tx.front.writeAndFlush(headBuf);
        if (acc.isReadable()) {                 // 101 后已抢跑到达的帧数据
            tx.front.writeAndFlush(acc.readRetainedSlice(acc.readableBytes()));
        }
        acc.clear();
        tx.front.channel().config().setAutoRead(true); // 握手期暂停的读取至此恢复，客户端帧进入原始泵
        ctx.pipeline().replace(this, "raw", new RawRelay(tx.front.channel(), tx));
        handoff = true;
    }

    private void fail(ChannelHandlerContext ctx, String reason) {
        ProxyCenter.noteError(tx.site, "隧道建立失败: " + reason);
        if (tx.front.channel().isActive()) {
            ProxyCenter.sendSimple(tx.front, 502, "NexHome 代理：协议升级失败 - " + reason,
                    null, null, false);
        }
        tx.finish(false);
        ctx.close();
        tx.front.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!handoff) fail(ctx, "后端在升级确认前关闭连接");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (handoff) {
            tx.finish(false);
            ctx.close();
            tx.front.close();
        } else {
            fail(ctx, String.valueOf(cause.getMessage()));
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        acc.release();
    }

    private static int indexOfHeadEnd(ByteBuf b) {
        int n = b.readableBytes();
        int reader = b.readerIndex();
        for (int i = 0; i + 3 < n; i++) {
            if (b.getByte(reader + i) == '\r' && b.getByte(reader + i + 1) == '\n'
                    && b.getByte(reader + i + 2) == '\r' && b.getByte(reader + i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** 状态行 "HTTP/1.x 101 ..." 解析 */
    private static int parseStatus(String head) {
        try {
            String line = head.split("\r\n", 2)[0];
            String[] parts = line.split(" ", 3);
            if (parts.length >= 2 && parts[0].startsWith("HTTP/")) return Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
        }
        return 0;
    }
}

/** 隧道原始字节泵：一端活跃则转发，另一端关闭时收尾事务 */
final class RawRelay extends SimpleChannelInboundHandler<ByteBuf> {

    private final Channel peer;
    private final ProxyHandler.Tx tx;

    RawRelay(Channel peer, ProxyHandler.Tx tx) {
        this.peer = peer;
        this.tx = tx;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) {
        if (peer.isActive()) peer.writeAndFlush(in.retain());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (tx != null) tx.finish(false);
        if (peer.isActive()) peer.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (tx != null) tx.finish(false);
        ctx.close();
        if (peer.isActive()) peer.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 隧道期间前端 pipeline 的空闲处理器仍在（ProxyHandler 已摘除），此处接管空闲回收
        if (evt instanceof IdleStateEvent) {
            if (tx != null) tx.finish(false);
            ctx.close();
            if (peer.isActive()) peer.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}

/** 分钟速率统计：秒级环形桶，读取近 60 秒总数 */
final class Rate {
    private final long[] counts = new long[64];
    private final long[] stamps = new long[64];

    synchronized void inc() {
        long s = System.currentTimeMillis() / 1000;
        int i = (int) (s & 63);
        if (stamps[i] != s) {
            stamps[i] = s;
            counts[i] = 0;
        }
        counts[i]++;
    }

    synchronized int perMinute() {
        long now = System.currentTimeMillis() / 1000;
        long c = 0;
        for (int j = 0; j < 60; j++) {
            long s = now - j;
            int i = (int) (s & 63);
            if (stamps[i] == s) c += counts[i];
        }
        return (int) c;
    }
}
