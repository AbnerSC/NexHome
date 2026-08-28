package com.nexhome.module.stun;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * STUN 穿透任务运行器（每个任务一个实例），穿透通道承载 UDP / TCP 两类数据转发。
 * <p>
 * <b>UDP 模式</b>：在绑定端口上打开 UDP Socket，持续向 STUN 服务器发送绑定请求
 * 建立并保活 NAT 映射；外网发入映射端口的数据包按会话转发到目标内网服务，
 * 响应沿原路回传给对端（简易会话表实现）。
 * <p>
 * <b>TCP 模式</b>（多级 NAT/运营商 CGNAT 场景按优先级自动选择出站通道，详见 {@link TcpPunch}）：
 * <ol>
 *   <li>路由器 WAN 口为公网：UPnP 端口映射即权威入站通道（外网端口=本地端口），跳过出站探测</li>
 *   <li>存在可用 STUN-over-TCP 服务器：从本地端口出站探测取得<b>精确</b>的 TCP 映射地址</li>
 *   <li>端口保留模式（兜底，全部 STUN/TCP 服务器不可用时的最后手段）：
 *       从本地端口出站连接公共透传端点（非 DNS 端口：53/TCP 被运营商 CGNAT 透明拦截，
 *       映射终结在 CGNAT 不接受入站）在运营商 CGNAT 上建立 TCP 映射，
 *       连接上不做应用层交互，周期检测链路存活、死亡即轮换新建连接维持映射；
 *       外部映射端口无法探测（实测同本地端口的 TCP/UDP 出站外部端口并不相同，
 *       展示取同端口 UDP STUN 映射仅为尽力而为的估计，入站不保证可达）</li>
 * </ol>
 * 出站保活长连接与本地监听共用同一端口（Linux SO_REUSEADDR 允许已连接 socket 与 LISTEN socket
 * 共存）：长连接维持沿途全部 NAT 的映射不回收，外网连入由监听 accept 后管道化转发到目标服务。
 * 配置了「对端公网地址」时额外周期性从同端口主动向对端打洞（双端 NAT 建立过滤条目）。
 * <p>
 * <b>地址权威性</b>：UDP 任务与 STUN/TCP 精确映射展示真实探测的映射地址；端口保留模式展示
 * 「UDP STUN 映射地址（同本地端口）」——实测本类运营商 CGNAT 对同一本地端口的 TCP/UDP 出站
 * 分配的外部端口并不相同，该展示仅为无 TCP 探测能力时的尽力而为估计，入站不保证可达；
 * 优于「外部端口=本地端口」假设（该 CGNAT 会改写端口，本地端口拼装在公网不存在）；
 * UDP STUN 暂无响应时退回「出口 IP + 本地端口」占位。UPnP 仅打通家用路由器一层（对未配 DMZ 的环境有价值），运营商
 * CGNAT 层的映射端口不受 UPnP 控制，因此 WAN 非公网时绝不用 UPnP 外部端口拼装展示地址
 * （该拼装地址在公网并不存在，是展示与自测双双失败的根源）。
 * <p>
 * <b>可用性自测</b>：验证「映射保活存活」（UDP：STUN 绑定响应刷新；TCP：保活链路交互有响应
 * + 本地监听正常），并按 NAT 类型给出入站可达性结论。运营商 CGNAT 普遍不支持 NAT 回流(hairpin)，
 * 从本机回环连接公网映射地址必然超时，不代表穿透失败；因此 TCP 自测另周期性调用第三方探测节点
 * 真实连接映射地址验证公网入站可达性（手动自测必验），补上「保活存活 ≠ 外网可主动连入」盲区。
 * <p>
 * <b>周期巡检</b>：仅按保活健康度触发重建（连续多个保活周期无响应才判定映射失效），
 * 不因回环自测超时反复重新穿透丢弃可用映射；TCP 映射缺失时退避重试出站探测。
 * 局限性：对称型 NAT（Symmetric）下 UDP 映射端口随机，纯 STUN 无法稳定穿透。
 */
final class StunRunner {

    /**
     * 预绑定备用出站 socket 数量：端口保留模式仅在保活检测发现链路死亡时消耗一个 spare 轮换
     * 新建出站连接（连接存活期间不消耗；监听开启后 LISTEN 占用端口无法再补充）。
     * 端点若为短空闲超时类型会周期性死亡轮换，60 个可支撑较长时间才需弹跳一次；
     * 若存活检测异常导致频繁轮换，加大该值可进一步拉长弹跳间隔。
     */
    private static final int TCP_SPARES = 60;

    private final long id;
    private final String name;
    private final String protocol;
    private final String targetIp;
    private final int targetPort;
    private final String stunHost;
    private final int stunPort;
    private final int bindPort;
    private final int keepaliveSec;
    /** TCP 打洞对端公网地址（ip:port，可为空；为空时仅依赖入站连接） */
    private final String peerAddr;
    /** 是否启用 UPnP 端口映射（路由器不支持 UPnP 时可关闭，避免无谓的 SSDP 发现等待） */
    private final boolean upnpEnabled;

    /** 本次运行是否已穿透成功（取得外网映射地址），用于记录穿透成功时间 */
    private volatile boolean punched;
    /** 当前已记录穿透时间的外网映射地址，映射地址（端口）变化时同步刷新穿透成功时间 */
    private volatile String punchedMapped;
    /** 巡检连续自测未通过的次数（恢复后清零，仅用于日志参考；自愈由保活/巡检调度自动完成） */
    private int watchFails;
    /** UPnP 网关与映射状态：配合 STUN 在路由器上显式开放入站端口 */
    private UpnpClient.Gateway upnpGateway;
    private boolean upnpMapped;
    private int upnpPort;
    /** UPnP 映射的路由器 WAN 地址（ip:port），自测本地链路以此为准 */
    private volatile String upnpWanAddr;
    /** 是否已建立 TCP 方向外网映射（STUN/TCP 精确映射、端口保留出站链路或 UPnP 公网直通），TCP 自测仅在其成立时有意义 */
    private volatile boolean wanTcpReady;
    /** TCP 出站保活链路管理器（STUN/TCP 精确映射与端口保留模式自动选择） */
    private TcpPunch punch;
    /** 最近一次真实外网入站连接时刻（排除本机公网回环的自测连接），日志参考 */
    private volatile long lastPeerInboundAt;
    /** 最近一次收到 STUN 绑定响应（映射刷新）的时间戳，用于判断保活是否失效 */
    private volatile long lastMappedAt;
    /** 最近一次 UDP STUN 映射地址（ip:port）：端口保留模式据此组装展示地址（尽力而为估计，入站不保证可达） */
    private volatile String udpMappedAddr;
    /** 路由器 WAN 口为公网且 UPnP 映射成功：UPnP 端口映射即权威入站通道，无需 STUN/TCP 出站探测与刷新 */
    private volatile boolean upnpPublicWan;
    /** TCP 外网映射来源：true=UPnP 端口映射（外网可主动连入），false=出站探测映射（实测外网可主动连入，自测通过即互联网可达） */
    private volatile boolean tcpMappedViaUpnp;
    /** TCP 映射刷新进行中（刷新需短暂关闭监听，自测与巡检应错开该窗口避免误报） */
    private volatile boolean tcpRefreshing;
    /** 保活失败已告警过的服务器（失效服务器仅告警一次，避免每个保活周期重复刷屏） */
    private final Set<String> warnedKeepalive = ConcurrentHashMap.newKeySet();
    /** 上次公网入站验证时刻（第三方探测节点真实连接）：周期自测限冷却 5 分钟，手动自测不限 */
    private volatile long lastExtCheckAt;
    /** 上次公网入站验证结果（仅状态变化时打日志，避免刷屏） */
    private volatile Boolean lastExtReachable;
    /** 公网入站验证用 HTTP 客户端（第三方探测服务，低频调用） */
    private static final HttpClient EXT_CHECK_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private volatile boolean running;
    private DatagramSocket udpSocket;
    private ServerSocket tcpServer;
    private ScheduledFuture<?> keepaliveTask;
    private ScheduledFuture<?> punchTask;
    /** 穿透成功后的周期有效性巡检任务 */
    private ScheduledFuture<?> checkTask;
    /** UDP 会话表：对端地址 -> 与目标服务通信的 socket */
    private final Map<String, DatagramSocket> sessions = new ConcurrentHashMap<>();

