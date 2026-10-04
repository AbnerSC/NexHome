package com.nexhome.module.stun;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;

import java.io.InputStream;
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
 *   <li>STUN-over-TCP 长连接（配置的 + 维护列表 + 内置候选，排除单事务型）：取得<b>精确</b>映射地址</li>
 *   <li><b>双链路模式</b>（参考 natmap，首选升级路径）：出站保活连接与映射探测<b>分离</b>——
 *       从本地端口连接公共端点（qq/baidu 等）作为长寿命保活链路维持 NAT 映射，再用同端口
 *       向 STUN/TCP 服务器新建<b>短连接</b>探测一次映射地址后即弃（即连即用，<b>单事务型服务器
 *       完全可用</b>）。全锥/受限锥 NAT（EIM 端口保持）下同源端口的出站映射端口与目标无关，
 *       探测地址即保活链路的真实映射；双源（两台不同 STUN 服务器）探测一致才确认为精确地址，
 *       不一致为对称型特征（探测地址不代表保活链路映射）退端口保留展示</li>
 *   <li>公共出站端点（端口保留模式兑底）：无任何可用 STUN/TCP 服务器时，出站长连接仅维持
 *       运营商 CGNAT 上的 TCP 映射，连接上不做应用层交互（TCP 握手完成即双向链路验证；
 *       域名解析按其设计走 UDP 53，不占用 TCP 连接）。端点须为非 DNS 端口的透传服务：
 *       实测运营商 CGNAT 对 53/TCP 透明拦截（连接能建立但终结在 CGNAT、查询无响应），
 *       此类映射不接受入站；外网映射端口无法直接探测，由运行器取同本地端口的 UDP STUN
 *       映射尽力估计展示（实测同本地端口 TCP/UDP 外部端口并不相同，入站不保证可达）</li>
 * </ol>
 * 链路死亡时优先复用预绑定的备用出站 socket（同端口、未连接，监听开启前预绑）立即重连，
 * 实现<b>零监听中断</b>的链路重建；备用耗尽才由调用方「弹跳」（关监听→重建→重开监听）。
 * STUN/TCP 候选整体失败后退避 300 秒（期间只用公共出站端点），避免每个保活周期刷屏。
 */
final class TcpPunch {

    /**
     * 一条保活链路：连接 + 模式 + 端点 + 精确映射地址（端口保留模式为 null）+ 建立时刻。
     * <ul>
     *   <li>viaStun=true 且 split=false：STUN 长连接，socket 即 STUN 连接，映射地址可反复交互刷新</li>
     *   <li>viaStun=true 且 split=true：双链路（natmap 式），socket 为公共端点保活连接，
     *       mapped 为同端口 STUN 短连接探测所得（即连即弃，不可在 socket 上刷新）；
     *       映射随保活连接存活而恒定，保活连接死亡重建后需重探测刷新</li>
     *   <li>viaStun=false：端口保留模式（无可用 STUN/TCP），mapped 恒为 null</li>
     * </ul>
     */
    record Link(Socket socket, boolean viaStun, String endpoint, String mapped, long at, boolean split) {
        Link(Socket socket, boolean viaStun, String endpoint, String mapped, long at) {
            this(socket, viaStun, endpoint, mapped, at, false);
        }
    }

    /** 新链路宽限期：建立/重建刚完成交互验证，期内再交互只会白白翻动连接（重建后立即自测失败的根源） */
    private static final long FRESH_GRACE_MS = 8_000;

