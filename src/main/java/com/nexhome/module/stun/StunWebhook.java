package com.nexhome.module.stun;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STUN 穿透结果 Webhook 推送：穿透成功 / 外网映射地址变化时，将结果按任务配置的
 * 多个 webhook 同步到其他系统。支持 GET / POST 两种请求方式与自定义参数。
 * <p>
 * 配置存于 {@code stun_task.webhook_config}（JSON 数组），每项：
 * <pre>{"url":"https://...","method":"POST","headers":{"Authorization":"Bearer xxx"},"params":{"token":"xxx","addr":"${mapped_addr}"}}</pre>
 * 占位符 <code>${字段}</code> 取任务结果列值（event/id/name/protocol/target_ip/target_port/
 * mapped_addr/nat_type/punched_at/check_result/status 等），URL、请求头与自定义参数值均支持。
 * <ul>
 *   <li>POST：以 {@code application/json} 发送「结果字段 + 自定义参数」合并的请求体（Content-Type 可被自定义头覆盖）</li>
 *   <li>GET：将「结果字段 + 自定义参数」URL 编码后拼为查询串</li>
 * </ul>
 * 每条 webhook 的最近一次调用时间与结果（成功/失败原因）写回 {@code stun_task.webhook_status}
 * （JSON 对象，url -> {time,result}），供前端展示。推送失败仅记日志，不影响穿透主流程；
 * 异步在独立线程执行，避免占用全局调度池拖慢保活
 * （与 {@code StunRunner} 的公网入站验证同理：调度池仅 3 线程，被慢 HTTP 占用会延迟保活
 * 导致 CGNAT 映射超时回收——穿透静默失效的直接诱因）。
 */
final class StunWebhook {