    /** STUN 服务器候选地址缓存（配置服务器 + 维护列表），60s 刷新：入站包判定与保活都高频使用，逐包查库成本过高 */
    private volatile Set<String> cachedStunCandidates = Set.of();
    private volatile long cachedCandidatesAt;

    StunRunner(Map<String, Object> task) {
        this.id = ((Number) task.get("id")).longValue();
        this.name = str(task, "name");
        this.protocol = str(task, "protocol");
        this.targetIp = str(task, "target_ip");
        this.targetPort = intVal(task, "target_port");
        this.stunHost = str(task, "stun_host");
        this.stunPort = intVal(task, "stun_port");
        this.bindPort = intVal(task, "bind_port");
        this.keepaliveSec = Math.max(10, intVal(task, "keepalive_sec"));
        this.peerAddr = str(task, "peer_addr").trim();
        this.upnpEnabled = intVal(task, "upnp_enabled") != 0;
    }

    /** 启动穿透任务（启动中途异常时释放已建立的 UPnP 映射，避免路由器残留） */
    void start() throws Exception {
        running = true; // 先置位：接收线程启动后立即依赖该标志
        punched = false;
        wanTcpReady = false;
        upnpWanAddr = null;
        upnpPublicWan = false;
        tcpMappedViaUpnp = false;
        tcpRefreshing = false;
        punch = new TcpPunch(name, stunHost, stunPort, this::onTcpLinkReady);
        try {
            if ("UDP".equalsIgnoreCase(protocol)) {
                startUdp();
            } else {
                startTcp();
            }
        } catch (Exception | Error e) {
            stop(); // 释放 socket 与已建立的 UPnP 映射，防止启动失败残留路由器映射
            throw e;
        }
        updateStatus("RUNNING");
        Logs.info(Logs.STUN, "穿透任务[" + name + "] 已启动 (" + protocol + ")");
        // 启动后自测一次并记录结果（仅验证映射保活存活，不触发自动重穿，理由见类注释）
        Tasks.delay(2, this::verifyQuietly);
        // 周期巡检：按保活健康度监测映射，静默失效时自动重建
        startWatcher();
    }

    /** 静默执行一次可用性自测（结果仅记录，异常不打断调度） */
    private void verifyQuietly() {
        if (!running) return;
        try {
            verifyChannel();
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 可用性自测异常: " + e.getMessage());
        }
    }

    /**
     * NAT 类型探测：配置的服务器不可达（域名失效/被墙）时回退到维护列表候选服务器，
     * 保证 NAT 类型与外网映射地址展示不受单点服务器故障影响。
     */
    private StunClient.Result detectNatWithFallback(DatagramSocket socket) {
        StunClient.Result r = StunClient.detectNatType(socket, stunHost, stunPort, 3000);
        if (r != null && r.mappedAddress() != null) return r;
        for (String addr : stunServerCandidates()) {
            int ci = addr.lastIndexOf(':');
            try {
                StunClient.Result f = StunClient.detectNatType(socket, addr.substring(0, ci),
                        Integer.parseInt(addr.substring(ci + 1)), 3000);
                if (f != null && f.mappedAddress() != null) return f;
            } catch (Exception ignored) {
                // 候选探测异常，继续尝试下一个
            }
        }
        return r;
    }

    private void startUdp() throws Exception {
        udpSocket = bindPort > 0 ? new DatagramSocket(bindPort) : new DatagramSocket();

        // 首次 STUN 探测：建立 NAT 映射并识别 NAT 类型
        StunClient.Result r = detectNatWithFallback(udpSocket);
        updateMapped(r == null ? null : r.mappedAddress(), r == null ? "Unknown" : r.natType());
        Logs.info(Logs.STUN, "任务[" + name + "] NAT类型: " + (r == null ? "Unknown" : r.natType())
                + "，映射地址: " + (r == null ? "无" : r.mappedAddress()));

        // 配合 UPnP：在路由器上显式开放入站端口，对称/受限 NAT 下外网也可主动连入
        if (upnpEnabled) applyUpnpMapping(udpSocket.getLocalPort(), false);
        else Logs.info(Logs.STUN, "任务[" + name + "] UPnP端口映射已按任务配置关闭，仅依赖STUN打洞");

        startUdpReceiver(true);
        startKeepalive();
    }

