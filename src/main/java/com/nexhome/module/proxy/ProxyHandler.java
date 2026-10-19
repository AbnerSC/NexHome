package com.nexhome.module.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单请求/单流转发处理器（HTTP/1.1 连接与 HTTP/2 流复用同一实现）。
 * <p>
 * HTTP/1.1 连接处理期间暂停读取（autoRead=false）实现请求串行化，避免流水线请求内容误转发；
 * Upgrade 请求走原始字节隧道；其余走消息级中继（头部改写 + 内容流式回写 + 缓存旁路落盘）。
 */
final class ProxyHandler extends SimpleChannelInboundHandler<Object> {

    private final boolean https;
    private final boolean isH2;

    private Tx tx; // 进行中的转发事务

    /**
     * h1 流水线积压队列：同一读批次中编解码器会连续下发多个请求，
     * autoRead=false 只能阻止后续 read 迭代，拦不住已解码消息；
     * 事务进行中新到达的 HttpRequest 及其体分片复制为脱离原管道的消息暂存
     * （引用由队列独占，不与框架 channelRead0 后的自动释放相互干扰），
     * 当前事务结束后按请求边界重放（对齐 nginx 串行处理流水线而非拒绝对行为）。
     */
    private final java.util.ArrayDeque<Object> backlog = new java.util.ArrayDeque<>();
    private long backlogBytes; // 积压体字节数，超限视为客户端异常
    private boolean accumulating; // 正在向 backlog 累积后续请求单元
    private ChannelHandlerContext frontCtx; // 连接级上下文（startRequest 时记录，供积压重放用）

    /** 流水线积压上限：客户端不等响应持续灌体则断连保护（对齐 nginx 流水线缓冲限制） */
    private static final long MAX_BACKLOG_BYTES = 16L * 1024 * 1024;

