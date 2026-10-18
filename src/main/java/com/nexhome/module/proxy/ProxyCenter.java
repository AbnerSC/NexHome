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
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
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
import io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AttributeKey;
import io.netty.util.Mapping;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import javax.net.ssl.SSLException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 反向代理监听引擎（Netty）。
 * <p>
 * 独立于面板 WebServer 的两组监听：
 * <ul>
 *   <li>HTTP 口：明文探测（h2c prior-knowledge 魔数识别），其余按 HTTP/1.1 处理</li>
 *   <li>HTTPS 口：SNI 选证（{@link SslStore}），ALPN 协商 h2 / http/1.1</li>
 * </ul>
 * 请求流水线：路由匹配（域名通配 + 路径前缀）→ force_https / IP 白名单 / BasicAuth / 限流 /
 * 文件缓存 → 头部改写（XFF 等 + 自定义）→ 后端连接（回源协议由站点 {@code backendProtocol} 指定：
 * {@code http1} 默认每请求短连接，对齐 nginx 默认不启用 upstream keepalive，开启 {@code upstreamKeepalive}
 * 后按后端 host:port + 事件循环复用空闲连接；{@code h2} 则按连接并发多流复用，一条连接常驻、
 * 事务只开关流）→ 响应流式回写（tee 写缓存）；
 * Upgrade 请求走全原始字节隧道（101 后双端原始泵，WebSocket 透传，仅 HTTP/1.1 连接且回源为 http1）。
 * <p>
 * 站点与证书热更新：路由表/证书库原子替换，不影响存量连接。
 */
public final class ProxyCenter {

    /** 监听配置快照（由 ProxyService 读取数据库后传入） */
    public record Cfg(boolean enabled, int httpPort, int httpsPort) {
    }

    static final AttributeKey<Boolean> IS_HTTPS = AttributeKey.valueOf("nx-proxy-https");

    /** 后端连接上常驻的中继处理器（用于连接复用：取出后重新绑定新事务） */
    static final AttributeKey<BackendRelay> RELAY = AttributeKey.valueOf("nx-backend-relay");

    /** 回源空闲连接池：路由 key → (事件循环 → 空闲连接队列)。
     * 每个 (key, loop) 分片仅由该 loop 线程读写（后端连接与前端连接同循环），无需加锁。
     * 仅存 HTTP/1.1 回源连接；h2 回源按连接并发多流复用，见 {@link #H2_POOL}。 */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<EventLoop, ArrayDeque<Channel>>> POOL =
            new ConcurrentHashMap<>();

    /** HTTP/2 客户端连接预面（prior-knowledge 魔数，24 字节） */
    private static final byte[] H2_MAGIC = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int H2_MAGIC_LEN = H2_MAGIC.length;

    private static volatile List<ProxySite> sites = List.of();
    private static volatile Cfg cfg = new Cfg(false, 80, 443);
    /**
     * 是否通过代理转发头解析访客真实来源 IP（{@code X-Forwarded-For} 首跳 / {@code X-Real-IP} /
     * {@code CF-Connecting-IP}），供访问统计与 IP 白名单使用。无条件信任这些头（口径对齐面板
     * {@code Ctx.clientIp()}），故为全局开关；前置有可信 CDN/反代时开启，直连暴露且需以真实
     * socket 对端为准时关闭。回源追加的 XFF 链不受此开关影响，始终附加直连对端地址。
     */
    static volatile boolean trustRealIp = true;
    private static NioEventLoopGroup boss, worker;
    private static volatile Channel httpCh, httpsCh;
    private static volatile int boundHttp = -1, boundHttps = -1;
    /** 后端 HTTPS 客户端上下文：默认校验 / backendInsecure 免校验，懒构建 */
    private static volatile SslContext backendTls, backendTlsInsecure;
    /** 回源 HTTP/2 的 TLS 客户端上下文（ALPN 只通告 h2），与 h1 上下文分开缓存 */
    private static volatile SslContext backendH2Tls, backendH2TlsInsecure;

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
        clearPool();
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
                .option(ChannelOption.SO_BACKLOG, 1024)
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

