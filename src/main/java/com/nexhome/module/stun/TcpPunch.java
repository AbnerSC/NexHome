package com.nexhome.module.stun;

import com.nexhome.core.Database;
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
 *   <li>公共出站端点（端口保留模式兜底）：出站长连接维持运营商 CGNAT 上的 TCP 映射，
 *       连接上不做应用层交互（TCP 握手完成即双向链路验证；域名解析按其设计走 UDP 53，
 *       不占用 TCP 连接）。端点须为非 DNS 端口的透传服务：实测运营商 CGNAT 对 53/TCP
 *       透明拦截（连接能建立但终结在 CGNAT、查询无响应），此类映射不接受入站；
 *       外网映射端口无法直接探测，无 TCP 探测能力时由运行器取同本地端口的 UDP STUN 映射
 *       尽力估计展示（实测同本地端口 TCP/UDP 外部端口并不相同，入站不保证可达）</li>
 * </ol>
 * 链路死亡时优先复用预绑定的备用出站 socket（同端口、未连接，监听开启前预绑）立即重连，
 * 实现<b>零监听中断</b>的链路重建；备用耗尽才由调用方「弹跳」（关监听→重建→重开监听）。
 * STUN/TCP 候选整体失败后退避 300 秒（期间只用公共出站端点），避免每个保活周期刷屏。
 */
final class TcpPunch {

    /** 一条保活链路：连接 + 模式 + 端点 + 精确映射地址（端口保留模式为 null） */
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
    /** 最近成功的出站连接端点（host:port），重建链路时优先复用 */
    private volatile String outEndpoint;
    /** STUN/TCP 候选最近一次整体失败时刻：退避期内跳过 STUN 探测直接用公共出站端点 */
    private volatile long probeFailAt;
    /** 链路死亡已告警标志（避免每个保活周期重复刷屏，重建成功后复位） */
    private volatile boolean downWarned;
    /** 预绑定的备用出站 socket（同端口、REUSEADDR、未连接）：监听开启前预绑，链路断开时零中断重连 */
    private final ConcurrentLinkedDeque<Socket> spares = new ConcurrentLinkedDeque<>();
    /** 已告警过的失效出站端点（端点恢复可用后不再重复告警，避免退避重试期间刷屏） */
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

    /**
     * 当前链路交互一次（保活 + 存活验证）。
     * STUN 精确模式：长连接上做绑定交互（STUN 服务器支持连接复用），映射地址变化时回调。
     * 出站端点模式（端口保留）：出站长连接存活即运营商 CGNAT 映射存活——先做 TCP 级存活检测
     * （短超时读：EOF/复位=死亡，超时=存活），存活直接返回，不消耗备用 socket；检测死亡时
     * <b>先 RST 废弃旧连接释放四元组</b>，再用预绑定备用 socket 同端口轮换新建出站连接。
     * 若旧连接未释放就连同一端点，四元组与存活连接完全重复，内核必然拒绝（保活/自测永远失败的根源）；
     * 端点可能为单事务/短空闲超时类型，死亡轮换是该模式常态（静默完成不打日志），
     * spare 耗尽或端点全部不可达返回 false，由调用方走失败重建流程（弹跳补充 spare）。
     */
    synchronized boolean keepaliveOnce() {
        Link cur = current;
        if (cur == null) return false;
        if (cur.viaStun()) {
            if (cur.socket().isClosed() || !cur.socket().isConnected()) return false;
            String mapped = StunClient.bindingOverTcp(cur.socket(), 3000);
            if (mapped == null) return rebindStunInPlace(cur);
            if (!mapped.equals(cur.mapped())) {
                current = cur.withMapped(mapped);
                onReady.accept(current);
            }
            return true;
        }
        if (isAlive(cur.socket())) return true; // 长连接存活：CGNAT 映射存活，无需轮换（映射地址不变）
        // 链路死亡：先废弃旧连接释放四元组，再轮换新建（当前端点优先，失败依次尝试其余候选）
        abandon(cur.socket());
        for (String[] ep : outboundCandidates()) {
            Socket spare = spares.poll();
            if (spare == null) return false;
            Link link = connectOutbound(spare, ep);
            if (link != null) {
                current = link;
                return true;
            }
        }
        return false;
    }

