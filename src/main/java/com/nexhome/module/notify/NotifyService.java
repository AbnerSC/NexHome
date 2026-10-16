package com.nexhome.module.notify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一告警通知中心：集中维护通知渠道（Webhook / Email / 钉钉），
 * 各业务模块通过 {@link #broadcast(String, String, String)} 将事件按启用渠道扇出。
 * <p>
 * 渠道配置存于 {@code notify_channel.config}（JSON，按类型字段不同）：
 * <ul>
 *   <li>WEBHOOK：{@code {"url","method":"GET|POST","headers":{...},"bodyTemplate":"..."}}，
 *       URL/请求头/bodyTemplate 支持占位符 {@code ${title} ${message} ${event} ${time}}</li>
 *   <li>EMAIL：{@code {"host","port":"465","ssl":true,"starttls":false,"username","password","from","to"}}</li>
 *   <li>DINGTALK：{@code {"webhook":"https://oapi.dingtalk.com/robot/send?access_token=xxx","secret":"加签密钥(可选)"}}</li>
 * </ul>
 * 投递在独立单线程 executor 串行执行，绝不占用 {@link Tasks} 共享调度池（慢 HTTP/SMTP 会拖垮调度），
 * 发送失败仅记日志并写 {@code notify_log}，不影响调用方主流程。
 */
public final class NotifyService {

    /** 渠道类型 */
    public static final String WEBHOOK = "WEBHOOK";
    public static final String EMAIL = "EMAIL";
    public static final String DINGTALK = "DINGTALK";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)\\}");

    /** 邮件正文里的 "key: value" 明细行（key 较短且不含冒号），渲染为对齐的两列表格 */
    private static final Pattern EMAIL_KV = Pattern.compile("([^:：]{1,16})[:：]\\s*(.+)");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 独立投递线程：HTTP/SMTP 可能耗时较久，不能占用全局调度池 */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nexhome-notify");
        t.setDaemon(true);
        return t;
    });

    private NotifyService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/notify/channels", ctx -> ctx.ok(Database.query(
                "SELECT * FROM notify_channel ORDER BY id")));
        WebServer.route("POST", "/api/notify/channels", NotifyService::create);
        WebServer.route("PUT", "/api/notify/channels/{id}", NotifyService::update);
        WebServer.route("DELETE", "/api/notify/channels/{id}", NotifyService::delete);
        // 测试已保存渠道
        WebServer.route("POST", "/api/notify/channels/{id}/test", ctx -> {
            Map<String, Object> ch = mustGet(ctx.paramLong("id"));
            String result = dispatch(str(ch, "type"), JsonUtils.parse(str(ch, "config")),
                    "测试通知", "这是一条来自 NexHome 的测试通知消息。", "notify.test");
            record(num(ch, "id"), str(ch, "name"), "notify.test", "测试通知", result);
            ctx.ok(result.startsWith("OK") ? "测试发送成功：" + result : result);
        });
        // 测试未保存的草稿（编辑弹窗直接试发）
        WebServer.route("POST", "/api/notify/channels/test", ctx -> {
            JsonObject b = ctx.body();
            String type = JsonUtils.str(b, "type");
            JsonObject config = b.has("config") && b.get("config").isJsonObject()
                    ? b.getAsJsonObject("config") : new JsonObject();
            String result = dispatch(type, config, "测试通知",
                    "这是一条来自 NexHome 的测试通知消息。", "notify.test");
            ctx.ok(result.startsWith("OK") ? "测试发送成功：" + result : "测试发送失败：" + result);
        });
        WebServer.route("GET", "/api/notify/logs", ctx -> {
            int limit = 100;
            try {
                String q = ctx.query("limit");
                if (q != null && !q.isBlank()) limit = Math.min(Math.max(Integer.parseInt(q), 1), 200);
            } catch (NumberFormatException ignored) {
            }
            ctx.ok(Database.query("SELECT id, time, channel, event, title, result "
                    + "FROM notify_log ORDER BY id DESC LIMIT " + limit));
        });
    }

    /** 启动时初始化：定期清理过旧通知日志（与 op_log 同策略），保留最近 5000 条 */
    public static void init() {
        Tasks.every(60, 3600, () -> {
            try {
                Database.update("DELETE FROM notify_log WHERE id <= (SELECT MAX(id) - 5000 FROM notify_log)");
            } catch (SQLException ignored) {
            }
            Logs.truncateOld();
        });
    }

    /**
     * 将事件按全部启用渠道异步扇出。无启用渠道时静默返回；调用方无需等待、不会因异常中断。
     *
     * @param event   事件标识（如 monitor.down / auth.lockout），供渠道模板与过滤使用
     * @param title   标题（简短）
     * @param message 正文（详情）
     */
    public static void broadcast(String event, String title, String message) {
        List<Map<String, Object>> channels;
        try {
            channels = Database.query("SELECT * FROM notify_channel WHERE enabled=1 ORDER BY id");
        } catch (SQLException e) {
            Logs.error(Logs.NOTIFY, "查询通知渠道失败: " + e.getMessage());
            return;
        }
        if (channels.isEmpty()) return;
        for (Map<String, Object> ch : channels) {
            String type = str(ch, "type");
            JsonObject config = JsonUtils.parse(str(ch, "config"));
            long id = num(ch, "id");
            String name = str(ch, "name");
            EXEC.submit(() -> {
                String result = dispatch(type, config, title, message, event);
                record(id, name, event, title, result);
            });
        }
    }

    // ---------- 渠道增删改 ----------

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        String type = normalizeType(JsonUtils.str(b, "type"));
        validate(type, configOf(b));
        long id = Database.insert("INSERT INTO notify_channel(name, type, enabled, config) VALUES(?,?,?,?)",
                JsonUtils.str(b, "name"), type, JsonUtils.bool(b, "enabled", true) ? 1 : 0,
                configOf(b).toString());
        Logs.info(Logs.NOTIFY, "新增通知渠道: " + JsonUtils.str(b, "name") + "(" + type + ")");
        ctx.ok(mustGet(id));
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        mustGet(id);
        JsonObject b = ctx.body();
        String type = normalizeType(JsonUtils.str(b, "type"));
        validate(type, configOf(b));
        Database.update("UPDATE notify_channel SET name=?, type=?, enabled=?, config=? WHERE id=?",
                JsonUtils.str(b, "name"), type, JsonUtils.bool(b, "enabled", true) ? 1 : 0,
                configOf(b).toString(), id);
        Logs.info(Logs.NOTIFY, "更新通知渠道 #" + id + ": " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> ch = mustGet(id);
        Database.update("DELETE FROM notify_channel WHERE id=?", id);
        Logs.info(Logs.NOTIFY, "删除通知渠道 #" + id + ": " + ch.get("name"));
        ctx.ok("已删除");
    }

    /** 请求体里的 config 对象（前端统一以 config 字段提交各类型表单） */
    private static JsonObject configOf(JsonObject b) {
        return b.has("config") && b.get("config").isJsonObject()
                ? b.getAsJsonObject("config") : new JsonObject();
    }

    private static String normalizeType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase();
        if (!WEBHOOK.equals(t) && !EMAIL.equals(t) && !DINGTALK.equals(t)) {
            throw new IllegalArgumentException("不支持的通知渠道类型: " + type);
        }
        return t;
    }

    private static void validate(String type, JsonObject c) {
        switch (type) {
            case WEBHOOK -> {
                if (JsonUtils.str(c, "url").isBlank()) throw new IllegalArgumentException("Webhook 地址不能为空");
            }
            case EMAIL -> {
                if (JsonUtils.str(c, "host").isBlank()) throw new IllegalArgumentException("SMTP 服务器不能为空");
                if (JsonUtils.str(c, "to").isBlank()) throw new IllegalArgumentException("收件人不能为空");
            }
            case DINGTALK -> {
                if (JsonUtils.str(c, "webhook").isBlank()) throw new IllegalArgumentException("钉钉机器人 Webhook 不能为空");
            }
            default -> throw new IllegalArgumentException("未知渠道类型: " + type);
        }
    }

    // ---------- 分发投递 ----------

    /** 按类型投递一条通知，返回结果文案（OK... / FAIL(...)），不抛出 */
    private static String dispatch(String type, JsonObject config, String title, String message, String event) {
        try {
            return switch (type) {
                case WEBHOOK -> sendWebhook(config, title, message, event);
                case EMAIL -> sendEmail(config, title, message, event);
                case DINGTALK -> sendDingTalk(config, title, message);
                default -> "FAIL(未知渠道类型 " + type + ")";
            };
        } catch (Exception e) {
            return "FAIL(" + e.getMessage() + ")";
        }
    }

    private static String sendWebhook(JsonObject c, String title, String message, String event) throws Exception {
        Map<String, String> data = placeholders(title, message, event);
        String url = replace(JsonUtils.str(c, "url").trim(), data);
        String method = JsonUtils.str(c, "method").trim().toUpperCase();
        if (!"GET".equals(method)) method = "POST";

        String body = null;
        if ("GET".equals(method)) {
            StringBuilder qs = new StringBuilder();
            data.forEach((k, v) -> {
                if (qs.length() > 0) qs.append('&');
                qs.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            });
            url = url + (url.contains("?") ? "&" : "?") + qs;
        } else {
            body = buildWebhookBody(c, data, title, message, event);
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", "NexHome-Notify");
        boolean hasContentType = false;
        if (c.has("headers") && c.get("headers").isJsonObject()) {
            for (Map.Entry<String, JsonElement> h : c.getAsJsonObject("headers").entrySet()) {
                if (h.getKey().equalsIgnoreCase("Content-Type")) hasContentType = true;
                try {
                    rb.setHeader(h.getKey(), replace(h.getValue().getAsString(), data));
                } catch (Exception ignored) {
                    // 受限/非法头忽略
                }
            }
        }
        if ("GET".equals(method)) {
            rb.GET();
        } else {
            if (!hasContentType) rb.header("Content-Type", "application/json; charset=utf-8");
            rb.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        return code >= 200 && code < 300 ? "OK(HTTP " + code + ")"
                : "FAIL(HTTP " + code + " " + truncate(resp.body()) + ")";
    }

    /** webhook 请求体：优先用户模板（占位符替换），否则默认 JSON（含 title/message/event/time 等） */
    private static String buildWebhookBody(JsonObject c, Map<String, String> data,
                                           String title, String message, String event) {
        String template = JsonUtils.str(c, "bodyTemplate");
        if (!template.isBlank()) return replace(template, data);
        JsonObject payload = new JsonObject();
        payload.addProperty("source", "NexHome");
        payload.addProperty("event", event);
        payload.addProperty("title", title);
        payload.addProperty("message", message);
        payload.addProperty("time", Database.now());
        return JsonUtils.GSON.toJson(payload);
    }

    private static String sendEmail(JsonObject c, String title, String message, String event) throws Exception {
        String host = JsonUtils.str(c, "host").trim();
        int port = JsonUtils.num(c, "port", 465);
        boolean ssl = JsonUtils.bool(c, "ssl", true);
        boolean starttls = JsonUtils.bool(c, "starttls", false);
        String username = JsonUtils.str(c, "username").trim();
        String password = JsonUtils.str(c, "password");
        String from = JsonUtils.str(c, "from").trim();
        String to = JsonUtils.str(c, "to").trim();
        String proto = ssl ? "smtps" : "smtp";

        Properties props = new Properties();
        props.put("mail." + proto + ".host", host);
        props.put("mail." + proto + ".port", String.valueOf(port));
        props.put("mail." + proto + ".connectiontimeout", "15000");
        props.put("mail." + proto + ".timeout", "15000");
        props.put("mail." + proto + ".writetimeout", "15000");
        if (ssl) props.put("mail.smtps.ssl.enable", "true");
        if (starttls) props.put("mail.smtp.starttls.enable", "true");
        boolean auth = !username.isBlank();
        if (auth) props.put("mail." + proto + ".auth", "true");

        Session session = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from.isBlank() ? username : from));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        msg.setSubject("[NexHome] " + title, "UTF-8");
        // multipart/alternative：纯文本兜底 + HTML 美化版，邮件客户端优先渲染 HTML
        String plain = title + "\n\n" + message + "\n\n-- " + Database.now();
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(plain, "UTF-8");
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(buildEmailHtml(title, message, event), "text/html; charset=UTF-8");
        MimeMultipart alt = new MimeMultipart("alternative");
        alt.addBodyPart(textPart);
        alt.addBodyPart(htmlPart);
        msg.setContent(alt);

        Transport t = session.getTransport(proto);
        try {
            t.connect(host, port, auth ? username : null, auth ? password : null);
            t.sendMessage(msg, msg.getAllRecipients());
        } finally {
            try {
                t.close();
            } catch (Exception ignored) {
            }
        }
        return "OK(已发送)";
    }

    /** 邮件 HTML 正文：卡片式布局 + 内联样式（邮件客户端不支持 <style>/外链 CSS），按事件自动配色 */
    private static String buildEmailHtml(String title, String message, String event) {
        String joined = (event == null ? "" : event) + " " + (title == null ? "" : title);
        String accent = "#3b82f6";
        String badgeText = "通知";
        if (joined.contains("down") || joined.contains("告警") || joined.contains("不可用") || joined.contains("失败")) {
            accent = "#e5484d";
            badgeText = "告警";
        } else if (joined.contains("up") || joined.contains("恢复")) {
            accent = "#2fb344";
            badgeText = "恢复";
        }

        StringBuilder rows = new StringBuilder();
        for (String line : message.split("\n")) {
            String text = line.trim();
            if (text.isEmpty()) continue;
            Matcher kv = EMAIL_KV.matcher(text);
            if (kv.matches()) {
                rows.append("<tr>")
                        .append("<td style=\"padding:7px 0;color:#8a94a6;font-size:13px;white-space:nowrap;vertical-align:top;\">")
                        .append(htmlEscape(kv.group(1))).append("</td>")
                        .append("<td style=\"padding:7px 0;color:#2c3444;font-size:14px;word-break:break-all;\">")
                        .append(htmlEscape(kv.group(2))).append("</td></tr>");
            } else {
                rows.append("<tr><td colspan=\"2\" style=\"padding:7px 0;color:#2c3444;font-size:14px;word-break:break-all;\">")
                        .append(htmlEscape(text)).append("</td></tr>");
            }
        }

        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head>"
                + "<body style=\"margin:0;padding:0;\">"
                + "<div style=\"margin:0;padding:24px 12px;background:#f2f4f7;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:560px;margin:0 auto;background:#ffffff;border-radius:10px;overflow:hidden;\">"
                + "<tr><td style=\"height:6px;line-height:6px;font-size:0;background:" + accent + ";\"></td></tr>"
                + "<tr><td style=\"padding:20px 28px 0;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td style=\"font-size:12px;color:#8a94a6;letter-spacing:2px;\">NEXHOME · 事件通知</td>"
                + "<td align=\"right\"><span style=\"display:inline-block;padding:3px 12px;border-radius:999px;"
                + "font-size:12px;color:#ffffff;background:" + accent + ";\">" + badgeText + "</span></td>"
                + "</tr></table></td></tr>"
                + "<tr><td style=\"padding:12px 28px 14px;font-size:18px;font-weight:600;color:#111827;line-height:1.5;\">"
                + htmlEscape(title) + "</td></tr>"
                + "<tr><td style=\"padding:0 28px 20px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-top:1px solid #eef1f5;\">" + rows + "</table></td></tr>"
                + "<tr><td style=\"padding:13px 28px;background:#fafbfc;border-top:1px solid #eef1f5;"
                + "font-size:12px;color:#a4adc0;\">由 NexHome 自动发送 · " + htmlEscape(Database.now()) + "</td></tr>"
                + "</table></div></body></html>";
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String sendDingTalk(JsonObject c, String title, String message) throws Exception {
        String webhook = JsonUtils.str(c, "webhook").trim();
        String secret = JsonUtils.str(c, "secret").trim();
        String url = webhook;
        if (!secret.isEmpty()) {
            long ts = System.currentTimeMillis();
            String sign = dingTalkSign(ts, secret);
            url = webhook + (webhook.contains("?") ? "&" : "?")
                    + "timestamp=" + ts + "&sign=" + sign;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("msgtype", "text");
        JsonObject text = new JsonObject();
        text.addProperty("content", "[NexHome] " + title + "\n" + message + "\n" + Database.now());
        payload.add("text", text);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code < 200 || code >= 300) return "FAIL(HTTP " + code + " " + truncate(resp.body()) + ")";
        // 钉钉即使 HTTP 200 也可能返回 errcode!=0（如加签错误、关键词不匹配）
        JsonObject rj = JsonUtils.parse(resp.body());
        long errcode = rj.has("errcode") ? rj.get("errcode").getAsLong() : 0;
        return errcode == 0 ? "OK(HTTP " + code + ")"
                : "FAIL(errcode=" + errcode + " " + JsonUtils.str(rj, "errmsg") + ")";
    }

    /** 钉钉加签：sign = urlencode(base64(HmacSHA256(timestamp + "\n" + secret, secret))) */
    private static String dingTalkSign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
    }

    // ---------- 记录与工具 ----------

    /** 写一条发送记录到 notify_log */
    private static void record(long channelId, String channelName, String event, String title, String result) {
        boolean ok = result != null && result.startsWith("OK");
        if (ok) {
            Logs.info(Logs.NOTIFY, "通知[" + channelName + "] " + event + " 发送成功: " + result);
        } else {
            Logs.warn(Logs.NOTIFY, "通知[" + channelName + "] " + event + " 发送失败: " + result);
        }
        try {
            Database.insert("INSERT INTO notify_log(time, channel_id, channel, event, title, result) "
                    + "VALUES(?,?,?,?,?,?)", Database.now(), channelId, channelName, event, title, result);
        } catch (SQLException ignored) {
        }
    }

    private static Map<String, String> placeholders(String title, String message, String event) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("title", title);
        data.put("message", message);
        data.put("event", event);
        data.put("time", Database.now());
        return data;
    }

    private static String replace(String text, Map<String, String> data) {
        if (text == null || text.indexOf("${") < 0) return text == null ? "" : text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(data.getOrDefault(m.group(1), "")));
        m.appendTail(sb);
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> ch = Database.queryOne("SELECT * FROM notify_channel WHERE id=?", id);
        if (ch == null) throw new IllegalArgumentException("通知渠道不存在: #" + id);
        return ch;
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
