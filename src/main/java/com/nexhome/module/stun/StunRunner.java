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
 * 在内的全部 NAT 上建立真实 TCP 映射，以返回的映射地址为权威外网地址（CGNAT 通常改写外网端口）；
 * 之后再在该本地端口监听入站。保活时同样需要出站刷新映射：Windows 下监听中无法绑定同端口出站，
 * 故保活会短暂暂停监听（约 1-2 秒，TCP 客户端 SYN 重传可自然恢复）。
 * <p>
 * 局限性：对称型 NAT（Symmetric）下映射端口随机，纯 STUN 无法保证穿透成功，
 * 因此同时配合 UPnP：任务启动时 SSDP 发现路由器并经 SOAP 添加 WAN-&gt;LAN 端口映射
 * （需路由器开启 UPnP），对称/受限 NAT 下外网也能主动连入。
 * 自测失败时自动重新穿透（刷新 STUN 映射 + 重新 UPnP 映射）并复测，直到成功或任务停止。
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

    /** 本次运行是否已穿透成功（取得外网映射地址），用于记录穿透成功时间 */
    private volatile boolean punched;
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

    private volatile boolean running;
    private DatagramSocket udpSocket;
    private ServerSocket tcpServer;
    private ScheduledFuture<?> keepaliveTask;
    private ScheduledFuture<?> punchTask;
    /** UDP 会话表：对端地址 -> 与目标服务通信的 socket */
    private final Map<String, DatagramSocket> sessions = new ConcurrentHashMap<>();

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
    }

    /** 启动穿透任务 */
    void start() throws Exception {
        running = true; // 先置位：接收线程启动后立即依赖该标志
        punched = false;
        wanTcpReady = false;
        upnpWanAddr = null;
        if ("UDP".equalsIgnoreCase(protocol)) {
            startUdp();
        } else {
            startTcp();
        }
        updateStatus("RUNNING");
        Logs.info(Logs.STUN, "穿透任务[" + name + "] 已启动 (" + protocol + ")");
        // 穿透成功后测试一次，确保通道可用（TCP 连接外网IP:穿透端口，UDP 发探测包）；失败则重新穿透并复测
        Tasks.delay(2, () -> verifyLoop());
    }

    private void startUdp() throws Exception {
        udpSocket = bindPort > 0 ? new DatagramSocket(bindPort) : new DatagramSocket();

        // 首次 STUN 探测：建立 NAT 映射并识别 NAT 类型
        StunClient.Result r = StunClient.detectNatType(udpSocket, stunHost, stunPort, 3000);
        updateMapped(r == null ? null : r.mappedAddress(), r == null ? "Unknown" : r.natType());
        Logs.info(Logs.STUN, "任务[" + name + "] NAT类型: " + (r == null ? "Unknown" : r.natType())
                + "，映射地址: " + (r == null ? "无" : r.mappedAddress()));

        // 配合 UPnP：在路由器上显式开放入站端口，对称/受限 NAT 下外网也可主动连入
        applyUpnpMapping(udpSocket.getLocalPort());

        startUdpReceiver(true);
        startKeepalive();
    }

    /**
     * UDP 接收线程：STUN 响应仅用于刷新映射地址；
     * relayData=true 时其余数据包按会话转发到内网目标（UDP 模式）。
     */
    private void startUdpReceiver(boolean relayData) throws Exception {
        InetAddress stunAddr = InetAddress.getByName(stunHost);
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
                    if (pkt.getAddress().equals(stunAddr) && pkt.getPort() == stunPort) {
                        // 保活响应：刷新外网映射地址（不再与保活线程竞争 receive）
                        String mapped = StunClient.parseBindingMapped(pkt.getData(), pkt.getLength());
                        if (mapped != null) updateMapped(mapped, null);
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

    /** 周期保活：UDP 只发绑定请求（响应由接收线程解析）；TCP 模式额外刷新 TCP 映射 */
    private void startKeepalive() {
        boolean tcpMode = tcpServer != null;
        keepaliveTask = Tasks.every(keepaliveSec, keepaliveSec, () -> {
            try {
                StunClient.sendBindingRequest(udpSocket, stunHost, stunPort);
            } catch (Exception e) {
                Logs.warn(Logs.STUN, "任务[" + name + "] STUN保活失败: " + e.getMessage());
            }
            if (tcpMode) refreshTcpMapping();
        });
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
        // 先出站探测，再监听：① 出站连接在沿途全部 NAT（含运营商 CGNAT）上建立真实 TCP 映射，
        // 返回地址即权威外网地址（CGNAT 通常改写外网端口）；② Windows 下监听中无法绑定同端口出站，
        // 探测必须在监听之前完成（后续保活采用「短暂停监听」策略）
        StunClient.TcpProbe probe = tcpProbe(bindPort);
        int listenPort = bindPort;
        if (probe != null) {
            listenPort = probe.localPort(); // bind_port=0 时以探测实际占用端口作为监听端口
            updateTcpMapped(probe.mapped());
            Logs.info(Logs.STUN, "任务[" + name + "] TCP映射已建立(STUN/TCP服务器 " + tcpStunServer + "): " + probe.mapped());
        } else {
            Logs.warn(Logs.STUN, "任务[" + name + "] 无可用STUN-over-TCP服务器，无法在运营商CGNAT上建立TCP映射；"
                    + "仍展示UDP映射，外网主动访问需路由器端口转发且上层NAT放行");
        }

        // 入站方向：同一本地端口监听 TCP，外网连接进来后转发到目标内网服务（HTTP 等 TCP 流量透明传输）
        tcpServer = openTcpListener(listenPort);
        int localPort = tcpServer.getLocalPort();

        // 同端口维护 UDP STUN 映射：展示外网地址并保活，锥形 NAT 下对端可经该映射打洞回连
        udpSocket = new DatagramSocket(localPort);
        StunClient.Result r = StunClient.detectNatType(udpSocket, stunHost, stunPort, 3000);
        updateMapped(r == null ? null : r.mappedAddress(), r == null ? "Unknown" : r.natType());
        Logs.info(Logs.STUN, "任务[" + name + "] NAT类型: " + (r == null ? "Unknown" : r.natType())
                + "，映射地址: " + (r == null ? "无" : r.mappedAddress()));
        startUdpReceiver(false);

        // 配合 UPnP：在路由器上显式开放入站端口（外网端口=本地端口，入站包经 CGNAT 翻译后以此端口到达路由器），外网也可主动连入
        applyUpnpMapping(localPort);
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
     */
    private void refreshTcpMapping() {
        if (!running || tcpServer == null) return;
        int port = tcpServer.getLocalPort();
        try {
            tcpServer.close();
        } catch (Exception ignored) {
        }
        try {
            Thread.sleep(800); // 等待端口与刚关闭连接完全释放（实测刚关闭立即重绑会被 Windows 拒绝）
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            StunClient.TcpProbe probe = tcpProbe(port);
            if (probe != null) {
                updateTcpMapped(probe.mapped());
            } else {
                Logs.warn(Logs.STUN, "任务[" + name + "] TCP映射保活失败(无可用STUN-over-TCP服务器)");
            }
        } finally {
            if (running) {
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
    private StunClient.TcpProbe tcpProbe(int localPort) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (tcpStunServer != null) candidates.add(tcpStunServer);
        candidates.add(stunHost + ":" + stunPort);
        for (String[] s : StunClient.TCP_STUN_SERVERS) candidates.add(s[0] + ":" + s[1]);
        for (String addr : candidates) {
            int ci = addr.lastIndexOf(':');
            StunClient.TcpProbe probe = StunClient.probeOverTcp(addr.substring(0, ci),
                    Integer.parseInt(addr.substring(ci + 1)), localPort, 3000);
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
        if (upnpMapped && upnpGateway != null) {
            UpnpClient.deletePortMapping(upnpGateway, protocol, upnpPort);
            upnpMapped = false;
            Logs.info(Logs.STUN, "任务[" + name + "] UPnP端口映射已移除");
        }
        if (keepaliveTask != null) keepaliveTask.cancel(false);
        if (punchTask != null) punchTask.cancel(false);
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
            if (mapped != null && !mapped.isBlank() && !punched) {
                punched = true;
                Database.update("UPDATE stun_task SET punched_at=? WHERE id=?", Database.now(), id);
                Logs.info(Logs.STUN, "任务[" + name + "] 穿透成功，外网映射地址: " + mapped);
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

    /** 本机局域网 IP：UDP connect 仅选出出口网卡（不真实发包），避免通配绑定 socket 返回 0.0.0.0 */
    private String lanIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(stunHost), stunPort);
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
            // 先测展示的公网映射地址（CGNAT 支持回环时可真正贯穿验证）；失败退回路由器 WAN 地址验证本地链路
            String pubResult = verifyTcp(mapped, "");
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
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 3000);
            return "OK(TCP连接成功，" + (System.currentTimeMillis() - t0) + "ms" + note + ")";
        } catch (Exception e) {
            return "FAIL(" + e.getMessage() + ")";
        }
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
                    : "FAIL(探测包无回音，外网可能无法主动连入)";
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
                if (displayIp != null) updateTcpMapped(displayIp + ":" + extPort);
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
                String mapped = StunClient.bind(udpSocket, stunHost, stunPort, 3000);
                if (mapped != null) updateMapped(mapped, null);
            } else {
                refreshTcpMapping();
            }
            applyUpnpMapping(localPort);
            Logs.info(Logs.STUN, "任务[" + name + "] 重新穿透完成(STUN + UPnP)");
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 重新穿透异常: " + e.getMessage());
        }
    }

    /** 自测循环：测试一次，失败则重新穿透并复测，直到成功或任务停止 */
    private void verifyLoop() {
        if (!running) return;
        boolean ok = false;
        try {
            ok = str(verifyChannel(), "result").startsWith("OK");
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 可用性自测异常: " + e.getMessage());
        }
        if (ok || !running) return;
        Logs.info(Logs.STUN, "任务[" + name + "] 自测未通过，" + keepaliveSec + "s 后重新穿透并复测");
        Tasks.delay(keepaliveSec, () -> {
            if (!running) return;
            repunch();
            Tasks.delay(2, this::verifyLoop);
        });
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
