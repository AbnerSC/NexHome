package com.nexhome.module.stun;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * STUN 协议客户端（RFC 5389/8489 Binding 请求/响应 + RFC 3489/RFC 5780 风格 NAT 类型探测）。
 * <p>
 * 纯 JDK Socket 实现，不依赖第三方库。
 * <p>
 * <b>NAT 类型与穿透成功率说明（界面同步展示）：</b>
 * <ul>
 *   <li>Open Internet / 端口保持型 NAT：外网可直达或映射端口不变，穿透成功率较高（入站仍受路由器防火墙策略约束）</li>
 *   <li>Full Cone（全锥形）：任意主机可发入，成功率高</li>
 *   <li>Restricted / Port Restricted（受限锥形）：需要对方先"打洞"握手，配合保活可用，成功率中等</li>
 *   <li>Symmetric（对称型）：每次出站映射端口不同，纯 STUN 无法穿透，需要中继服务器，成功率极低</li>
 * </ul>
 * 注意：STUN 只负责建立/探测 NAT 映射；映射端口上的数据转发由 StunRunner 完成。
 */
public final class StunClient {

    /** STUN Binding Request */
    private static final int MSG_BINDING_REQUEST = 0x0001;
    /** STUN Binding Success Response */
    private static final int MSG_BINDING_RESPONSE = 0x0101;
    /** RFC 5389 魔术饼干 */
    private static final int MAGIC_COOKIE = 0x2112A442;

    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_CHANGE_REQUEST = 0x0003;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    private static final int ATTR_CHANGED_ADDRESS = 0x0005;
    private static final int ATTR_OTHER_ADDRESS = 0x802C;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 支持 STUN-over-TCP 的公共 STUN 服务器候选（host:port），配置的服务器仅支持 UDP 时兜底。
     * TCP 穿透优先借助支持 TCP 的 STUN 服务器出站，才能在运营商 CGNAT 上建立可探测端口的 TCP 映射。
     * 实测部分运营商 CGNAT 封锁 3478/TCP 出站且境内无公共 TCP STUN 服务，本列表全不可用时
     * 自动回退「端口保留模式」（出站连 {@link #KEEPALIVE_TCP_ENDPOINTS} 建立并保活 CGNAT 映射，
     * 展示端口取同本地端口的 UDP STUN 映射）；若自建/发现可用的 TCP STUN 服务器，
     * 可在「STUN 服务器维护」中登记（tcp_support）获得精确映射。
     */
    public static final String[][] TCP_STUN_SERVERS = {
            {"stun.antisip.com", "3478"},
            {"stun.nextcloud.com", "443"},
            {"stun.nextcloud.com", "3478"},
            {"turn.cloudflare.com", "3478"},
            {"stun.freeswitch.org", "3478"},
    };
    
    /**
     * 公共出站连接端点（host:port）：无可用 STUN-over-TCP 服务器时的 TCP 穿透兜底。
     * 从本地端口出站连接端点即可在运营商 CGNAT 上建立 TCP 映射，出站长连接存活期间
     * 映射不回收（死亡时由调用方轮换新建连接维持）。外部映射端口由调用方取同本地端口
     * 的 UDP STUN 映射组装展示（实测本类运营商 CGNAT 对同本地端口的 TCP/UDP 分配相同外部端口）。
     * <p>
     * <b>端点必须是非 DNS 端口的透传服务</b>（qq.com:443/80 等，与 Lucky 的 TCP 通道保活
     * 服务器同路）：实测运营商 CGNAT 对 53/TCP 做透明拦截——连接能建立但终结在 CGNAT 上
     * （合法 DNS 查询永远无响应），此类映射不接受入站，外网无法主动连入；非 DNS 端口的
     * 连接真实穿透 CGNAT，映射方可入站。连接上不做任何应用层交互（域名解析按其设计走
     * UDP 53，由系统解析器承担，不占用 TCP 连接）。DNS 53 端点仅列末尾，供无拦截策略的网络兜底。
     */
    public static final String[][] KEEPALIVE_TCP_ENDPOINTS = {
            {"qq.com", "443"},
            {"qq.com", "80"},
            {"www.baidu.com", "443"},
            {"www.baidu.com", "80"},
            {"223.5.5.5", "53"},
            {"119.29.29.29", "53"}
    };

    /** STUN 探测结果：外网映射地址 + NAT 类型 */
    public record Result(String mappedAddress, String natType) {
    }

    /**
     * STUN-over-TCP 探测结果：外网映射地址 + 本地出站端口（绑定 0 时用于回填实际占用端口）
     * + 保持打开的探测连接（需长期保活维持运营商NAT映射时由调用方持有，不用时调用方负责关闭）。
     */
    public record TcpProbe(String mapped, int localPort, Socket socket) {
    }

    private StunClient() {
    }

    /**
     * 通过指定 socket 发送 Binding 请求，返回映射地址（ip:port）。
     * 该请求会在 NAT 上建立一条映射（即"打洞"），失败返回 null。
     */
    public static String bind(DatagramSocket socket, String stunHost, int stunPort, int timeoutMs) throws Exception {
        byte[] tid = new byte[12];
        RANDOM.nextBytes(tid);
        byte[] req = buildRequest(tid, false);
        BindingResponse resp = exchange(socket,
                new InetSocketAddress(InetAddress.getByName(stunHost), stunPort), req, tid, timeoutMs, 2);
        return resp == null ? null : resp.mapped;
    }

    /**
     * 只发送一次 Binding 请求，不等待响应（fire-and-forget 保活）。
     * <p>
     * 供与接收线程共用同一 socket 的保活场景使用：响应包由接收线程通过
     * {@link #parseBindingMapped} 解析，避免两个线程在同一个 socket 上竞争 receive。
     */
    public static void sendBindingRequest(DatagramSocket socket, String stunHost, int stunPort) throws Exception {
        byte[] tid = new byte[12];
        RANDOM.nextBytes(tid);
        byte[] req = buildRequest(tid, false);
        socket.send(new DatagramPacket(req, req.length, InetAddress.getByName(stunHost), stunPort));
    }

    /**
     * 解析入站报文：若是 STUN Binding 响应则返回映射地址（ip:port），否则返回 null。
     * 用于接收线程在区分业务数据的同时刷新映射地址。
     */
    public static String parseBindingMapped(byte[] data, int len) {
        if (data == null || len < 20) return null;
        if (readU16(data, 0) != MSG_BINDING_RESPONSE) return null;
        return extractMapped(data, len);
    }

    /** 从绑定响应报文中提取映射地址（支持 MAPPED-ADDRESS 与 XOR-MAPPED-ADDRESS） */
    private static String extractMapped(byte[] data, int len) {
        int msgLen = readU16(data, 2);
        int pos = 20;
        while (pos + 4 <= 20 + msgLen && pos + 4 <= len) {
            int attrType = readU16(data, pos);
            int attrLen = readU16(data, pos + 2);
            int valStart = pos + 4;
            if (valStart + attrLen > len) break;
            if (attrType == ATTR_MAPPED_ADDRESS) {
                return parseAddress(data, valStart, false);
            }
            if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                return parseAddress(data, valStart, true);
            }
            pos = valStart + ((attrLen + 3) & ~3);
        }
        return null;
    }

