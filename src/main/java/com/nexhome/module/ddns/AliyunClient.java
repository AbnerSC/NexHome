package com.nexhome.module.ddns;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云 OpenAPI 轻量客户端（不依赖官方 SDK，避免引入重型依赖）。
 * <p>
 * 支持两套接口与签名算法：
 * <ol>
 *   <li>云解析 DNS（alidns.aliyuncs.com，Version=2015-01-09）：RPC 风格 + 签名算法 1.0（HMAC-SHA1）</li>
 *   <li>边缘安全加速 ESA（esa.cn-hangzhou.aliyuncs.com，Version=2024-09-10）：签名算法 3.0（ACS3-HMAC-SHA256）</li>
 * </ol>
 * AccessKey 配置位置：每个 DDNS 任务中单独填写（存于 SQLite ddns_task 表）。
 */
public final class AliyunClient {

    private static final String DNS_HOST = "https://alidns.aliyuncs.com/";
    private static final String ESA_HOST = "esa.cn-hangzhou.aliyuncs.com";
    private static final DateTimeFormatter ISO_Z = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private AliyunClient() {
    }

    // ==================== 云解析 DNS（签名 1.0） ====================

    /**
     * 查询域名下指定主机记录的解析列表。
     *
     * @return Records.Record JSON 数组
     */
    public static JsonArray dnsDescribeRecords(String ak, String sk, String domain, String rr) throws Exception {
        JsonObject resp = dnsCall(ak, sk, Map.of(
                "Action", "DescribeDomainRecords",
                "DomainName", domain,
                "RRKeyWord", rr));
        if (resp.has("Code")) {
            throw new IllegalStateException("阿里云DNS错误[" + resp.get("Code").getAsString() + "]: "
                    + (resp.has("Message") ? resp.get("Message").getAsString() : ""));
        }
        return resp.get("Records").getAsJsonObject()
                .get("Record").getAsJsonArray();
    }

    /** 新增解析记录，返回 RecordId */
    public static String dnsAddRecord(String ak, String sk, String domain, String rr,
                                      String type, String value, int ttl) throws Exception {
        JsonObject resp = dnsCall(ak, sk, Map.of(
                "Action", "AddDomainRecord",
                "DomainName", domain,
                "RR", rr,
                "Type", type,
                "Value", value,
                "TTL", String.valueOf(ttl)));
        checkDnsError(resp);
        return resp.get("RecordId").getAsString();
    }

    /** 更新解析记录 */
    public static void dnsUpdateRecord(String ak, String sk, String recordId, String rr,
                                       String type, String value, int ttl) throws Exception {
        JsonObject resp = dnsCall(ak, sk, Map.of(
                "Action", "UpdateDomainRecord",
                "RecordId", recordId,
                "RR", rr,
                "Type", type,
                "Value", value,
                "TTL", String.valueOf(ttl)));
        checkDnsError(resp);
    }

    private static void checkDnsError(JsonObject resp) {
        if (resp.has("Code")) {
            throw new IllegalStateException("阿里云DNS错误[" + resp.get("Code").getAsString() + "]: "
                    + (resp.has("Message") ? resp.get("Message").getAsString() : ""));
        }
    }

    /** 云解析 RPC 调用：组装公共参数并按签名算法 1.0 签名（HMAC-SHA1 + Base64） */
    private static JsonObject dnsCall(String ak, String sk, Map<String, String> biz) throws Exception {
        TreeMap<String, String> params = new TreeMap<>(biz);
        params.put("Format", "JSON");
        params.put("Version", "2015-01-09");
        params.put("AccessKeyId", ak);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("Timestamp", ISO_Z.format(Instant.now().atZone(ZoneOffset.UTC)));

        // 1) 构造规范化查询串（按参数名排序）
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            canonical.append(percentEncode(e.getKey())).append('=')
                    .append(percentEncode(e.getValue())).append('&');
        }
        canonical.deleteCharAt(canonical.length() - 1);

        // 2) 构造待签名字符串：HTTPMethod&%2F&percentEncode(canonical)
        String stringToSign = "GET&" + percentEncode("/") + "&" + percentEncode(canonical.toString());

        // 3) HMAC-SHA1 签名（密钥为 AccessKeySecret + "&"）
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((sk + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));

