package com.nexhome.module.docker;

import com.nexhome.core.JsonUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * 极简 Docker Engine API 客户端（仅 GET，只读查询）。
 * <p>
 * 默认通过 Unix socket {@code /var/run/docker.sock} 与 Docker Daemon 通信
 * （容器部署时挂载该文件即可），也支持环境变量 {@code DOCKER_HOST} 指定
 * {@code unix:///path/to/docker.sock} 或 {@code tcp://host:2375}。
 * 注意：JDK 的 Unix Domain Socket 仅 SocketChannel 支持（JEP 380），
 * java.net.Socket 传 UDS 地址会抛 Unsupported address type，且其适配器对 UDS
 * 调用 setSoTimeout 会抛 UnsupportedOperationException("Not supported")，
 * 故全程基于 SocketChannel 直接收发字节、手写 HTTP/1.1 报文：
 * Connection: close，读至 EOF 即完整响应，不引入 docker-java 等重型依赖。
 */
final class DockerClient {

    /** 连接超时（毫秒）：端口不通时快速失败（仅 TCP 生效，UDS 连接为本地瞬时操作） */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final SocketAddress address;

    /** 配置错误描述（非 null 时所有请求直接失败），保证 DOCKER_HOST 配错不拖垮应用启动 */
    private final String broken;

    /** 连接描述（如 unix:///var/run/docker.sock），用于界面与日志展示 */
    final String describe;

    private DockerClient(SocketAddress address, String describe, String broken) {
        this.address = address;
        this.describe = describe;
        this.broken = broken;
    }

    /** 从环境变量 DOCKER_HOST 构建客户端，未配置时默认 unix:///var/run/docker.sock；解析失败不抛异常 */
    static DockerClient create() {
        String host = System.getenv("DOCKER_HOST");
        if (host == null || host.isBlank()) host = "unix:///var/run/docker.sock";
        try {
            if (host.startsWith("tcp://")) {
                String hp = host.substring("tcp://".length());
                int i = hp.lastIndexOf(':');
                String h = i > 0 ? hp.substring(0, i) : "127.0.0.1";
                int p = 2375;
                if (i > 0) {
                    try {
                        p = Integer.parseInt(hp.substring(i + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                return new DockerClient(new InetSocketAddress(h, p), host, null);
            }
            String path = host.startsWith("unix://") ? host.substring("unix://".length()) : host;
            // Windows 盘符路径兼容：unix:///C:/x.sock 去掉多余前导斜杠
            if (path.matches("^/[A-Za-z]:.*")) path = path.substring(1);
            return new DockerClient(UnixDomainSocketAddress.of(path), "unix://" + path, null);
        } catch (Exception e) {
            return new DockerClient(null, host, "DOCKER_HOST 配置无效（" + host + "）: " + e.getMessage());
        }
    }

    /** GET 请求并返回响应体字符串；非 200 抛出携带状态码与 Daemon 错误信息的异常 */
    String get(String pathAndQuery) throws IOException {
        if (broken != null) throw new IOException(broken);
        SocketChannel ch = openChannel();
        try {
            connect(ch);
            // 直接用 SocketChannel 收发字节：避开 Socket 适配器对 UDS 的诸多限制
            // （如 setSoTimeout 会抛 UnsupportedOperationException("Not supported")）
            writeFully(ch, ("GET " + pathAndQuery + " HTTP/1.1\r\n"
                    + "Host: docker\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            // Connection: close：Daemon 发送完响应即断开连接，读到 EOF 即为完整响应
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ByteBuffer bb = ByteBuffer.allocate(8192);
            while (ch.read(bb) != -1) {
                bb.flip();
                byte[] chunk = new byte[bb.remaining()];
                bb.get(chunk);
                buf.write(chunk);
                bb.clear();
            }
            return new String(body(buf.toByteArray()), StandardCharsets.UTF_8);
        } finally {
            try {
                ch.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 按地址类型选择协议族：UDS 必须用 UNIX 族通道（Socket 类不支持 UDS） */
    private SocketChannel openChannel() throws IOException {
        return address instanceof UnixDomainSocketAddress
                ? SocketChannel.open(StandardProtocolFamily.UNIX)
                : SocketChannel.open(StandardProtocolFamily.INET);
    }

    /**
     * 建立连接，返回后通道处于阻塞模式。
     * <p>UDS 为本地瞬时连接：路径不存在直接抛错，无需超时；
     * TCP 用非阻塞 connect + Selector 实现连接超时（端口不通时快速失败）。
     */
    private void connect(SocketChannel ch) throws IOException {
        if (address instanceof UnixDomainSocketAddress) {
            ch.connect(address);
            return;
        }
        ch.configureBlocking(false);
        try {
            if (ch.connect(address)) return;
            try (Selector sel = Selector.open()) {
                ch.register(sel, SelectionKey.OP_CONNECT);
                // select 返回就绪通道数，0 表示超时仍无可连接事件
                if (sel.select(CONNECT_TIMEOUT_MS) == 0) {
                    throw new IOException("连接 Docker 超时: " + describe);
                }
                if (!ch.finishConnect()) {
                    throw new IOException("连接 Docker 失败: " + describe);
                }
            }
        } finally {
            ch.configureBlocking(true);
        }
    }

    /** 阻塞模式下完整写出字节（SocketChannel.write 可能部分写入，需循环至写完） */
    private static void writeFully(SocketChannel ch, byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data);
        while (bb.hasRemaining()) ch.write(bb);
    }

    /** 解析原始响应：校验状态码，按 Content-Length / chunked 提取响应体 */
    private static byte[] body(byte[] raw) throws IOException {
        int headEnd = indexOf(raw, 0, "\r\n\r\n");
        if (headEnd < 0) throw new IOException("Docker 响应格式异常（缺少 HTTP 头）");
        String head = new String(raw, 0, headEnd, StandardCharsets.UTF_8);
        String[] lines = head.split("\r\n");
        int code = 0;
        try {
            String[] status = lines[0].split(" ", 3);
            if (status.length > 1) code = Integer.parseInt(status[1].trim());
        } catch (NumberFormatException ignored) {
        }
        boolean chunked = false;
        int contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c < 0) continue;
            String name = lines[i].substring(0, c).trim().toLowerCase();
            String value = lines[i].substring(c + 1).trim();
            if (name.equals("transfer-encoding") && value.contains("chunked")) {
                chunked = true;
            } else if (name.equals("content-length")) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        int bodyStart = headEnd + 4;
        byte[] payload = chunked
                ? dechunk(raw, bodyStart)
                : new byte[Math.max(0, contentLength >= 0
                        ? Math.min(contentLength, raw.length - bodyStart)
                        : raw.length - bodyStart)];
        if (!chunked && payload.length > 0) {
            System.arraycopy(raw, bodyStart, payload, 0, payload.length);
        }
        if (code != 200) {
            String text = new String(payload, StandardCharsets.UTF_8).trim();
            // Daemon 错误体形如 {"message":"..."}，提取后更友好
            String message = JsonUtils.str(JsonUtils.parse(text), "message");
            throw new IOException("Docker API " + code + ": "
                    + (message.isBlank() ? text : message));
        }
        return payload;
    }

    /** chunked 编码解码（字节级处理，避免多字节 UTF-8 字符被切断） */
    private static byte[] dechunk(byte[] data, int start) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = start;
        while (pos < data.length) {
            int lineEnd = indexOf(data, pos, "\r\n");
            if (lineEnd < 0) break;
            String sizeLine = new String(data, pos, lineEnd - pos, StandardCharsets.US_ASCII)
                    .split(";", 2)[0].trim();
            int size;
            try {
                size = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) break;
            int from = lineEnd + 2;
            int to = Math.min(from + size, data.length);
            out.write(data, from, to - from);
            pos = to + 2;
        }
        return out.toByteArray();
    }

    /** 字节数组中查找 ASCII 子串 */
    private static int indexOf(byte[] data, int from, String token) {
        byte[] t = token.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = from; i <= data.length - t.length; i++) {
            for (int j = 0; j < t.length; j++) {
                if (data[i + j] != t[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
