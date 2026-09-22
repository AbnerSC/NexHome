package com.nexhome.module.docker;

import com.nexhome.core.JsonUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 极简 Docker Engine API 客户端（仅 GET，只读查询）。
 * <p>
 * 默认通过 Unix socket {@code /var/run/docker.sock} 与 Docker Daemon 通信
 * （容器部署时挂载该文件即可），也支持环境变量 {@code DOCKER_HOST} 指定
 * {@code unix:///path/to/docker.sock} 或 {@code tcp://host:2375}。
 * JDK 内置 HttpClient 不支持 Unix Domain Socket，故基于 {@link Socket}
 * （JDK 16+ 支持 UDS）手写 HTTP/1.1 报文：Connection: close，读至 EOF 即完整响应，
 * 不引入 docker-java 等重型依赖。
 */
final class DockerClient {

    /** 连接超时（毫秒）：socket 不存在 / 端口不通时快速失败 */
    private static final int CONNECT_TIMEOUT_MS = 3000;
    /** 读超时（毫秒）：/stats 单次 CPU 采样约需 1~2 秒，留足余量 */
    private static final int READ_TIMEOUT_MS = 15000;

    private final SocketAddress address;

    /** 连接描述（如 unix:///var/run/docker.sock），用于界面与日志展示 */
    final String describe;

    private DockerClient(SocketAddress address, String describe) {
        this.address = address;
        this.describe = describe;
    }

    /** 从环境变量 DOCKER_HOST 构建客户端，未配置时默认 unix:///var/run/docker.sock */
    static DockerClient create() {
        String host = System.getenv("DOCKER_HOST");
        if (host == null || host.isBlank()) host = "unix:///var/run/docker.sock";
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
            return new DockerClient(new InetSocketAddress(h, p), host);
        }
        String path = host.startsWith("unix://") ? host.substring("unix://".length()) : host;
        return new DockerClient(UnixDomainSocketAddress.of(path), "unix://" + path);
    }

    /** GET 请求并返回响应体字符串；非 200 抛出携带状态码与 Daemon 错误信息的异常 */
    String get(String pathAndQuery) throws IOException {
        try (Socket sock = new Socket()) {
            sock.connect(address, CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = sock.getOutputStream();
            out.write(("GET " + pathAndQuery + " HTTP/1.1\r\n"
                    + "Host: docker\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // Connection: close：Daemon 发送完响应即断开连接，读到 EOF 即为完整响应
            InputStream in = sock.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) {
                buf.write(b, 0, n);
            }
            return new String(body(buf.toByteArray()), StandardCharsets.UTF_8);
        }
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
