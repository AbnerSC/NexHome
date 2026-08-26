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
 * 局限性：对称型 NAT（Symmetric）下映射端口随机，纯 STUN 无法保证穿透成功，
 * 此时建议在路由器配置端口转发或改用中继方案。
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
        if ("UDP".equalsIgnoreCase(protocol)) {
            startUdp();
        } else {
            startTcp();
        }
        updateStatus("RUNNING");
        Logs.info(Logs.STUN, "穿透任务[" + name + "] 已启动 (" + protocol + ")");
    }

    private void startUdp() throws Exception {
        udpSocket = bindPort > 0 ? new DatagramSocket(bindPort) : new DatagramSocket();

        // 首次 STUN 探测：建立 NAT 映射并识别 NAT 类型
        StunClient.Result r = StunClient.detectNatType(udpSocket, stunHost, stunPort, 3000);
        updateMapped(r == null ? null : r.mappedAddress(), r == null ? "Unknown" : r.natType());
        Logs.info(Logs.STUN, "任务[" + name + "] NAT类型: " + (r == null ? "Unknown" : r.natType())
                + "，映射地址: " + (r == null ? "无" : r.mappedAddress()));

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
            if (tcpMode) {
                // 出站连接本身即建立/保活 TCP 映射，并返回最新映射地址供展示
                String mapped = StunClient.bindOverTcp(stunHost, stunPort, tcpServer.getLocalPort(), 3000);
                if (mapped != null) updateMapped(mapped, null);
            }
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
        // 入站方向：同一本地端口监听 TCP，外网连接进来后转发到目标内网服务（HTTP 等 TCP 流量透明传输）
        tcpServer = new ServerSocket();
        tcpServer.setReuseAddress(true);
        tcpServer.bind(new InetSocketAddress(bindPort));
        int localPort = tcpServer.getLocalPort();

        // 同端口维护 UDP STUN 映射：展示外网地址并保活，锥形 NAT 下对端可经该映射打洞回连
        udpSocket = new DatagramSocket(localPort);
        StunClient.Result r = StunClient.detectNatType(udpSocket, stunHost, stunPort, 3000);
        updateMapped(r == null ? null : r.mappedAddress(), r == null ? "Unknown" : r.natType());
        Logs.info(Logs.STUN, "任务[" + name + "] NAT类型: " + (r == null ? "Unknown" : r.natType())
                + "，映射地址: " + (r == null ? "无" : r.mappedAddress()));
        startUdpReceiver(false);

        // STUN-over-TCP 探测：TCP 入站真正对应的是 TCP 映射（可能与 UDP 探测结果不同），优先展示并保活
        String tcpMapped = StunClient.bindOverTcp(stunHost, stunPort, localPort, 3000);
        if (tcpMapped != null) {
            updateMapped(tcpMapped, null);
            Logs.info(Logs.STUN, "任务[" + name + "] TCP映射地址(STUN/TCP): " + tcpMapped);
        } else {
            Logs.warn(Logs.STUN, "任务[" + name + "] STUN服务器不支持TCP探测，仍展示UDP映射；"
                    + "外网主动访问需全锥形NAT或路由器端口转发，并请确认主机防火墙已放行该端口");
        }
        startKeepalive();

        Thread acceptor = new Thread(() -> {
            while (running && !tcpServer.isClosed()) {
                try {
                    Socket client = tcpServer.accept();
                    Thread pipe = new Thread(() -> pipeToTarget(client), "stun-tcp-" + id);
                    pipe.setDaemon(true);
                    pipe.start();
                } catch (Exception e) {
                    if (running) Logs.warn(Logs.STUN, "任务[" + name + "] TCP接收异常: " + e.getMessage());
                }
            }
        }, "stun-accept-" + id);
        acceptor.setDaemon(true);
        acceptor.start();

        // 出站方向：配置了对端公网地址时，周期性从同一本地端口主动打洞（TCP 需双方向建立 NAT 过滤条目）
        if (!peerAddr.isBlank()) {
            punchTask = Tasks.every(3, keepaliveSec, () -> punchToPeer(parsePeerAddr(peerAddr)));
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
            if (natType == null) {
                Database.update("UPDATE stun_task SET mapped_addr=? WHERE id=?", mapped, id);
            } else {
                Database.update("UPDATE stun_task SET mapped_addr=?, nat_type=? WHERE id=?", mapped, natType, id);
            }
        } catch (Exception e) {
            Logs.error(Logs.STUN, "更新映射状态失败: " + e.getMessage());
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
