package com.nexhome.module.ddns;

import com.nexhome.core.Logs;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DDNS IP 来源解析器，支持三种模式：
 * <ul>
 *   <li>LOCAL  - 读取本机网卡 IP（可指定网卡名，缺省取第一个 IPv4 内网地址）</li>
 *   <li>MANUAL - 用户手动输入的 IP</li>
 *   <li>PUBLIC - 调用公网接口获取本机出口公网 IP（多接口容错）</li>
 * </ul>
 */
public final class IpResolver {

    /** 公网 IP 查询接口（逐个尝试，任一成功即返回） */
    private static final String[] PUBLIC_IP_URLS = {
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://api.my-ip.io/v2/ip.txt",
            "https://ip.3322.net"
    };

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private IpResolver() {
    }

    /** 根据任务配置解析应同步的 IP */
    public static String resolve(Map<String, Object> task) throws Exception {
        String mode = str(task, "ip_mode");
        return switch (mode) {
            case "MANUAL" -> {
                String ip = str(task, "manual_ip");
                if (ip.isBlank()) throw new IllegalArgumentException("手动模式未填写 IP 地址");
                yield ip;
            }
            case "LOCAL" -> localIp(str(task, "local_nic"));
            default -> publicIp();
        };
    }

    /** 读取本机网卡 IPv4 地址 */
    public static String localIp(String nicName) throws Exception {
        List<NetworkInterface> nics = Collections.list(NetworkInterface.getNetworkInterfaces());
        for (NetworkInterface nic : nics) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
            if (nicName != null && !nicName.isBlank() && !nic.getName().equals(nicName)) continue;
            for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                    return addr.getHostAddress();
                }
            }
        }
        throw new IllegalStateException(nicName == null || nicName.isBlank()
                ? "未找到可用的本机网卡 IPv4 地址"
                : "网卡 " + nicName + " 未找到可用 IPv4 地址");
    }

    /** 列出本机所有网卡名（供前端下拉选择） */
    public static List<String> listNics() throws Exception {
        List<String> names = new ArrayList<>();
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (nic.isUp() && !nic.isLoopback() && !nic.isVirtual()) {
                names.add(nic.getName());
            }
        }
        return names;
    }

    /** 调用公网接口获取出口公网 IP */
    public static String publicIp() throws Exception {
        Exception last = null;
        for (String url : PUBLIC_IP_URLS) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(8))
                        .GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                String ip = resp.body().trim();
                if (resp.statusCode() == 200 && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                    return ip;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        Logs.warn(Logs.DDNS, "所有公网 IP 查询接口均失败" + (last == null ? "" : ": " + last.getMessage()));
        throw new IllegalStateException("获取公网 IP 失败，请检查网络或改用手动/本地网卡模式");
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
