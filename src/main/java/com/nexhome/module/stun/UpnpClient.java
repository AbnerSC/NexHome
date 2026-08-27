package com.nexhome.module.stun;

import com.nexhome.core.Logs;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP IGD 端口映射客户端（纯 JDK 实现）：SSDP 发现路由器网关，
 * 经 WANIPConnection / WANPPPConnection 服务 SOAP 调用添加/删除 WAN-&gt;LAN 端口映射。
 * <p>
 * 与 STUN 穿透配合使用：STUN 负责探测 NAT 类型与外网映射地址，UPnP 在路由器上
 * 显式开放入站端口，对称型/受限型 NAT 下外网也能主动连入，显著提升穿透成功率。
 */
final class UpnpClient {

    /** SSDP 发现结果：SOAP 控制地址 + 服务类型 */
    record Gateway(String controlUrl, String serviceType) {
    }

    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "upnp:rootdevice"
    };

    private UpnpClient() {
    }

    /** 发现局域网 UPnP 网关并定位 WAN 连接控制地址，未发现返回 null */
    static Gateway discover(int timeoutMs) {
        for (String location : ssdpSearch(timeoutMs)) {
            try {
                Gateway g = parseDescription(location);
                if (g != null) {
                    Logs.info(Logs.STUN, "UPnP 网关发现: " + g.serviceType() + " @ " + g.controlUrl());
                    return g;
                }
            } catch (Exception ignored) {
                // 该设备描述解析失败，尝试下一个
            }
        }
        return null;
    }

    /** 添加端口映射（外网端口 -> 内网客户端，租期 86400=1天），失败返回 false */
    static boolean addPortMapping(Gateway g, String protocol, int externalPort, int internalPort,
                                  String internalClient, String description) {
        try {
            soap(g, "AddPortMapping",
                    "<NewRemoteHost></NewRemoteHost>"
                            + "<NewExternalPort>" + externalPort + "</NewExternalPort>"
                            + "<NewProtocol>" + protocol + "</NewProtocol>"
                            + "<NewInternalPort>" + internalPort + "</NewInternalPort>"
                            + "<NewInternalClient>" + internalClient + "</NewInternalClient>"
                            + "<NewEnabled>1</NewEnabled>"
                            + "<NewPortMappingDescription>" + description + "</NewPortMappingDescription>"
                            + "<NewLeaseDuration>86400</NewLeaseDuration>");
            return true;
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "UPnP AddPortMapping 失败: " + e.getMessage());
            return false;
        }
    }

    /** 删除端口映射（停止任务时释放路由器映射） */
    static void deletePortMapping(Gateway g, String protocol, int externalPort) {
        try {
            soap(g, "DeletePortMapping",
                    "<NewRemoteHost></NewRemoteHost>"
                            + "<NewExternalPort>" + externalPort + "</NewExternalPort>"
                            + "<NewProtocol>" + protocol + "</NewProtocol>");
        } catch (Exception ignored) {
            // 映射不存在或网关离线，忽略
        }
    }

    /** 查询路由器 WAN 口 IP，失败返回 null */
    static String externalIp(Gateway g) {
        try {
            String resp = soap(g, "GetExternalIPAddress", "");
            int s = resp.indexOf("<NewExternalIPAddress>");
            int e = resp.indexOf("</NewExternalIPAddress>");
            return s >= 0 && e > s ? resp.substring(s + "<NewExternalIPAddress>".length(), e).trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 内部实现 ----------

    /**
     * SSDP M-SEARCH 发现：逐块合格网卡绑定发送发现包
     * （Windows 多网卡/WSL/Hyper-V 环境下默认组播路由可能走虚拟网卡，路由器收不到发现包），
     * 并持续收集响应直到超时（设备响应延迟在 MX 内随机，不能因单次超前提前退出）。
     */
    private static List<String> ssdpSearch(int timeoutMs) {
        Set<String> locations = new LinkedHashSet<>();
        List<MulticastSocket> sockets = new ArrayList<>();
        try {
            InetAddress group = InetAddress.getByName("239.255.255.250");
            List<NetworkInterface> ifaces = eligibleInterfaces();
            for (NetworkInterface ni : ifaces) {
                try {
                    sockets.add(newSocket(ni));
                } catch (Exception ignored) {
                    // 该网卡创建 socket 失败，跳过
                }
            }
            if (sockets.isEmpty()) sockets.add(newSocket(null));
            Logs.info(Logs.STUN, "UPnP SSDP 发现网卡: "
                    + ifaces.stream().map(NetworkInterface::getDisplayName).toList());
            for (MulticastSocket ms : sockets) {
                ms.setTimeToLive(4);
                ms.setSoTimeout(300);
                for (int round = 0; round < 2; round++) { // UDP 可能丢包，发两轮
                    for (String st : SEARCH_TARGETS) {
                        byte[] data = msearch(st).getBytes(StandardCharsets.UTF_8);
                        ms.send(new DatagramPacket(data, data.length, group, 1900));
                    }
                }
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                for (MulticastSocket ms : sockets) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        ms.receive(pkt);
                        collectLocation(pkt, locations);
                    } catch (java.net.SocketTimeoutException ignored) {
                        // 该 socket 暂无响应，继续等待其他 socket / 下一轮
                    }
                }
            }
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "UPnP SSDP 发现异常: " + e.getMessage());
        } finally {
            sockets.forEach(MulticastSocket::close);
        }
        return new ArrayList<>(locations);
    }

    private static String msearch(String st) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + st + "\r\n\r\n";
    }

    /** 从 SSDP 响应中提取 LOCATION 描述地址 */
    private static void collectLocation(DatagramPacket pkt, Set<String> locations) {
        String resp = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
        for (String line : resp.split("\r?\n")) {
            if (line.regionMatches(true, 0, "LOCATION:", 0, 9)) {
                String loc = line.substring(9).trim();
                if (!loc.isEmpty()) locations.add(loc);
            }
        }
    }

    /** 枚举可发送发现包的网卡：已启用、支持组播、有 IPv4 地址（排除环回） */
    private static List<NetworkInterface> eligibleInterfaces() throws SocketException {
        List<NetworkInterface> list = new ArrayList<>();
        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
        while (en.hasMoreElements()) {
            NetworkInterface ni = en.nextElement();
            if (ni.isLoopback() || !ni.isUp() || !ni.supportsMulticast()) continue;
            for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                if (a.getAddress() instanceof Inet4Address) {
                    list.add(ni);
                    break;
                }
            }
        }
        return list;
    }

    /** 创建发现 socket：绑定到指定网卡的 IPv4 地址并指定组播出口网卡，确保发现包走正确的局域网 */
    private static MulticastSocket newSocket(NetworkInterface ni) throws IOException {
        if (ni == null) return new MulticastSocket();
        Inet4Address addr = null;
        for (InterfaceAddress a : ni.getInterfaceAddresses()) {
            if (a.getAddress() instanceof Inet4Address v4) {
                addr = v4;
                break;
            }
        }
        MulticastSocket ms = new MulticastSocket(new InetSocketAddress(addr, 0));
        ms.setNetworkInterface(ni);
        return ms;
    }

    /** 拉取设备描述 XML，定位 WANIPConnection / WANPPPConnection 服务的控制地址 */
    private static Gateway parseDescription(String location) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setExpandEntityReferences(false);
        HttpURLConnection conn = (HttpURLConnection) URI.create(location).toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        Document doc;
        try (InputStream in = conn.getInputStream()) {
            doc = f.newDocumentBuilder().parse(in);
        } finally {
            conn.disconnect();
        }
        NodeList services = doc.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element svc = (Element) services.item(i);
            String type = childText(svc, "serviceType");
            String control = childText(svc, "controlURL");
            if (type == null || control == null) continue;
            if (type.contains("WANIPConnection") || type.contains("WANPPPConnection")) {
                return new Gateway(resolveControlUrl(location, control), type);
            }
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent().trim();
    }

    /** controlURL 可能为相对路径，按描述地址的 scheme/host/port 解析为绝对地址 */
    private static String resolveControlUrl(String location, String control) throws Exception {
        URI base = new URI(location);
        URI root = new URI(base.getScheme(), null, base.getHost(), base.getPort(), null, null, null);
        return root.resolve(control).toString();
    }

    /** 发送 SOAP 请求，非 200 抛出带 UPnP 错误码的异常 */
    private static String soap(Gateway g, String action, String bodyInner) throws IOException {
        String env = "<?xml version=\"1.0\"?>\r\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + g.serviceType() + "\">"
                + bodyInner + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection conn = (HttpURLConnection) URI.create(g.controlUrl()).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        conn.setRequestProperty("SOAPAction", "\"" + g.serviceType() + "#" + action + "\"");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(env.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();
        if (code != 200) {
            throw new IOException("UPnP " + action + " 失败(HTTP " + code + "): " + extractError(resp));
        }
        return resp;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 提取 UPnP SOAP 错误码与描述，便于日志定位（如 718=映射条目冲突） */
    private static String extractError(String resp) {
        Matcher m = Pattern.compile(
                "<errorCode>(\\d+)</errorCode>\\s*<errorDescription>([^<]*)</errorDescription>")
                .matcher(resp == null ? "" : resp);
        return m.find() ? "错误" + m.group(1) + "(" + m.group(2).trim() + ")" : "响应非200";
    }
}
