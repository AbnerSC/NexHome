package com.nexhome.module.proxy;

import com.nexhome.core.Logs;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AttributeKey;
import io.netty.util.Mapping;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 反向代理监听引擎（Netty）。
 * <p>
 * 独立于面板 WebServer 的两组监听：
 * <ul>
 *   <li>HTTP 口：明文探测（h2c prior-knowledge 魔数识别），其余按 HTTP/1.1 处理</li>
 *   <li>HTTPS 口：SNI 选证（{@link SslStore}），ALPN 协商 h2 / http/1.1</li>
 * </ul>
 * 请求流水线：路由匹配（域名通配 + 路径前缀）→ force_https / IP 白名单 / BasicAuth / 限流 /
 * 文件缓存 → 头部改写（XFF 等 + 自定义）→ 后端连接（每请求短连接，对齐 nginx 默认
 * 不启用 upstream keepalive）→ 响应流式回写（tee 写缓存）；
 * Upgrade 请求走全原始字节隧道（101 后双端原始泵，WebSocket 透传，仅 HTTP/1.1 连接）。
 * <p>
 * 站点与证书热更新：路由表/证书库原子替换，不影响存量连接。
 */
public final class ProxyCenter {

    /** 监听配置快照（由 ProxyService 读取数据库后传入） */
    public record Cfg(boolean enabled, int httpPort, int httpsPort) {
    }

    static final AttributeKey<Boolean> IS_HTTPS = AttributeKey.valueOf("nx-proxy-https");

    /** HTTP/2 客户端连接预面（prior-knowledge 魔数，24 字节） */
    private static final byte[] H2_MAGIC = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int H2_MAGIC_LEN = H2_MAGIC.length;

    private static volatile List<ProxySite> sites = List.of();
    private static volatile Cfg cfg = new Cfg(false, 80, 443);
    private static NioEventLoopGroup boss, worker;
    private static volatile Channel httpCh, httpsCh;
    private static volatile int boundHttp = -1, boundHttps = -1;
    /** 后端 HTTPS 客户端上下文：默认校验 / backendInsecure 免校验，懒构建 */
    private static volatile SslContext backendTls, backendTlsInsecure;

    private ProxyCenter() {
    }

    // ==================== 生命周期 ====================

