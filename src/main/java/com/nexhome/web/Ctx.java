package com.nexhome.web;

import com.google.gson.JsonObject;
import com.nexhome.core.JsonUtils;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 请求上下文门面：封装 Javalin 的 {@link Context}，
 * 对外提供项目统一的 JSON 响应（{ok,data} / {ok,error}）、文件下载、
 * 查询参数、请求体解析等便捷方法，使各业务模块与底层 Web 框架解耦。
 */
public final class Ctx {

    private static final String JSON = "application/json; charset=utf-8";

    private final Context ctx;

    public Ctx(Context ctx) {
        this.ctx = ctx;
    }

    /** 获取底层 Javalin 上下文 */
    public Context javalin() {
        return ctx;
    }

    public String method() {
        return ctx.method().name();
    }

    public String path() {
        return ctx.path();
    }

    /** 路径参数，如 /api/ddns/tasks/{id} 中的 id；不存在返回 null */
    public String param(String name) {
        return ctx.pathParamMap().get(name);
    }

    public long paramLong(String name) {
        String v = param(name);
        return v == null ? 0L : Long.parseLong(v);
    }

    /** 查询参数，不存在返回 null */
    public String query(String name) {
        return ctx.queryParam(name);
    }

    /** 请求头，不存在返回 null */
    public String header(String name) {
        return ctx.header(name);
    }

    /** 读取请求体（Javalin 内部已缓存，可多次调用） */
    public String bodyText() {
        return ctx.body();
    }

    /** 请求体解析为 JsonObject */
    public JsonObject body() {
        return JsonUtils.parse(ctx.body());
    }

    // ---------- 响应输出 ----------

    /** 输出 JSON 响应 */
    public void json(int code, Object data) {
        ctx.status(code).contentType(JSON).result(JsonUtils.GSON.toJson(data));
    }

    /** 输出成功 JSON：{ok:true, data:...} */
    public void ok(Object data) {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("data", data);
        json(200, r);
    }

    /** 输出错误 JSON：{ok:false, error:...} */
    public void fail(int code, String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", false);
        r.put("error", message);
        json(code, r);
    }

    /** 文件下载 */
    public void file(Path file, String downloadName) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ctx.header("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
        ctx.status(200).contentType("application/octet-stream").result(bytes);
    }

    /** 纯文本响应（ACME http-01 校验文件） */
    public void text(int code, String content, String contentType) {
        ctx.status(code).contentType(contentType).result(content);
    }

    /** 原始字节响应 */
    public void raw(int code, byte[] bytes, String contentType) {
        ctx.status(code).contentType(contentType).result(bytes);
    }

    public void notFound() {
        fail(404, "接口不存在");
    }
}