    private final String taskName;
    private final String stunHost;
    private final int stunPort;
    /** 出站源 IP（null=通配绑定）：通配绑定被拒时退回绑定该具体地址（Windows 监听后解锁同端口绑定） */
    private final String srcIp;
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
    /** 单事务型 STUN/TCP 服务器（响应后即 RST 断连，链路活不过一个保活周期，映射端口随轮换漂移无法稳定入站），降权改选持久型 */
    private final Set<String> singleTx = ConcurrentHashMap.newKeySet();
    /** 当前链路已完成的保活交互次数：为 0 即死亡 = 单事务型服务器特征（从未存活过一个保活周期） */
    private volatile int currentKeepalives;
    /** 单事务服务器黑名单持久化键（重启后仍生效，启动建链不再重选注定握不住映射的服务器） */
    private static final String SINGLE_TX_KEY = "stun.tcpSingleTx";
    /** 最近成功的双链路探测服务器（host:port），重建/重探测时优先复用 */
    private volatile String probeServer;
    /** 最近一次双链路精确映射地址：独立于链路对象跟踪（链路死亡/关闭后仍保留，供重建时比对是否变化） */
    private volatile String lastSplitMapped;
    /** 双链路探测服务器持久化键（重启后优先复用，免去逐候选探测） */
    private static final String PROBE_SERVER_KEY = "stun.tcpProbeServer";

    TcpPunch(String taskName, String srcIp, String stunHost, int stunPort, Consumer<Link> onReady) {
        this.taskName = taskName;
        this.srcIp = srcIp;
        this.stunHost = stunHost;
        this.stunPort = stunPort;
        this.onReady = onReady;
        singleTx.addAll(loadPersistedSingleTx());
        try {
            String v = Database.getConfig(PROBE_SERVER_KEY);
            if (v != null && v.lastIndexOf(':') > 0) probeServer = v;
        } catch (Exception ignored) {
            // 读取失败不影响主流程：运行期探测成功后会重新写入
        }
    }