    /**
     * UDP 接收线程：STUN 响应仅用于刷新映射地址；
     * relayData=true 时其余数据包按会话转发到内网目标（UDP 模式）。
     */
    private void startUdpReceiver(boolean relayData) throws Exception {
        Thread receiver = new Thread(() -> {
            byte[] buf = new byte[65535];
            while (running && !udpSocket.isClosed()) {
                try {
                    udpSocket.setSoTimeout(2000);
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(pkt);
                    if (isStunResponseSource(pkt.getAddress(), pkt.getPort())) {
                        // 保活响应（配置的或兜底 STUN 服务器）：刷新外网映射地址（不再与保活线程竞争 receive）
                        String mapped = StunClient.parseBindingMapped(pkt.getData(), pkt.getLength());
                        if (mapped != null) updateUdpMapped(mapped, null);
                        continue;
                    }
                    if (relayData) {
                        // 真实对端数据到达（非STUN响应）：通道实际在用的证据，巡检据此避免误重穿丢端口
                        lastPeerInboundAt = System.currentTimeMillis();
                        forwardToTarget(pkt);
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // 超时继续循环
                } catch (Exception e) {
                    if (running) Logs.warn(Logs.STUN, "任务[" + name + "] UDP接收异常: " + e.getMessage());
                }
            }
        }, "stun-udp-" + id);
        receiver.setDaemon(true);
        receiver.start();
    }

    /**
     * 周期保活：周期性刷新 NAT 映射防止超时回收。
     * UDP 模式向配置的与兜底的 STUN 服务器发绑定请求（响应由接收线程解析刷新映射地址）；
     * TCP 模式周期检测保活链路存活：连接存活即运营商 CGNAT 映射存活（交互周期 ≤10 秒，
     * 及时暴露死亡链路）；检测到死亡即轮换新建出站连接刷新映射（连接上不做应用层交互，
     * 域名解析按其设计走 UDP 53）。
     */
    private void startKeepalive() {
        boolean tcpMode = !"UDP".equalsIgnoreCase(protocol);
        int tick = tcpMode ? Math.min(keepaliveSec, 10) : keepaliveSec;
        keepaliveTask = Tasks.every(tick, tick, () -> {
            // UDP 绑定保活两模式都需要：UDP 模式维持映射；TCP 模式维持同端口 STUN 映射（展示与锥形回连）
            repunchUdpMapping();
            // UPnP 公网直通时无需刷新 STUN/TCP 映射（刷新需短暂关监听，会造成入站中断与自测误报）
            if (tcpMode && !upnpPublicWan) refreshTcpLink();
        });
    }

    /**
     * 刷新 UDP STUN 映射（保活/重新穿透共用）：向配置的服务器与维护列表中兜底服务器
     * 发送绑定请求（fire-and-forget），响应由接收线程解析后刷新映射地址。
     * 配置服务器无响应时兜底服务器可恢复映射，避免单点失效导致穿透静默失效。
     */
    private void repunchUdpMapping() {
        if (udpSocket == null || udpSocket.isClosed()) return;
        for (String addr : stunServerCandidates()) {
            int ci = addr.lastIndexOf(':');
            try {
                StunClient.sendBindingRequest(udpSocket, addr.substring(0, ci), Integer.parseInt(addr.substring(ci + 1)));
            } catch (Exception e) {
                // 失效服务器（如域名已注销）每次保活都会解析失败，仅首次告警避免刷屏
                if (warnedKeepalive.add(addr)) {
                    Logs.warn(Logs.STUN, "任务[" + name + "] STUN保活失败(" + addr + "): " + e.getMessage());
                }
            }
        }
    }

    /** STUN 服务器候选地址（host:port）：配置的服务器 + 维护列表中全部服务器（按维护排序兜底），结果缓存 60s */
    private Set<String> stunServerCandidates() {
        long now = System.currentTimeMillis();
        if (cachedStunCandidates.isEmpty() || now - cachedCandidatesAt > 60_000) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            set.add(stunHost + ":" + stunPort);
            for (String[] s : StunServerService.allServers()) set.add(s[0] + ":" + s[1]);
            cachedStunCandidates = Set.copyOf(set);
            cachedCandidatesAt = now;
        }
        return cachedStunCandidates;
    }

    /** 入站包是否来自任一候选 STUN 服务器（保活响应判定） */
    private boolean isStunResponseSource(InetAddress addr, int port) {
        for (String candidate : stunServerCandidates()) {
            int ci = candidate.lastIndexOf(':');
            if (port != Integer.parseInt(candidate.substring(ci + 1))) continue;
            try {
                if (addr.equals(InetAddress.getByName(candidate.substring(0, ci)))) return true;
            } catch (Exception ignored) {
                // 域名解析失败：视为不匹配，继续比较其余候选
            }
        }
        return false;
    }

    /** UDP 业务数据转发：对端 -> 目标服务；目标响应经会话 socket 回流 */
    private void forwardToTarget(DatagramPacket pkt) throws Exception {
        String peerKey = pkt.getAddress().getHostAddress() + ":" + pkt.getPort();
        InetSocketAddress peer = new InetSocketAddress(pkt.getAddress(), pkt.getPort());
        DatagramSocket session = sessions.computeIfAbsent(peerKey, k -> {
            try {
                DatagramSocket s = new DatagramSocket();
                s.connect(InetAddress.getByName(targetIp), targetPort); // 连接模式：只收发目标服务的包
                Logs.info(Logs.STUN, "任务[" + name + "] 新会话: " + peerKey + " -> " + targetIp + ":" + targetPort);
                Thread back = new Thread(() -> relayBack(s, peer, k), "stun-sess-" + id);
                back.setDaemon(true);
                back.start();
                return s;
            } catch (Exception e) {
                Logs.error(Logs.STUN, "任务[" + name + "] 创建会话失败: " + e.getMessage());
                return null;
            }
        });
        if (session != null) {
            session.send(new DatagramPacket(pkt.getData(), pkt.getLength()));
        }
    }

    /** 目标服务响应回传对端，会话空闲 120 秒自动关闭 */
    private void relayBack(DatagramSocket session, InetSocketAddress peer, String peerKey) {
        byte[] buf = new byte[65535];
        try {
            session.setSoTimeout(120_000);
            while (running && !session.isClosed()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                session.receive(p);
                if (udpSocket != null && !udpSocket.isClosed()) {
                    udpSocket.send(new DatagramPacket(p.getData(), p.getLength(), peer));
                }
            }
        } catch (Exception ignored) {
            // 超时或任务停止，正常结束会话
        } finally {
            sessions.remove(peerKey);
            session.close();
        }
    }

