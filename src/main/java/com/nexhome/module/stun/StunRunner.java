package com.nexhome.module.stun;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * STUN 穿透任务运行器（每个任务一个实例），穿透通道承载 UDP / TCP / HTTP 三类数据传输。
 * <p>
 * <b>UDP 模式</b>：在绑定端口上打开 UDP Socket，持续向 STUN 服务器发送绑定请求
 * 建立并保活 NAT 映射；外网发入该映射端口的数据包按会话转发到目标内网服务，
 * 响应沿原路回传给对端（简易会话表实现）。
 * <p>
 * <b>TCP 模式</b>：在同一本地端口监听 TCP，接受外网入站连接并双向管道化转发到目标服务，
 * HTTP 等 TCP 流量透明传输；若配置了「对端公网地址」，额外周期性从同一本地端口主动向对端
 * 发起连接（TCP 打洞，在 NAT 上建立双向过滤条目），连接建立后同样并入转发管道。
 * 同时在同端口用 STUN 探测/保活外网映射地址用于展示。
 * <p>
 * <b>CGNAT 多级 NAT 场景</b>：外网主动连入要求沿途每一层 NAT（含运营商 CGNAT）都有对应
 * 端口的映射条目，路由器上的 UPnP 映射管不到运营商那一层。因此 TCP 模式会先从计划监听的端口向
 * 支持 STUN-over-TCP 的服务器出站（配置的服务器不支持时自动改用内置兜底服务器），在包括 CGNAT
 * 在内的全部 NAT 上建立真实 TCP 映射（CGNAT 通常改写外网端口）；之后再在该本地端口监听入站。
 * 出站探测建立的映射经实测可被外网设备主动连入（自测通过即代表互联网可达，无需更换端口重新穿透），保活时同样需要出站刷新映射：Windows 下监听中无法绑定同端口出站，
 * 故保活会短暂暂停监听（约 1-2 秒，TCP 客户端 SYN 重传可自然恢复）。
 * 同理，路由器 WAN 口为公网且 UPnP 映射成功时，UPnP 端口映射即权威入站通道：此时跳过 STUN/TCP
 * 出站探测与周期刷新（路由器上的临时映射不受控，是展示端口漂移、周期关监听与自测误报的根源），
 * 只有路由器 WAN 口非公网（上层还有 CGNAT）时才启用出站探测。
 * <p>
 * 局限性：对称型 NAT（Symmetric）下映射端口随机，纯 STUN 无法保证穿透成功，
 * 因此同时配合 UPnP：任务启动时 SSDP 发现路由器并经 SOAP 添加 WAN-&gt;LAN 端口映射
 * （需路由器开启 UPnP），对称/受限 NAT 下外网也能主动连入。
 * 自测失败时自动重新穿透（刷新 STUN 映射 + 重新 UPnP 映射）并复测，直到成功或任务停止。
 * 穿透成功后启动周期巡检：定时自测通道有效性并监测保活响应，发现映射失效
 * （自测失败/保活长时间无响应）时自动重新穿透并复测，防止长时间运行后映射静默失效。
 */