    /**
     * STUN-over-TCP 探测（兼容旧调用）：仅返回外网映射地址，探测连接用后即关闭，详见 {@link #probeOverTcp}。
     */
    public static String bindOverTcp(String stunHost, int stunPort, int localPort, int timeoutMs) {
        TcpProbe p = probeOverTcp(stunHost, stunPort, localPort, timeoutMs);
        if (p == null) return null;
        try {
            return p.mapped();
        } finally {
            try {
                p.socket().close();
            } catch (Exception ignored) {
                // 关闭探测连接失败不影响结果返回
            }
        }
    }

    /**
     * STUN-over-TCP 探测（RFC 5389 §7.1）：从指定本地端口向 STUN 服务器建立 TCP 连接
     * 并发送绑定请求，返回该 TCP 出口的外网映射地址、实际本地端口与<b>保持打开的探测连接</b>。
     * <p>
     * 出站连接会在沿途全部 NAT（含运营商 CGNAT）上建立真实 TCP 映射，
     * CGNAT 通常会改写外网端口，入站访问需以返回的映射地址为准。
     * 连接不关闭：实测该类映射的入站可达性依赖出站连接的活跃状态，连接关闭后映射很快失效；
     * 调用方应持有该连接作为保活长连接（周期复用发送绑定请求），不用时自行关闭。
     * 服务器不支持 STUN/TCP 或本地端口绑定失败时返回 null。
     */
    public static TcpProbe probeOverTcp(String stunHost, int stunPort, int localPort, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Math.max(localPort, 0)));
            int bound = s.getLocalPort();
            s.connect(new InetSocketAddress(InetAddress.getByName(stunHost), stunPort), timeoutMs);
            String mapped = exchangeTcpBinding(s, timeoutMs);
            if (mapped == null) {
                s.close();
                return null;
            }
            return new TcpProbe(mapped, bound, s);
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
                // 探测失败，关闭连接后返回
            }
            return null;
        }
    }

    /**
     * 在已建立的 STUN-over-TCP 长连接上做一次绑定交互（保活复用同一连接时外网映射端口不漂移），
     * 返回外网映射地址；连接异常/服务器无响应时返回 null（连接本身保持，由调用方决定重连或关闭）。
     */
    public static String bindingOverTcp(Socket s, int timeoutMs) {
        try {
            return exchangeTcpBinding(s, timeoutMs);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 公共出站端点连接（端口保留模式兜底）：从指定本地端口向端点建立 TCP 连接，连接建立
     * 即返回——出站连接在沿途全部 NAT（含运营商 CGNAT）上建立映射，TCP 三次握手完成
     * 本身就是双向链路的验证，连接上不做任何应用层交互（域名解析按其设计走 UDP 53，
     * 由系统解析器承担）。注意端点须为透传服务：53/TCP 等被运营商透明拦截的端口，
     * 连接终结在 CGNAT 上，建立的映射不接受入站。
     * 调用方持有连接至下一保活周期（映射跟随连接存活），失败携带原因上抛
     * （连接被拒/超时等，供调用方记录端点失效原因）。
     */
    public static Socket connectOutboundEx(String host, int port, int localPort, int timeoutMs) throws Exception {
        Socket s = new Socket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Math.max(localPort, 0)));
            s.connect(new InetSocketAddress(InetAddress.getByName(host), port), timeoutMs);
            return s;
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
                // 连接失败，关闭 socket 后抛出
            }
            throw e;
        }
    }

    /**
     * STUN-over-TCP 绑定交互（RFC 5389 §7.1/§7.2.2）：发送 Binding 请求并解析响应返回映射地址。
     * 直接读底层流不做缓冲保留，同一连接上可反复调用（长连接保活）。
     */
    private static String exchangeTcpBinding(Socket s, int timeoutMs) throws Exception {
        s.setSoTimeout(timeoutMs);
        byte[] tid = new byte[12];
        RANDOM.nextBytes(tid);
        byte[] req = buildRequest(tid, false);
        OutputStream out = s.getOutputStream();
        // RFC 5389 §7.2.2：TCP 传输时每条 STUN 消息前必须写 2 字节长度帧头（值为消息体字节数），
        // 缺失时 coturn 等标准服务器无法解析请求；读数时两者兼容（个别实现不回帧头按裸消息解析）
        out.write((req.length >>> 8) & 0xFF);
        out.write(req.length & 0xFF);
        out.write(req);
        out.flush();
        InputStream in = s.getInputStream();
        byte[] b = in.readNBytes(22);
        if (b.length < 22) return null;
        byte[] head;
        int msgLen;
        if (readU16(b, 2) == MSG_BINDING_RESPONSE && readU32(b, 6) == MAGIC_COOKIE) {
            int frame = readU16(b, 0);
            if (frame < 20 || frame > 65535 || (frame - 20) % 4 != 0 || readU16(b, 4) != frame - 20) return null;
            head = Arrays.copyOfRange(b, 2, 22);
            msgLen = readU16(head, 2);
        } else if (readU16(b, 0) == MSG_BINDING_RESPONSE && readU32(b, 4) == MAGIC_COOKIE) {
            head = Arrays.copyOfRange(b, 0, 20);
            msgLen = readU16(head, 2);
        } else {
            return null;
        }
        if (msgLen % 4 != 0) return null; // STUN 消息长度恒为 4 字节的倍数
        if (!Arrays.equals(tid, Arrays.copyOfRange(head, 8, 20))) return null;
        byte[] attrs = in.readNBytes(msgLen);
        if (attrs.length < msgLen) return null;
        byte[] full = new byte[20 + attrs.length];
        System.arraycopy(head, 0, full, 0, 20);
        System.arraycopy(attrs, 0, full, 20, attrs.length);
        return extractMapped(full, full.length);
    }

    /**
     * NAT 类型探测（RFC 3489 / RFC 5780 简化流程）。
     * <ol>
     *   <li>Test1：普通绑定，取得映射地址（RTO 重发）</li>
     *   <li>本地地址 == 映射地址 => Open Internet / 1:1 NAT</li>
     *   <li>Test2：带 CHANGE-REQUEST 标志，响应来自服务器更换后的地址 => Full Cone；
     *       同源回复说明服务器忽略该属性，继续测试</li>
     *   <li>Test3：向服务器的备用地址(OTHER-ADDRESS)再探测，映射不同 => Symmetric</li>
     *   <li>其余 => Restricted / Port Restricted；服务器不支持检测时给出保守结论</li>
     * </ol>
     */
    public static Result detectNatType(DatagramSocket socket, String stunHost, int stunPort, int timeoutMs) {
        InetSocketAddress server;
        try {
            server = new InetSocketAddress(InetAddress.getByName(stunHost), stunPort);
        } catch (Exception e) {
            // 配置的服务器域名失效/不可达：返回未知结果，不阻断任务启动（运行期保活另有维护列表服务器兜底）
            return new Result(null, "Unknown(STUN服务器无响应)");
        }
        // Test1：普通绑定，取得映射地址（RTO 重发，UDP 单次丢包不至于误判服务器无响应）
        byte[] tid1 = new byte[12];
        RANDOM.nextBytes(tid1);
        byte[] req1 = buildRequest(tid1, false);
        BindingResponse r1;
        try {
            r1 = exchange(socket, server, req1, tid1, timeoutMs, 2);
        } catch (Exception e) {
            return new Result(null, "Unknown(STUN服务器无响应)");
        }
        if (r1 == null) {
            return new Result(null, "Unknown(STUN服务器无响应)");
        }

        // 本机出口 == 映射地址 => 无 NAT；映射端口 == 本地端口 => 端口保持型 NAT
        String localIp = socket.getLocalAddress().getHostAddress();
        int localPort = socket.getLocalPort();
        if (r1.mapped.equals(localIp + ":" + localPort)) {
            return new Result(r1.mapped, "Open Internet(无NAT)");
        }
        if (r1.mapped.endsWith(":" + localPort) && !r1.mapped.startsWith("0.0.0.0")) {
            return new Result(r1.mapped, "端口保持型NAT(映射端口=本地端口)");
        }

        // Test2：请求服务器更换 IP+端口 回复（CHANGE-REQUEST 标志位 0x06）。
        // 只有响应确实来自更换后的地址才是 Full Cone；多数现代服务器不支持该属性会原址回复，
        // 同源响应不能作为入站无过滤的证据（否则会被误判为 Full Cone），继续后续测试。
        byte[] tid2 = new byte[12];
        RANDOM.nextBytes(tid2);
        byte[] req2 = buildRequest(tid2, true);
        try {
            BindingResponse r2 = exchange(socket, server, req2, tid2, timeoutMs, 1);
            if (r2 != null && !server.equals(r2.src)) {
                return new Result(r1.mapped, "Full Cone(全锥形)");
            }
        } catch (Exception ignored) {
            // 后续探测失败不影响已取得的 NAT 映射结论
        }

        // Test3：向备用地址（OTHER-ADDRESS / CHANGED-ADDRESS，RFC 5780）再探测，映射端口变化说明是对称型
        if (r1.otherAddress != null) {
            try {
                byte[] tid3 = new byte[12];
                RANDOM.nextBytes(tid3);
                byte[] req3 = buildRequest(tid3, false);
                BindingResponse r3 = exchange(socket, r1.otherAddress, req3, tid3, timeoutMs, 1);
                if (r3 != null && !r3.mapped.equals(r1.mapped)) {
                    return new Result(r1.mapped, "Symmetric(对称型)");
                }
                return new Result(r1.mapped, "Restricted(受限锥形)");
            } catch (Exception ignored) {
                // 备用地址探测失败，按保守结论返回
            }
        }
        return new Result(r1.mapped, "Restricted(受限锥形)");
    }

    // ---------- 协议报文构造与解析 ----------

    /** 构造 Binding Request，可选携带 CHANGE-REQUEST 属性 */
    private static byte[] buildRequest(byte[] tid, boolean changeRequest) {
        int attrLen = changeRequest ? 8 : 0;
        byte[] buf = new byte[20 + attrLen];
        writeU16(buf, 0, MSG_BINDING_REQUEST);
        writeU16(buf, 2, attrLen);
        writeU32(buf, 4, MAGIC_COOKIE);
        System.arraycopy(tid, 0, buf, 8, 12);
        if (changeRequest) {
            writeU16(buf, 20, ATTR_CHANGE_REQUEST);
            writeU16(buf, 22, 4);
            writeU32(buf, 24, 0x06); // change IP + change port
        }
        return buf;
    }

    /** 绑定响应解析结果（src 为响应包源地址，供 CHANGE-REQUEST 换址回复判定） */
    private record BindingResponse(String mapped, InetSocketAddress otherAddress, InetSocketAddress src) {
    }

    /**
     * 请求-响应交换：按 RFC 5389 §7.2.1 的 RTO 机制重发（起始 500ms，指数退避封顶 2s），
     * 总时长不超过 timeoutMs；全部超时返回 null。
     */
    private static BindingResponse exchange(DatagramSocket socket, InetSocketAddress target,
                                            byte[] req, byte[] tid, int timeoutMs, int maxRetries) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int wait = 500;
        for (int attempt = 0; ; attempt++) {
            socket.send(new DatagramPacket(req, req.length, target));
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) break;
            BindingResponse r = receiveOnce(socket, tid, (int) Math.min(wait, remain));
            if (r != null) return r;
            if (attempt >= maxRetries || System.currentTimeMillis() >= deadline) break;
            wait = Math.min(wait * 2, 2000);
        }
        return null;
    }

    /** 接收并解析一次绑定响应（忽略类型/事务 ID 不匹配的包），超时返回 null */
    private static BindingResponse receiveOnce(DatagramSocket socket, byte[] tid, int waitMs) throws Exception {
        socket.setSoTimeout(waitMs);
        byte[] buf = new byte[512];
        while (true) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(pkt);
            } catch (java.net.SocketTimeoutException e) {
                return null;
            }
            byte[] data = Arrays.copyOf(pkt.getData(), pkt.getLength());
            if (data.length < 20) continue;
            int type = readU16(data, 0);
            if (type != MSG_BINDING_RESPONSE) continue;
            if (!Arrays.equals(tid, Arrays.copyOfRange(data, 8, 20))) continue;

            String mapped = null;
            InetSocketAddress other = null;
            int len = readU16(data, 2);
            int pos = 20;
            while (pos + 4 <= 20 + len && pos + 4 <= data.length) {
                int attrType = readU16(data, pos);
                int attrLen = readU16(data, pos + 2);
                int valStart = pos + 4;
                if (valStart + attrLen > data.length) break;
                switch (attrType) {
                    case ATTR_MAPPED_ADDRESS -> mapped = parseAddress(data, valStart, false);
                    case ATTR_XOR_MAPPED_ADDRESS -> mapped = parseAddress(data, valStart, true);
                    case ATTR_CHANGED_ADDRESS, ATTR_OTHER_ADDRESS -> {
                        String addr = parseAddress(data, valStart, false);
                        if (addr != null) {
                            String[] parts = addr.split(":");
                            other = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
                        }
                    }
                    default -> {
                    }
                }
                pos = valStart + ((attrLen + 3) & ~3); // 属性值按 4 字节对齐
            }
            if (mapped != null) {
                return new BindingResponse(mapped, other,
                        new InetSocketAddress(pkt.getAddress(), pkt.getPort()));
            }
        }
    }

    /**
     * 解析 MAPPED-ADDRESS / XOR-MAPPED-ADDRESS（IPv4）。
     * RFC 5389 §15.2：XOR 编码仅与魔术饼干异或（端口异或高 16 位、IP 异或全 32 位），与事务 ID 无关。
     */
    private static String parseAddress(byte[] data, int offset, boolean xorMapped) {
        if (offset + 8 > data.length) return null;
        int family = data[offset + 1] & 0xFF;
        if (family != 0x01) return null; // 仅处理 IPv4（家庭 NAT 穿透场景以 IPv4 为主）
        int port = readU16(data, offset + 2);
        int ip = readU32(data, offset + 4);
        if (xorMapped) {
            port ^= (MAGIC_COOKIE >>> 16) & 0xFFFF;
            ip ^= MAGIC_COOKIE;
        }
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "."
                + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF) + ":" + port;
    }

    private static void writeU16(byte[] buf, int pos, int v) {
        buf[pos] = (byte) (v >>> 8);
        buf[pos + 1] = (byte) v;
    }

    private static void writeU32(byte[] buf, int pos, int v) {
        buf[pos] = (byte) (v >>> 24);
        buf[pos + 1] = (byte) (v >>> 16);
        buf[pos + 2] = (byte) (v >>> 8);
        buf[pos + 3] = (byte) v;
    }

    private static int readU16(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
    }

    private static int readU32(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
                | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
    }
}