    ProxyHandler(boolean https, boolean isH2) {
        this.https = https;
        this.isH2 = isH2;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req) {
            if (tx != null && !tx.done.get()) {
                if (isH2) {
                    // h2 每流一处理器，再收第二个 HttpRequest 必为客户端违规：防御性拒绝
                    ProxyCenter.sendSimple(ctx, 400, "请求流水线异常", null, null, false);
                    ctx.close();
                    return;
                }
                accumulating = true;
                backlog.add(detach(req));
                return;
            }
            startRequest(ctx, req);
            if (req instanceof io.netty.handler.codec.http.FullHttpMessage full) {
                // h2 前端的无体请求（HEADERS 带 END_STREAM）会被流编解码器合成<em>一条</em> FullHttpRequest：
                // 框架不再另行下发 LastHttpContent，此处就地把它当请求体收尾交回事务。
                // 否则回源 h2 流永远等不到 END_STREAM（后端挂在读请求体上），h1 回源也缺「无体」信号。
                handleContent(ctx, new DefaultLastHttpContent(full.content().retain()));
            }
        } else if (msg instanceof HttpContent c) {
            if (accumulating) {
                long size = c.content().readableBytes();
                if (backlogBytes + size > MAX_BACKLOG_BYTES) {
                    releaseBacklog();
                    ctx.close(); // 积压超限：客户端违规，直接断开
                    return;
                }
                backlogBytes += size;
                backlog.add(detach(c));
                if (c instanceof LastHttpContent) drainBacklog(); // 事务已收尾时及时排空
                return;
            }
            handleContent(ctx, c);
        }
    }

    /** 复制为脱离原管道的消息：头与内容独立于解码器缓冲，入队后引用由队列独占 */
    private static Object detach(Object msg) {
        if (msg instanceof HttpRequest req)
            return new DefaultHttpRequest(req.protocolVersion(), req.method(), req.uri(),
                    new DefaultHttpHeaders().add(req.headers()));
        HttpContent c = (HttpContent) msg;
        return c instanceof LastHttpContent
                ? new DefaultLastHttpContent(c.content().copy())
                : new DefaultHttpContent(c.content().copy());
    }

    /** 事务间隙尝试排空积压：按 HttpRequest 边界重放，直到新事务建立或队列空 */
    private void drainBacklog() {
        ChannelHandlerContext ctx = frontCtx;
        if (ctx == null || !ctx.channel().isActive()) {
            releaseBacklog();
            return;
        }
        while ((tx == null || tx.done.get()) && !backlog.isEmpty()) {
            if (tx != null && tx.done.get()) tx = null; // 允许重新开始下一事务
            Object head = backlog.peekFirst();
            if (!(head instanceof HttpRequest)) {
                Object orphan = backlog.pollFirst(); // 无主内容（前请求已收尾）：丢弃
                backlogBytes -= ((HttpContent) orphan).content().readableBytes();
                release(orphan);
                continue;
            }
            HttpRequest req = (HttpRequest) backlog.pollFirst();
            accumulating = false;
            startRequest(ctx, req);
            release(req); // startRequest 同步取用完毕，释放队列独占引用
            // 重放该请求已缓存的体分片，至下一个请求边界或 LastHttpContent 为止
            while (!backlog.isEmpty() && !(backlog.peekFirst() instanceof HttpRequest)) {
                Object c = backlog.pollFirst();
                backlogBytes -= ((HttpContent) c).content().readableBytes();
                channelRead0(ctx, c); // 事务已建→ handleContent 自行 retain 接管；未建→ 忽略
                release(c);           // 释放队列独占引用（等价于框架 channelRead0 后的自动释放）
                if (c instanceof LastHttpContent) break;
            }
            if (tx != null && !tx.done.get()) return; // 新事务进行中，余下等 onFinish 重排
        }
    }

    private static void release(Object msg) {
        if (msg instanceof io.netty.util.ReferenceCounted rc) rc.release();
    }

    /** 连接收尾：释放全部积压消息引用 */
    private void releaseBacklog() {
        Object o;
        while ((o = backlog.pollFirst()) != null) release(o);
        backlogBytes = 0;
        accumulating = false;
    }

    // ==================== 请求处理 ====================

    private void startRequest(ChannelHandlerContext ctx, HttpRequest req) {
        frontCtx = ctx;
        if (req.decoderResult().isFailure()) {
            // 请求行/请求头非法（典型为超长头）：解码器不抛异常而是下发失败标记的空请求，
            // 若不拦下会被后面的「缺 Host」误报 400，语义上应为 431 并断开连接
            String why = String.valueOf(req.decoderResult().cause());
            ProxyCenter.logFrontError(ctx, req.decoderResult().cause());
            ProxyCenter.sendSimple(ctx, 431, "请求行或请求头非法/过长：" + why, null, null, false);
            return;
        }
        String hostHdr = req.headers().get(HttpHeaderNames.HOST);
        if (hostHdr == null || hostHdr.isBlank()) {
            ProxyCenter.sendSimple(ctx, 400, "缺少 Host 请求头", null, null, false);
            return;
        }
        String host = ProxyCenter.hostOnly(hostHdr);
        String uri = req.uri();
        if (!uri.startsWith("/")) {
            ProxyCenter.sendSimple(ctx, 400, "仅支持标准请求路径（不支持 CONNECT/绝对 URI）", null, null, false);
            return;
        }
        int q = uri.indexOf('?');
        String path = q < 0 ? uri : uri.substring(0, q);
        String query = q < 0 ? "" : uri.substring(q + 1);
        // 路径穿越防护只针对「段级 ..」（/a/../b），不能用 contains("..")：
        // 否则 Gitea compare 等合法路径里的 "..."（如 release-v1.2.0...release-v1.3.0）会被误伤
        if (hasDotDotSegment(path)) {
            ProxyCenter.sendSimple(ctx, 400, "请求路径非法（包含 .. 上跳段）", null, null, false);
            return;
        }

        ProxySite site = ProxyCenter.route(host, path);
        if (site == null) {
            ProxyCenter.sendSimple(ctx, 404,
                    "NexHome 代理：未找到匹配站点\nHost: " + host + "\n路径: " + path
                            + "\n（请在「代理中心」添加或启用该域名的站点）", null, null, true);
            return;
        }
        ProxyCenter.SiteStats st = ProxyCenter.stats(site.id());
        st.requests.incrementAndGet();
        st.rate.inc();

        ProxyAdvanced adv = site.adv();
        // peerIp：直连对端地址，用于回源时向下游追加 XFF 链；
        // clientIp：解析后的访客真实 IP（前置代理/CDN 场景取转发头），用于访问统计与 IP 白名单
        String peerIp = ProxyCenter.clientAddr(ctx.channel()).getAddress().getHostAddress();
        String clientIp = ProxyCenter.resolveClientIp(req, peerIp);
        // 访问统计：路由命中即计一次（含被访问控制拒绝的请求），仅内存累加，不阻塞转发
        VisitStats.record(host, clientIp);

        // 访问控制链：IP 白名单 -> BasicAuth -> 限流 -> 请求体上限
        if (!adv.ipAllow.isEmpty() && !ipAllowed(clientIp, adv.ipAllow)) {
            ProxyCenter.sendSimple(ctx, 403, "来源 IP 不在白名单内: " + clientIp, null, null, true);
            return;
        }
        if (!adv.authUser.isEmpty() && !checkBasicAuth(req, adv)) {
            ProxyCenter.sendSimple(ctx, 401, "需要 Basic 认证（" + site.name() + "）",
                    "WWW-Authenticate", "Basic realm=\"" + site.name() + "\"", true);
            return;
        }
        if (adv.rateLimitRps > 0 && !ProxyCenter.bucket(site).tryAcquire()) {
            ProxyCenter.sendSimple(ctx, 429, "请求过于频繁（限流 " + adv.rateLimitRps + " QPS）",
                    "Retry-After", "1", true);
            return;
        }
        long bodyLen = HttpUtil.getContentLength(req, -1);
        if (adv.maxBodyMB > 0 && (bodyLen > (long) adv.maxBodyMB * 1024 * 1024
                || (bodyLen < 0 && HttpUtil.isTransferEncodingChunked(req)))) {
            // chunked 请求体无声明长度，无法流式限长，直接拒绝（对齐 nginx client_max_body_size 语义）
            ProxyCenter.sendSimple(ctx, 413,
                    "请求体超过上限 " + adv.maxBodyMB + " MB（chunked 请求体需声明 Content-Length）",
                    null, null, false);
            return;
        }
        // 强制 HTTPS 跳转（仅明文口）
        if (!https && site.forceHttps()) {
            int hp = ProxyCenter.boundHttpsPort();
            String loc = "https://" + host + (hp > 0 && hp != 443 ? ":" + hp : "") + uri;
            ProxyCenter.sendSimple(ctx, 301, "跳转到 " + loc, "Location", loc, true);
            return;
        }

        String upath = ProxyCenter.upstreamPath(site, path, query);

        // WebSocket 等 Upgrade 请求：原始字节隧道（仅 HTTP/1.1）
        if (isUpgrade(req)) {
            if (isH2) {
                ProxyCenter.sendSimple(ctx, 400, "HTTP/2 连接不支持协议升级（WebSocket 请经 HTTP/1.1）",
                        null, null, true);
                return;
            }
            if (adv.backendH2()) {
                // h2 回源无法承载 Upgrade（RFC 7540 8.4 明确禁止连接级升级）：
                // 明确拒绝而不给一个语义不明的高速源错误，告知该改站点回源协议
                ProxyCenter.sendSimple(ctx, 501,
                        "该站点回源协议为 HTTP/2，不支持 Upgrade/WebSocket，请将回源协议改为 http1", null, null, true);
                return;
            }
            startTunnel(ctx, site, req, upath, peerIp, st);
            return;
        }
        // gRPC 依赖 trailer 里的 grpc-status：HTTP/1.1 前端无法透传 trailer，
        // 若硬转发会给出一个“成功但无状态码”的坏响应，此处明确拒绝（后端为 h2 时前端也必须走 h2）
        if (adv.backendH2() && !isH2 && isGrpc(req)) {
            ProxyCenter.sendSimple(ctx, 501,
                    "gRPC 需经 HTTP/2 前端访问（本连接为 HTTP/1.1，无法透传 grpc-status trailer）",
                    null, null, true);
            return;
        }

        // 文件缓存查询（GET、无 Authorization、无 Range）
        boolean mayCache = adv.cacheEnabled && HttpMethod.GET.equals(req.method())
                && !req.headers().contains(HttpHeaderNames.AUTHORIZATION)
                && !req.headers().contains(HttpHeaderNames.RANGE);
        String cacheKey = mayCache
                ? ProxyCache.buildKey("GET", (https ? "https" : "http") + "://" + host + uri)
                : null;
        if (mayCache) {
            ProxyCache.Entry hit = ProxyCache.lookup(site.id(), cacheKey, false);
            if (hit != null) {
                serveCache(ctx, req, hit);
                return;
            }
        }

        beginRelay(ctx, site, req, upath, path + (query.isEmpty() ? "" : "?" + query),
                peerIp, st, cacheKey, HttpMethod.GET.equals(req.method()));
    }

    /** 按 '/' 切分后是否存在恰好等于 ".." 的路径段（真正的上跳穿越）；
     *  段内包含连续点（如 "a...b"、"..hidden"）不算，避免误伤合法业务路径 */
    private static boolean hasDotDotSegment(String path) {
        int start = 0;
        while (start <= path.length()) {
            int slash = path.indexOf('/', start);
            int end = slash < 0 ? path.length() : slash;
            if (end - start == 2 && path.charAt(start) == '.' && path.charAt(start + 1) == '.') return true;
            if (slash < 0) break;
            start = slash + 1;
        }
        return false;
    }

    /** 消息级中继：改写请求头后连后端，请求体流式转发；h1 期间暂停读取实现串行 */
    private void beginRelay(ChannelHandlerContext ctx, ProxySite site, HttpRequest req,
                            String upath, String pathQuery, String peerIp, ProxyCenter.SiteStats st,
                            String cacheKey, boolean methodGet) {
        tx = new Tx(ctx, site, st, cacheKey, methodGet, pathQuery);
        tx.onFinish = this::drainBacklog; // 本事务收尾后继续排空流水线积压
        st.active.incrementAndGet();
        boolean keepAlive = !isH2 && HttpUtil.isKeepAlive(req);
        ProxyAdvanced adv = site.adv();
        if (!isH2) ctx.channel().config().setAutoRead(false);

        // 构造回源头：剥离逐跳头与 Expect（对齐 nginx：Expect 不转发后端，也不代回 100），
        // 追加 XFF/XFP/XFH 与自定义头改写
        DefaultHttpRequest fwd = new DefaultHttpRequest(HttpVersion.HTTP_1_1, req.method(), upath,
                new DefaultHttpHeaders());
        copyHeaders(req.headers(), fwd.headers());
        // 启用回源连接池时不声明 close（HTTP/1.1 默认 keep-alive），供响应读完后归还复用；
        // 否则维持既有短连接语义（每请求 close）。h2 回源则两者都不写：逐跳头在 h2 中属违规头
        // （RFC 7540 8.1.2.2），事务结束由流的 END_STREAM/RST 自然定界
        if (adv.backendH2()) {
            // 不写 Connection/Keep-Alive；chunked 请求体也不写 Transfer-Encoding，改由 DATA + END_STREAM 定界
        } else {
            if (adv.upstreamKeepalive) HttpUtil.setKeepAlive(fwd, true);
            else fwd.headers().set(HttpHeaderNames.CONNECTION, "close");
            // 上游 chunked 请求体（TE 已被剥离）：向后端重新声明 chunked 分帧，
            // 否则后端按连接关闭定界等待 body 而挂起不响应
            if (HttpUtil.isTransferEncodingChunked(req)) {
                HttpUtil.setTransferEncodingChunked(fwd, true);
            }
        }
        fwd.headers().set("X-Forwarded-Proto", https ? "https" : "http");
        fwd.headers().set("X-Forwarded-Host", req.headers().get(HttpHeaderNames.HOST, ""));
        String xff = req.headers().get("X-Forwarded-For");
        fwd.headers().set("X-Forwarded-For", xff == null || xff.isBlank() ? peerIp : xff + ", " + peerIp);
        fwd.headers().set("X-Forwarded-By", "NexHome");
        applyHeaderOps(adv.reqHeaders, fwd.headers());

        // 捕获本次事务引用：流水线重放会推进 tx 字段，迟到的连接回调不得误伤后续事务
        final Tx t = tx;
        if (!isH2) {
            // autoRead=false 后 Netty 会撤走 OP_READ 兴趣，客户端在事务进行中提前断开（abort）
            // 就无人唤醒，事务与两端 fd 悬挂到后端读超时才回收；此处显式保持一次读兴趣，
            // 以便感知 FIN/RST 与流水线后续请求（后者进积压队列，不在此直接处理）
            ctx.read();
        }
        // 关键：回源可用后再写回源请求。跨事件循环时，连接/流未就绪时的预写与 flush
        // 不会被可靠地下发到 socket（表现为后端收不到完整请求、响应永远不到达），
        // 故统一放到就绪回调内，此时 backend channel 已 active 且可写。
        // h1 复用连接与 h2 新建流都在同一事件循环线程内回调。
        ProxyCenter.openBackend(site, ctx, (b, err) -> {
            if (err != null) {
                connectFailed(t, "后端连接失败: " + err.getMessage());
                return;
            }
            if (t.done.get()) { // 回调迟到（前端已断开/事务已收尾）：只释放刚取到的连接或流
                b.close();
                return;
            }
            t.backend = b;
            BackendRelay relay = b.attr(ProxyCenter.RELAY).get();
            if (relay == null) { // 连接/流未绑定中继（不应发生）：明确失败，不要把事务静默挂到读超时
                connectFailed(t, "后端连接未就绪（缺少中继处理器）");
                b.close();
                return;
            }
            relay.beginExchange(site, t, keepAlive);
            b.writeAndFlush(fwd);
            t.backendReady = true;
            // 请求体先于连接到达的缓存内容直接续流
            if (t.pendingBody != null && !t.pendingBody.isEmpty()) {
                for (Object o : t.pendingBody) {
                    b.write(o);
                    if (o instanceof LastHttpContent) t.requestBodyDone = true;
                }
                t.pendingBody = null;
                b.flush();
            }
            // 大请求体（如 Docker 层 PUT）在首读批次后不会自动续读：前端 autoRead=false 期间
            // 必须显式驱动续读，否则后端等不到完整 body 而超时（对齐 nginx 请求体流式转发）。
            // 无体请求（GET 或空 POST）已由 LastHttpContent 置 requestBodyDone，不会误读下一个流水线请求。
            if (!t.requestBodyDone && b.isWritable()) ctx.read();
        });
    }

    /** 后端连接建立失败：回 502 并收尾事务（只针对发起连接的那个事务） */
    private void connectFailed(Tx t, String reason) {
        if (t == null || t.done.get()) return;
        ProxyCenter.noteError(t.site, reason);
        ProxyCenter.sendSimple(t.front, 502, "NexHome 代理：" + reason + "，请检查后端服务与端口",
                null, null, false);
        t.front.close();
        t.finish(false);
    }

    private void handleContent(ChannelHandlerContext ctx, HttpContent c) {
        // 事务已收尾（后端失败/提前回错误页后的残留分片）：丢弃，由框架统一释放
        if (tx == null || tx.done.get()) return;
        if (tx.tunnel) {
            if (tx.backendReady && tx.backend != null && tx.backend.isActive()) {
                // 隧道后端为原始字节管线，retain 后写字节即可（禁止手动 release，框架会释放原消息）
                tx.backend.writeAndFlush(c.content().retain());
            } else {
                tx.pendingBody.add(c.retain());
            }
        } else if (tx.backendReady && tx.backend != null) {
            // 请求体方向批量下发：本批次只 write 不 flush，统一由 channelReadComplete 一次 flush + 续读，
            // 把「每个 HttpContent 一次 writeAndFlush + ctx.read」的逐块系统调用降为「每读批次一次」。
            tx.backend.write(c.retain());
            if (c instanceof LastHttpContent) {
                tx.backend.flush(); // 请求体收尾必须立即下发，触发后端产生响应
                tx.requestBodyDone = true;
            }
        } else {
            tx.pendingBody.add(c.retain());
        }
    }

    /** 一次转发事务的共享状态（前后端处理器同事件循环线程访问，无需加锁除 done） */
    static final class Tx {
        final ChannelHandlerContext front;
        final ProxySite site;
        final ProxyCenter.SiteStats st;
        final String cacheKey;
        final boolean methodGet;
        final String pathQuery;
        final AtomicBoolean done = new AtomicBoolean();
        volatile Channel backend;
        volatile boolean tunnel, backendReady;
        volatile boolean requestBodyDone; // 回源请求体是否已完整转发到后端（连接复用前置条件）
        volatile Runnable onFinish;       // 事务正常收尾（恢复读取）后的回调：排空流水线积压
        List<Object> pendingBody = new ArrayList<>();

        Tx(ChannelHandlerContext front, ProxySite site, ProxyCenter.SiteStats st,
           String cacheKey, boolean methodGet, String pathQuery) {
            this.front = front;
            this.site = site;
            this.st = st;
            this.cacheKey = cacheKey;
            this.methodGet = methodGet;
            this.pathQuery = pathQuery;
            // GET/无体请求默认视为已发完（HttpServerCodec 对无体请求即时下发 LastHttpContent）
            this.requestBodyDone = methodGet;
        }

        /** 结束事务：活跃数归位、释放未转发请求体、关闭后端、恢复读取（幂等） */
        void finish(boolean resumeReads) {
            if (!done.compareAndSet(false, true)) return;
            st.active.decrementAndGet();
            if (pendingBody != null) {
                for (Object o : pendingBody) {
                    if (o instanceof HttpContent hc) hc.release();
                }
                pendingBody = null;
            }
            if (backend != null) backend.close();
            if (front.channel().isActive() && resumeReads) {
                front.channel().config().setAutoRead(true);
                if (onFinish != null) onFinish.run();
            }
        }
    }

    // ==================== WebSocket / Upgrade 隧道 ====================

    private void startTunnel(ChannelHandlerContext ctx, ProxySite site, HttpRequest req,
                             String upath, String peerIp, ProxyCenter.SiteStats st) {
        tx = new Tx(ctx, site, st, null, false, upath);
        tx.tunnel = true;
        st.active.incrementAndGet();
        ctx.channel().config().setAutoRead(false); // 握手期间不读后续帧（101 转入原始泵时恢复）
        ctx.read(); // 同 beginRelay：保持读兴趣以感知握手期的客户端提前断开

        // 原始序列化回源请求头（保留 Upgrade，Connection 归一为 upgrade）
        StringBuilder sb = new StringBuilder(512);
        sb.append(req.method().name()).append(' ').append(upath).append(" HTTP/1.1\r\n");
        for (Map.Entry<String, String> h : req.headers()) {
            String name = h.getKey();
            if (hopNoTunnel(name)) continue;
            sb.append(name).append(": ").append(h.getValue()).append("\r\n");
        }
        appendForwardHeaders(sb, req, peerIp);
        applyTunnelOps(sb, site.adv().reqHeaders);
        sb.append("Connection: upgrade\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);

        ChannelFuture bf = ProxyCenter.connectBackend(site, new UpgradeBridge(tx, this), ctx, false);
        tx.backend = bf.channel();
        bf.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                connectFailed(tx, "后端连接失败: " + f.cause().getMessage());
                return;
            }
            Channel bc = f.channel();
            bc.writeAndFlush(Unpooled.wrappedBuffer(head));
            tx.backendReady = true;
            if (tx.pendingBody != null && !tx.pendingBody.isEmpty()) {
                for (Object o : tx.pendingBody) {
                    if (o instanceof HttpContent hc) {
                        bc.write(hc.content().retain()); // 转移引用给后端出站队列
                        hc.release();                     // 平衡 pendingBody 持有的引用，避免泄漏
                    }
                }
                tx.pendingBody = null;
                bc.flush();
            }
        });
    }

    /** 隧道头过滤：剥离逐跳头但保留 Upgrade；Host/Content-Length 等原样透传 */
    private static boolean hopNoTunnel(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                 "te", "trailer", "transfer-encoding", "proxy-connection", "expect" -> true;
            default -> false;
        };
    }

    private void appendForwardHeaders(StringBuilder sb, HttpRequest req, String peerIp) {
        sb.append("X-Forwarded-Proto: ").append(https ? "https-tunnel" : "http-tunnel").append("\r\n");
        String xff = req.headers().get("X-Forwarded-For");
        sb.append("X-Forwarded-For: ")
                .append(xff == null || xff.isBlank() ? peerIp : xff + ", " + peerIp).append("\r\n");
        sb.append("X-Forwarded-By: NexHome\r\n");
    }

    /** 隧道模式自定义头改写：直接作用于序列化文本（简单可靠，头名已由配置校验） */
    private static void applyTunnelOps(StringBuilder sb, List<ProxyAdvanced.HeaderOp> ops) {
        for (ProxyAdvanced.HeaderOp op : ops) {
            String n = op.name();
            if (!op.name().isEmpty() && !hopNoTunnel(n) && !n.equalsIgnoreCase("upgrade")) {
                // delete：从 sb 移除该头行；set：追加
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "(?im)^" + java.util.regex.Pattern.quote(n) + ":.*\\r?\\n");
                String removed = p.matcher(sb).replaceAll("");
                sb.setLength(0);
                sb.append(removed);
                if (op.op().equals("set")) sb.append(n).append(": ").append(op.value()).append("\r\n");
            }
        }
    }

    // ==================== 文件缓存命中直出 ====================

    private void serveCache(ChannelHandlerContext ctx, HttpRequest req, ProxyCache.Entry hit) {
        boolean keepAlive = !isH2 && HttpUtil.isKeepAlive(req);
        if (isH2) {
            // h2 下 FileRegion 零拷贝不可用（DATA 帧须切帧并经流控），改 64KB 分块流式直出：
            // 避免整文件 readAllBytes 撑大堆（并发大文件直出的堆峰值 ≈ 并发数 × 文件大小），
            // 上一块写出完成（受 h2 流控放行，天然背压）后再读下一块
            java.nio.channels.FileChannel fc;
            try {
                fc = FileChannel.open(hit.file, StandardOpenOption.READ);
            } catch (Exception e) {
                ProxyCenter.sendSimple(ctx, 500, "缓存文件读取失败: " + e.getMessage(), null, null, false);
                ctx.close();
                return;
            }
            ctx.write(cachedHead(hit));
            pumpCacheChunk(ctx, fc, hit.size);
            return;
        }
        java.nio.channels.FileChannel fc = null;
        try {
            fc = FileChannel.open(hit.file, StandardOpenOption.READ);
            final java.nio.channels.FileChannel channel = fc;
            DefaultHttpResponse head = cachedHead(hit);
            HttpUtil.setKeepAlive(head, keepAlive);
            ctx.write(head);
            // DefaultFileRegion 释放时自动关闭底层 FileChannel
            ctx.write(new io.netty.channel.DefaultFileRegion(channel, 0, hit.size));
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    channel.close();
                    ctx.close();
                    return;
                }
                if (keepAlive) {
                    // keep-alive：缓存直出未经中继事务，此处显式恢复读取
                    f.channel().config().setAutoRead(true);
                } else {
                    f.channel().close(); // 非保活：响应完毕即关闭连接
                }
            });
        } catch (Exception e) {
            if (fc != null) try {
                fc.close();
            } catch (Exception ignored) {
            }
            ProxyCenter.sendSimple(ctx, 500, "缓存文件读取失败: " + e.getMessage(), null, null, false);
            ctx.close();
        }
    }

    /** h2 缓存直出分块大小：64KB，平衡系统调用次数与单块堆占用 */
    private static final int CACHE_CHUNK = 64 * 1024;

    /**
     * h2 缓存直出泵：按 {@link #CACHE_CHUNK} 逐块读文件写 DATA 帧，
     * 块间以写出回调衔接——写不出去（流控窗口满/客户端慢）就不继续读盘，自带背压。
     */
    private static void pumpCacheChunk(ChannelHandlerContext ctx, FileChannel channel, long remaining) {
        try {
            if (remaining <= 0) { // 发送完毕（含 0 字节文件）：END_STREAM 收尾并关闭文件
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        .addListener((ChannelFutureListener) f -> closeQuietly(channel));
                return;
            }
            int n = (int) Math.min(CACHE_CHUNK, remaining);
            ByteBuf buf = ctx.alloc().buffer(n, n);
            ByteBuffer nio = buf.nioBuffer(0, n);
            int read = channel.read(nio);
            if (read <= 0) { // 文件比索引记录短：响应头已发出无法改报错误，重置流收尾
                buf.release();
                closeQuietly(channel);
                ctx.close();
                return;
            }
            buf.writerIndex(read);
            long left = remaining - read;
            ctx.writeAndFlush(new DefaultHttpContent(buf))
                    .addListener((ChannelFutureListener) f -> {
                        if (!f.isSuccess()) { // 客户端断开/流被重置：释放文件即止
                            closeQuietly(channel);
                            ctx.close();
                            return;
                        }
                        pumpCacheChunk(ctx, channel, left);
                    });
        } catch (Exception e) {
            closeQuietly(channel);
            ctx.close();
        }
    }

    private static void closeQuietly(FileChannel ch) {
        try {
            ch.close();
        } catch (Exception ignored) {
        }
    }

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private static DefaultHttpResponse cachedHead(ProxyCache.Entry hit) {
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(hit.status), new DefaultHttpHeaders());
        for (String[] h : hit.headers) head.headers().add(h[0], h[1]);
        head.headers().set(HttpHeaderNames.CONTENT_LENGTH, hit.size)
                .set(HttpHeaderNames.DATE,
                        java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(HTTP_DATE))
                .set("X-Cache", "HIT");
        return head;
    }

    // ==================== 校验与工具 ====================

    private static boolean isUpgrade(HttpRequest req) {
        String up = req.headers().get(HttpHeaderNames.UPGRADE);
        String conn = req.headers().get(HttpHeaderNames.CONNECTION);
        return up != null && !up.isBlank() && conn != null
                && conn.toLowerCase(Locale.ROOT).contains("upgrade");
    }

    /** gRPC 请求判定（含 websocket-style 的 grpc-web 文本帧，均依赖 h2 trailer） */
    private static boolean isGrpc(HttpRequest req) {
        String ct = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
        return ct != null && ct.toLowerCase(Locale.ROOT).startsWith("application/grpc");
    }

    /** IP 白名单：精确 IP，或显式以点结尾的网段前缀（192.168.1. 匹配 192.168.1.*） */
    private static boolean ipAllowed(String ip, List<String> allow) {
        String v = ip.toLowerCase(Locale.ROOT);
        for (String a : allow) {
            if (v.equals(a)) return true;
            if (a.endsWith(".") && v.startsWith(a)) return true; // 仅显式前缀段，避免 10.0.0.5 误配 10.0.0.50
        }
        return false;
    }

    private static boolean checkBasicAuth(HttpRequest req, ProxyAdvanced adv) {
        String hdr = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (hdr == null || !hdr.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        try {
            String dec = new String(Base64.getDecoder().decode(hdr.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int i = dec.indexOf(':');
            if (i < 0) return false;
            return MessageDigest.isEqual(dec.substring(0, i).getBytes(StandardCharsets.UTF_8),
                    adv.authUser.getBytes(StandardCharsets.UTF_8))
                    && MessageDigest.isEqual(dec.substring(i + 1).getBytes(StandardCharsets.UTF_8),
                    adv.authPass.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** 头复制：剥离逐跳头与 Expect（含 Connection 头声明的附加逐跳头），保留其余（含 Host/Content-Length） */
    static void copyHeaders(HttpHeaders src, HttpHeaders dst) {
        Set<String> dynHop = connectionTokens(src);
        for (Map.Entry<String, String> h : src) {
            String name = h.getKey();
            if (ProxyCenter.hopByHop(name)) continue;
            if (name.equalsIgnoreCase("expect")) continue;
            if (syntheticH2(name)) continue; // 流对象编解码器注入的元数据头，不得透传
            if (dynHop != null && dynHop.contains(name.toLowerCase(Locale.ROOT))) continue;
            dst.add(name, h.getValue());
        }
    }

    /**
     * h2 流对象编解码器将伪头翻译成的元数据头：
     * 向前端（h1）透传会泄漏内部流号与连接参数，向 h2 后端透传则属违规头，两端一律剔除。
     */
    private static boolean syntheticH2(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("x-http2-");
    }

    /** Connection 头中声明的额外逐跳头名（RFC 7230 §6.1，小写；无则返回 null） */
    private static Set<String> connectionTokens(HttpHeaders h) {
        List<String> conn = h.getAll(HttpHeaderNames.CONNECTION);
        if (conn.isEmpty()) return null;
        Set<String> set = null;
        for (String v : conn) {
            for (String tok : v.split(",")) {
                String t = tok.trim();
                if (t.isEmpty() || ProxyCenter.hopByHop(t)) continue;
                if (set == null) set = new HashSet<>();
                set.add(t.toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    static void applyHeaderOps(List<ProxyAdvanced.HeaderOp> ops, HttpHeaders h) {
        for (ProxyAdvanced.HeaderOp op : ops) {
            if (op.op().equals("delete")) h.remove(op.name());
            else h.set(op.name(), op.value());
        }
    }

    // ==================== 连接级事件 ====================

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof io.netty.handler.timeout.IdleStateEvent e
                && e.state() == io.netty.handler.timeout.IdleState.READER_IDLE) {
            ctx.close(); // 前端连接 300 秒空闲回收
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // 上传方向批量收尾：把本读批次累积的请求体一次性 flush 给后端，并在后端仍可写时续拉下一批
        // （后端不可写＝写缓冲积压，暂停续读，由 BackendRelay.channelWritabilityChanged 恢复，形成端到端流控）
        Tx t = tx;
        if (!isH2 && t != null && !t.done.get() && t.backendReady && t.backend != null && !t.requestBodyDone) {
            t.backend.flush();
            if (t.backend.isWritable()) ctx.read();
        }
        // 等待后端响应阶段保持读兴趣（见 beginRelay）：autoRead=false 下若不做一次 read，
        // 前端提前断开无法感知，事务悬挂至后端读超时才回收（请求体上传阶段仍由可写回调驱动续读）
        if (!isH2 && t != null && !t.done.get() && t.requestBodyDone
                && !ctx.channel().config().isAutoRead() && ctx.channel().isActive()) {
            ctx.read();
        }
        super.channelReadComplete(ctx);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        // 背压恢复：前端出站缓冲回落低水位后恢复后端读取（与 BackendRelay 的暂停配对）
        if (ctx.channel().isWritable() && tx != null && !tx.done.get() && tx.backend != null) {
            tx.backend.config().setAutoRead(true);
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releaseBacklog(); // 流水线积压随连接终止释放
        if (tx != null) {
            tx.finish(false);
            if (tx.backend != null) tx.backend.close();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseBacklog(); // Upgrade 手术摘除处理器时同样清理
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ProxyCenter.logFrontError(ctx, cause);
        ctx.close();
    }
}