final class StunRunner {

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
    /** UDP 可用性探测：期望的探测包内容与接收线程命中标志 */
    private volatile String udpProbe;
    private volatile boolean udpProbeOk;
    /** UPnP 网关与映射状态：配合 STUN 在路由器上显式开放入站端口 */
    private UpnpClient.Gateway upnpGateway;
    private boolean upnpMapped;
    private int upnpPort;
    /** UPnP 映射的路由器 WAN 地址（ip:port），自测本地链路以此为准 */
    private volatile String upnpWanAddr;
    /** 是否已建立 TCP 方向外网映射（STUN/TCP 探测或 UPnP 映射），TCP 自测仅在其成立时有意义 */
    private volatile boolean wanTcpReady;
    /** STUN-over-TCP 探测成功的服务器（host:port），后续保活优先复用它 */
    private volatile String tcpStunServer;
    /** 自测失败后自动重新穿透的已重试次数 */
    private int repunchCount;
    /** 最近一次收到 STUN 绑定响应（映射刷新）的时间戳，用于判断保活是否失效 */
    private volatile long lastMappedAt;
    /** 周期巡检连续失败次数（巡检周期内自动重穿后复测仍失败的累计） */
    private volatile int watchFails;
    /** 路由器 WAN 口为公网且 UPnP 映射成功：UPnP 端口映射即权威入站通道，无需 STUN/TCP 出站探测与刷新 */
    private volatile boolean upnpPublicWan;
    /** TCP 外网映射来源：true=UPnP 端口映射（外网可主动连入），false=出站探测映射（实测外网可主动连入，自测通过即互联网可达） */
    private volatile boolean tcpMappedViaUpnp;
    /** TCP 映射刷新进行中（刷新需短暂关闭监听，自测与巡检应错开该窗口避免误报） */
    private volatile boolean tcpRefreshing;
    /** 保活失败已告警过的服务器（失效服务器仅告警一次，避免每个保活周期重复刷屏） */
    private final Set<String> warnedKeepalive = ConcurrentHashMap.newKeySet();

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
        repunchCount = 0;
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
        // 穿透成功后测试一次，确保通道可用（TCP 连接外网IP:穿透端口，UDP 发探测包）；失败则重新穿透并复测
        Tasks.delay(2, () -> verifyLoop());
        // 周期巡检：持续监测通道有效性，失效自动重新穿透，防止长时间运行后映射静默失效
        startWatcher();
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
        if (upnpEnabled) applyUpnpMapping(udpSocket.getLocalPort());
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
                    // 可用性自测探测包：命中即标记，不再转发
                    String probe = udpProbe;
                    if (probe != null && probe.equals(new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8))) {
                        udpProbeOk = true;
                        continue;
                    }
                    if (isStunResponseSource(pkt.getAddress(), pkt.getPort())) {
                        // 保活响应（配置的或兜底 STUN 服务器）：刷新外网映射地址（不再与保活线程竞争 receive）
                        String mapped = StunClient.parseBindingMapped(pkt.getData(), pkt.getLength());
                        if (mapped != null) updateUdpMapped(mapped, null);
                        continue;
                    }
                    if (relayData) forwardToTarget(pkt);
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
     * TCP 模式额外出站刷新运营商 CGNAT 上的 TCP 映射。
     */
    private void startKeepalive() {
        boolean tcpMode = !"UDP".equalsIgnoreCase(protocol);
        keepaliveTask = Tasks.every(keepaliveSec, keepaliveSec, () -> {
            // UDP 绑定保活两模式都需要：UDP 模式维持映射；TCP 模式维持同端口 STUN 映射（展示与锥形回连）
            repunchUdpMapping();
            // UPnP 公网直通时无需刷新 STUN/TCP 映射（刷新需短暂关监听，会造成入站中断与自测误报）
            if (tcpMode && !upnpPublicWan) refreshTcpMapping();
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
        // 此时跳过 STUN/TCP 出站探测——探测在路由器上建立的临时映射不受控，是展示端口漂移、
        // 周期关监听抖动与自测误报的根源；仅路由器 WAN 非公网（上层还有 CGNAT）时探测才必要
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
        // 先出站探测，再监听：① 出站连接在沿途全部 NAT（含运营商 CGNAT）上建立真实 TCP 映射，
        // 返回地址即权威外网地址（CGNAT 通常改写外网端口）；② Windows 下监听中无法绑定同端口出站，
        // 探测必须在监听之前完成（后续保活采用「短暂停监听」策略）
        StunClient.TcpProbe probe = skipProbe ? null : tcpProbe(bindPort, 3000);
        int listenPort = bindPort;
        if (probe != null) {
            listenPort = probe.localPort(); // bind_port=0 时以探测实际占用端口作为监听端口
            tcpMappedViaUpnp = false;
            updateTcpMapped(probe.mapped());
            Logs.info(Logs.STUN, "任务[" + name + "] TCP映射已建立(STUN/TCP服务器 " + tcpStunServer + "): " + probe.mapped());
            // 出站探测映射经实测可被外网设备主动访问，自测成功即代表互联网可达，无需更换端口重新穿透
            if (!upnpEnabled) {
                Logs.info(Logs.STUN, "任务[" + name + "] 已通过出站探测建立TCP映射(未启用UPnP)，外网设备可经该映射地址主动连入");
            } else {
                Logs.info(Logs.STUN, "任务[" + name + "] 路由器WAN口非公网或UPnP网关不可用，改以出站探测在运营商NAT上建立TCP映射，"
                        + "外网设备可经该映射地址主动连入");
            }
        } else if (!skipProbe) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 无可用STUN-over-TCP服务器，无法在运营商CGNAT上建立TCP映射；"
                    + "仍展示UDP映射，外网主动访问需路由器端口转发且上层NAT放行");
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
        if (upnpEnabled) applyUpnpMapping(localPort);
        startKeepalive();
        startAcceptor();

        // 出站方向：配置了对端公网地址时，周期性主动向对端打洞（TCP 需双方向建立 NAT 过滤条目）
        if (!peerAddr.isBlank()) {
            punchTask = Tasks.every(3, keepaliveSec, () -> punchToPeer(parsePeerAddr(peerAddr)));
        }
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
     * TCP 映射保活：从监听端口出站刷新运营商 CGNAT 上的 TCP 映射（防会话超时）并更新展示地址。
     * Windows 下监听中无法绑定同端口出站，需先短暂停止监听：探测完成后重开监听，
     * 窗口内（约 1-2 秒）入站 SYN 被丢弃，由客户端 TCP SYN 重传自然恢复。
     * <p>
     * 刷新前先以临时端口快速预检服务器可用性：全部不可用时直接跳过（无需关闭监听），
     * 避免服务器故障期监听被关闭数十秒导致入站中断、自测误报被拒。
     */
    private synchronized void refreshTcpMapping() {
        if (!running || tcpServer == null || upnpPublicWan) return;
        if (tcpProbe(0, 1500) == null) { // 临时端口快速预检：服务器全不可用时无需打扰监听
            Logs.warn(Logs.STUN, "任务[" + name + "] TCP映射保活预检失败(无可用STUN-over-TCP服务器)");
            return;
        }
        tcpRefreshing = true;
        int port = tcpServer.getLocalPort();
        boolean bounced = false;
        try {
            try {
                tcpServer.close();
                bounced = true;
            } catch (Exception e) {
                Logs.warn(Logs.STUN, "任务[" + name + "] 关闭TCP监听失败，跳过本次映射刷新: " + e.getMessage());
            }
            if (bounced) {
                try {
                    Thread.sleep(800); // 等待端口与刚关闭连接完全释放（实测刚关闭立即重绑会被 Windows 拒绝）
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                StunClient.TcpProbe probe = tcpProbe(port, 3000);
                if (probe != null) {
                    tcpMappedViaUpnp = false;
                    updateTcpMapped(probe.mapped());
                } else {
                    Logs.warn(Logs.STUN, "任务[" + name + "] TCP映射保活失败(无可用STUN-over-TCP服务器)");
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

    /**
     * STUN-over-TCP 探测：依次尝试上次成功的服务器、配置的服务器与内置支持 TCP 的兜底服务器，
     * 从指定本地端口（0=随机）出站，返回真实 TCP 出口映射，成功服务器记入 {@link #tcpStunServer}。
     */
    private StunClient.TcpProbe tcpProbe(int localPort, int timeoutMs) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (tcpStunServer != null) candidates.add(tcpStunServer);
        candidates.add(stunHost + ":" + stunPort);
        for (String[] s : StunServerService.tcpServers()) candidates.add(s[0] + ":" + s[1]);
        for (String[] s : StunClient.TCP_STUN_SERVERS) candidates.add(s[0] + ":" + s[1]);
        for (String addr : candidates) {
            int ci = addr.lastIndexOf(':');
            StunClient.TcpProbe probe = StunClient.probeOverTcp(addr.substring(0, ci),
                    Integer.parseInt(addr.substring(ci + 1)), localPort, timeoutMs);
            if (probe != null) {
                tcpStunServer = addr;
                return probe;
            }
        }
        return null;
    }

    /** TCP 双向管道：外网连接 <-> 目标内网服务（字节级透明，HTTP 等应用层协议直接可用） */
    private void pipeToTarget(Socket client) {
        try (client; Socket target = new Socket(targetIp, targetPort)) {
            Logs.info(Logs.STUN, "任务[" + name + "] 外网连接: " + client.getRemoteSocketAddress());
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
        udpProbe = null;
        upnpWanAddr = null;
        releaseUpnpMapping();
        if (keepaliveTask != null) keepaliveTask.cancel(false);
        if (punchTask != null) punchTask.cancel(false);
        if (checkTask != null) checkTask.cancel(false);
        if (udpSocket != null) udpSocket.close();
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
        udpProbe = null;
        upnpWanAddr = null;
        releaseUpnpMapping();
        if (keepaliveTask != null) keepaliveTask.cancel(false);
        if (punchTask != null) punchTask.cancel(false);
        if (checkTask != null) checkTask.cancel(false);
        if (udpSocket != null) udpSocket.close();
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

    /** 更新 TCP 方向外网映射（STUN/TCP 探测或 UPnP 映射），TCP 自测以此为准 */
    private void updateTcpMapped(String mapped) {
        wanTcpReady = true;
        updateMapped(mapped, null);
    }

    /**
     * UDP STUN 映射刷新（启动探测/保活响应）。
     * TCP 模式下权威外网地址以 STUN/TCP 探测的映射为准（见类注释），而 UDP 映射端口与其可能不同
     * （CGNAT 通常改写 TCP 外网端口）：若用 UDP 映射覆盖展示地址，穿透端口会在两个值之间来回跳变，
     * 巡检自测还会打到仅 UDP 可达的端口上误判失效并触发重穿。因此已有 TCP 映射时只刷新保活时间与 NAT 类型。
     */
    private void updateUdpMapped(String mapped, String natType) {
        if (!"UDP".equalsIgnoreCase(protocol) && wanTcpReady) {
            if (mapped != null && !mapped.isBlank()) {
                lastMappedAt = System.currentTimeMillis(); // 保活有效：供巡检判断保活是否失效，但不改动权威地址
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

    /** 手动自测入口（REST 接口调用，同步执行并返回结果） */
    Map<String, Object> verifyNow() throws Exception {
        return verifyChannel();
    }

    /**
     * 可用性自测：验证外网能否经映射地址主动连入，结果写入 check_time / check_result。
     * TCP 任务向外网映射地址发起 TCP 连接（握手成功即通道可用）；
     * UDP 任务从新 socket 向映射地址发送探测包，接收线程收到即映射可达。
     */
    private Map<String, Object> verifyChannel() throws Exception {
        // 保活刷新会短暂关闭 TCP 监听（约 1-2 秒），等待其完成再自测，避免恰逢窗口误报被拒
        if (tcpRefreshing) {
            long deadline = System.currentTimeMillis() + 6000;
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
            result = verifyUdp(mapped);
        } else if (!wanTcpReady) {
            // 展示的是 UDP 映射，对 UDP 映射端口发 TCP 连接必然超时，直接给出原因
            result = "FAIL(未建立TCP映射: STUN不支持TCP探测且UPnP未生效)";
        } else {
            // 先测展示的公网映射地址（CGNAT 支持回环时可真正贯穿验证）；失败退回路由器 WAN 地址验证本地链路。
            // 出站探测映射实测可被外网主动连入，自测通过即代表互联网可达
            String probeNote = tcpMappedViaUpnp ? ""
                    : "；映射来自出站探测，自测通过即外网可经该地址主动连入";
            String pubResult = verifyTcp(mapped, probeNote);
            if (pubResult.startsWith("OK")) {
                result = pubResult;
            } else if (upnpWanAddr != null) {
                String note = isPublicIp(upnpWanAddr.substring(0, upnpWanAddr.lastIndexOf(':')))
                        ? "" : "；路由器WAN非公网，真实外网可达性取决于上层NAT";
                String wanResult = verifyTcp(upnpWanAddr, note);
                result = wanResult.startsWith("OK") ? wanResult : pubResult;
            } else {
                result = pubResult;
            }
        }
        String time = Database.now();
        Database.update("UPDATE stun_task SET check_time=?, check_result=? WHERE id=?", time, result, id);
        Logs.info(Logs.STUN, "任务[" + name + "] 可用性自测: " + result + "，映射地址: " + mapped);
        return Map.of("time", time, "result", result);
    }

    /** TCP 任务：向外网映射地址发起 TCP 连接，握手成功即视为穿透通道可用 */
    private String verifyTcp(String mapped, String note) {
        long t0 = System.currentTimeMillis();
        String[] parts = mapped.split(":");
        int port = Integer.parseInt(parts[1]);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(parts[0], port), 3000);
                }
                return "OK(TCP连接成功，" + (System.currentTimeMillis() - t0) + "ms" + note + ")";
            } catch (java.net.ConnectException e) {
                // RST 拒绝：自测走本机→路由器WAN回环，多为路由器自身拒绝（无NAT回流/拦截入站），
                // 也可能撞上保活刷新的短暂关监听窗口，短暂等待后重试再下结论
                if (attempt < 3) {
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                return "FAIL(" + e.getMessage() + note + ")";
            }
        }
        // 重试后仍被拒：多为路由器回环策略限制（映射通常正常），不记为 FAIL 以免周期性误报干扰判断；
        // 映射真正失效时通常表现为连接超时（而非被拒），由超时分支触发重新穿透
        return "UNKNOWN(回环自测被拒: 自测经路由器WAN回环, 路由器无NAT回流或拦截未请求入站时即使穿透正常也会被拒绝, 请用外部设备验证" + note + ")";
    }

    /** UDP 任务：从新 socket 向映射地址发送探测包，接收线程在超时前收到即视为映射可达 */
    private String verifyUdp(String mapped) {
        String nonce = "nxprobe-" + System.nanoTime();
        udpProbeOk = false;
        udpProbe = nonce;
        long t0 = System.currentTimeMillis();
        try (DatagramSocket s = new DatagramSocket()) {
            String[] parts = mapped.split(":");
            byte[] data = nonce.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName(parts[0]), Integer.parseInt(parts[1])));
            while (!udpProbeOk && System.currentTimeMillis() - t0 < 5000) {
                Thread.sleep(100);
            }
            return udpProbeOk
                    ? "OK(映射可达，" + (System.currentTimeMillis() - t0) + "ms)"
                    : "FAIL(探测包无回音: 外网可能无法主动连入; 路由器无NAT回流时自测同样失败, 请用外部设备验证)";
        } catch (Exception e) {
            return "FAIL(" + e.getMessage() + ")";
        } finally {
            udpProbe = null;
        }
    }

    // ---------- UPnP 配合与重新穿透 ----------

    /**
     * UPnP 端口映射：SSDP 发现路由器并添加 WAN-&gt;LAN 映射（外网端口=本地端口），
     * 映射成功后以路由器 WAN IP + 本地端口作为权威外网地址展示。
     */
    private void applyUpnpMapping(int localPort) {
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
                Logs.info(Logs.STUN, "任务[" + name + "] UPnP端口映射成功: "
                        + (wanIp == null ? "外网IP未知" : wanIp) + ":" + extPort + " -> " + lanIp + ":" + localPort);
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

    /** 重新穿透：刷新 STUN NAT 映射 + 重新应用 UPnP 端口映射 */
    private void repunch() {
        try {
            int localPort = udpSocket.getLocalPort();
            if ("UDP".equalsIgnoreCase(protocol)) {
                repunchUdpMapping();
            } else {
                refreshTcpMapping();
            }
            if (upnpEnabled) applyUpnpMapping(localPort);
            Logs.info(Logs.STUN, "任务[" + name + "] 重新穿透完成(STUN" + (upnpEnabled ? " + UPnP" : "") + ")");
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 重新穿透异常: " + e.getMessage());
        }
    }

    /** 自测失败后自动重新穿透的最大重试次数，超过后停止自动复测（仍可手动「自测」） */
    private static final int MAX_AUTO_REPUNCH = 3;

    /**
     * 自测循环：测试一次，失败后按失败类型决定是否重新穿透复测。
     * 连接被拒绝（RST）说明包被某设备主动拒绝（多为路由器自身：不支持 NAT 回流或拦截入站），
     * 映射本身通常正常，重新穿透无意义，直接停止自动复测并提示用外部设备验证；
     * 超时等其他失败可能是映射失效，最多自动重新穿透 {@link #MAX_AUTO_REPUNCH} 次。
     */
    private void verifyLoop() {
        if (!running) return;
        String result = "";
        try {
            result = str(verifyChannel(), "result");
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 可用性自测异常: " + e.getMessage());
        }
        if (result.startsWith("OK") || !running) return;
        if (result.contains("refused") || result.contains("被拒绝")) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 自测连接被拒绝：自测路径为本机→路由器WAN回环，"
                    + "路由器不支持NAT回流(hairpin)或拦截未请求入站时，即使穿透正常也会被拒绝，重新穿透无意义，停止自动复测；"
                    + "请用外部设备(手机流量等)访问映射地址验证真实可达性");
            return;
        }
        if (++repunchCount > MAX_AUTO_REPUNCH) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 连续 " + MAX_AUTO_REPUNCH
                    + " 次重新穿透后自测仍未通过，停止自动复测；可手动「自测」复测，或检查路由器入站策略/端口转发与主机防火墙");
            return;
        }
        Logs.info(Logs.STUN, "任务[" + name + "] 自测未通过，" + keepaliveSec + "s 后重新穿透并复测(第 "
                + repunchCount + "/" + MAX_AUTO_REPUNCH + " 次)");
        Tasks.delay(keepaliveSec, () -> {
            if (!running) return;
            repunch();
            Tasks.delay(2, this::verifyLoop);
        });
    }

    // ---------- 周期有效性巡检 ----------

    /** 巡检周期：至少 60 秒，且不低于保活周期的 2 倍（保证两次保活后仍无响应才判定失效） */
    private long checkIntervalSec() {
        return Math.max(60, keepaliveSec * 2L);
    }

    /**
     * 穿透成功后的周期有效性巡检：NAT 映射可能因超时、网络切换或运营商策略静默失效且无任何提示，
     * 巡检持续监测并在失效时自动重新穿透复测，防止长时间运行后穿透失效不可用。
     * <ul>
     *   <li>保活无响应：连续超过 3 个保活周期未收到 STUN 绑定响应，先刷新映射再复测</li>
     *   <li>TCP 映射缺失：建立失败时周期内重试，直到成功</li>
     *   <li>自测失败：自动重新穿透并延迟复测；被拒绝（路由器回环策略）不重穿，属环境限制</li>
     * </ul>
     */
    private void startWatcher() {
        long sec = checkIntervalSec();
        checkTask = Tasks.every(sec, sec, this::watchChannel);
    }

    private void watchChannel() {
        if (!running || !punched) return;
        if (tcpRefreshing) return; // 保活刷新短暂关闭监听，本周期跳过自测避免误报
        try {
            long silentMs = System.currentTimeMillis() - lastMappedAt;
            if ("UDP".equalsIgnoreCase(protocol) && silentMs > keepaliveSec * 3_000L) {
                // 保活连续无响应：映射可能已静默失效（网络切换/STUN 服务器故障），先刷新映射再复测
                Logs.warn(Logs.STUN, "任务[" + name + "] STUN保活 " + (silentMs / 1000)
                        + "s 无响应，映射可能已失效，重新穿透并复测");
                repunchUdpMapping();
            }
            if (!"UDP".equalsIgnoreCase(protocol) && !wanTcpReady) {
                // TCP 映射尚未建立（启动时无可用 STUN-over-TCP 服务器），周期内重试直到成功
                Logs.info(Logs.STUN, "任务[" + name + "] TCP映射未建立，巡检重试出站探测");
                refreshTcpMapping();
                return;
            }
            String result = str(verifyChannel(), "result");
            if (result.startsWith("OK")) {
                if (watchFails > 0) Logs.info(Logs.STUN, "任务[" + name + "] 巡检复测通过，穿透通道已恢复");
                watchFails = 0;
                return;
            }
            if (result.contains("refused") || result.contains("被拒绝")) {
                // 被拒绝多为路由器回环策略限制（详见自测说明），映射本身通常正常，重新穿透无意义；保活照常维持
                watchFails = 0;
                return;
            }
            watchFails++;
            Logs.warn(Logs.STUN, "任务[" + name + "] 巡检自测未通过(" + result + ")，自动重新穿透并复测(连续第 "
                    + watchFails + " 次)");
            repunch();
            Tasks.delay(3, () -> {
                if (!running) return;
                try {
                    String recheck = str(verifyChannel(), "result");
                    if (recheck.startsWith("OK")) {
                        Logs.info(Logs.STUN, "任务[" + name + "] 重新穿透后复测通过，通道已恢复");
                        watchFails = 0;
                    } else {
                        Logs.warn(Logs.STUN, "任务[" + name + "] 重新穿透后复测仍未通过(" + recheck
                                + ")，" + checkIntervalSec() + "s 后巡检继续尝试");
                    }
                } catch (Exception e) {
                    Logs.warn(Logs.STUN, "任务[" + name + "] 巡检复测异常: " + e.getMessage());
                }
            });
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
