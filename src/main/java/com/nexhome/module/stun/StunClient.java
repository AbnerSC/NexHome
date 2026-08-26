package com.nexhome.module.stun;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * STUN 协议客户端（RFC 5389 Binding 请求/响应 + RFC 3489 风格 NAT 类型探测）。
 * <p>
 * 纯 JDK Socket 实现，不依赖第三方库。
 * <p>
 * <b>NAT 类型与穿透成功率说明（界面同步展示）：</b>
 * <ul>
 *   <li>Open Internet / 1:1 NAT：外网可直达，穿透成功率最高</li>
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

    /** STUN 探测结果：外网映射地址 + NAT 类型 */
    public record Result(String mappedAddress, String natType) {
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
        socket.send(new DatagramPacket(req, req.length, InetAddress.getByName(stunHost), stunPort));

        BindingResponse resp = receive(socket, tid, timeoutMs);
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
                return parseAddress(data, valStart, null);
            }
            if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                return parseAddress(data, valStart, Arrays.copyOfRange(data, 8, 20));
            }
            pos = valStart + ((attrLen + 3) & ~3);
        }
        return null;
    }

    /**
     * STUN-over-TCP 探测（RFC 5389 §7.1）：从指定本地端口向 STUN 服务器建立 TCP 连接
     * 并发送绑定请求，返回该 TCP 出口的外网映射地址（ip:port）。
     * <p>
     * TCP 入站访问真正对应的是 TCP 映射，与 UDP 探测结果可能不同；
     * 该出站连接同时起到建立/保活 TCP 映射的作用。
     * 服务器不支持 STUN/TCP 或本地端口绑定失败时返回 null。
     */
    public static String bindOverTcp(String stunHost, int stunPort, int localPort, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.setReuseAddress(true);
            if (localPort > 0) {
                // 必须与监听同端口：只有这样得到的映射才对应外网可访问的入站端口
                s.bind(new InetSocketAddress(localPort));
            }
            s.connect(new InetSocketAddress(InetAddress.getByName(stunHost), stunPort), timeoutMs);
            s.setSoTimeout(timeoutMs);
            byte[] tid = new byte[12];
            RANDOM.nextBytes(tid);
            s.getOutputStream().write(buildRequest(tid, false));
            InputStream in = s.getInputStream();
            byte[] head = in.readNBytes(20);
            if (head.length < 20 || readU16(head, 0) != MSG_BINDING_RESPONSE) return null;
            if (!Arrays.equals(tid, Arrays.copyOfRange(head, 8, 20))) return null;
            byte[] attrs = in.readNBytes(readU16(head, 2));
            byte[] full = new byte[head.length + attrs.length];
            System.arraycopy(head, 0, full, 0, head.length);
            System.arraycopy(attrs, 0, full, head.length, attrs.length);
            return extractMapped(full, full.length);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * NAT 类型探测（RFC 3489 简化流程）。
     * <ol>
     *   <li>Test1：普通绑定，取得映射地址</li>
     *   <li>本地地址 == 映射地址 => Open Internet / 1:1 NAT</li>
     *   <li>Test2：带 CHANGE-REQUEST 标志，有响应 => Full Cone</li>
     *   <li>Test3：向服务器的备用地址(OTHER-ADDRESS)再探测，映射不同 => Symmetric</li>
     *   <li>其余 => Restricted / Port Restricted；服务器不支持检测时给出保守结论</li>
     * </ol>
     */
    public static Result detectNatType(DatagramSocket socket, String stunHost, int stunPort, int timeoutMs) throws Exception {
        byte[] tid1 = new byte[12];
        RANDOM.nextBytes(tid1);
        byte[] req1 = buildRequest(tid1, false);
        socket.send(new DatagramPacket(req1, req1.length,
                InetAddress.getByName(stunHost), stunPort));
        BindingResponse r1 = receive(socket, tid1, timeoutMs);
        if (r1 == null) {
            return new Result(null, "Unknown(STUN服务器无响应)");
        }

        // 本机出口 == 映射地址，说明没有 NAT 或 1:1 NAT
        String localIp = socket.getLocalAddress().getHostAddress();
        int localPort = socket.getLocalPort();
        if (r1.mapped.equals(localIp + ":" + localPort)
                || (r1.mapped.endsWith(":" + localPort) && !r1.mapped.startsWith("0.0.0.0"))) {
            return new Result(r1.mapped, "Open Internet / 1:1 NAT(穿透成功率最高)");
        }

        // Test2：请求服务器更换 IP+端口 回复（标志位 0x06），能收到说明入站无过滤
        byte[] tid2 = new byte[12];
        RANDOM.nextBytes(tid2);
        byte[] req2 = buildRequest(tid2, true);
        socket.send(new DatagramPacket(req2, req2.length,
                InetAddress.getByName(stunHost), stunPort));
        if (receive(socket, tid2, timeoutMs) != null) {
            return new Result(r1.mapped, "Full Cone(全锥形，穿透成功率高)");
        }

        // Test3：向备用地址再探测，映射端口变化说明是对称型
        if (r1.otherAddress != null) {
            byte[] tid3 = new byte[12];
            RANDOM.nextBytes(tid3);
            byte[] req3 = buildRequest(tid3, false);
            socket.send(new DatagramPacket(req3, req3.length, r1.otherAddress));
            BindingResponse r3 = receive(socket, tid3, timeoutMs);
            if (r3 != null && !r3.mapped.equals(r1.mapped)) {
                return new Result(r1.mapped, "Symmetric(对称型，纯STUN难以穿透，建议改用端口转发/中继)");
            }
            return new Result(r1.mapped, "Restricted(受限锥形，配合保活与对端打洞可用)");
        }
        return new Result(r1.mapped, "Restricted?(服务器不支持RFC3489检测，按受限/对称保守处理)");
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

    /** 绑定响应解析结果 */
    private record BindingResponse(String mapped, InetSocketAddress otherAddress) {
    }

    /** 接收并解析绑定响应（忽略事务 ID 不匹配的包），超时返回 null */
    private static BindingResponse receive(DatagramSocket socket, byte[] tid, int timeoutMs) throws Exception {
        socket.setSoTimeout(timeoutMs);
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[512];
        while (System.currentTimeMillis() < deadline) {
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
                    case ATTR_MAPPED_ADDRESS -> mapped = parseAddress(data, valStart, null);
                    case ATTR_XOR_MAPPED_ADDRESS -> mapped = parseAddress(data, valStart, tid);
                    case ATTR_CHANGED_ADDRESS, ATTR_OTHER_ADDRESS -> {
                        String addr = parseAddress(data, valStart, null);
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
            if (mapped != null) return new BindingResponse(mapped, other);
        }
        return null;
    }

    /** 解析 MAPPED-ADDRESS / XOR-MAPPED-ADDRESS（IPv4），xor 参数为 null 表示非 XOR 编码 */
    private static String parseAddress(byte[] data, int offset, byte[] tid) {
        if (offset + 8 > data.length) return null;
        int family = data[offset + 1] & 0xFF;
        if (family != 0x01) return null; // 仅处理 IPv4
        int port = readU16(data, offset + 2);
        int ip = readU32(data, offset + 4);
        if (tid != null) {
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
