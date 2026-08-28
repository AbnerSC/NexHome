package com.nexhome.module.stun;

import com.nexhome.core.Logs;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/**
 * TCP 出站保活链路管理器（每个 TCP 任务一个实例）：负责在运营商 CGNAT 上建立并维持
 * TCP 映射的出站连接，按优先级选择通道：
 * <ol>
 *   <li>STUN-over-TCP 服务器（配置的 + 维护列表 + 内置候选）：取得<b>精确</b>映射地址</li>
 *   <li>公共 DNS-over-TCP 端点（端口保留模式兜底）：出站连接建立映射，周期 DNS 查询
 *       双向报文保活并验证链路存活，外部映射端口=本地源端口（多数运营商 CGNAT 对 TCP
 *       保留源端口；公共 TCP STUN 服务稀缺的现实下与 Lucky 等工具同路）</li>
 * </ol>
 * 链路死亡时优先复用预绑定的备用出站 socket（同端口、未连接，监听开启前预绑）立即重连，
 * 实现<b>零监听中断</b>的链路重建；备用耗尽才由调用方「弹跳」（关监听→重建→重开监听）。
 * STUN/TCP 候选整体失败后退避 300 秒（期间只用 DNS 端点），避免每个保活周期刷屏。
 */
final class TcpPunch {

    /** 一条保活链路：连接 + 模式 + 端点 + 精确映射地址（dns 端口保留模式为 null） */
    record Link(Socket socket, boolean viaStun, String endpoint, String mapped) {
        Link withMapped(String m) {
            return new Link(socket, viaStun, endpoint, m);
        }
    }

    private final String taskName;
    private final String stunHost;
    private final int stunPort;
    /** 链路就绪/映射地址变化回调（调用方据此更新权威展示地址与日志） */
    private final Consumer<Link> onReady;

    private volatile Link current;
    /** 端口保留模式：展示地址语义为「出口IP:本地端口」（外部端口=本地源端口假设） */
    private volatile boolean addrPresumed;
    /** 最近成功的 STUN-over-TCP 服务器（host:port），重建链路时优先复用 */
    private volatile String tcpStunServer;
    /** 最近成功的 DNS 保活端点（host:port），重建链路时优先复用 */
    private volatile String dnsEndpoint;
    /** STUN/TCP 候选最近一次整体失败时刻：退避期内跳过 STUN 探测直接用 DNS 端点 */
    private volatile long probeFailAt;
    /** 链路死亡已告警标志（避免每个保活周期重复刷屏，重建成功后复位） */
    private volatile boolean downWarned;
    /** 预绑定的备用出站 socket（同端口、REUSEADDR、未连接）：监听开启前预绑，链路断开时零中断重连 */
    private final ConcurrentLinkedDeque<Socket> spares = new ConcurrentLinkedDeque<>();
    /** 已告警过的失效 DNS 端点（端点恢复可用后不再重复告警，避免退避重试期间刷屏） */
    private final Set<String> warnedEndpoints = ConcurrentHashMap.newKeySet();

    TcpPunch(String taskName, String stunHost, int stunPort, Consumer<Link> onReady) {
        this.taskName = taskName;
        this.stunHost = stunHost;
        this.stunPort = stunPort;
        this.onReady = onReady;
    }

    /** 端口保留模式：展示地址语义为「出口IP:本地端口」（外部端口=本地源端口假设） */
    boolean addrPresumed() {
        return addrPresumed;
    }

    /** STUN/TCP 候选是否已过退避期（整体失败 300 秒后允许再试） */
    boolean allowStunNow() {
        return System.currentTimeMillis() - probeFailAt > 300_000;
    }

    /** 当前链路交互一次（保活 + 存活验证）：STUN 精确模式下映射地址变化时回调 */
    boolean keepaliveOnce() {
        Link cur = current;
        if (cur == null || cur.socket().isClosed() || !cur.socket().isConnected()) return false;
        if (cur.viaStun()) {
            String mapped = StunClient.bindingOverTcp(cur.socket(), 3000);
            if (mapped == null) return false;
            if (!mapped.equals(cur.mapped())) {
                current = cur.withMapped(mapped);
                onReady.accept(current);
            }
            return true;
        }
        return StunClient.dnsTcpExchange(cur.socket(), 3000);
    }

    /** 链路已死亡时的提示（每个死亡周期只告警一次，重建成功自动复位） */
    void noteLinkDownIfNeeded() {
        if (!downWarned) {
            downWarned = true;
            Logs.warn(Logs.STUN, "任务[" + taskName + "] TCP保活链路失效(交互无响应)，重建出站链路");
        }
    }

    /**
     * 全新建立出站链路（新 socket，从指定本地端口出站）：依次尝试 STUN/TCP 候选
     * （allowStun=false 时跳过，用于退避期），全部失败回退 DNS 端点。成功登记为当前链路
     * 并回调 onReady；失败返回 null。本地端口必须尚未被监听占用（LISTEN 存在时无法 bind）。
     */
    synchronized Link establish(int localPort, boolean allowStun) {
        if (allowStun) {
            for (String addr : stunCandidates()) {
                int ci = addr.lastIndexOf(':');
                StunClient.TcpProbe p = StunClient.probeOverTcp(addr.substring(0, ci),
                        Integer.parseInt(addr.substring(ci + 1)), localPort, 2500);
                if (p != null) {
                    tcpStunServer = addr;
                    probeFailAt = 0;
                    return install(new Link(p.socket(), true, addr, p.mapped()));
                }
            }
            if (System.currentTimeMillis() - probeFailAt > 300_000) {
                // 刚进入新的退避期才告警（含首次）：避免每个保活周期重复刷屏
                Logs.warn(Logs.STUN, "任务[" + taskName + "] 无可用STUN-over-TCP服务器，回退端口保留模式"
                        + "(DNS端点出站，外部映射端口=本地端口；自建TCP STUN服务器可获得精确映射)");
            }
            probeFailAt = System.currentTimeMillis();
        }
        for (String[] ep : dnsCandidates()) {
            Socket s;
            try {
                s = StunClient.probeOverDnsEx(ep[0], Integer.parseInt(ep[1]), localPort, 2500);
            } catch (Exception e) {
                // 端点失效原因告警仅首次：国内端点被网络策略拦截时无法从外部观察，这是定位关键
                if (warnedEndpoints.add(ep[0] + ":" + ep[1])) {
                    Logs.warn(Logs.STUN, "任务[" + taskName + "] DNS保活端点不可用("
                            + ep[0] + ":" + ep[1] + "): " + e.getMessage());
                }
                continue;
            }
            dnsEndpoint = ep[0] + ":" + ep[1];
            warnedEndpoints.remove(dnsEndpoint); // 端点恢复可用：复位告警以便下次失效再提示
            return install(new Link(s, false, dnsEndpoint, null));
        }
        return null;
    }