    /**
     * 按配置启动监听（已启动则先停）。HTTPS 口绑定失败降级为仅 HTTP 并抛出提示，
     * 由调用方（ProxyService）写入 proxy.last_error；HTTP 口失败整体不监听。
     */
    public static synchronized String start(Cfg c) throws Exception {
        stop();
        cfg = c;
        if (!c.enabled()) return "";
        if (boss == null) {
            boss = new NioEventLoopGroup(1, new DefaultThreadFactory("nexhome-proxy-boss", true));
            worker = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2,
                    new DefaultThreadFactory("nexhome-proxy-worker", true));
        }
        sites = ProxySite.loadAll();
        SslStore.reload();
        String warn = "";
        try {
            httpCh = bind(c.httpPort(), false);
            boundHttp = c.httpPort();
        } catch (Exception e) {
            shutdownGroups();
            throw new IOException("HTTP 端口 " + c.httpPort() + " 绑定失败: " + e.getMessage(), e);
        }
        if (c.httpsPort() > 0) {
            try {
                httpsCh = bind(c.httpsPort(), true);
                boundHttps = c.httpsPort();
            } catch (Exception e) {
                warn = "HTTPS 端口 " + c.httpsPort() + " 绑定失败，已降级为仅 HTTP 监听: " + e.getMessage();
                Logs.warn(Logs.PROXY, warn);
            }
        }
        Logs.info(Logs.PROXY, "代理监听已启动: HTTP:" + boundHttp
                + (boundHttps > 0 ? " HTTPS:" + boundHttps : "")
                + "，站点 " + sites.size() + " 个，证书 " + SslStore.apiView().size() + " 张");
        return warn;
    }

    public static synchronized void stop() {
        closeChannel(httpCh);
        httpCh = null;
        boundHttp = -1;
        closeChannel(httpsCh);
        httpsCh = null;
        boundHttps = -1;
    }

    /** 停止监听并释放线程组（进程退出/关闭开关时调用） */
    public static synchronized void shutdown() {
        stop();
        shutdownGroups();
    }

    private static void shutdownGroups() {
        if (worker != null) {
            worker.shutdownGracefully();
            boss.shutdownGracefully();
            worker = null;
            boss = null;
        }
    }

    private static void closeChannel(Channel ch) {
        if (ch != null) {
            try {
                ch.close().syncUninterruptibly();
            } catch (Exception e) {
                Logs.warn(Logs.PROXY, "关闭代理监听失败: " + e.getMessage());
            }
        }
    }

    private static Channel bind(int port, boolean https) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 512)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true);
        b.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.attr(IS_HTTPS).set(https);
                if (https) {
                    // 握手阶段读超时：半开/慢速握手连接不长期占用（握手完成后移交业务 idle）
                    ch.pipeline().addLast("hsTimeout", new ReadTimeoutHandler(15, TimeUnit.SECONDS));
                    ch.pipeline().addLast(new SniHandler(SNI_MAPPING));
                    ch.pipeline().addLast(new AlpnSwitch());
                } else {
                    // 探测阶段读超时：只发魔数前缀即挂起的连接不长期占用
                    ch.pipeline().addLast("probeTimeout", new ReadTimeoutHandler(15, TimeUnit.SECONDS));
                    ch.pipeline().addLast(new ProtocolProbe());
                }
            }
        });
        return b.bind(port).sync().channel();
    }

    /** SNI 握手选证：站点指定任务优先，其余按域名自动匹配；无证书时终止握手 */
    private static final Mapping<CharSequence, SslContext> SNI_MAPPING = hostname -> {
        SslContext ctx = SslStore.forSni(hostname == null ? "" : hostname.toString(), sites);
        if (ctx == null) {
            Logs.warn(Logs.PROXY, "SNI 域名 " + hostname + " 无可用证书（请在 SSL 证书管理中签发，或检查站点证书模式）");
        }
        return ctx;
    };

    /** 站点/证书热更新 */
    public static void reloadSites() {
        sites = ProxySite.loadAll();
    }

    public static void reloadCerts() {
        SslStore.reload();
    }

    /** 监听状态视图（/api/proxy/status 使用） */
    public static Map<String, Object> listenView() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        Cfg c = cfg;
        m.put("enabled", c.enabled());
        m.put("httpPort", c.httpPort());
        m.put("httpsPort", c.httpsPort());
        m.put("boundHttp", boundHttp);
        m.put("boundHttps", boundHttps);
        m.put("httpsActive", boundHttps > 0);
        m.put("certs", SslStore.apiView());
        return m;
    }

    /** 各站点运行统计（活跃连接/每分钟请求/最近错误） */
    public static List<Map<String, Object>> sitesStatusView() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ProxySite s : sites) {
            SiteStats st = STATS.get(s.id());
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", s.id());
            m.put("requests", st == null ? 0 : st.requests.get());
            m.put("active", st == null ? 0 : st.active.get());
            m.put("qpm", st == null ? 0 : st.rate.perMinute());
            m.put("errors", st == null ? 0 : st.errors.get());
            m.put("lastError", st == null ? "" : st.lastError);
            list.add(m);
        }
        return list;
    }

    // ==================== 路由与统计 ====================

    /** 每站点统计 */
    static final class SiteStats {
        final AtomicLong requests = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicInteger active = new AtomicInteger();
        final Rate rate = new Rate();
        volatile String lastError = "";
    }

    private static final ConcurrentHashMap<Long, SiteStats> STATS = new ConcurrentHashMap<>();

    static SiteStats stats(long siteId) {
        return STATS.computeIfAbsent(siteId, k -> new SiteStats());
    }

    static void noteError(ProxySite s, String msg) {
        SiteStats st = stats(s.id());
        st.errors.incrementAndGet();
        st.lastError = com.nexhome.core.Database.now() + " " + msg;
    }

    /** 限流令牌桶（站点级，平滑速率） */
    static final class Bucket {
        private final double rps;
        private long nextNanos;

        Bucket(double rps) {
            this.rps = rps;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            if (nextNanos < now) nextNanos = now;
            if (nextNanos - now > 1_000_000_000L) return false; // 积压超 1 秒拒绝
            nextNanos += (long) (1_000_000_000L / rps);
            return true;
        }
    }

    private static final ConcurrentHashMap<Long, Bucket> BUCKETS = new ConcurrentHashMap<>();

    static Bucket bucket(ProxySite s) {
        return BUCKETS.computeIfAbsent(s.id(), k -> new Bucket(s.adv().rateLimitRps));
    }

    /** 路由匹配：精确域名优于通配域名，长路径前缀优先，再按 id */
    static ProxySite route(String host, String path) {
        ProxySite best = null;
        int bestScore = -1;
        for (ProxySite s : sites) {
            if (!s.enabled() || !s.matchDomain(host) || !s.matchPath(path)) continue;
            int domScore = s.domains().contains(host) ? 200000 : 100000;
            int score = domScore + Math.min(s.pathPrefixLen(), 99999);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    // ==================== pipeline 安装 ====================

    /** HTTP/1.1 pipeline：编解码（头总量 64KB）+ 转发处理器 */
    static void installHttp1(io.netty.channel.ChannelPipeline p, boolean https) {
        p.addLast("codec", new HttpServerCodec(4096, 65536, 8192));
        p.addLast("idle", new io.netty.handler.timeout.IdleStateHandler(300, 0, 0, TimeUnit.SECONDS));
        p.addLast("proxy", new ProxyHandler(https, false));
    }

    /** HTTP/2 pipeline：帧编解码 + 流多路复用，每流复用同一转发处理器（经流对象编解码转换） */
    static void installHttp2(io.netty.channel.ChannelPipeline p, boolean https) {
        // h2 连接整体空闲回收（子流读写均经 parent 字节，活跃连接不会误杀）
        p.addLast("h2Idle", new io.netty.handler.timeout.IdleStateHandler(300, 0, 0, TimeUnit.SECONDS));
        p.addLast("h2IdleClose", new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof IdleStateEvent) {
                    ctx.close();
                    return;
                }
                super.userEventTriggered(ctx, evt);
            }
        });
        p.addLast(Http2FrameCodecBuilder.forServer().build());
        p.addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.attr(IS_HTTPS).set(https);
                ch.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true),
                        new ProxyHandler(https, true));
            }
        }));
    }

    /**
     * 明文口协议探测：命中 h2c 连接预面按 HTTP/2 装 pipeline，否则 HTTP/1.1。
     * 自管理累积缓冲：将入站字节全部缓存至嗅探出结果后，连同已缓存字节整体下发，
     * 避免只转发魔数前缀而丢失请求其余部分。
     */
    static final class ProtocolProbe extends ChannelInboundHandlerAdapter {
        private final ByteBuf acc = Unpooled.buffer(H2_MAGIC_LEN * 4);
        private boolean decided;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (decided) {
                ctx.fireChannelRead(msg); // 已判定：直接下传（所有权一并转移）
                return;
            }
            ByteBuf in = (ByteBuf) msg;
            acc.writeBytes(in);
            in.release();
            int avail = acc.readableBytes();
            if (!matchesMagicPrefix(acc)) {
                decide(ctx, false); // 首字节即偏离魔数：HTTP/1.1
            } else if (avail >= H2_MAGIC_LEN) {
                decide(ctx, true); // 收满 24 字节且前缀全等：h2c prior-knowledge
            }
            // 否则为罕见分片：魔数前缀尚未收满，继续等待
        }

        private void decide(ChannelHandlerContext ctx, boolean h2) {
            decided = true;
            ByteBuf all = acc.copy(); // 完整缓存（含预面）交给下层编解码器
            io.netty.channel.ChannelPipeline p = ctx.pipeline();
            try {
                p.remove("probeTimeout"); // 探测完成，交给业务 idle 处理
            } catch (NoSuchElementException ignored) {
            }
            // remove 后 ctx 已失效（其 fireChannelRead 会被静默丢弃），
            // 必须从 pipeline 头部重新下发，才能进入新装编解码器
            p.remove(this); // 触发 handlerRemoved 统一释放 acc
            if (h2) installHttp2(p, false);
            else installHttp1(p, false);
            p.fireChannelRead(all);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            ctx.fireChannelInactive(); // acc 由 handlerRemoved 统一释放
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logFrontError(ctx, cause);
            ctx.close(); // acc 由 handlerRemoved 统一释放
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            acc.release(); // 唯一释放点：decide 移除/连接关闭均经此处，避免双重释放
        }

        /** 已读部分是否与 h2c 魔数前缀一致（存在任一字节偏离即返回 false） */
        private static boolean matchesMagicPrefix(ByteBuf b) {
            int n = Math.min(b.readableBytes(), H2_MAGIC.length);
            for (int i = 0; i < n; i++) {
                if (b.getByte(b.readerIndex() + i) != H2_MAGIC[i]) return false;
            }
            return true;
        }
    }

    /** TLS 握手完成后按 ALPN 结果切换 h2 / h1 pipeline */
    static final class AlpnSwitch extends ApplicationProtocolNegotiationHandler {
        AlpnSwitch() {
            super(ApplicationProtocolNames.HTTP_1_1);
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
            try {
                ctx.pipeline().remove("hsTimeout"); // 握手完成，交给业务 idle 处理
            } catch (NoSuchElementException ignored) {
            }
            boolean https = Boolean.TRUE.equals(ctx.channel().attr(IS_HTTPS).get());
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                installHttp2(ctx.pipeline(), https);
            } else {
                installHttp1(ctx.pipeline(), https);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof SslHandshakeCompletionEvent e && !e.isSuccess()) {
                Logs.warn(Logs.PROXY, "代理 TLS 握手失败 " + ctx.channel().remoteAddress() + ": "
                        + e.cause().getMessage());
                ctx.close();
                return;
            }
            super.userEventTriggered(ctx, evt);
        }
    }

    // ==================== 后端连接 ====================

    static SslContext backendTlsCtx(boolean insecure) {
        if (insecure) {
            SslContext c = backendTlsInsecure;
            if (c == null) {
                try {
                    c = SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .protocols("TLSv1.2", "TLSv1.3")
                            .endpointIdentificationAlgorithm(null)
                            .build();
                } catch (Exception e) {
                    throw new IllegalStateException("构建后端 TLS 上下文失败", e);
                }
                backendTlsInsecure = c;
            }
            return c;
        }
        SslContext c = backendTls;
        if (c == null) {
            try {
                c = SslContextBuilder.forClient().protocols("TLSv1.2", "TLSv1.3").build();
            } catch (Exception e) {
                throw new IllegalStateException("构建后端 TLS 上下文失败", e);
            }
            backendTls = c;
        }
        return c;
    }

    /**
     * 连接后端：每请求短连接（回源请求带 Connection: close，对齐 nginx 默认行为；
     * 客户端侧 keep-alive / HTTP/2 多路复用不受影响）。
     *
     * @param withCodec true = 消息级中继（挂 HttpClientCodec + 读超时）；false = 原始隧道（Upgrade 请求）
     */
    static ChannelFuture connectBackend(ProxySite s, io.netty.channel.ChannelHandler handler,
                                        ChannelHandlerContext front, boolean withCodec) {
        return new Bootstrap()
                .group(front.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, s.adv().connectSec * 1000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if ("https".equals(s.scheme())) {
                            ch.pipeline().addLast(backendTlsCtx(s.adv().backendInsecure).newHandler(
                                    ch.alloc(), s.backendHost(), s.backendPort()));
                        }
                        if (withCodec) {
                            ch.pipeline().addLast(new HttpClientCodec(4096, 8192, 65536));
                            ch.pipeline().addLast(new ReadTimeoutHandler(s.adv().idleSec, TimeUnit.SECONDS));
                        }
                        ch.pipeline().addLast(handler);
                    }
                })
                .connect(new InetSocketAddress(s.backendHost(), s.backendPort()));
    }

    /** 当前 HTTPS 实际绑定端口（未监听返回 -1，force_https 拼 Location 用） */
    public static int boundHttpsPort() {
        return boundHttps;
    }

    /** 前端连接异常日志（连接重置/探测与握手超时等常见噪声不记，其余 warn） */
    static void logFrontError(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof java.io.IOException
                || cause instanceof ReadTimeoutException
                || cause instanceof NoSuchElementException) return;
        Logs.warn(Logs.PROXY, "代理处理异常: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    static InetSocketAddress clientAddr(Channel ch) {
        return (InetSocketAddress) ch.remoteAddress();
    }

    // ==================== 公共小工具 ====================

    static String hostOnly(String host) {
        if (host == null) return "";
        host = host.trim().toLowerCase(Locale.ROOT);
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) { // 单冒号才视为端口分隔（避开 IPv6 字面量）
            String maybePort = host.substring(colon + 1);
            if (maybePort.chars().allMatch(Character::isDigit)) host = host.substring(0, colon);
        }
        return host;
    }

    /** 构造回源路径：stripPrefix / pathRewrite / 后端基础路径拼接 */
    static String upstreamPath(ProxySite s, String path, String query) {
        String p = path;
        if (s.adv().stripPrefix != null && p.startsWith(s.adv().stripPrefix)) {
            p = p.substring(s.adv().stripPrefix.length());
            if (p.isEmpty() || !p.startsWith("/")) p = "/" + p;
        } else if (s.adv().pathRewriteFrom != null && p.startsWith(s.adv().pathRewriteFrom)) {
            p = s.adv().pathRewriteTo + p.substring(s.adv().pathRewriteFrom.length());
            if (p.isEmpty() || !p.startsWith("/")) p = "/" + p;
        }
        if (!s.basePath().isEmpty()) p = s.basePath() + (p.equals("/") ? "" : p);
        return (p.isEmpty() ? "/" : p) + (query == null || query.isEmpty() ? "" : "?" + query);
    }

    /** 逐跳头判定（请求与响应共用，小写比较） */
    static boolean hopByHop(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                 "te", "trailer", "transfer-encoding", "upgrade", "proxy-connection" -> true;
            default -> false;
        };
    }

    // ==================== 常量响应 ====================

    /**
     * 输出简单文本响应（错误页/重定向）：h1 经 HttpServerCodec、h2 经流对象编解码器均可直接写。
     * keepAlive=false 时写后关闭连接。
     */
    static void sendSimple(ChannelHandlerContext ctx, int status, String text,
                           String extraName, String extraValue, boolean keepAlive) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        io.netty.handler.codec.http.DefaultFullHttpResponse res =
                new io.netty.handler.codec.http.DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        io.netty.handler.codec.http.HttpResponseStatus.valueOf(status),
                        Unpooled.wrappedBuffer(body));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, body.length)
                .set(HttpHeaderNames.SERVER, "NexHome");
        if (extraName != null) res.headers().set(extraName, extraValue);
        HttpUtil.setKeepAlive(res, keepAlive);
        io.netty.channel.ChannelFuture f = ctx.writeAndFlush(res);
        if (!keepAlive) f.addListener(ChannelFutureListener.CLOSE);
    }
}