    /** 结果字段：作为 POST 请求体 / GET 查询串的内容，同时供 ${字段} 占位符引用 */
    private static final String[] FIELDS = {
            "id", "name", "protocol", "target_ip", "target_port", "bind_port",
            "stun_host", "stun_port", "peer_addr", "mapped_addr", "nat_type",
            "punched_at", "check_time", "check_result", "status"
    };

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)\\}");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 独立推送线程：webhook HTTP 可能耗时较久，不能占用全局调度池（详见类注释） */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stun-webhook");
        t.setDaemon(true);
        return t;
    });

    private StunWebhook() {
    }

    /** 按任务配置推送穿透结果（event 为 stun.punched / stun.mapped_changed）；无配置时静默返回 */
    static void push(Map<String, Object> task, String event) {
        String config = str(task, "webhook_config");
        if (config.isBlank()) return;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(config).getAsJsonArray();
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "解析 webhook 配置失败: " + e.getMessage());
            return;
        }
        long taskId = num(task, "id");
        Map<String, String> data = buildData(task, event);
        String name = str(task, "name");
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject w = el.getAsJsonObject();
            String url = JsonUtils.str(w, "url").trim();
            if (url.isEmpty()) continue;
            String method = JsonUtils.str(w, "method").trim().toUpperCase();
            if (!"GET".equals(method)) method = "POST";
            Map<String, String> headers = readMap(w, "headers", data);
            Map<String, String> params = readMap(w, "params", data);
            final String target = replace(url, data);
            final String verb = method;
            final String key = url; // 状态以配置的原始 URL（含占位符）为键，与前端 webhook_config 一致便于回显
            EXEC.submit(() -> recordResult(taskId, key, send(name, target, verb, data, params, headers)));
        }
    }

    /** 执行一次 webhook 推送，返回调用结果文案（OK(HTTP xxx) / FAIL(...)），仅记日志不抛出 */
    private static String send(String taskName, String url, String method,
                             Map<String, String> data, Map<String, String> params, Map<String, String> headers) {
        try {
            String target = url;
            if ("GET".equals(method)) {
                StringBuilder qs = new StringBuilder();
                data.forEach((k, v) -> appendQs(qs, k, v));
                params.forEach((k, v) -> appendQs(qs, k, v));
                target = url + (url.contains("?") ? "&" : "?") + qs;
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "NexHome-STUN");
            boolean hasContentType = false;
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (h.getKey().equalsIgnoreCase("Content-Type")) hasContentType = true;
                try {
                    rb.setHeader(h.getKey(), h.getValue()); // setHeader 覆盖默认头；受限/非法头仅告警忽略
                } catch (Exception e) {
                    Logs.warn(Logs.STUN, "任务[" + taskName + "] 忽略受限/非法请求头 " + h.getKey() + ": " + e.getMessage());
                }
            }
            if ("GET".equals(method)) {
                rb.GET();
            } else {
                JsonObject payload = new JsonObject();
                data.forEach(payload::addProperty);
                params.forEach(payload::addProperty);
                if (!hasContentType) rb.header("Content-Type", "application/json; charset=utf-8");
                rb.POST(HttpRequest.BodyPublishers.ofString(JsonUtils.GSON.toJson(payload), StandardCharsets.UTF_8));
            }
            HttpResponse<String> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                Logs.info(Logs.STUN, "任务[" + taskName + "] 穿透结果已推送 webhook(" + method + "): " + url + " HTTP " + code);
                return "OK(HTTP " + code + ")";
            }
            String r = "FAIL(HTTP " + code + " " + truncate(resp.body()) + ")";
            Logs.warn(Logs.STUN, "任务[" + taskName + "] 推送 webhook 返回非 2xx: " + r);
            return r;
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "任务[" + taskName + "] 推送 webhook 失败(" + url + "): " + e.getMessage());
            return "FAIL(" + e + ")";
        }
    }

    /** 读取 webhook 项内的对象字段（headers/params）并对值做占位符替换 */
    private static Map<String, String> readMap(JsonObject w, String key, Map<String, String> data) {
        Map<String, String> m = new LinkedHashMap<>();
        if (w.has(key) && w.get(key).isJsonObject()) {
            for (Map.Entry<String, JsonElement> pe : w.getAsJsonObject(key).entrySet()) {
                JsonElement pv = pe.getValue();
                m.put(pe.getKey(), replace(pv == null || pv.isJsonNull() ? "" : pv.getAsString(), data));
            }
        }
        return m;
    }

    /** 将某条 webhook 最近一次调用时间与结果写回 stun_task.webhook_status（url -> {time,result}）；推送在单线程串行执行无需加锁 */
    private static void recordResult(long taskId, String url, String result) {
        if (taskId <= 0) return;
        try {
            Map<String, Object> row = Database.queryOne("SELECT webhook_status FROM stun_task WHERE id=?", taskId);
            if (row == null) return;
            JsonObject status = JsonUtils.parse(str(row, "webhook_status"));
            JsonObject entry = new JsonObject();
            entry.addProperty("time", Database.now());
            entry.addProperty("result", result);
            status.add(url, entry);
            Database.update("UPDATE stun_task SET webhook_status=? WHERE id=?", JsonUtils.GSON.toJson(status), taskId);
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "记录 webhook 调用结果失败(task#" + taskId + "): " + e.getMessage());
        }
    }

    /** 结果字段值表：event + 各任务列（缺失列以空串占位，保证占位符可解析） */
    private static Map<String, String> buildData(Map<String, Object> task, String event) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("event", event);
        for (String f : FIELDS) data.put(f, str(task, f));
        return data;
    }

    /** 将文本中的 ${字段} 占位符替换为结果字段值（未知字段替换为空串） */
    private static String replace(String text, Map<String, String> data) {
        if (text == null || text.indexOf("${") < 0) return text == null ? "" : text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(data.getOrDefault(m.group(1), "")));
        m.appendTail(sb);
        return sb.toString();
    }

    private static void appendQs(StringBuilder sb, String k, String v) {
        if (sb.length() > 0) sb.append('&');
        sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
