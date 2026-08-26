package com.nexhome.module.nav;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import java.sql.SQLException;
import java.util.Map;

/**
 * 网站导航服务。
 * <p>
 * 每个条目同时配置内网地址与外网地址；前端根据访问来源智能选择优先地址，
 * 也可手动切换。排序使用权重字段（拖拽排序后批量回传新顺序）。
 */
public final class NavService {

    private NavService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        // 按权重降序返回启用的条目（首页导航）与全部条目（管理页）
        WebServer.route("GET", "/api/nav/items", ctx -> ctx.ok(Database.query(
                "SELECT * FROM nav_item ORDER BY weight DESC, id")));
        WebServer.route("POST", "/api/nav/items", NavService::create);
        WebServer.route("PUT", "/api/nav/items/{id}", NavService::update);
        WebServer.route("DELETE", "/api/nav/items/{id}", NavService::delete);
        // 拖拽排序：前端按新顺序提交 id 数组，权重从大到小赋值
        WebServer.route("PUT", "/api/nav/reorder", ctx -> {
            JsonObject b = ctx.body();
            JsonArray ids = b.has("ids") ? b.getAsJsonArray("ids") : new JsonArray();
            int weight = ids.size() * 10;
            for (var el : ids) {
                Database.update("UPDATE nav_item SET weight=? WHERE id=?", weight, el.getAsLong());
                weight -= 10;
            }
            Logs.info(Logs.NAV, "更新导航排序，共 " + ids.size() + " 项");
            ctx.ok("排序已保存");
        });
    }

    private static void create(Ctx ctx) throws Exception {
        JsonObject b = ctx.body();
        validate(b);
        long id = Database.insert("""
                INSERT INTO nav_item(name, icon_url, description, lan_url, wan_url, weight, enabled)
                VALUES(?,?,?,?,?,?,?)""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "icon_url"), JsonUtils.str(b, "description"),
                JsonUtils.str(b, "lan_url"), JsonUtils.str(b, "wan_url"),
                JsonUtils.num(b, "weight", 0), JsonUtils.bool(b, "enabled", true) ? 1 : 0);
        Logs.info(Logs.NAV, "新增导航: " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void update(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        mustGet(id);
        JsonObject b = ctx.body();
        validate(b);
        Database.update("""
                UPDATE nav_item SET name=?, icon_url=?, description=?, lan_url=?, wan_url=?, weight=?, enabled=?
                WHERE id=?""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "icon_url"), JsonUtils.str(b, "description"),
                JsonUtils.str(b, "lan_url"), JsonUtils.str(b, "wan_url"),
                JsonUtils.num(b, "weight", 0), JsonUtils.bool(b, "enabled", true) ? 1 : 0, id);
        Logs.info(Logs.NAV, "更新导航 #" + id + ": " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> item = mustGet(id);
        Database.update("DELETE FROM nav_item WHERE id=?", id);
        Logs.info(Logs.NAV, "删除导航 #" + id + ": " + item.get("name"));
        ctx.ok("已删除");
    }

    private static void validate(JsonObject b) {
        if (JsonUtils.str(b, "name").isBlank()) throw new IllegalArgumentException("网站名称不能为空");
        if (JsonUtils.str(b, "lan_url").isBlank()) throw new IllegalArgumentException("内网地址不能为空");
        if (JsonUtils.str(b, "wan_url").isBlank()) throw new IllegalArgumentException("外网地址不能为空");
        if (!isUrl(JsonUtils.str(b, "lan_url")) || !isUrl(JsonUtils.str(b, "wan_url"))) {
            throw new IllegalArgumentException("访问地址需以 http:// 或 https:// 开头");
        }
    }

    private static boolean isUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private static Map<String, Object> mustGet(long id) throws SQLException {
        Map<String, Object> item = Database.queryOne("SELECT * FROM nav_item WHERE id=?", id);
        if (item == null) throw new IllegalArgumentException("导航条目不存在: #" + id);
        return item;
    }
}