    /** SNI 握手选证：站点指定任务优先，其余按域名自动匹配；未命中兜底剩余有效期最长的证书，证书库为空才终止握手 */
    private static final Mapping<CharSequence, SslContext> SNI_MAPPING = hostname -> {
        SslContext ctx = SslStore.forSni(hostname == null ? "" : hostname.toString(), sites);
        if (ctx == null) {
            Logs.warn(Logs.PROXY, "SNI 域名 " + hostname + " 无可用证书（证书库为空，请在 SSL 证书管理中签发）");
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
        m.put("trustRealIp", trustRealIp);
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

    /** 站点删除：清理其限流桶与运行统计（否则长期增删站点会让两个按 id 索引的表只增不减） */
    public static void dropSiteState(long id) {
        BUCKETS.remove(id);
        STATS.remove(id);
    }

    /** 限流令牌桶（站点级，平滑速率） */
    static final class Bucket {
        final double rps;
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

    /** 站点热更新后 rateLimitRps 变化时重建桶，避免改限流配置不生效 */
    static Bucket bucket(ProxySite s) {
        return BUCKETS.compute(s.id(), (k, b) ->
                b != null && b.rps == s.adv().rateLimitRps ? b : new Bucket(s.adv().rateLimitRps));
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

    /**
     * HTTP 报文解析上限（前后端对称）：初始行 4KB、头部总量 64KB、分块 8KB。
     * 回源侧曾误用 8KB 头限额，后端返回大 Cookie/JWT 头时解码失败会拖挂事务，
     * 故两侧统一取同一组常量，超限一律由 {@link BackendRelay} 显式回 502。
     */
    static final int MAX_INITIAL_LINE = 4096;
    static final int MAX_HEADER_SIZE = 65536;
    static final int MAX_CHUNK_SIZE = 8192;

    /** HTTP/1.1 pipeline：编解码（头总量 64KB）+ 转发处理器 */
    static void installHttp1(io.netty.channel.ChannelPipeline p, boolean https) {
        p.addLast("codec", new HttpServerCodec(MAX_INITIAL_LINE, MAX_HEADER_SIZE, MAX_CHUNK_SIZE));
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
            if (c == null) backendTlsInsecure = c = buildBackendTls(true, false);
            return c;
        }
        SslContext c = backendTls;
        if (c == null) backendTls = c = buildBackendTls(false, false);
        return c;
    }

    /**
     * 回源 HTTP/2 的 TLS 客户端上下文：ALPN 只通告 {@code h2}（站点显式声明后端为 h2）。
     * 后端不支持 h2 时握手结果为 null/http/1.1，由 {@link #newH2Parent} 判定后明确报错，不静默降级。
     */
    static SslContext backendH2TlsCtx(boolean insecure) {
        if (insecure) {
            SslContext c = backendH2TlsInsecure;
            if (c == null) backendH2TlsInsecure = c = buildBackendTls(true, true);
            return c;
        }
        SslContext c = backendH2Tls;
        if (c == null) backendH2Tls = c = buildBackendTls(false, true);
        return c;
    }

    private static SslContext buildBackendTls(boolean insecure, boolean h2) {
        try {
            SslContextBuilder cb = insecure
                    ? SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                    : SslContextBuilder.forClient();
            cb.protocols("TLSv1.2", "TLSv1.3");
            if (insecure) cb.endpointIdentificationAlgorithm(null);
            if (h2) cb.applicationProtocolConfig(new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2));
            return cb.build();
        } catch (Exception e) {
            throw new IllegalStateException("构建后端 TLS 上下文失败", e);
        }
    }

    /**
     * 取用一条可直接收发 HTTP 对象的回源 channel（回调形态）。
     * <ul>
     *   <li>HTTP/1.1：启用 {@code upstreamKeepalive} 时优先从当前事件循环的空闲池取回可复用连接，
     *       否则每请求新建短连接；回调参数即物理连接本身。</li>
     *   <li>HTTP/2：在既有 h2 连接上新开一条流（多路复用），回调参数为流 channel；池内连接全部满载
     *       且已达连接上限时，临时新建一条用后即关的连接（不拒绝请求）。</li>
     * </ul>
     * 回调总在该 channel 的事件循环线程（与前端同循环）执行；失败时 channel 为 null、cause 非空。
     * 取到的 channel 均已绑定 {@link #RELAY}，调用方在回调里 {@code beginExchange(...)} 绑定本次事务再写请求。
     */
    static void openBackend(ProxySite s, ChannelHandlerContext front, BiConsumer<Channel, Throwable> ready) {
        if (s.adv().backendH2()) {
            EventLoop loop = front.channel().eventLoop();
            openH2Stream(s, h2Shard(s, loop), loop, ready);
            return;
        }
        openH1Backend(s, front, ready);
    }

    /** 建连瞬时失败的重试次数与首档间隔：共补两次（30ms / 120ms 退避），避免与真正下线的后端打拉锯 */
    private static final int CONNECT_RETRIES = 2;
    private static final int CONNECT_RETRY_MS = 30;

    /** 第 attempt 次重试前的等待：端口冲突的在途 RST 比一次 RTT 活得久，第二档多等几倍 */
    private static int connectRetryDelay(int attempt) {
        return attempt == 0 ? CONNECT_RETRY_MS : CONNECT_RETRY_MS * 4;
    }

    /**
     * 该建连失败是否值得重试。判据是「请求字节尚未发出」：TCP 层被中止/重置/拒绝都属此类，
     * 重试与业务是否幂等无关。典型场景是 Windows 回环在客户端提前 RST 后把同一临时端口立即分给出站连接，
     * 新连接会被在途的旧 RST 判死（WSAECONNABORTED，实测千分之几的发生率，单次重试不能完全消掉）
     * ——不重试就把这个系统抖动放大成用户可见的 502。超时与 TLS 失败不重试：那是确定性的可达性/配置问题，
     * 重试只会拖慢错误反馈。
     */
    private static boolean retryableConnect(Throwable t) {
        if (t instanceof ConnectTimeoutException || t instanceof TimeoutException) return false;
        if (t instanceof SSLException) return false;
        return t instanceof IOException;
    }

    /** HTTP/1.1 回源连接：启用池化时优先复用空闲连接，无空闲则新建 */
    private static void openH1Backend(ProxySite s, ChannelHandlerContext front, BiConsumer<Channel, Throwable> ready) {
        ProxyAdvanced adv = s.adv();
        EventLoop loop = front.channel().eventLoop();
        if (adv.upstreamKeepalive) {
            ConcurrentHashMap<EventLoop, ArrayDeque<Channel>> perLoop = POOL.get(routeKey(s));
            ArrayDeque<Channel> dq = perLoop == null ? null : perLoop.get(loop);
            if (dq != null) {
                while (!dq.isEmpty()) {
                    Channel c = dq.pollLast(); // LIFO：优先复用最近使用的，最大化后端侧保活命中
                    BackendRelay r = c.attr(RELAY).get();
                    if (r != null && r.parked && c.isActive()) {
                        r.parked = false;
                        r.cancelEvict();
                        ready.accept(c, null);
                        return;
                    }
                    // 陈旧条目（已关闭/已不在 parked 态）：确保关闭后丢弃
                    if (c.isActive()) c.close();
                }
            }
        }
        connectH1Backend(s, loop, 0, ready);
    }

    private static void connectH1Backend(ProxySite s, EventLoop loop, int attempt, BiConsumer<Channel, Throwable> ready) {
        ProxyAdvanced adv = s.adv();
        BackendRelay relay = new BackendRelay(adv.upstreamKeepalive, false);
        new Bootstrap()
                .group(loop)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, adv.connectSec * 1000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if ("https".equals(s.scheme())) {
                            ch.pipeline().addLast(backendTlsCtx(adv.backendInsecure).newHandler(
                                    ch.alloc(), s.backendHost(), s.backendPort()));
                        }
                        ch.pipeline().addLast(new HttpClientCodec(MAX_INITIAL_LINE, MAX_HEADER_SIZE, MAX_CHUNK_SIZE));
                        ch.pipeline().addLast("brelay", relay);
                        ch.attr(RELAY).set(relay);
                    }
                })
                .connect(new InetSocketAddress(s.backendHost(), s.backendPort()))
                .addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        ready.accept(f.channel(), null);
                        return;
                    }
                    Throwable cause = f.cause();
                    if (attempt < CONNECT_RETRIES && retryableConnect(cause)) {
                        loop.schedule(() -> connectH1Backend(s, loop, attempt + 1, ready),
                                connectRetryDelay(attempt), TimeUnit.MILLISECONDS);
                        return;
                    }
                    ready.accept(null, cause);
                });
    }

    /**
     * 交换结束将后端连接归还空闲池（由 {@link BackendRelay} 在确认响应可保活后调用）：
     * 摘除读超时、置 parked、入队并安排空闲回收。池已满或站点已关池化则直接关闭。
     */
    static void park(ProxySite s, ChannelHandlerContext ctx, BackendRelay relay) {
        Channel ch = ctx.channel();
        relay.removeReadTimeout();
        ProxyAdvanced adv = s.adv();
        if (!adv.upstreamKeepalive) {
            ch.close();
            return;
        }
        ArrayDeque<Channel> dq = POOL
                .computeIfAbsent(routeKey(s), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ch.eventLoop(), k -> new ArrayDeque<>());
        if (dq.size() >= adv.upstreamMaxIdleConns) { // 池已满：不再保留，关闭回收 fd
            ch.close();
            return;
        }
        relay.parked = true;
        relay.txParked();   // 清除与已完成事务的绑定，parked 期间不引用任何前端
        dq.addLast(ch);
        relay.evict = ch.eventLoop().schedule(
                () -> { if (relay.parked) ch.close(); }, adv.upstreamIdleSec, TimeUnit.SECONDS);
    }

    /** 空闲连接被关闭/异常时从池剔除（同 loop 线程回调，无锁） */
    static void evictParked(Channel ch, BackendRelay relay) {
        relay.parked = false;
        relay.cancelEvict();
        ProxySite s = relay.lastSite;
        if (s == null) return;
        ConcurrentHashMap<EventLoop, ArrayDeque<Channel>> perLoop = POOL.get(routeKey(s));
        if (perLoop == null) return;
        ArrayDeque<Channel> dq = perLoop.get(ch.eventLoop());
        if (dq != null) dq.remove(ch);
    }

    /** 后端路由 key：协议 + scheme + host + port +（免校验 TLS 时附加标记）。
     * 回源协议参与取池，使 http1/h2 两条池互不串用（站点改协议后旧连接按空闲超时自然回收）。 */
    private static String routeKey(ProxySite s) {
        return (s.adv().backendH2() ? "h2+" : "http1+") + s.scheme() + "://" + s.backendHost() + ":" + s.backendPort()
                + (s.adv().backendInsecure ? "#insecure" : "");
    }

    /** 停止/重启监听时清空并关闭全部空闲连接（先复制再关，避免 channelInactive 回调并发修改队列） */
    private static void clearPool() {
        for (ConcurrentHashMap<EventLoop, ArrayDeque<Channel>> perLoop : POOL.values()) {
            for (ArrayDeque<Channel> dq : perLoop.values()) {
                for (Channel c : dq.toArray(new Channel[0])) {
                    BackendRelay r = c.attr(RELAY).get();
                    if (r != null) r.cancelEvict();
                    c.close();
                }
            }
        }
        POOL.clear();
        for (ConcurrentHashMap<EventLoop, H2Shard> perLoop : H2_POOL.values()) {
            for (H2Shard sh : perLoop.values()) {
                // 管理线程只负责关连接；出池与等待者交付仍由该循环上的 channelInactive 完成
                for (H2Conn c : sh.live.toArray(new H2Conn[0])) c.closeParent();
                H2Conn b = sh.building;
                if (b != null) b.closeParent();
            }
        }
        H2_POOL.clear();
    }

    // ==================== 回源 HTTP/2（连接常驻 + 流级事务） ====================

    /** h2 后端连接（parent）上的并发记账状态 */
    static final AttributeKey<H2Conn> H2_CONN = AttributeKey.valueOf("nx-h2-conn");

    /**
     * 回源 h2 连接池：路由 key → (事件循环 → 分片状态)。
     * 与 {@link #POOL}（空闲待取的整连接）语义不同：这里的连接是「承载体」，同一 (key, loop)
     * 至多保留 {@code h2MaxConns} 条，每条按后端 SETTINGS 并发承载多条流，一条流的结束不影响其他流。
     * 每个分片仅由该 loop 线程读写（后端与前端同循环），无需加锁。
     */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<EventLoop, H2Shard>> H2_POOL =
            new ConcurrentHashMap<>();

    /** 一个 (路由, 事件循环) 分片：已完成握手的常驻连接，外加至多一条在建连接（等待者挂在其上） */
    private static final class H2Shard {
        final ArrayDeque<H2Conn> live = new ArrayDeque<>();
        /**
         * 正在建连/握手的连接（TCP + 可选 TLS ALPN + 连接预面 + 后端 SETTINGS）。
         * 后端并发上限在收到 SETTINGS 前未知，冷启动的这批请求先共用同一条连接；
         * 无此字段时同批请求会逐条新建连接，把连接数瞬时打爆。
         */
        H2Conn building;
    }

    /**
     * 一条 h2 后端连接的握手与并发记账（仅在该 parent 的事件循环线程读写）。
     * <p>
     * 「就绪」由三个相互独立、到达顺序不定的信号共同决定：{@code preface}（连接预面已写出）、
     * {@code settings}（收到后端 SETTINGS，并发上限的权威来源）、{@code alpn}（TLS 后端确认协商到
     * h2，明文后端恒为真）。就绪之前绝不在该连接上开流：预面未发就写 HEADERS 会打乱帧序，
     * SETTINGS 未知就放开并发则可能超出后端上限——Netty 对此是<em>整条连接</em>报 connection error，
     * 而不是只拒绝多出来的那几条流。
     */
    static final class H2Conn {
        final ProxySite site;
        /** 池满时临时新建的连接：单条流用完即关，不入池、不保留 */
        final boolean overflow;
        /** 后端为 https：需先等 ALPN 确认 h2 */
        final boolean tls;
        /** 流的 pipeline 模板（与多路复用器共用同一实例，重复初始化时靠 {@link #RELAY} 属性幂等） */
        final StreamInitializer init;
        /** 等待握手完成的取流请求，就绪后统一交付（溢出连接只服务它自己那一条） */
        List<BiConsumer<Channel, Throwable>> waiters = new ArrayList<>();
        /** 后端连接本体，在 initChannel 里绑定（不能等 channelActive，见 {@link #newH2Parent}） */
        Channel parent;
        /** TCP 已连通：未连通前，那次尝试的 channelInactive/异常不作为失败交付（见 {@link #connectH2Parent}） */
        boolean connected;
        /** 已做过几次 TCP 建连尝试（仅用于限制重试次数） */
        int tcpTries;
        /** 允许的并发流数：SETTINGS 到达前按 1，之后与后端通告值、站点配置取小 */
        int cap = 1;
        /** 已占用并发额度的流数（含正在建立、尚未注册完成的流） */
        int inflight;
        boolean preface;
        boolean settings;
        boolean alpn;
        /** 后端发来 GOAWAY（或连接已废）：不再安排新流 */
        boolean goaway;
        /** 等待者已交付（入池或失败），不重复处理 */
        boolean handed;
        ScheduledFuture<?> evict;     // 空闲回收
        ScheduledFuture<?> handshake; // 握手兜底超时

        H2Conn(ProxySite site, boolean overflow) {
            this.site = site;
            this.overflow = overflow;
            this.tls = "https".equals(site.scheme());
            this.alpn = !this.tls;
            this.init = new StreamInitializer(site);
        }

        void bind(Channel ch) {
            parent = ch;
            ch.attr(H2_CONN).set(this);
        }

        boolean ready() {
            return preface && settings && alpn;
        }

        /** 已就绪（入池）且仍有余量才可承载新流 */
        boolean usable() {
            return handed && !goaway && parent != null && parent.isActive() && inflight < cap;
        }

        void take() {
            inflight++;
            cancelEvict();
        }

        /** 一条流结束：归还并发额度；溢出/已 GOAWAY 连接立即关闭，池内连接在无流时安排空闲回收 */
        void release() {
            if (inflight > 0) inflight--;
            if (overflow || goaway) {
                closeParent();
                return;
            }
            if (inflight == 0 && parent != null && parent.isActive()) {
                int idle = Math.max(1, site.adv().upstreamIdleSec);
                evict = parent.eventLoop().schedule(
                        () -> {
                            if (inflight == 0) closeParent();
                        }, idle, TimeUnit.SECONDS);
            }
        }

        void cancelEvict() {
            if (evict != null) {
                evict.cancel(false);
                evict = null;
            }
        }

        void cancelHandshake() {
            if (handshake != null) {
                handshake.cancel(false);
                handshake = null;
            }
        }

        void closeParent() {
            Channel p = parent;
            if (p != null) p.close();
        }
    }

    /**
     * 取用一条 h2 回源流：优先在已就绪且仍有余量的连接上开流；有连接在建则挂到它上等握手完成
     * （冷启动并发共用一条连接）；池已满且全部满载则新建「用后即关」的溢出连接——宁可多一条
     * TCP 也不拒绝请求（对齐 nginx 超出 upstream keepalive 池即新建短连接）。
     */
    private static void openH2Stream(ProxySite s, H2Shard sh, EventLoop loop, BiConsumer<Channel, Throwable> ready) {
        H2Conn pick = null;
        List<H2Conn> dead = null;
        // 从最近使用的一端榨干并发容量（最大化单连接复用），顺带摘除已失效条目
        Iterator<H2Conn> it = sh.live.descendingIterator();
        while (it.hasNext()) {
            H2Conn c = it.next();
            if (c.parent == null || !c.parent.isActive() || c.goaway) {
                if (dead == null) dead = new ArrayList<>();
                dead.add(c);
                continue;
            }
            if (pick == null && c.usable()) pick = c;
        }
        if (dead != null) {
            for (H2Conn c : dead) {
                sh.live.remove(c);
                c.cancelEvict();
            }
        }
        if (pick != null) {
            newH2Stream(pick, ready);
            return;
        }
        if (sh.building != null && !sh.building.overflow) {
            sh.building.waiters.add(ready); // 有一条常驻连接正在握手：挂上去共用，不重复建连
            return;
        }
        boolean overflow = sh.live.size() + (sh.building == null ? 0 : 1) >= s.adv().h2MaxConns;
        H2Conn conn = new H2Conn(s, overflow);
        conn.waiters.add(ready);
        if (!overflow) sh.building = conn;
        newH2Parent(s, sh, loop, conn);
    }

    private static H2Shard h2Shard(ProxySite s, EventLoop loop) {
        return H2_POOL.computeIfAbsent(routeKey(s), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(loop, k -> new H2Shard());
    }

    /**
     * 在既有 h2 连接上开一条流。并发额度在本方法内同步占用（而非等流的 handlerAdded），
     * 否则同一读批次里的多个请求会挑中同一条连接而超出后端 SETTINGS 上限；
     * 归还以流的 closeFuture 为唯一触发点，天然幂等。
     */
    private static void newH2Stream(H2Conn conn, BiConsumer<Channel, Throwable> ready) {
        if (conn.parent == null || !conn.parent.isActive() || conn.goaway) {
            ready.accept(null, new IOException("后端 HTTP/2 连接已关闭")); // 连接已失效（回调竞态）
            return;
        }
        conn.take();
        Http2StreamChannelBootstrap sb = new Http2StreamChannelBootstrap(conn.parent);
        sb.handler(conn.init);
        sb.open().addListener((GenericFutureListener<Future<Http2StreamChannel>>) f -> {
            if (!f.isSuccess()) {
                conn.release(); // 开流失败：额度立即归还（溢出连接此时会随之关闭）
                ready.accept(null, f.cause());
                return;
            }
            Http2StreamChannel stream = f.getNow();
            stream.closeFuture().addListener((ChannelFutureListener) cf -> conn.release());
            ready.accept(stream, null);
        });
    }

    /**
     * 新建一条 h2 后端连接。明文后端按 prior-knowledge 直接发连接预面（站点已声明 h2，无需升级协商）；
     * HTTPS 后端在 ALPN 只通告 {@code h2}，握手后校验协商结果——协商不到 h2 就明确失败，
     * 绝不静默按 HTTP/1.1 回源（后端配置与站点声明不符必须暴露出来）。
     * <p>
     * 本方法不直接交付出流：预面、SETTINGS、ALPN 三个信号谁先到不确定（{@code Bootstrap} 的连接成功
     * 回调甚至可能早于 {@code channelActive} 传播到下游），因此只在 {@code initChannel} 里把连接绑定到
     * {@link H2Conn}，后续统一由 {@link #maybeReadyH2} 在三者齐备时入池并派发等待者。
     */
    private static void newH2Parent(ProxySite s, H2Shard sh, EventLoop loop, H2Conn conn) {
        ProxyAdvanced adv = s.adv();
        int waitSec = Math.max(2, adv.connectSec);
        // 服务端必在预面后立刻下发 SETTINGS；迟迟不发即不是合法的 h2 服务（如只监听 h1），
        // 兜底失败避免等待者无限挂起；预算从发起取流时开始计，包含建连重试在内
        conn.handshake = loop.schedule(() -> failH2(s, sh, conn, new IOException(
                        "后端 " + waitSec + " 秒内未完成 HTTP/2 握手（未收到 SETTINGS 帧），该端口很可能只提供 "
                                + "HTTP/1.1：请把回源协议改回 http1，或为后端启用 HTTP/2")),
                waitSec, TimeUnit.SECONDS);
        connectH2Parent(s, sh, loop, conn);
    }

    /** h2 后端连接的 TCP 建连（瞬时失败按 {@link #CONNECT_RETRIES} 重试一次，与 h1 同一判据） */
    private static void connectH2Parent(ProxySite s, H2Shard sh, EventLoop loop, H2Conn conn) {
        ProxyAdvanced adv = s.adv();
        new Bootstrap()
                .group(loop)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, adv.connectSec * 1000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        conn.bind(ch);
                        if (conn.tls) {
                            ch.pipeline().addLast(backendH2TlsCtx(adv.backendInsecure).newHandler(
                                    ch.alloc(), s.backendHost(), s.backendPort()));
                        }
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient()
                                .initialSettings(Http2Settings.defaultSettings()
                                        .maxConcurrentStreams(adv.h2MaxConcurrentStreams))
                                // 兜底：一旦超出后端 SETTINGS 通告的并发，由 Netty 缓冲而非把整条连接判死
                                .encoderEnforceMaxConcurrentStreams(true)
                                .build());
                        ch.pipeline().addLast(new H2ParentWatch(s, sh, conn));
                        // 第二个处理器应对后端主动开启的流（HTTP/2 推送）：本代理不支持推送
                        ch.pipeline().addLast(new Http2MultiplexHandler(conn.init, new PushReject()));
                    }
                })
                .connect(new InetSocketAddress(s.backendHost(), s.backendPort()))
                .addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        Throwable cause = f.cause();
                        // 建连阶段还没向后端发出任何字节，重试与请求是否幂等无关。
                        // 失败那次尝试的 channel 也会走 channelInactive，已由 conn.connected 挡在观察点之外，
                        // 不会抢先把等待者判为失败
                        if (conn.tcpTries++ < CONNECT_RETRIES && retryableConnect(cause)) {
                            loop.schedule(() -> connectH2Parent(s, sh, loop, conn),
                                    connectRetryDelay(conn.tcpTries - 1), TimeUnit.MILLISECONDS);
                            return;
                        }
                        failH2(s, sh, conn, cause);
                        return;
                    }
                    conn.connected = true;
                    if (!conn.tls) return; // 明文：等预面与 SETTINGS
                    SslHandler ssl = conn.parent.pipeline().get(SslHandler.class);
                    ssl.handshakeFuture().addListener((GenericFutureListener<Future<Channel>>) hf -> {
                        if (!hf.isSuccess()) {
                            failH2(s, sh, conn, alpnFail(hf.cause()));
                            return;
                        }
                        String proto = ssl.applicationProtocol();
                        if (!ApplicationProtocolNames.HTTP_2.equals(proto)) {
                            failH2(s, sh, conn, new IOException("后端未协商 HTTP/2（ALPN=" + proto
                                    + "），backendProtocol=h2 要求后端启用 HTTP/2；仅支持 HTTP/1.1 请改回 http1"));
                            return;
                        }
                        conn.alpn = true;
                        maybeReadyH2(s, sh, conn);
                    });
                });
    }

    /**
     * TLS 侧失败包装。后端不同意 h2 ALPN 时可能正常完成握手续而协商到 http/1.1（上面已处理），
     * 也可能直接回 TLS 致命告警 {@code no_application_protocol} 使握手失败（Node 即为后者的典型），
     * 两者归成同一句可读原因，不让用户去猜 SSL 堆栈。
     */
    private static IOException alpnFail(Throwable cause) {
        String m = String.valueOf(cause);
        if (m.contains("no_application_protocol") || m.contains("application protocol")) {
            return new IOException("后端未协商 HTTP/2（TLS 握手拒绝 h2 ALPN：no_application_protocol），"
                    + "backendProtocol=h2 要求后端启用 HTTP/2；仅支持 HTTP/1.1 请改回 http1");
        }
        return cause instanceof IOException ? (IOException) cause : new IOException(m, cause);
    }

    /** 握手信号齐备：入池并派发等待者（就绪前一条流都不开，避免帧序与并发越界） */
    private static void maybeReadyH2(ProxySite s, H2Shard sh, H2Conn conn) {
        if (conn.handed || !conn.ready() || conn.goaway) return;
        if (conn.parent == null || !conn.parent.isActive()) return; // 由 channelInactive 统一按失败交付
        conn.handed = true;
        conn.cancelHandshake();
        if (sh.building == conn) sh.building = null;
        List<BiConsumer<Channel, Throwable>> ws = conn.waiters;
        conn.waiters = List.of();
        if (!conn.overflow) sh.live.addLast(conn);
        for (BiConsumer<Channel, Throwable> w : ws) {
            // 常驻连接重新走调度：满载的那部分会自然另起连接；溢出连接只服务它自己那条
            if (conn.overflow) newH2Stream(conn, w);
            else openH2Stream(s, sh, conn.parent.eventLoop(), w);
        }
    }

    /**
     * 建连/握手失败（或已入池的连接提前终结）：等待者一律按失败交付，不重试协议层错误
     * （后端帧序错乱重试只会拖慢反馈；TCP 建连阶段的重试在 {@link #connectH2Parent} 内完成）；
     * 已入池的只出池，在途流各自收到本流关闭事件。
     */
    private static void failH2(ProxySite s, H2Shard sh, H2Conn conn, Throwable cause) {
        conn.cancelHandshake();
        if (sh.building == conn) sh.building = null;
        sh.live.remove(conn);
        conn.goaway = true;
        if (conn.handed) { // 已入池后失效：无等待者需交付，关连接即可
            conn.closeParent();
            return;
        }
        conn.handed = true;
        IOException err = cause instanceof IOException ? (IOException) cause
                : new IOException(h2Why(cause), cause);
        List<BiConsumer<Channel, Throwable>> ws = conn.waiters;
        conn.waiters = List.of();
        for (BiConsumer<Channel, Throwable> w : ws) w.accept(null, err);
        conn.closeParent();
    }

    /** 把 h2 协议错乱翻译成人能读的原因：最典型的是对回源声明了 h2、后端却只听 HTTP/1.1 */
    private static String h2Why(Throwable t) {
        String m = String.valueOf(t);
        if (t instanceof Http2Exception || m.contains("not SETTINGS")) {
            return "后端未按 HTTP/2 应答（首个控制帧不是 SETTINGS）：该端口很可能只提供 HTTP/1.1，"
                    + "请把回源协议改回 http1，或为后端启用 HTTP/2";
        }
        return m;
    }

    /**
     * h2 后端连接的帧观察点：预面 / SETTINGS / GOAWAY 决定连接何时可承载流、何时不再承载，
     * 以及断连时的出池与等待者交付。置于帧编解码器之后（连接级帧由其作为 {@code channelRead}
     * 消息下发，部分版本为用户事件，两者都接），且不消费任何事件，保证多路复用器仍能看到完整帧序列。
     */
    private static final class H2ParentWatch extends ChannelInboundHandlerAdapter {
        private final ProxySite site;
        private final H2Shard shard;
        private final H2Conn conn;

        H2ParentWatch(ProxySite site, H2Shard shard, H2Conn conn) {
            this.site = site;
            this.shard = shard;
            this.conn = conn;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            note(msg);
            ctx.fireChannelRead(msg);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            note(evt);
            super.userEventTriggered(ctx, evt);
        }

        private void note(Object o) {
            if (o instanceof Http2ConnectionPrefaceAndSettingsFrameWrittenEvent) {
                conn.preface = true;
                maybeReadyH2(site, shard, conn);
            } else if (o instanceof Http2SettingsFrame frame) {
                // 后端 SETTINGS 是并发上限的权威来源：与站点配置取小（超出部分另开连接）
                Long mc = frame.settings().maxConcurrentStreams();
                conn.cap = mc == null || mc <= 0
                        ? Math.max(1, site.adv().h2MaxConcurrentStreams)
                        : (int) Math.min(mc, site.adv().h2MaxConcurrentStreams);
                conn.settings = true;
                maybeReadyH2(site, shard, conn);
            } else if (o instanceof Http2GoAwayFrame) {
                // 后端优雅关闭：不再安排新流，在途流走完后由 release 收尾
                conn.goaway = true;
                shard.live.remove(conn);
                if (conn.inflight == 0) conn.closeParent();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (pendingConnect()) return; // 失败的是还没连通的那次尝试，结果由建连监听器决定
            failH2(site, shard, conn, new IOException("后端 HTTP/2 连接已关闭"));
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (pendingConnect()) return;
            failH2(site, shard, conn, cause);
            ctx.close();
        }

        /** TCP 尚未连通且结果尚未交付：此刻的关闭/异常事件属于失败的那次建连，不能判死等待者 */
        private boolean pendingConnect() {
            return !conn.connected && !conn.handed;
        }
    }

    /**
     * h2 回源流的 pipeline：流帧 <-> HTTP 对象转换 + 响应中继（每条流一个中继实例，
     * 事务结束只关流不动连接）。同一连接的多路复用器与本流的 bootstrap 共用同一实例，
     * 以 {@link #RELAY} 属性保证重复初始化时幂等。
     */
    private static final class StreamInitializer extends ChannelInitializer<Channel> {
        private final ProxySite site;

        StreamInitializer(ProxySite site) {
            this.site = site;
        }

        @Override
        protected void initChannel(Channel ch) {
            if (ch.attr(RELAY).get() != null) return;
            BackendRelay relay = new BackendRelay(false, true);
            ch.attr(RELAY).set(relay);
            ch.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(false));
            ch.pipeline().addLast("brelay", relay);
        }
    }

    /** 后端主动开启的流（HTTP/2 服务端推送）：不支持，立即重置该流 */
    private static final class PushReject extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) {
            ch.close();
        }
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
                            ch.pipeline().addLast(new HttpClientCodec(MAX_INITIAL_LINE, MAX_HEADER_SIZE, MAX_CHUNK_SIZE));
                            ch.pipeline().addLast(new ReadTimeoutHandler(s.adv().idleSec, TimeUnit.SECONDS));
                        }
                        ch.pipeline().addLast(handler);
                    }
                })
                .connect(new InetSocketAddress(s.backendHost(), s.backendPort()));
    }

    /** 当前 HTTP 实际绑定端口（未监听返回 -1，总览代理状态卡用） */
    public static int boundHttpPort() {
        return boundHttp;
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

    /**
     * 解析访客真实来源 IP（访问统计 / IP 白名单用）：{@link #trustRealIp} 开启时按
     * {@code X-Forwarded-For} 首跳 → {@code X-Real-IP} → {@code CF-Connecting-IP} 依次取值，
     * 全部为空回退直连对端地址；关闭时恒返回直连对端地址。口径对齐面板 {@code Ctx.clientIp()}。
     * <p>仅用于「识别访客」；回源时向下游追加的 XFF 链由调用方另取直连对端 {@code peerIp}，二者分离。
     */
    static String resolveClientIp(io.netty.handler.codec.http.HttpRequest req, String peerIp) {
        if (!trustRealIp || req == null) return peerIp;
        String xff = req.headers().get("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) return first;
        }
        String real = req.headers().get("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        String cf = req.headers().get("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        return peerIp;
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