    /**
     * STUN 交互单次无响应的就地自愈：同本地端口重连同一服务器重试一次。
     * 单次无响应多为服务器侧抖动/丢包，就地恢复避免计入失败走「告警 + 消耗备用 socket」的重建流程；
     * 本地端口必须与监听一致（CGNAT 映射挂在出站五元组上），恢复后映射端口可能漂移（回调刷新展示）。
     * 仍失败返回 false，由调用方走备用/弹跳重建流程。
     */
    private boolean rebindStunInPlace(Link cur) {
        int localPort = cur.socket().getLocalPort();
        abandon(cur.socket());
        current = null;
        try {
            Socket s = new Socket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(localPort));
            int ci = cur.endpoint().lastIndexOf(':');
            s.connect(new InetSocketAddress(cur.endpoint().substring(0, ci),
                    Integer.parseInt(cur.endpoint().substring(ci + 1))), 3000);
            String mapped = StunClient.bindingOverTcp(s, 3000);
            if (mapped != null) {
                current = new Link(s, true, cur.endpoint(), mapped);
                if (!mapped.equals(cur.mapped())) onReady.accept(current); // 映射漂移：更新权威展示地址
                return true;
            }
            abandon(s);
        } catch (Exception ignored) {
            // 重连失败：交由调用方重建流程处理
        }
        return false;
    }

    /**
     * 出站长连接的 TCP 级存活检测：短超时读，超时（无数据且无复位）=存活，EOF/IO 异常=死亡。
     * connect-only 模式连接上无应用层流量，常态即「无数据可读」；已关闭的旧连接读立即得 EOF。
     */
    private static boolean isAlive(Socket s) {
        if (s == null || s.isClosed() || !s.isConnected()) return false;
        try {
            s.setSoTimeout(500);
            return s.getInputStream().read() >= 0; // 有数据到达（预期外但证明链路存活）；-1 为 EOF，链路已死
        } catch (java.net.SocketTimeoutException e) {
            return true; // 无数据无复位：连接存活，沿途 NAT 映射随之存活
        } catch (Exception e) {
            return false; // 复位/IO 异常：链路已死
        }
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
     * （allowStun=false 时跳过，用于退避期），全部失败回退公共出站端点。成功登记为当前链路
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
                    persistStunServer(addr);
                    return install(new Link(p.socket(), true, addr, p.mapped()));
                }
            }
            if (System.currentTimeMillis() - probeFailAt > 300_000) {
                // 刚进入新的退避期才告警（含首次）：避免每个保活周期重复刷屏
                Logs.warn(Logs.STUN, "任务[" + taskName + "] 无可用STUN-over-TCP服务器，回退端口保留模式"
                        + "(公共端点出站维持CGNAT映射，展示端口取同端口UDP STUN估计、入站不保证可达；登记可用TCP STUN服务器可获得精确映射)");
            }
            probeFailAt = System.currentTimeMillis();
        }
        for (String[] ep : outboundCandidates()) {
            Socket s;
            try {
                s = StunClient.connectOutboundEx(ep[0], Integer.parseInt(ep[1]), localPort, 2500);
            } catch (Exception e) {
                // 端点失效原因告警仅首次：端点被网络策略拦截时无法从外部观察，这是定位关键
                if (warnedEndpoints.add(ep[0] + ":" + ep[1])) {
                    Logs.warn(Logs.STUN, "任务[" + taskName + "] TCP出站端点不可用("
                            + ep[0] + ":" + ep[1] + "): " + e.getMessage());
                }
                continue;
            }
            outEndpoint = ep[0] + ":" + ep[1];
            warnedEndpoints.remove(outEndpoint); // 端点恢复可用：复位告警以便下次失效再提示
            return install(new Link(s, false, outEndpoint, null));
        }
        return null;
    }

    /** 持久化最近成功的 STUN/TCP 服务器：容器/任务重启后优先复用，免去逐候选探测的启动延迟 */
    private void persistStunServer(String addr) {
        try {
            Database.setConfig("stun.tcpLastServer", addr);
        } catch (Exception ignored) {
            // 写入失败不影响主流程：运行期内仍有内存态 tcpStunServer 兜底
        }
    }

    /** STUN-over-TCP 候选：上次成功服务器 → 持久化的上次成功（重启快路径） → 配置服务器 → 内置列表（实测可达优先） → 维护列表 */
    private LinkedHashSet<String> stunCandidates() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (tcpStunServer != null) set.add(tcpStunServer);
        try {
            String last = Database.getConfig("stun.tcpLastServer");
            if (last != null && last.lastIndexOf(':') > 0) set.add(last);
        } catch (Exception ignored) {
            // 读取失败：按常规顺序探测
        }
        set.add(stunHost + ":" + stunPort);
        // 内置列表按电信 CGNAT 实测可达排序，优先于维护列表：失效候选探测每个耗时数秒，
        // 维护列表历史种子多为 3478 端口（本类运营商封锁），排在后面仅作其他网络兜底
        for (String[] s : StunClient.TCP_STUN_SERVERS) set.add(s[0] + ":" + s[1]);
        for (String[] s : StunServerService.tcpServers()) set.add(s[0] + ":" + s[1]);
        return set;
    }

    /** 出站端点候选：最近成功的优先 */
    private List<String[]> outboundCandidates() {
        List<String[]> list = new ArrayList<>();
        if (outEndpoint != null) {
            int ci = outEndpoint.lastIndexOf(':');
            list.add(new String[]{outEndpoint.substring(0, ci), outEndpoint.substring(ci + 1)});
        }
        for (String[] ep : StunClient.KEEPALIVE_TCP_ENDPOINTS) {
            if (outEndpoint == null || !outEndpoint.equals(ep[0] + ":" + ep[1])) list.add(ep);
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
     * 失败转公共出站端点；每次尝试恰好消耗一个备用 socket（连接失败的 socket 无法复用），
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
        for (String[] ep : outboundCandidates()) {
            Socket spare = spares.poll();
            if (spare == null) return null;
            Link link = connectOutbound(spare, ep);
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
        abandon(s);
        return null;
    }

    /**
     * 用预绑定 socket 重连出站端点：TCP 连接建立（握手完成）即映射刷新成功，通过返回链路，
     * 失败返回 null（socket 已废弃）。连接上不做应用层交互（域名解析按其设计走 UDP 53，
     * 由系统解析器承担）。
     */
    private Link connectOutbound(Socket s, String[] ep) {
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName(ep[0]), Integer.parseInt(ep[1])), 3000);
            outEndpoint = ep[0] + ":" + ep[1];
            return new Link(s, false, outEndpoint, null);
        } catch (Exception ignored) {
            // 端点不可达：换下一个端点
        }
        abandon(s);
        return null;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
            // 关闭失败不影响后续流程
        }
    }

    /**
     * 废弃出站连接：SO_LINGER(0) 使 close 发 RST 复位而非 FIN 四次挥手，本端不进 TIME_WAIT。
     * 普通 FIN 关闭会把 (本地端口, 端点) 四元组占用 60 秒（TIME_WAIT），期间以同一本地端口
     * 重连同一端点会被内核拒绝（"Cannot assign requested address"），是链路失效后长时间
     * 重建失败的根源；RST 复位后同端点立即可重连（端口保留模式下本地端口不变，映射地址不变）。
     */
    private static void abandon(Socket s) {
        try {
            s.setSoLinger(true, 0);
        } catch (Exception ignored) {
            // 连接可能已死无法设置 linger：直接关闭即可
        }
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

    /** 关闭当前链路（幂等，RST 复位以释放四元组），备用 socket 保留 */
    void closeLink() {
        Link cur = current;
        current = null;
        if (cur != null) {
            abandon(cur.socket());
        }
    }

    /** 释放全部资源（任务停止时调用；备用 socket 未连接，普通关闭即可） */
    void closeAll() {
        closeLink();
        Socket s;
        while ((s = spares.poll()) != null) {
            closeQuietly(s);
        }
    }
}