    private void startTcp() throws Exception {
        // 先判断 UPnP 能否提供公网直达通道：路由器 WAN 口为公网时，UPnP 端口映射即权威入站路径，
        // 跳过出站探测（出站映射不受控，是展示端口漂移与周期关监听抖动的根源）
        boolean skipProbe = false;
        if (upnpEnabled) {
            try {
                if (upnpGateway == null) upnpGateway = UpnpClient.discover(3000);
                String wanIp = upnpGateway == null ? null : UpnpClient.externalIp(upnpGateway);
                skipProbe = wanIp != null && isPublicIp(wanIp);
                if (skipProbe) {
                    Logs.info(Logs.STUN, "任务[" + name + "] 路由器WAN口 " + wanIp
                            + " 为公网，以UPnP端口映射为权威入站通道，跳过STUN/TCP出站探测");
                }
            } catch (Exception e) {
                Logs.warn(Logs.STUN, "任务[" + name + "] UPnP网关预检异常: " + e.getMessage());
            }
        }
        // 先固定本地端口（bind_port=0 时取随机端口）：出站链路与监听共用同一本地端口，
        // 固定本地端口使外网映射端口在链路重建间保持稳定
        int listenPort = bindPort;
        if (listenPort <= 0) {
            try (ServerSocket tmp = new ServerSocket(0)) {
                listenPort = tmp.getLocalPort();
            }
        }
        // 先出站探测，再监听：① 出站连接在沿途全部 NAT（含运营商 CGNAT）上建立真实 TCP 映射，
        // 返回地址即权威外网地址；② Linux 下监听存在后无法再绑定同端口出站（LISTEN 占用），
        // 探测与备用 socket 都必须先行（链路日后重连优先用备用 socket，关监听弹跳仅为兜底）
        if (!skipProbe) {
            TcpPunch.Link link = punch.establish(listenPort, true);
            if (link != null) {
                // 预绑定备用出站 socket：链路断开时无需关监听即可重连（零入站中断）；
                // 端点空闲超时死亡轮换时每次消耗一个，大池量拉长弹跳间隔
                punch.openSpares(listenPort, TCP_SPARES);
            } else {
                Logs.warn(Logs.STUN, "任务[" + name + "] TCP出站链路建立失败(STUN与公共出站端点均不可达)，"
                        + "TCP映射未建立，巡检将按退避重试；UDP映射照常保活展示");
            }
        }

        // 入站方向：同一本地端口监听 TCP，外网连接进来后转发到目标内网服务（HTTP 等 TCP 流量透明传输）
        tcpServer = openTcpListener(listenPort);
        int localPort = tcpServer.getLocalPort();

        // 同端口维护 UDP STUN 映射：展示外网地址并保活，锥形 NAT 下对端可经该映射打洞回连
        udpSocket = new DatagramSocket(localPort);
        StunClient.Result r = detectNatWithFallback(udpSocket);
        updateUdpMapped(r == null ? null : r.mappedAddress(), r == null ? "Unknown" : r.natType());
        Logs.info(Logs.STUN, "任务[" + name + "] NAT类型: " + (r == null ? "Unknown" : r.natType())
                + "，映射地址: " + (r == null ? "无" : r.mappedAddress()));
        startUdpReceiver(false);

        // 配合 UPnP：在路由器上显式开放入站端口（外网端口=本地端口，入站包经 CGNAT 翻译后以此端口到达路由器），外网也可主动连入
        if (upnpEnabled) applyUpnpMapping(localPort, false);
        startKeepalive();
        startAcceptor();

        // 出站方向：配置了对端公网地址时，周期性主动向对端打洞（TCP 需双方向建立 NAT 过滤条目）
        if (!peerAddr.isBlank()) {
            punchTask = Tasks.every(3, keepaliveSec, () -> punchToPeer(parsePeerAddr(peerAddr)));
        }
    }

    /**
     * TCP 保活链路就绪回调（{@link TcpPunch} 建立/重建链路或映射地址变化时调用）：
     * STUN 精确模式直接登记映射地址；端口保留模式优先按同端口 UDP STUN 映射组装展示地址（尽力而为），
     * UDP 映射暂无时退回「出口 IP + 本地端口」占位（待 UDP STUN 响应后由 updateUdpMapped 修正）。
     */
    private void onTcpLinkReady(TcpPunch.Link link) {
        wanTcpReady = true;
        tcpMappedViaUpnp = false;
        if (link.viaStun()) {
            updateTcpMapped(link.mapped());
            Logs.info(Logs.STUN, "任务[" + name + "] TCP映射已建立(STUN/TCP服务器 " + link.endpoint()
                    + "，精确映射): " + link.mapped());
            return;
        }
        String presumed = presumedAddr();
        if (presumed != null) {
            updateTcpMapped(presumed);
            Logs.info(Logs.STUN, "任务[" + name + "] TCP出站链路已建立(出站端点 " + link.endpoint()
                    + "，端口保留模式): " + presumed + "；展示端口取自同端口UDP STUN探测"
                    + "(仅尽力而为估计，外网入站不保证可达，请用外部设备验证)");
        } else {
            Logs.info(Logs.STUN, "任务[" + name + "] TCP出站链路已建立(出站端点 " + link.endpoint()
                    + "，端口保留模式)：暂无UDP STUN映射，待响应后组装展示地址");
        }
    }

    /**
     * 端口保留模式展示地址：优先「UDP STUN 出口 IP + UDP 映射端口」，UDP 映射暂无时退回「出口 IP + 本地端口」。
     * 注意：实测同本地端口的 TCP/UDP 出站外部端口并不相同，该地址仅为无 TCP 探测能力时的尽力估计，入站不保证可达。
     */
    private String presumedAddr() {
        String udp = udpMappedAddr;
        if (udp != null) {
            int ci = udp.lastIndexOf(':');
            if (ci > 0) return udp; // UDP 映射完整可用：ip:port 直接作为展示地址
        }
        String exitIp = currentMappedIp();
        int localPort = tcpServer != null ? tcpServer.getLocalPort()
                : (udpSocket != null ? udpSocket.getLocalPort() : 0);
        return exitIp != null && localPort > 0 ? exitIp + ":" + localPort : null;
    }