    /** STUN-over-TCP 候选：上次成功服务器 → 配置服务器 → 维护列表 → 内置列表 */
    private LinkedHashSet<String> stunCandidates() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (tcpStunServer != null) set.add(tcpStunServer);
        set.add(stunHost + ":" + stunPort);
        for (String[] s : StunServerService.tcpServers()) set.add(s[0] + ":" + s[1]);
        for (String[] s : StunClient.TCP_STUN_SERVERS) set.add(s[0] + ":" + s[1]);
        return set;
    }

    /** DNS 保活端点候选：最近成功的优先 */
    private List<String[]> dnsCandidates() {
        List<String[]> list = new ArrayList<>();
        if (dnsEndpoint != null) {
            int ci = dnsEndpoint.lastIndexOf(':');
            list.add(new String[]{dnsEndpoint.substring(0, ci), dnsEndpoint.substring(ci + 1)});
        }
        for (String[] ep : StunClient.DNS_TCP_ENDPOINTS) {
            if (dnsEndpoint == null || !dnsEndpoint.equals(ep[0] + ":" + ep[1])) list.add(ep);
        }
        return list;
    }

    /** 登记新链路并回调（端口保留模式地址由调用方在回调中组装展示） */
    private Link install(Link link) {
        current = link;
        addrPresumed = !link.viaStun();
        downWarned = false;
        onReady.accept(link);
        return link;
    }

    /**
     * 链路死亡后重连：优先消耗预绑定备用 socket（监听不中断），STUN 优先（非退避期且有过成功），
     * 失败转 DNS 端点；每次尝试恰好消耗一个备用 socket（连接失败的 socket 无法复用），
     * 备用耗尽或全部失败返回 null（由调用方决定弹跳重建）。
     */
    synchronized Link reconnect(int localPort, boolean allowStun) {
        if (allowStun && tcpStunServer != null) {
            Socket spare = spares.poll();
            if (spare != null) {
                Link link = connectStun(spare);
                if (link != null) return install(link);
            }
        }
        for (String[] ep : dnsCandidates()) {
            Socket spare = spares.poll();
            if (spare == null) return null;
            Link link = connectDns(spare, ep);
            if (link != null) return install(link);
        }
        return null;
    }

    /** 用预绑定 socket 重连最近成功的 STUN/TCP 服务器：失败返回 null（socket 已关闭作废） */
    private Link connectStun(Socket s) {
        try {
            int ci = tcpStunServer.lastIndexOf(':');
            s.connect(new InetSocketAddress(tcpStunServer.substring(0, ci),
                    Integer.parseInt(tcpStunServer.substring(ci + 1))), 3000);
            String mapped = StunClient.bindingOverTcp(s, 3000);
            if (mapped != null) {
                probeFailAt = 0;
                return new Link(s, true, tcpStunServer, mapped);
            }
        } catch (Exception ignored) {
            // 服务器连接失败/无响应：备用 socket 作废
        }
        closeQuietly(s);
        return null;
    }

    /** 用预绑定 socket 重连 DNS 保活端点：DNS 交换验证通过即返回链路，失败返回 null（socket 已关闭作废） */
    private Link connectDns(Socket s, String[] ep) {
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName(ep[0]), Integer.parseInt(ep[1])), 3000);
            if (StunClient.dnsTcpExchange(s, 3000)) {
                dnsEndpoint = ep[0] + ":" + ep[1];
                return new Link(s, false, dnsEndpoint, null);
            }
        } catch (Exception ignored) {
            // 端点不可达：换下一个端点
        }
        closeQuietly(s);
        return null;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
            // 关闭失败不影响后续流程
        }
    }

    /** 预绑定备用出站 socket：必须在监听开启前完成（LISTEN 占用端口后无法再绑定出站 socket） */
    void openSpares(int localPort, int count) {
        for (int i = 0; i < count; i++) {
            try {
                Socket s = new Socket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(localPort));
                spares.add(s);
            } catch (Exception e) {
                Logs.warn(Logs.STUN, "任务[" + taskName + "] 预绑定备用出站socket失败(端口" + localPort + "): "
                        + e.getMessage());
                break;
            }
        }
    }

    /** 关闭当前链路（幂等），备用 socket 保留 */
    void closeLink() {
        Link cur = current;
        current = null;
        if (cur != null) {
            try {
                cur.socket().close();
            } catch (Exception ignored) {
                // 连接可能已断开，仍按成功处理
            }
        }
    }

    /** 释放全部资源（任务停止时调用） */
    void closeAll() {
        closeLink();
        Socket s;
        while ((s = spares.poll()) != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // 备用 socket 关闭失败不影响任务
            }
        }
    }
}