    /** 读取持久化的单事务服务器黑名单（逗号分隔），读取失败返回空集 */
    private static Set<String> loadPersistedSingleTx() {
        try {
            String v = Database.getConfig(SINGLE_TX_KEY);
            Set<String> s = ConcurrentHashMap.newKeySet();
            if (v != null && !v.isBlank()) {
                for (String x : v.split(",")) {
                    if (!x.isBlank()) s.add(x.trim());
                }
            }
            return s;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** 端口保留模式：展示地址语义为「出口IP:本地端口」（外部端口=本地源端口假设） */
    boolean addrPresumed() {
        return addrPresumed;
    }

    /** 当前链路是否双链路模式（natmap 式：出站端点保活 + STUN 短连接探测的精确映射） */
    boolean splitActive() {
        Link c = current;
        return c != null && c.split();
    }

    /** STUN/TCP 候选是否已过退避期（整体失败 300 秒后允许再试） */
    boolean allowStunNow() {
        return System.currentTimeMillis() - probeFailAt > 300_000;
    }

    /**
     * 当前链路交互一次（保活 + 存活验证）。
     * 新链路宽限：建立/重建刚成功即完成过交互验证，期内直接返回存活，不重复翻动连接。
     * STUN 精确模式：<b>存活优先</b>——先做 TCP 级存活检测（短超时读：EOF/复位=死亡，超时=存活）。
     * 连接存活时在<b>原连接</b>上做刷新交互：映射挂在原四元组上，外部端口恒定不漂移，
     * 发包本身即刷新沿途 NAT；服务器对二次请求无响应但连接仍存活时视为映射随连接保活；
     * 仅当连接确已死亡（实测公共服务器多为单事务型，响应后约 1 秒内 RST/FIN 断开，
     * 2026-08 实测 stun.nextcloud.com 1 秒复位；或网络切换）才从同本地端口轮换新建连接，
     * 优先消耗预绑定备用 socket，备用耗尽回退新建绑定。轮换失败返回 false，由调用方走备用/弹跳重建流程。
     * 出站端点模式（端口保留）：出站长连接存活即运营商 CGNAT 映射存活——先做 TCP 级存活检测
     * （短超时读：EOF/复位=死亡，超时=存活），存活直接返回，不消耗备用 socket；检测死亡时
     * <b>先 RST 废弃旧连接释放四元组</b>，再用预绑定备用 socket 同端口轮换新建出站连接（备用耗尽回退新建绑定）。
     * 若旧连接未释放就连同一端点，四元组与存活连接完全重复，内核必然拒绝（保活/自测永远失败的根源）；
     * 端点可能为单事务/短空闲超时类型，死亡轮换是该模式常态（静默完成不打日志），
     * 备用与新建均失败或端点全部不可达返回 false，由调用方走失败重建流程（弹跳补充备用）。
     */
    synchronized boolean keepaliveOnce() {
        Link cur = current;
        if (cur == null) return false;
        if (System.currentTimeMillis() - cur.at() < FRESH_GRACE_MS) return true; // 新建链路刚验证过，无需再交互
        if (cur.viaStun() && !cur.split()) { // split 双链路的 socket 是端点保活连接（非 STUN），走下方端点分支
            if (isAlive(cur.socket())) {
                // 连接存活：在原连接上刷新交互（映射挂在原四元组上，外部端口恒定，避免重建即漂移）
                String mapped = StunClient.bindingOverTcp(cur.socket(), 3000);
                if (mapped != null) {
                    current = new Link(cur.socket(), true, cur.endpoint(), mapped, cur.at());
                    currentKeepalives++;
                    if (!mapped.equals(cur.mapped())) onReady.accept(current); // 映射漂移：更新权威展示地址
                    return true;
                }
                if (isAlive(cur.socket())) {
                    currentKeepalives++;
                    return true; // 服务器不应答二次请求但连接存活：映射随连接保活，地址不变
                }
            }
            // 连接已死（服务器单事务断开/网络切换）：同本地端口轮换新建连接重建映射
            if (currentKeepalives == 0) markSingleTx(cur.endpoint()); // 从未存活过一个保活周期：单事务型服务器
            String ep = cur.endpoint();
            if (singleTx.contains(ep)) {
                for (String c : stunCandidates()) { ep = c; break; } // 改选持久型候选，避免反复轮换到单事务服务器
            }
            int localPort = cur.socket().getLocalPort();
            abandon(cur.socket());
            current = null;
            Link rotated = rotateStun(localPort, ep);
            if (rotated != null) {
                downWarned = false;
                tcpStunServer = ep;
                persistStunServer(ep);
                if (!rotated.mapped().equals(cur.mapped())) onReady.accept(rotated); // 映射漂移：更新权威展示地址
                return true;
            }
            return false; // 轮换失败（服务器不可达/无响应）：由调用方走备用/弹跳重建流程
        }
        if (isAlive(cur.socket())) {
            currentKeepalives++;
            return true; // 保活长连接存活：CGNAT 映射存活，无需轮换（双链路/端口保留的映射地址均不变）
        }
        // 链路死亡：先废弃旧连接释放四元组，再轮换新建（当前端点优先，失败依次尝试其余候选）。
        // 双链路模式（split）下轮换成功后需重探测刷新精确映射（旧映射随旧连接失效）；
        // 端口保留模式在非退避期顺带尝试探测升级为双链路（地址从估计值精确化）
        int localPort = cur.socket().getLocalPort();
        if (cur.split() && cur.mapped() != null) lastSplitMapped = cur.mapped(); // 供重建后比对是否变化
        abandon(cur.socket());
        current = null;
        for (String[] ep : outboundCandidates()) {
            Socket spare = spares.poll();
            Socket s = spare != null ? spare : freshBound(localPort); // 备用耗尽：回退新建绑定，避免备用耗尽后永远失败
            if (s == null) return false;
            Link link = connectOutbound(s, ep);
            if (link != null) {
                return installEndpointLink(link, localPort) != null;
            }
        }
        return false;
    }

    /**
     * 登记端点链路并按需升级为双链路：曾取得过精确映射（lastSplitMapped 非空）或端口保留模式下
     * 非退避期重探测一次精确映射——成功则升级/维持双链路（映射变化时回调调用方刷新展示），
     * 失败时曾为双链路则保留旧探测值（下轮重探测修正），否则退回端口保留估计展示。
     */
    private Link installEndpointLink(Link base, int localPort) {
        String prevMapped = lastSplitMapped;
        String mapped = null;
        if (allowStunNow()) {
            mapped = probeMappedOnce(localPort, true); // 监听已开：优先消耗预绑备用 socket
        }
        if (mapped != null) {
            lastSplitMapped = mapped;
            Link split = new Link(base.socket(), true, base.endpoint(), mapped,
                    System.currentTimeMillis(), true);
            current = split;
            addrPresumed = false;
            downWarned = false;
            currentKeepalives = 0;
            if (!mapped.equals(prevMapped)) onReady.accept(split); // 映射变化/升级：更新权威展示地址
            return split;
        }
        if (prevMapped != null) {
            // 退避期内探测不可用：保留旧探测值（地址可能已随旧连接失效，退避期结束后下轮重探测修正）
            Link split = new Link(base.socket(), true, base.endpoint(), prevMapped,
                    System.currentTimeMillis(), true);
            current = split;
            addrPresumed = false;
            downWarned = false;
            currentKeepalives = 0;
            return split;
        }
        current = base;
        return base;
    }

    /**
     * 双链路重探测：从同本地端口向 STUN/TCP 服务器新建短连接探测一次精确映射。
     * 优先用上次探测成功的服务器（快路径），失败顺延下一候选（限 2 个控制耗时）。
     * 探测连接即连即弃（RST 释放），不占用保活链路。
     */
    private String probeMappedOnce(int localPort, boolean useSpare) {
        int tried = 0;
        for (String addr : stunProbeCandidates()) {
            if (tried++ >= 2) break; // 限制耗时：重探测是保活周期的附带动作，不占过多预算
            String m = probeOnce(addr, localPort, useSpare);
            if (m != null) {
                probeServer = addr;
                persistProbeServer(addr);
                return m;
            }
        }
        return null;
    }

    /**
     * 单次短连接探测：新建同端口连接 → 绑定交互 → RST 释放，失败返回 null。
     * useSpare=true 时优先消耗预绑备用 socket（监听已开、新建绑定受限的场景），
     * 否则新建绑定（监听未开的 establish 阶段，spare 留给监听开启后用）。
     */
    private String probeOnce(String addr, int localPort, boolean useSpare) {
        Socket s = useSpare ? spares.poll() : null;
        if (s == null) {
            s = freshBound(localPort);
        }
        if (s == null) return null;
        try {
            int ci = addr.lastIndexOf(':');
            s.connect(new InetSocketAddress(InetAddress.getByName(addr.substring(0, ci)),
                    Integer.parseInt(addr.substring(ci + 1))), 2500);
            return StunClient.bindingOverTcp(s, 2500);
        } catch (Exception ignored) {
            // 探测失败：换下一候选
        } finally {
            abandon(s); // RST 复位即弃：单事务型服务器本就活不长，长连接型也不作保活用
        }
        return null;
    }

    /** 持久化最近成功的双链路探测服务器：重启后优先复用 */
    private void persistProbeServer(String addr) {
        try {
            Database.setConfig(PROBE_SERVER_KEY, addr);
        } catch (Exception ignored) {
            // 写入失败不影响主流程：运行期内仍有内存态 probeServer 兜底
        }
    }

    /**
     * STUN 精确模式轮换重建：旧连接死亡后从同一本地端口新建出站连接做一次绑定交互。
     * 优先消耗预绑定备用 socket（同端口已绑好），备用耗尽回退新建绑定（受内核同端口容量约束可能失败）。
     * 本地端口必须与监听一致（CGNAT 映射挂在出站五元组上）。失败返回 null（由调用方走重建流程）。
     */
    private Link rotateStun(int localPort, String endpoint) {
        Socket s = spares.poll();
        if (s == null) s = freshBound(localPort);
        if (s == null) return null;
        try {
            int ci = endpoint.lastIndexOf(':');
            s.connect(new InetSocketAddress(endpoint.substring(0, ci),
                    Integer.parseInt(endpoint.substring(ci + 1))), 3000);
            String mapped = StunClient.bindingOverTcp(s, 3000);
            if (mapped != null) {
                Link link = new Link(s, true, endpoint, mapped, System.currentTimeMillis());
                current = link;
                currentKeepalives = 0;
                return link;
            }
        } catch (Exception ignored) {
            // 轮换失败：交由调用方重建流程处理（备用重连/弹跳）
        }
        abandon(s);
        return null;
    }

    /** 新建出站 socket 并绑定指定本地端口（备用耗尽时兑底），通配被拒时退回具体源地址，绑定失败返回 null */
    private Socket freshBound(int localPort) {
        try {
            Socket s = new Socket();
            StunClient.bindOutbound(s, srcIp, localPort);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 出站长连接的 TCP 级存活检测：短超时读，超时（无数据且无复位）=存活，EOF/IO 异常=死亡。
     * connect-only 模式连接上无应用层流量，常态即「无数据可读」；已关闭的旧连接读立即得 EOF。
     */
    private static boolean isAlive(Socket s) {
        if (s == null || s.isClosed() || !s.isConnected()) return false;
        try {
            s.setSoTimeout(500);
            InputStream in = s.getInputStream();
            if (in.read() < 0) return false; // -1 为 EOF，链路已死
            // 有数据到达（多为迟到的上一事务响应，预期外但证明链路存活）：全部读掉保持流对齐，
            // 只吞 1 字节会使后续绑定交互的报文头解析错位（被误判为交互无响应）
            int avail;
            while ((avail = in.available()) > 0) {
                if (in.readNBytes(avail).length < avail) break;
            }
            return true;
        } catch (java.net.SocketTimeoutException e) {
            return true; // 无数据无复位：连接存活，沿途 NAT 映射随之存活
        } catch (Exception e) {
            return false; // 复位/IO 异常：链路已死
        }
    }

    /**
     * 标记单事务型 STUN/TCP 服务器（首次告警）：持久化黑名单并清空最近成功记录，避免快速路径重选。
     * 黑名单仅约束<b>长连接保活链路</b>的选择（单事务连接活不过一个保活周期、映射随轮换漂移）；
     * 双链路的短连接探测不受限（即连即用即弃，单事务型完全可用，见 stunProbeCandidates）。
     */
    private void markSingleTx(String ep) {
        if (singleTx.add(ep)) {
            Logs.warn(Logs.STUN, "任务[" + taskName + "] STUN/TCP服务器 " + ep
                    + " 为单事务型(响应后即断连)，不宜作保活长连接(映射随轮换漂移)，降权改选持久型；"
                    + "仍可用作双链路短连接探测");
            try {
                Database.setConfig(SINGLE_TX_KEY, String.join(",", singleTx));
            } catch (Exception ignored) {
                // 持久化失败不影响主流程：运行期内仍有内存态黑名单兜底
            }
        }
        if (ep.equals(tcpStunServer)) tcpStunServer = null;
    }

    /**
     * 单事务判定：绑定响应后稍候再做存活检测——单事务型服务器响应后即 RST 断连（实测约 1 秒），
     * 持久型服务器保持连接存活。只有持久型才值得作为保活链路（映射外部端口稳定、可入站）。
     */
    private static boolean diedQuickly(Socket s) {
        try {
            Thread.sleep(1600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !isAlive(s);
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
        return establish(localPort, allowStun, Long.MAX_VALUE);
    }

    /**
     * 全新建立出站链路，语义同 {@link #establish(int, boolean)}，另加时间预算：
     * STUN/TCP 候选逐个探测（被封锁/失效候选每个都要等满连接与交互超时，全表 20+ 个候选
     * 总耗时可达数分钟），超预算即跳出候选循环直接回退公共出站端点（仅 6 个、耗时有界），
     * 避免把弹跳窗口与调用线程拖到分钟级。
     */
    synchronized Link establish(int localPort, boolean allowStun, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        if (allowStun) {
            for (String addr : stunCandidates()) {
                if (System.currentTimeMillis() >= deadline) break; // 预算耗尽：不再逐个等超时，直接回退公共端点
                int ci = addr.lastIndexOf(':');
                StunClient.TcpProbe p = StunClient.probeOverTcp(addr.substring(0, ci),
                        Integer.parseInt(addr.substring(ci + 1)), srcIp, localPort, 2500);
                if (p != null) {
                    if (diedQuickly(p.socket())) {
                        // 绑定有响应但连接随即断开：单事务服务器，注定握不住映射（建立的地址也在数秒内失效），
                        // 拉黑换下一候选，避免每次（重）启动都先展示一个马上失效的映射地址
                        markSingleTx(addr);
                        abandon(p.socket());
                        continue;
                    }
                    tcpStunServer = addr;
                    probeFailAt = 0;
                    persistStunServer(addr);
                    return install(new Link(p.socket(), true, addr, p.mapped(), System.currentTimeMillis()));
                }
            }
            // natmap 双链路升级：长连接候选全败后，改用「短连接探测」——同端口向 STUN/TCP
            // 服务器即连即用即弃，单事务型服务器完全可用；双源一致才作为精确映射（见 probeSplitMapped）
            String splitMapped = null;
            if (System.currentTimeMillis() < deadline - 15_000) { // 预留端点连接预算，避免启动拖到分钟级
                splitMapped = probeSplitMapped(localPort, deadline - 15_000);
            }
            if (splitMapped != null) {
                lastSplitMapped = splitMapped;
            } else {
                if (System.currentTimeMillis() - probeFailAt > 300_000) {
                    // 刚进入新的退避期才告警（含首次）：避免每个保活周期重复刷屏
                    Logs.warn(Logs.STUN, "任务[" + taskName + "] 长连接与短连接探测均无可用STUN-over-TCP服务器，"
                            + "回退端口保留模式(公共端点出站维持CGNAT映射，展示端口取同端口UDP STUN估计、"
                            + "入站不保证可达；登记可用TCP STUN服务器可获得精确映射)");
                }
                probeFailAt = System.currentTimeMillis();
            }
            return establishEndpoint(localPort, splitMapped);
        }
        return establishEndpoint(localPort, null);
    }

    /** 端点连接段（双链路保活段/端口保留兜底）：依次连接公共出站端点，探测到精确映射则升级双链路 */
    private Link establishEndpoint(int localPort, String splitMapped) {
        for (String[] ep : outboundCandidates()) {
            Socket s;
            try {
                s = StunClient.connectOutboundEx(ep[0], Integer.parseInt(ep[1]), srcIp, localPort, 2500);
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
            if (splitMapped != null) {
                // 双链路（natmap 式）：端点连接仅负责保活，展示地址用同端口 STUN 短连接探测所得
                // （EIM 端口保持 NAT 下同源端口的出站映射与目标无关，二者为同一映射）
                return install(new Link(s, true, outEndpoint, splitMapped,
                        System.currentTimeMillis(), true));
            }
            return install(new Link(s, false, outEndpoint, null, System.currentTimeMillis()));
        }
        return null;
    }

    /**
     * 双链路探测（natmap 式）：从同本地端口向 STUN/TCP 服务器新建短连接探测映射地址，
     * 依次取两台不同服务器各探测一次——映射一致（EIM 端口保持特征）确认为保活链路的精确映射；
     * 不一致是对称型特征（映射随目标变化，探测地址不代表保活连接的真实映射）返回 null 退端口保留。
     * 仅一台可达时接受单源结果（有总比无好，对称型下端口保留的 UDP 估计同样不准）。
     */
    private String probeSplitMapped(int localPort, long deadline) {
        String first = null;
        for (String addr : stunProbeCandidates()) {
            if (System.currentTimeMillis() >= deadline) break;
            String m = probeOnce(addr, localPort, false); // 监听未开：新建绑定即可，spare 留给监听开启后
            if (m == null) continue;
            if (first == null) {
                first = m;
                probeServer = addr;
                persistProbeServer(addr);
                continue;
            }
            if (m.equals(first)) {
                Logs.info(Logs.STUN, "任务[" + taskName + "] 双源STUN探测映射一致(端口保持/EIM特征): "
                        + first + "，作为保活链路精确映射");
                return first;
            }
            Logs.warn(Logs.STUN, "任务[" + taskName + "] 不同STUN服务器探测映射不一致(对称型NAT特征): "
                    + first + " vs " + m + "，探测地址不代表保活链路映射，退端口保留模式展示");
            return null;
        }
        if (first != null) {
            Logs.info(Logs.STUN, "任务[" + taskName + "] STUN短连接探测: " + first
                    + "（仅单源可达，未做双源校验）");
        }
        return first;
    }

    /** 持久化最近成功的 STUN/TCP 服务器：容器/任务重启后优先复用，免去逐候选探测的启动延迟 */
    private void persistStunServer(String addr) {
        try {
            Database.setConfig("stun.tcpLastServer", addr);
        } catch (Exception ignored) {
            // 写入失败不影响主流程：运行期内仍有内存态 tcpStunServer 兜底
        }
    }

    /**
     * 双链路探测候选（短连接即连即弃）：上次探测成功服务器优先（重启快路径）→ 持久型优先的
     * 长连接候选 → 单事务型兜底。单事务型服务器在本模式下完全可用（不要求保持连接）。
     */
    private LinkedHashSet<String> stunProbeCandidates() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (probeServer != null) all.add(probeServer);
        all.addAll(stunCandidates());
        all.addAll(singleTx); // 单事务型兜底：长连接黑名单仅约束保活链路选择，探测不受限
        return all;
    }

    /** STUN-over-TCP 候选：上次成功服务器 → 持久化的上次成功（重启快路径） → 配置服务器 → 内置列表（实测可达优先） → 维护列表 */
    private LinkedHashSet<String> stunCandidates() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (tcpStunServer != null) all.add(tcpStunServer);
        try {
            String last = Database.getConfig("stun.tcpLastServer");
            if (last != null && last.lastIndexOf(':') > 0) all.add(last);
        } catch (Exception ignored) {
            // 读取失败：按常规顺序探测
        }
        all.add(stunHost + ":" + stunPort);
        // 内置列表按电信 CGNAT 实测可达排序，优先于维护列表：失效候选探测每个耗时数秒，
        // 维护列表历史种子多为 3478 端口（本类运营商封锁），排在后面仅作其他网络兜底
        for (String[] s : StunClient.TCP_STUN_SERVERS) all.add(s[0] + ":" + s[1]);
        for (String[] s : StunServerService.tcpServers()) all.add(s[0] + ":" + s[1]);
        // 单事务型服务器降权：链路活不过一个保活周期、映射端口随轮换漂移无法稳定入站，仅在无其他候选时兜底
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for (String a : all) if (!singleTx.contains(a)) kept.add(a);
        return kept.isEmpty() ? all : kept;
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
        currentKeepalives = 0;
        onReady.accept(link);
        return link;
    }

    /**
     * 链路死亡后重连：优先消耗预绑定备用 socket（监听不中断），STUN 优先（非退避期且有过成功），
     * 失败转公共出站端点；每次尝试恰好消耗一个备用 socket（连接失败的 socket 无法复用），
     * 备用耗尽或全部失败返回 null（由调用方决定弹跳重建）。
     */
    synchronized Link reconnect(int localPort, boolean allowStun) {
        if (allowStun && tcpStunServer != null && !singleTx.contains(tcpStunServer)) {
            Socket spare = spares.poll();
            if (spare != null) {
                Link link = connectStun(spare, tcpStunServer);
                if (link != null) return install(link);
            }
        }
        for (String[] ep : outboundCandidates()) {
            Socket spare = spares.poll();
            if (spare == null) return null;
            Link link = connectOutbound(spare, ep);
            if (link != null) return installEndpointLink(link, localPort);
        }
        return null;
    }

    /**
     * 弹跳后用预绑定备用 socket 逐候选彻底重建：依次尝试全部 STUN/TCP 候选（非退避期）与
     * 公共出站端点，每次尝试恰好消耗一个备用 socket（失败作废）。与 {@link #reconnect} 的区别：
     * reconnect 仅尝试最近成功的 STUN 服务器（快速路径），本方法遍历全部候选——弹跳刚在
     * 监听重开前的端口空闲窗口内完成预绑、备用充足，适合做一次彻底重建而不再占用监听端口。
     * 备用耗尽或全部失败返回 null（由调用方在下次弹跳时重新预绑再试）。
     */
    synchronized Link reconnectAll(int localPort, boolean allowStun) {
        if (allowStun) {
            for (String addr : stunCandidates()) {
                Socket spare = spares.poll();
                if (spare == null) return null;
                Link link = connectStun(spare, addr);
                if (link != null) {
                    tcpStunServer = addr;
                    persistStunServer(addr);
                    return install(link);
                }
            }
            // 弹跳窗口备用充足：顺带做一次双源探测（长连接候选全败时升级双链路，单事务型可用）
            String splitMapped = probeSplitMapped(localPort, System.currentTimeMillis() + 10_000);
            if (splitMapped != null) {
                lastSplitMapped = splitMapped;
                return establishEndpoint(localPort, splitMapped);
            }
            probeFailAt = System.currentTimeMillis(); // 全部候选失败：进入退避期，快速路径期间不再逐个探测
        }
        for (String[] ep : outboundCandidates()) {
            Socket spare = spares.poll();
            if (spare == null) return null;
            Link link = connectOutbound(spare, ep);
            if (link != null) return installEndpointLink(link, localPort);
        }
        return null;
    }

    /** 用预绑定 socket 重连指定 STUN/TCP 服务器：失败返回 null（socket 已关闭作废） */
    private Link connectStun(Socket s, String server) {
        try {
            int ci = server.lastIndexOf(':');
            s.connect(new InetSocketAddress(server.substring(0, ci),
                    Integer.parseInt(server.substring(ci + 1))), 3000);
            String mapped = StunClient.bindingOverTcp(s, 3000);
            if (mapped != null) {
                probeFailAt = 0;
                return new Link(s, true, server, mapped, System.currentTimeMillis());
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
            return new Link(s, false, outEndpoint, null, System.currentTimeMillis());
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

    /**
     * 预绑定备用出站 socket：必须在监听开启前完成（LISTEN 占用端口后无法再绑定出站 socket）。
     * 数量由调用方按内核同端口通配绑定容量上限约束（超限时首个绑定即失败，继续绑定还会顶掉监听开启机会）；
     * 任一绑定失败即停止（端口已被占用/达到容量上限），任务降级为仅靠弹跳重建链路，不影响启动。
     */
    void openSpares(int localPort, int count) {
        for (int i = 0; i < count; i++) {
            try {
                Socket s = new Socket();
                StunClient.bindOutbound(s, srcIp, localPort);
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

    /** 废弃全部备用 socket：换本地端口重试时旧端口备用已作废，残留会被重连误用而从错误端口出站建映射 */
    void drainSpares() {
        Socket s;
        while ((s = spares.poll()) != null) {
            closeQuietly(s);
        }
    }

    /** 释放全部资源（任务停止时调用；备用 socket 未连接，普通关闭即可） */
    void closeAll() {
        closeLink();
        drainSpares();
    }
}
