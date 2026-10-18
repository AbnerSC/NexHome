package com.nexhome.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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

    /**
     * 取布尔字段，缺失返回默认值。
     * 兼容关系型列直出的整数/字符串形态（SQLite 布尔列存 0/1），
     * 使「GET 列表行 → 编辑 → PUT 回写」的往返不会把已启用记录静默改为关闭。
     */
    public static boolean bool(JsonObject o, String key, boolean def) {
        try {
            if (!o.has(key) || o.get(key).isJsonNull()) return def;
            JsonElement e = o.get(key);
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble() != 0;
            String v = e.getAsString().trim();
            if (v.equalsIgnoreCase("true")) return true;
            if (v.equalsIgnoreCase("false")) return false;
            return v.equals("1");
        } catch (Exception e) {
            return def;
        }
    }
}
