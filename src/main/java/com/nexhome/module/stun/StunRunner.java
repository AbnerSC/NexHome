package com.nexhome.module.stun;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;

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
 * STUN 穿透任务运行器（每个任务一个实例）。
 * <p>
 * <b>UDP 模式</b>：在绑定端口上打开 UDP Socket，持续向 STUN 服务器发送绑定请求
 * 建立并保活 NAT 映射；外网发入该映射端口的数据包被转发到目标内网服务，
 * 响应按会话回传给对端（简易会话表实现）。
 * <p>
 * <b>TCP 模式</b>：TCP 无法仅靠 STUN 打洞（需双端同时发起连接），
 * 因此这里在同一本地端口监听 TCP 并转发到目标服务，同时用 STUN 探测该端口的
 * 外网映射地址用于展示。该模式在路由器存在端口映射/DMZ/锥形 NAT 时可被外网访问。
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

    private volatile boolean running;
    private DatagramSocket udpSocket;
    private ServerSocket tcpServer;
    private ScheduledFuture<?> keepaliveTask;
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
    }

    /** 启动穿透任务 */
    void start() throws Exception {
        if ("UDP".equalsIgnoreCase(protocol)) {
            startUdp();
        } else {
            startTcp();
        }
        running = true;
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

        // 接收线程：区分 STUN 响应与业务数据
        InetAddress stunAddr = InetAddress.getByName(stunHost);
        Thread receiver = new Thread(() -> {
            byte[] buf = new byte[65535];
            while (running && !udpSocket.isClosed()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    udpSocket.setSoTimeout(2000);
                    udpSocket.receive(pkt);
                    if (pkt.getAddress().equals(stunAddr) && pkt.getPort() == stunPort) {
                        // STUN 响应仅用于确认映射存活，不做解析转发
                        continue;
                    }
                    forwardToTarget(pkt);
                } catch (java.net.SocketTimeoutException ignored) {
                    // 超时继续循环
                } catch (Exception e) {
                    if (running) Logs.warn(Logs.STUN, "任务[" + name + "] UDP接收异常: " + e.getMessage());
                }
            }
        }, "stun-udp-" + id);
        receiver.setDaemon(true);
        receiver.start();

        // 周期保活：重复发送绑定请求，防止 NAT 映射超时回收
        keepaliveTask = com.nexhome.core.Tasks.every(keepaliveSec, keepaliveSec, () -> {
            try {
                String mapped = StunClient.bind(udpSocket, stunHost, stunPort, 2000);
                if (mapped != null) {
                    updateMapped(mapped, null);
                }
            } catch (Exception e) {
                Logs.warn(Logs.STUN, "任务[" + name + "] STUN保活失败: " + e.getMessage());
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
        // 用同一本地端口先做 UDP STUN 探测，得到外网映射地址供展示
        try (DatagramSocket probe = bindPort > 0 ? new DatagramSocket(bindPort) : new DatagramSocket()) {
            StunClient.Result r = StunClient.detectNatType(probe, stunHost, stunPort, 3000);
            updateMapped(r == null ? null : r.mappedAddress(), r == null ? "Unknown" : r.natType());
            // TCP 监听与 UDP 探测保持同一本地端口
            tcpServer = new ServerSocket(probe.getLocalPort());
        }

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
    }

    /** TCP 双向管道：外网连接 <-> 目标内网服务 */
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
