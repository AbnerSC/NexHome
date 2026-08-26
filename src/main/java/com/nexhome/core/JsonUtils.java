package com.nexhome.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON 工具：全局共享一个 Gson 实例（Gson 线程安全）。
 */
public final class JsonUtils {

    public static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private JsonUtils() {
    }

    /** 解析 JSON 字符串为 JsonObject，解析失败返回空对象 */
    public static JsonObject parse(String text) {
        if (text == null || text.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    /** 取字符串字段，缺失返回空串 */
    public static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 取整型字段，缺失/非法返回默认值 */
    public static int num(JsonObject o, String key, int def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** 取布尔字段，缺失返回默认值 */
    public static boolean bool(JsonObject o, String key, boolean def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
