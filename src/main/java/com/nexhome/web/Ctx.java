package com.nexhome.web;

import com.google.gson.JsonObject;
import com.nexhome.core.JsonUtils;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 请求上下文：封装 JDK 内置 HttpServer 的 HttpExchange，
 * 提供 JSON 响应、文件下载、查询参数、请求体解析等便捷方法。
 */
public final class Ctx {

    private final HttpExchange ex;
    private final Map<String, String> pathParams;
    private String bodyCache;

    public Ctx(HttpExchange ex, Map<String, String> pathParams) {
        this.ex = ex;
        this.pathParams = pathParams;
    }

    /** 获取底层 HttpExchange（用于路由命中后携带路径参数重新包装） */
    public HttpExchange exchange() {
        return ex;
    }

    public String method() {
        return ex.getRequestMethod();
    }

    public String path() {
        return ex.getRequestURI().getPath();
    }

    /** 路径参数，如 /api/ddns/tasks/{id} 中的 id */
    public String param(String name) {
        return pathParams.get(name);
    }

    public long paramLong(String name) {
        return Long.parseLong(pathParams.getOrDefault(name, "0"));
    }

    /** 查询参数 */
    public String query(String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            if (k.equals(name)) {
                return i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 请求头 */
    public String header(String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    /** 读取请求体（缓存，可多次调用） */
    public String bodyText() throws IOException {
        if (bodyCache == null) {
            try (InputStream in = ex.getRequestBody()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                in.transferTo(buf);
                bodyCache = buf.toString(StandardCharsets.UTF_8);
            }
        }
        return bodyCache;
    }

    /** 请求体解析为 JsonObject */
    public JsonObject body() throws IOException {
        return JsonUtils.parse(bodyText());
    }

    // ---------- 响应输出 ----------

    /** 输出 JSON 响应 */
    public void json(int code, Object data) throws IOException {
        byte[] bytes = JsonUtils.GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 输出成功 JSON：{ok:true, data:...} */
    public void ok(Object data) throws IOException {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("data", data);
        json(200, r);
    }

    /** 输出错误 JSON：{ok:false, error:...} */
    public void fail(int code, String message) throws IOException {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", false);
        r.put("error", message);
        json(code, r);
    }

    /** 文件下载 */
    public void file(Path file, String downloadName) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + downloadName + "\"");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 纯文本响应（ACME http-01 校验文件） */
    public void text(int code, String content, String contentType) throws IOException {
        raw(code, content.getBytes(StandardCharsets.UTF_8), contentType);
    }

    /** 原始字节响应 */
    public void raw(int code, byte[] bytes, String contentType) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public void notFound() throws IOException {
        fail(404, "接口不存在");
    }

    /** 释放底层连接 */
    public void close() {
        ex.close();
    }
}