        String url = DNS_HOST + "?Signature=" + percentEncode(signature) + "&" + canonical;
        return httpGetJson(url, Map.of());
    }

    // ==================== ESA 边缘安全加速（签名 3.0） ====================

    /** 查询站点下指定记录名的解析列表 */
    public static JsonArray esaListRecords(String ak, String sk, String siteId, String recordName) throws Exception {
        JsonObject resp = esaCall(ak, sk, "ListRecords", Map.of(
                "SiteId", siteId,
                "RecordName", recordName));
        return resp.has("Records") ? resp.get("Records").getAsJsonArray() : new JsonArray();
    }

    /** 新增 ESA 解析记录，返回 RecordId */
    public static String esaCreateRecord(String ak, String sk, String siteId, String recordName,
                                         String type, String value, int ttl) throws Exception {
        JsonObject resp = esaCall(ak, sk, "CreateRecord", Map.of(
                "SiteId", siteId,
                "RecordName", recordName,
                "Type", type,
                "Data.Value", value,
                "Ttl", String.valueOf(ttl)));
        checkEsaError(resp);
        return resp.get("RecordId").getAsString();
    }

    /** 更新 ESA 解析记录 */
    public static void esaUpdateRecord(String ak, String sk, String recordId,
                                       String type, String value, int ttl) throws Exception {
        JsonObject resp = esaCall(ak, sk, "UpdateRecord", Map.of(
                "RecordId", recordId,
                "Type", type,
                "Data.Value", value,
                "Ttl", String.valueOf(ttl)));
        checkEsaError(resp);
    }

    private static void checkEsaError(JsonObject resp) {
        if (resp.has("Code") && !resp.get("Code").getAsString().isBlank()) {
            throw new IllegalStateException("阿里云ESA错误[" + resp.get("Code").getAsString() + "]: "
                    + (resp.has("Message") ? resp.get("Message").getAsString() : ""));
        }
    }

    /**
     * ESA API 调用：签名算法 3.0（ACS3-HMAC-SHA256）。
     * 流程：构造规范请求 -> SHA256 摘要 -> HMAC-SHA256 签名 -> Authorization 头。
     */
    private static JsonObject esaCall(String ak, String sk, String action, Map<String, String> params) throws Exception {
        TreeMap<String, String> sorted = new TreeMap<>(params);

        // 规范化查询串（参数名排序，URL 编码）
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            query.append(rfcEncode(e.getKey())).append('=').append(rfcEncode(e.getValue())).append('&');
        }
        if (!query.isEmpty()) query.deleteCharAt(query.length() - 1);

        String date = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String nonce = UUID.randomUUID().toString();
        String hashedEmptyBody = sha256Hex("");

        // 参与签名的头（按名称排序）
        String canonicalHeaders = "host:" + ESA_HOST + "\n"
                + "x-acs-action:" + action + "\n"
                + "x-acs-content-sha256:" + hashedEmptyBody + "\n"
                + "x-acs-date:" + date + "\n"
                + "x-acs-signature-nonce:" + nonce + "\n"
                + "x-acs-version:2024-09-10\n";
        String signedHeaders = "host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version";

        // 规范请求
        String canonicalRequest = "GET\n/\n" + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + hashedEmptyBody;
        // 待签名字符串
        String stringToSign = "ACS3-HMAC-SHA256\n" + sha256Hex(canonicalRequest);
        // 计算签名
        String signature = HexFormat.of().formatHex(
                hmacSha256(sk.getBytes(StandardCharsets.UTF_8), stringToSign.getBytes(StandardCharsets.UTF_8)));

        String authorization = "ACS3-HMAC-SHA256 Credential=" + ak
                + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature;

        String url = "https://" + ESA_HOST + "/?" + query;
        return httpGetJson(url, Map.of(
                "x-acs-action", action,
                "x-acs-version", "2024-09-10",
                "x-acs-date", date,
                "x-acs-signature-nonce", nonce,
                "x-acs-content-sha256", hashedEmptyBody,
                "Authorization", authorization));
    }

    // ==================== 底层工具 ====================

    private static JsonObject httpGetJson(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET();
        headers.forEach(b::header);
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /** 阿里云签名 1.0 专用编码：URL 编码后修正 + * ~ 三个字符 */
    private static String percentEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /** RFC3986 编码（签名 3.0 使用） */
    private static String rfcEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sha256Hex(String data) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