    /** 在指定端口开启 TCP 监听（重试以容忍刚关闭连接的端口释放延迟） */
    private ServerSocket openTcpListener(int port) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                return ss;
            } catch (Exception e) {
                last = e;
                Thread.sleep(800);
            }
        }
        throw last;
    }

    /** TCP 入站接收线程：外网连接进来后逐条并入转发管道（监听重建时需重新启动） */
    private void startAcceptor() {
        Thread acceptor = new Thread(() -> {
            while (running && tcpServer != null && !tcpServer.isClosed()) {
                try {
                    Socket client = tcpServer.accept();
                    Thread pipe = new Thread(() -> pipeToTarget(client), "stun-tcp-" + id);
                    pipe.setDaemon(true);
                    pipe.start();
                } catch (Exception e) {
                    if (running && tcpServer != null && !tcpServer.isClosed()) {
                        Logs.warn(Logs.STUN, "任务[" + name + "] TCP接收异常: " + e.getMessage());
                    }
                }
            }
        }, "stun-accept-" + id);
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * TCP 映射保活与链路维护：优先在存活长连接上交互（STUN 绑定交互/TCP 存活检测，
     * 监听不中断、映射端口不漂移）；链路死亡时优先消耗预绑定备用 socket 重连（零监听中断），
     * 备用耗尽才「弹跳」重建（关监听→出站→重开监听，窗口约 1-3 秒，入站 SYN 由客户端
     * TCP 重传自然恢复）。STUN/TCP 候选整体失败后退避 300 秒（期间只用公共出站端点）。
     */
    private synchronized void refreshTcpLink() {
        if (!running || tcpServer == null || upnpPublicWan) return;
        if (punch.keepaliveOnce()) return; // 长连接交互保活成功：监听不中断、映射端口不漂移
        // 链路死亡（服务器断开/网络切换）：优先消耗预绑定备用 socket 重连（零监听中断）
        int port = tcpServer.getLocalPort();
        punch.noteLinkDownIfNeeded();
        punch.closeLink();
        if (punch.reconnect(port, punch.allowStunNow()) != null) return;
        // 备用耗尽：弹跳重建（短暂关闭监听）
        tcpRefreshing = true;
        boolean bounced = false;
        try {
            try {
                tcpServer.close();
                bounced = true;
            } catch (Exception e) {
                Logs.warn(Logs.STUN, "任务[" + name + "] 关闭TCP监听失败，跳过本次链路重建: " + e.getMessage());
            }
            if (bounced) {
                try {
                    Thread.sleep(300); // 等待端口释放
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (punch.establish(port, punch.allowStunNow()) != null) {
                    punch.openSpares(port, TCP_SPARES); // 监听重开前补足备用 socket
                } else {
                    wanTcpReady = false;
                    Logs.warn(Logs.STUN, "任务[" + name + "] TCP出站链路重建失败(STUN与公共出站端点均不可达)，"
                            + "TCP映射暂缺，巡检将退避重试；UDP映射照常保活");
                }
            }
        } finally {
            tcpRefreshing = false;
            if (running && bounced) {
                try {
                    tcpServer = openTcpListener(port);
                    startAcceptor();
                } catch (Exception e) {
                    Logs.error(Logs.STUN, "任务[" + name + "] 重建TCP监听失败: " + e.getMessage());
                }
            }
        }
    }

    /** 解析对端地址 ip:port */
    private static InetSocketAddress parsePeerAddr(String addr) {
        int ci = addr.lastIndexOf(':');
        return new InetSocketAddress(addr.substring(0, ci), Integer.parseInt(addr.substring(ci + 1)));
    }

    /**
     * TCP 打洞：从与监听相同的本地端口主动向对端发起连接，在双端 NAT 上建立过滤条目。
     * 连接建立后并入转发管道，双向承载 TCP/HTTP 数据（无论连接由哪一端发起）。
     */
    private void punchToPeer(InetSocketAddress peer) {
        if (!running) return;
        Socket punch = new Socket();
        try {
            punch.setReuseAddress(true);
            try {
                // 与监听同端口绑定：锥形 NAT 下对端可直接回连该映射端口（同时打开也可建立连接）
                punch.bind(new InetSocketAddress(tcpServer.getLocalPort()));
            } catch (Exception ignored) {
                // 同端口绑定受限时退回随机端口，主动连接方向仍可建立数据通道
            }
            punch.connect(peer, 3000);
            Logs.info(Logs.STUN, "任务[" + name + "] TCP打洞成功，与对端 " + peer + " 建立转发通道");
            Thread pipe = new Thread(() -> pipeToTarget(punch), "stun-punch-" + id);
            pipe.setDaemon(true);
            pipe.start();
        } catch (Exception e) {
            try {
                punch.close();
            } catch (Exception ignored) {
            }
            Logs.warn(Logs.STUN, "任务[" + name + "] TCP打洞未成功(" + peer + "): " + e.getMessage());
        }
    }

    /** TCP 双向管道：外网连接 <-> 目标内网服务（字节级透明，HTTP 等应用层协议直接可用） */
    private void pipeToTarget(Socket client) {
        try (client; Socket target = new Socket(targetIp, targetPort)) {
            Logs.info(Logs.STUN, "任务[" + name + "] 外网连接: " + client.getRemoteSocketAddress());
            // 对端不是本机公网回环（自测连接源）时记录真实外网访问时刻：
            // 作为通道实际在用的证据，自测未通过时据此避免误重穿把可用映射丢弃
            String mappedIp = currentMappedIp();
            if (mappedIp == null || !mappedIp.equals(client.getInetAddress().getHostAddress())) {
                lastPeerInboundAt = System.currentTimeMillis();
            }
            Thread t2c = new Thread(() -> copy(target, client), "stun-pipe");
            t2c.setDaemon(true);
            t2c.start();
            copy(client, target);
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 转发失败: " + e.getMessage());
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            in.transferTo(out);
        } catch (Exception ignored) {
            // 任一端断开即结束管道
        } finally {
            try {
                to.shutdownOutput();
            } catch (Exception ignored) {
            }
        }
    }

    /** 停止任务并释放资源 */
    void stop() {
        running = false;
        punched = false;
        upnpWanAddr = null;
        releaseUpnpMapping();
        if (keepaliveTask != null) keepaliveTask.cancel(false);
        if (punchTask != null) punchTask.cancel(false);
        if (checkTask != null) checkTask.cancel(false);
        if (udpSocket != null) udpSocket.close();
        if (punch != null) punch.closeAll();
        if (tcpServer != null) {
            try {
                tcpServer.close();
            } catch (Exception ignored) {
            }
        }
        sessions.values().forEach(DatagramSocket::close);
        sessions.clear();
        updateStatus("STOPPED");
        // 停止后清除外网映射地址、穿透成功时间与可用性自测结果，下次启动重新记录
        try {
            Database.update("UPDATE stun_task SET punched_at=NULL, check_time=NULL, check_result=NULL,"
                    + " mapped_addr=NULL, nat_type=NULL WHERE id=?", id);
        } catch (Exception e) {
            Logs.error(Logs.STUN, "清除自测状态失败: " + e.getMessage());
        }
        Logs.info(Logs.STUN, "穿透任务[" + name + "] 已停止");
    }

    /**
     * 关停静默停止：释放资源但不更新任务启停状态（进程即将退出，无需再写库）。
     */
    void stopSilently() {
        running = false;
        punched = false;
        upnpWanAddr = null;
        releaseUpnpMapping();
        if (keepaliveTask != null) keepaliveTask.cancel(false);
        if (punchTask != null) punchTask.cancel(false);
        if (checkTask != null) checkTask.cancel(false);
        if (udpSocket != null) udpSocket.close();
        if (punch != null) punch.closeAll();
        if (tcpServer != null) {
            try {
                tcpServer.close();
            } catch (Exception ignored) {
            }
        }
        sessions.values().forEach(DatagramSocket::close);
        sessions.clear();
    }

    /** 释放路由器上的 UPnP 端口映射（幂等，重复调用安全） */
    private synchronized void releaseUpnpMapping() {
        if (upnpMapped && upnpGateway != null) {
            UpnpClient.deletePortMapping(upnpGateway, protocol, upnpPort);
            upnpMapped = false;
            Logs.info(Logs.STUN, "任务[" + name + "] UPnP端口映射已移除");
        }
    }

    boolean isRunning() {
        return running;
    }

    private void updateStatus(String status) {
        try {
            Database.update("UPDATE stun_task SET status=? WHERE id=?", status, id);
        } catch (Exception e) {
            Logs.error(Logs.STUN, "更新任务状态失败: " + e.getMessage());
        }
    }

    /** natType 为 null 时只更新映射地址 */
    private void updateMapped(String mapped, String natType) {
        try {
            if (mapped != null && !mapped.isBlank()) {
                lastMappedAt = System.currentTimeMillis(); // 保活/穿透有效：刷新映射时间，供巡检判断保活是否失效
                if (!punched) {
                    punched = true;
                    punchedMapped = mapped;
                    Database.update("UPDATE stun_task SET punched_at=? WHERE id=?", Database.now(), id);
                    Logs.info(Logs.STUN, "任务[" + name + "] 穿透成功，外网映射地址: " + mapped);
                } else if (!mapped.equals(punchedMapped)) {
                    // 映射地址变化（重穿/保活刷新后外网端口被 NAT 改写）：穿透时间同步更新为最新穿透时刻
                    punchedMapped = mapped;
                    Database.update("UPDATE stun_task SET punched_at=? WHERE id=?", Database.now(), id);
                    Logs.info(Logs.STUN, "任务[" + name + "] 外网映射地址变更为 " + mapped + "，穿透时间已同步更新");
                }
            }
            if (natType == null) {
                Database.update("UPDATE stun_task SET mapped_addr=? WHERE id=?", mapped, id);
            } else {
                Database.update("UPDATE stun_task SET mapped_addr=?, nat_type=? WHERE id=?", mapped, natType, id);
            }
        } catch (Exception e) {
            Logs.error(Logs.STUN, "更新映射状态失败: " + e.getMessage());
        }
    }

    /** 更新 TCP 方向外网映射（STUN/TCP 精确探测或 UPnP 公网直通），TCP 自测以此为准 */
    private void updateTcpMapped(String mapped) {
        wanTcpReady = true;
        updateMapped(mapped, null);
    }

    /**
     * UDP STUN 映射刷新（启动探测/保活响应）。
     * TCP 模式下权威外网地址以 TCP 出站映射为准（STUN/TCP 精确映射或端口保留地址），UDP 映射端口
     * 与其通常不同（实测同本地端口 TCP/UDP 外部端口并不相同）：若用 UDP 映射覆盖展示地址，穿透端口会在两个值
     * 之间来回跳变。但端口保留模式例外：无 TCP 探测能力时只能以同端口 UDP 映射尽力估计展示地址，
     * 每次刷新持续修正（链路重建窗口内保留最后已知地址）。
     */
    private void updateUdpMapped(String mapped, String natType) {
        if (mapped != null && !mapped.isBlank()) udpMappedAddr = mapped;
        if (!"UDP".equalsIgnoreCase(protocol) && (wanTcpReady || punched)) {
            if (mapped != null && !mapped.isBlank()) {
                lastMappedAt = System.currentTimeMillis(); // 保活有效：供巡检判断保活是否失效，但不改动权威地址
                if (punch != null && punch.addrPresumed()) {
                    // 端口保留模式：展示地址跟随同端口 UDP STUN 映射（含启动初期的本地端口占位修正）
                    String presumed = presumedAddr();
                    if (presumed != null) updateTcpMapped(presumed);
                }
            }
            if (natType != null) {
                try {
                    Database.update("UPDATE stun_task SET nat_type=? WHERE id=?", natType, id);
                } catch (Exception e) {
                    Logs.error(Logs.STUN, "更新NAT类型失败: " + e.getMessage());
                }
            }
            return;
        }
        updateMapped(mapped, natType);
    }

    /** 本机局域网 IP：UDP connect 仅选出出口网卡（不真实发包），避免通配绑定 socket 返回 0.0.0.0 */
    private String lanIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            try {
                s.connect(InetAddress.getByName(stunHost), stunPort);
            } catch (Exception e) {
                // 配置的 STUN 域名失效时退回公共 DNS 地址选出口网卡（UDP connect 仅本机路由决策，不真实发包）
                s.connect(InetAddress.getByName("223.5.5.5"), 53);
            }
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前 DB 中 STUN 映射地址的 IP 部分（即 STUN 看到的出口公网 IP） */
    private String currentMappedIp() {
        try {
            Map<String, Object> row = Database.queryOne("SELECT mapped_addr FROM stun_task WHERE id=?", id);
            String m = row == null ? null : str(row, "mapped_addr");
            if (m != null) {
                int ci = m.lastIndexOf(':');
                if (ci > 0) return m.substring(0, ci);
            }
        } catch (Exception ignored) {
            // 查询失败时返回 null，调用方回退展示 UPnP WAN IP
        }
        return null;
    }

    /** 是否公网 IP：排除环回/私网/链路本地/组播及运营商 CGNAT(100.64.0.0/10) */
    private static boolean isPublicIp(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress()
                    || a.isLinkLocalAddress() || a.isMulticastAddress()) return false;
            byte[] b = a.getAddress();
            if (b.length == 4) {
                int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
                if (b0 == 100 && b1 >= 64 && b1 <= 127) return false; // RFC6598 共享地址空间
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 可用性自测 ----------

    /** 手动自测入口（REST 接口调用，同步执行并返回结果；必做公网入站验证，耗时数秒） */
    Map<String, Object> verifyNow() throws Exception {
        return verifyChannel(true);
    }

    /**
     * 可用性自测：验证「映射保活存活」而非回环连通，结果写入 check_time / check_result。
     * <ul>
     *   <li>UDP 任务：向候选 STUN 服务器发绑定请求，接收线程在超时内收到响应（映射刷新）即存活</li>
     *   <li>TCP 任务：保活链路交互有响应且本地监听正常即存活，无响应时立即重建链路再复验</li>
     * </ul>
     * 运营商 CGNAT 普遍不支持 NAT 回流(hairpin)：从内网回环连接公网映射地址必然超时，
     * 不代表穿透失败，因此自测不依赖回环连通性；保活存活后另由第三方探测节点真实连接映射地址
     * 验证公网入站可达性（周期自测限冷却，手动自测必验），第三方服务不可用时静默跳过。
     */
    private Map<String, Object> verifyChannel() throws Exception {
        return verifyChannel(false);
    }

    private Map<String, Object> verifyChannel(boolean manual) throws Exception {
        // TCP 弹跳重建会短暂关闭监听（关监听→全候选出站探测→重开，候选全部超时时可达 20s+），
        // 等待窗口须覆盖弹跳全程再自测，否则撞上关闭窗口会误报「本地TCP监听已关闭」
        if (tcpRefreshing) {
            long deadline = System.currentTimeMillis() + 30_000;
            while (tcpRefreshing && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
        }
        Map<String, Object> row = Database.queryOne("SELECT mapped_addr FROM stun_task WHERE id=?", id);
        String mapped = row == null ? null : str(row, "mapped_addr");
        String result;
        if (mapped == null || mapped.isBlank()) {
            result = "FAIL(无外网映射地址)";
        } else if ("UDP".equalsIgnoreCase(protocol)) {
            result = verifyUdpKeepalive();
        } else {
            result = verifyTcpKeepalive();
        }
        // 公网入站真实验证：补上「保活存活 ≠ 外网可主动连入」盲区（本机无 NAT 回流无法自验）。
        // 仅保活存活时验证；周期自测限冷却避免频繁调用第三方服务，手动自测必验
        if (result.startsWith("OK") && !"UDP".equalsIgnoreCase(protocol)
                && (manual || System.currentTimeMillis() - lastExtCheckAt > 300_000)) {
            lastExtCheckAt = System.currentTimeMillis();
            String ext = checkExternalInbound(mapped);
            if (ext != null) {
                boolean reachable = "OK".equals(ext);
                if (lastExtReachable == null || lastExtReachable != reachable) {
                    lastExtReachable = reachable;
                    if (reachable) {
                        Logs.info(Logs.STUN, "任务[" + name + "] 公网入站验证通过: 第三方节点真实连接 " + mapped + " 成功");
                    } else {
                        Logs.warn(Logs.STUN, "任务[" + name + "] 公网入站验证失败" + ext.substring(4)
                                + "：保活存活但外网无法连入，请检查上层NAT/运营商策略");
                    }
                }
                result += "，公网入站验证" + (reachable ? "可达" : "不可达" + ext.substring(4));
            }
        }
        String time = Database.now();
        Database.update("UPDATE stun_task SET check_time=?, check_result=? WHERE id=?", time, result, id);
        Logs.info(Logs.STUN, "任务[" + name + "] 可用性自测: " + result + "，映射地址: " + mapped);
        return Map.of("time", time, "result", result);
    }

    /** UDP 自测：向全部候选 STUN 服务器发绑定请求，接收线程在超时前收到响应（lastMappedAt 前进）即保活存活 */
    private String verifyUdpKeepalive() {
        long before = lastMappedAt;
        long t0 = System.currentTimeMillis();
        repunchUdpMapping();
        while (lastMappedAt <= before && System.currentTimeMillis() - t0 < 5000) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (lastMappedAt > before) {
            return "OK(映射保活存活，" + (System.currentTimeMillis() - t0)
                    + "ms；真实入站可达性受NAT类型与路由器策略影响，请用外部设备验证)";
        }
        return "FAIL(STUN绑定无响应：映射可能已失效或候选STUN服务器均不可达，保活调度将持续重试)";
    }

    /** TCP 自测：保活链路交互有响应 + 本地监听正常即映射存活；无响应时立即重建链路再复验 */
    private String verifyTcpKeepalive() {
        if (!wanTcpReady) {
            return "FAIL(TCP映射未建立：STUN-over-TCP服务器不可用且端口保留出站链路未建成，巡检自动重试)";
        }
        if (tcpServer == null || tcpServer.isClosed()) {
            return "FAIL(本地TCP监听已关闭)";
        }
        long t0 = System.currentTimeMillis();
        if (punch.keepaliveOnce()) {
            return okTcp(System.currentTimeMillis() - t0, false);
        }
        // 链路交互无响应（服务器断开/网络切换）：立即重建（多数经预绑定备用socket零监听中断完成）
        refreshTcpLink();
        if (wanTcpReady && punch.keepaliveOnce()) {
            return okTcp(System.currentTimeMillis() - t0, true);
        }
        return "FAIL(TCP保活链路无响应且重建失败：保活调度与巡检将按退避持续重试)";
    }

    /** TCP 自测通过时的结果文案（区分映射来源，便于用户判断验证方式） */
    private String okTcp(long costMs, boolean rebuilt) {
        String mode = tcpMappedViaUpnp ? "UPnP公网直通"
                : (punch != null && punch.addrPresumed() ? "端口保留模式(展示端口取自同端口UDP STUN估计，入站不保证可达，请外部设备验证)"
                : "STUN精确映射");
        return "OK(TCP映射保活存活" + (rebuilt ? "，链路已重建" : "") + "[" + mode + "]，" + costMs
                + "ms；运营商CGNAT普遍无NAT回流，端到端可达以公网入站验证/外部设备为准)";
    }

    /**
     * 公网入站可达性验证：第三方探测节点（check-host.net 免费 API）真实 TCP 连接映射地址，
     * 验证「外网能否主动连入」（本机因 NAT 回流缺失无法自验）。提交后轮询结果（实测约 3 秒完成）：
     * 响应为顶层节点结果表，节点值 null=仍在探测；样本对象含 address 即连接成功，含 error 即失败。
     * 任一节点成功即视为可达；全部报错视为不可达（携带首个错误原因）；持续未完成/服务异常返回 null（本轮跳过）。
     * 第三方服务不可用不影响主流程，自测结论退回保活存活语义。
     */
    private String checkExternalInbound(String mapped) {
        try {
            HttpResponse<String> submit = EXT_CHECK_HTTP.send(HttpRequest.newBuilder()
                    .uri(URI.create("https://check-host.net/check-tcp?host=" + mapped))
                    .header("Accept", "application/json").header("User-Agent", "NexHome-STUN")
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (submit.statusCode() != 200) return null;
            JsonObject root = JsonParser.parseString(submit.body()).getAsJsonObject();
            if (!root.has("request_id")) return null;
            String reqId = root.get("request_id").getAsString();
            for (int round = 0; round < 3; round++) {
                try {
                    Thread.sleep(3000); // 实测探测约 3 秒出结果，最多轮询 3 次覆盖慢节点/排队
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                HttpResponse<String> poll = EXT_CHECK_HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create("https://check-host.net/check-result/" + reqId))
                        .header("Accept", "application/json").timeout(Duration.ofSeconds(8)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (poll.statusCode() != 200) return null;
                String verdict = parseCheckResult(JsonParser.parseString(poll.body()).getAsJsonObject(), reqId);
                if (verdict != null) return verdict;
            }
            return null; // 持续未完成：本轮跳过，下轮自测再验
        } catch (Exception e) {
            return null; // 第三方服务不可用/解析异常：静默跳过，保持原保活自测结论
        }
    }

    /**
     * 解析 check-host 轮询响应：结果可能包裹在 request_id 键下，也可能直接平铺在顶层（实测后者）。
     * 全部节点仍为 null（探测中）返回 null 继续轮询；否则按样本汇总：含 address 即连接成功，含 error 即失败。
     */
    private static String parseCheckResult(JsonObject rroot, String reqId) {
        JsonObject nodes = null;
        JsonElement wrapped = rroot.get(reqId);
        if (wrapped != null && wrapped.isJsonObject()) {
            nodes = wrapped.getAsJsonObject();
        } else if (rroot.size() > 0) {
            // 实测响应直接以节点 id 为顶层键（无 request_id 包裹）；仍为 pending 时响应为空对象/全 null 由下方汇总判定
            nodes = rroot;
        }
        if (nodes == null) return null;
        boolean anyOk = false, anyDone = false;
        String firstErr = null;
        for (Map.Entry<String, JsonElement> e : nodes.entrySet()) {
            JsonElement v = e.getValue();
            if (v == null || !v.isJsonArray()) continue; // null=该节点仍在探测
            for (JsonElement sample : v.getAsJsonArray()) {
                if (sample == null || !sample.isJsonObject()) continue;
                JsonObject obj = sample.getAsJsonObject();
                if (obj.has("address")) {
                    anyOk = true; // 含 address：探测节点真实连接成功（time 为连接耗时）
                } else if (obj.has("error") && firstErr == null) {
                    firstErr = obj.get("error").getAsString();
                }
                anyDone = true;
            }
        }
        if (anyOk) return "OK";
        return anyDone ? "FAIL(" + firstErr + ")" : null;
    }

    // ---------- UPnP 配合与重新穿透 ----------

    /**
     * UPnP 端口映射：SSDP 发现路由器并添加 WAN-&gt;LAN 映射（外网端口=本地端口），
     * 映射成功后以路由器 WAN IP + 本地端口作为权威外网地址展示。
     * quiet=true 为巡检周期静默重挂（路由器重启后映射会丢失）：成功不打日志避免刷屏，失败仍告警。
     */
    private void applyUpnpMapping(int localPort, boolean quiet) {
        try {
            if (upnpGateway == null) {
                upnpGateway = UpnpClient.discover(3000);
            }
            if (upnpGateway == null) {
                Logs.warn(Logs.STUN, "任务[" + name + "] 未发现UPnP网关(需路由器开启UPnP)，仅依赖STUN打洞");
                return;
            }
            String lanIp = lanIp();
            if (lanIp == null) {
                Logs.warn(Logs.STUN, "任务[" + name + "] 无法确定本机局域网IP，跳过UPnP映射");
                return;
            }
            // 外网端口=本地端口：入站包经运营商 CGNAT 翻译后，以本地端口号到达路由器 WAN 口
            int extPort = localPort;
            boolean ok = UpnpClient.addPortMapping(upnpGateway, protocol, extPort, localPort, lanIp, "NexHome " + name);
            if (!ok) { // 端口可能被旧映射占用：先删除再重试
                UpnpClient.deletePortMapping(upnpGateway, protocol, extPort);
                ok = UpnpClient.addPortMapping(upnpGateway, protocol, extPort, localPort, lanIp, "NexHome " + name);
            }
            if (ok) {
                upnpMapped = true;
                upnpPort = extPort;
                String wanIp = UpnpClient.externalIp(upnpGateway);
                // 路由器 WAN 公网：UPnP 映射即权威入站通道，关闭 TCP 方向周期 STUN 出站刷新
                if (wanIp != null && isPublicIp(wanIp)) upnpPublicWan = true;
                upnpWanAddr = wanIp == null ? null : wanIp + ":" + extPort;
                String displayIp = wanIp;
                if (wanIp != null && !isPublicIp(wanIp)) {
                    // 路由器 WAN 口是私网/CGNAT 地址（如 100.64.x），展示改用 STUN 探测的出口公网 IP + UPnP 外网端口
                    String stunIp = currentMappedIp();
                    if (stunIp != null) displayIp = stunIp;
                    Logs.warn(Logs.STUN, "任务[" + name + "] 路由器WAN口地址 " + wanIp
                            + " 非公网(可能处于运营商CGNAT后)，展示改用STUN出口IP；外网能否主动连入取决于上层NAT");
                }
                if (!quiet) {
                    Logs.info(Logs.STUN, "任务[" + name + "] UPnP端口映射成功: "
                            + (wanIp == null ? "外网IP未知" : wanIp) + ":" + extPort + " -> " + lanIp + ":" + localPort);
                }
                // TCP 模式已建立权威 TCP 映射时不用 UPnP 地址覆盖（入站以 STUN/TCP 探测映射为准），
                // 否则每次启动/重穿都会把已验证可用的穿透端口换成 UPnP 展示地址；仅在无 TCP 映射时以 UPnP 地址兜底展示
                if (displayIp != null && !wanTcpReady) {
                    tcpMappedViaUpnp = true; // UPnP 映射可被外网主动访问，自测成功即代表外网可达（受路由器防火墙约束）
                    updateTcpMapped(displayIp + ":" + extPort);
                }
            } else {
                Logs.warn(Logs.STUN, "任务[" + name + "] UPnP端口映射失败，仅依赖STUN打洞");
            }
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "任务[" + name + "] UPnP映射异常: " + e.getMessage());
        }
    }

    // ---------- 周期有效性巡检 ----------

    /** 巡检周期：至少 60 秒，且不低于保活周期的 2 倍（保证两次保活后仍无响应才判定失效） */
    private long checkIntervalSec() {
        return Math.max(60, keepaliveSec * 2L);
    }

    /**
     * 穿透成功后的周期有效性巡检：NAT 映射可能因超时、网络切换或运营商策略静默失效且无任何提示，
     * 巡检持续监测并驱动自愈，防止长时间运行后穿透失效不可用。
     * <ul>
     *   <li>UPnP 重挂：路由器重启后 UPnP 映射会丢失，曾成功过的任务周期幂等重挂（静默，失败才告警）</li>
     *   <li>UDP 保活静默：连续超过 3 个保活周期未收到 STUN 绑定响应，立即重发绑定请求</li>
     *   <li>TCP 映射缺失：出站链路建立失败时周期重试，直到成功</li>
     *   <li>自测未通过（保活失效）：链路重建已在自测中触发，保活调度持续重试，不额外丢弃端口重建</li>
     * </ul>
     */
    private void startWatcher() {
        long sec = checkIntervalSec();
        checkTask = Tasks.every(sec, sec, this::watchChannel);
    }

    private void watchChannel() {
        if (!running || !punched) return;
        if (tcpRefreshing) return; // TCP 弹跳重建短暂关闭监听，本周期跳过避免误判
        try {
            // 路由器重启后 UPnP 映射会丢失：曾发现过网关的任务周期幂等重挂
            // （从未发现过网关的环境不反复 SSDP 探测，避免每周期无谓等待）
            if (upnpEnabled && upnpGateway != null && udpSocket != null && !udpSocket.isClosed()) {
                applyUpnpMapping(udpSocket.getLocalPort(), true);
            }
            long silentMs = System.currentTimeMillis() - lastMappedAt;
            if ("UDP".equalsIgnoreCase(protocol) && silentMs > keepaliveSec * 3_000L) {
                // 保活连续无响应：映射可能已静默失效（网络切换/STUN 服务器故障），立即重发绑定请求
                Logs.warn(Logs.STUN, "任务[" + name + "] STUN保活 " + (silentMs / 1000)
                        + "s 无响应，映射可能已失效，已重发绑定请求重建");
                repunchUdpMapping();
            }
            if (!"UDP".equalsIgnoreCase(protocol) && !wanTcpReady) {
                // TCP 映射尚未建立（出站链路全端点不可达），周期重试直到成功
                Logs.info(Logs.STUN, "任务[" + name + "] TCP映射未建立，巡检重试出站探测");
                refreshTcpLink();
                return;
            }
            String result = str(verifyChannel(), "result");
            if (result.startsWith("OK")) {
                if (watchFails > 0) Logs.info(Logs.STUN, "任务[" + name + "] 巡检复测通过，穿透通道已恢复");
                watchFails = 0;
                return;
            }
            // 自测未通过但近期仍有真实外网数据到达：通道实际在用（STUN 链路故障≠转发不可用），
            // 不丢弃现有端口重建，保活调度会继续尝试恢复
            long peerAgoMs = System.currentTimeMillis() - lastPeerInboundAt;
            if (peerAgoMs < keepaliveSec * 6_000L) {
                Logs.info(Logs.STUN, "任务[" + name + "] 巡检自测未通过但 " + (peerAgoMs / 1000)
                        + "s 前仍有真实外网访问，通道视为可用，保活调度继续恢复");
                watchFails = 0;
                return;
            }
            // 自测失败=保活链路失效：链路重建已在自测内部触发（零监听中断优先），
            // 保活调度每周期持续重试，无需额外丢弃端口重建
            watchFails++;
            Logs.warn(Logs.STUN, "任务[" + name + "] 巡检自测未通过(" + result + ")，已触发链路重建"
                    + "(连续第 " + watchFails + " 次)，保活调度将持续重试)");
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 周期巡检异常: " + e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }
}
