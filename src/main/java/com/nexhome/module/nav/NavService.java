package com.nexhome.module.nav;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nexhome.core.AppConfig;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.web.Ctx;
import com.nexhome.web.WebServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 网站导航服务。
 * <p>
 * 每个条目至少配置内网地址或外网地址之一，也可两者都填；前端根据访问来源智能选择优先地址，
 * 缺失时自动回退到另一地址，也可手动切换。排序使用权重字段（拖拽排序后批量回传新顺序）。
 */
public final class NavService {

    /** 内网探测链接配置键：浏览器探测该地址可达时判定当前处于内网（如路由器管理地址），空=未启用 */
    private static final String KEY_LAN_PROBE_URL = "nav.lan_probe_url";
    /** 导航页背景图配置键：系统内路径（/images/xx、/nav-icons/xx）或外部 http(s) 直链，空=未启用 */
    private static final String KEY_BG_URL = "nav.bg_url";
    /** 本地图片目录：用户自行放置文件后经 /images/bg.png 这类系统内地址引用 */
    private static final Path IMG_DIR = AppConfig.DATA_DIR.resolve("images");
    /** 本地图片文件名白名单：字母/数字/中文/._- + 图片扩展名，不含路径分隔符以防穿越 */
    private static final Pattern IMG_NAME = Pattern.compile("[\\w.\\-\\u4e00-\\u9fa5]+\\.(png|jpe?g|gif|webp|svg|ico)");

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
        // 本地图标上传（multipart），返回相对 URL 回填 icon_url
        WebServer.route("POST", "/api/nav/icons", NavService::uploadIcon);
        // 图标展示：位于 /api/** 之外故免登录，供 <img> 直接加载
        WebServer.route("GET", "/nav-icons/{file}", ctx -> NavIconStore.serve(ctx, ctx.param("file")));
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
        // 内网探测链接配置：经反代/CDN 访问时连接源 IP 全是内网代理地址，服务端无法判断访客真实位置，
        // 改由浏览器在本地探测固定内网地址（如路由器）来判定，探测结果最贴近访客实际所在网络
        WebServer.route("GET", "/api/nav/settings", ctx -> {
            String probe = Database.getConfig(KEY_LAN_PROBE_URL);
            String bg = Database.getConfig(KEY_BG_URL);
            ctx.ok(Map.of("lanProbeUrl", probe == null ? "" : probe, "bgUrl", bg == null ? "" : bg));
        });
        WebServer.route("PUT", "/api/nav/settings", ctx -> {
            JsonObject b = ctx.body();
            // 探测链接与背景图由不同表单提交，按键存在与否分别更新，避免互相覆盖
            if (b.has("lanProbeUrl")) {
                String url = JsonUtils.str(b, "lanProbeUrl").trim();
                if (!url.isBlank() && !isUrl(url))
                    throw new IllegalArgumentException("内网探测链接需以 http:// 或 https:// 开头");
                Database.setConfig(KEY_LAN_PROBE_URL, url);
                Logs.info(Logs.NAV, url.isBlank() ? "已清空内网探测链接" : "内网探测链接已设置: " + url);
            }
            if (b.has("bgUrl")) {
                String bg = JsonUtils.str(b, "bgUrl").trim();
                if (!bg.isBlank() && !isUrl(bg) && !bg.startsWith("/"))
                    throw new IllegalArgumentException("背景图需为 / 开头的系统内路径或 http(s) 地址");
                Database.setConfig(KEY_BG_URL, bg);
                Logs.info(Logs.NAV, bg.isBlank() ? "已清空导航背景图" : "导航背景图已设置: " + bg);
            }
            ctx.ok("已保存");
        });
        // 图片管理：列出导航菜单上传的本地图片（附引用它的导航名），供面板预览/复制路径/删除
        WebServer.route("GET", "/api/nav/images", ctx -> {
            List<Map<String, Object>> imgs = NavIconStore.list();
            Map<String, List<String>> refs = new HashMap<>();
            for (Map<String, Object> row : Database.query("SELECT name, icon_url FROM nav_item")) {
                Object u = row.get("icon_url");
                if (u != null && !u.toString().isBlank())
                    refs.computeIfAbsent(u.toString(), k -> new ArrayList<>()).add(str(row.get("name")));
            }
            for (Map<String, Object> m : imgs) m.put("refs", refs.getOrDefault(m.get("url"), List.of()));
            ctx.ok(Map.of("images", imgs));
        });
        WebServer.route("DELETE", "/api/nav/images/{file}", ctx -> {
            String file = ctx.param("file");
            String url = NavIconStore.URL_PREFIX + file;
            List<String> names = new ArrayList<>();
            for (Map<String, Object> row : Database.query("SELECT name FROM nav_item WHERE icon_url=?", url))
                names.add(str(row.get("name")));
            if (!names.isEmpty())
                throw new IllegalArgumentException("图片正被导航「" + String.join("、", names) + "」引用，请先更换图标再删除");
            if (url.equals(Database.getConfig(KEY_BG_URL)))
                throw new IllegalArgumentException("图片当前用作导航背景，请先更换背景再删除");
            if (!NavIconStore.delete(file)) throw new IllegalArgumentException("图片不存在或已删除");
            Logs.info(Logs.NAV, "删除导航图片: " + url);
            ctx.ok("已删除");
        });
        // 本地图片目录展示：data/images 由用户自行放置文件，/images/bg.png 免登录供背景 CSS 加载
        WebServer.route("GET", "/images/{file}", NavService::serveLocalImage);
    }

    /** 本地图片目录（data/images）展示路由（免登录）：文件名白名单 + 目录穿越防护 */
    private static void serveLocalImage(Ctx ctx) throws Exception {
        String file = ctx.param("file");
        Path p = file == null ? null : IMG_DIR.resolve(file).normalize();
        if (file == null || !IMG_NAME.matcher(file).matches() || !p.startsWith(IMG_DIR) || !Files.isRegularFile(p)) {
            ctx.fail(404, "图片不存在");
            return;
        }
        ctx.javalin().header("Cache-Control", "public, max-age=3600");
        ctx.raw(200, Files.readAllBytes(p), NavIconStore.typeOf(file));
    }

    /** 启动维护：清理上传后取消表单遗留的孤儿图标文件 */
    public static void init() {
        NavIconStore.cleanOrphans();
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
        Map<String, Object> old = mustGet(id);
        JsonObject b = ctx.body();
        validate(b);
        Database.update("""
                UPDATE nav_item SET name=?, icon_url=?, description=?, lan_url=?, wan_url=?, weight=?, enabled=?
                WHERE id=?""",
                JsonUtils.str(b, "name"), JsonUtils.str(b, "icon_url"), JsonUtils.str(b, "description"),
                JsonUtils.str(b, "lan_url"), JsonUtils.str(b, "wan_url"),
                JsonUtils.num(b, "weight", 0), JsonUtils.bool(b, "enabled", true) ? 1 : 0, id);
        // 更换图标后清理不再被引用的旧本地文件（仍被引用时内部自动跳过）
        NavIconStore.deleteIfUnreferenced(str(old.get("icon_url")));
        Logs.info(Logs.NAV, "更新导航 #" + id + ": " + JsonUtils.str(b, "name"));
        ctx.ok(mustGet(id));
    }

    private static void delete(Ctx ctx) throws Exception {
        long id = ctx.paramLong("id");
        Map<String, Object> item = mustGet(id);
        Database.update("DELETE FROM nav_item WHERE id=?", id);
        NavIconStore.deleteIfUnreferenced(str(item.get("icon_url")));
        Logs.info(Logs.NAV, "删除导航 #" + id + ": " + item.get("name"));
        ctx.ok("已删除");
    }

    /** 图标上传：落盘本地目录后返回相对 URL，供前端回填表单 icon_url */
    private static void uploadIcon(Ctx ctx) throws Exception {
        var up = ctx.javalin().uploadedFile("file");
        if (up == null) throw new IllegalArgumentException("请选择要上传的图标文件");
        byte[] bytes;
        try (var in = up.content()) {
            bytes = in.readAllBytes();
        }
        String url = NavIconStore.save(up.filename(), bytes);
        Logs.info(Logs.NAV, "上传导航图标: " + url);
        ctx.ok(Map.of("url", url));
    }

    private static void validate(JsonObject b) {
        if (JsonUtils.str(b, "name").isBlank()) throw new IllegalArgumentException("网站名称不能为空");
        String lan = JsonUtils.str(b, "lan_url");
        String wan = JsonUtils.str(b, "wan_url");
        // 两地址允许只填其一，但至少要有其一
        if (lan.isBlank() && wan.isBlank()) throw new IllegalArgumentException("内网地址与外网地址至少填写一个");
        if ((!lan.isBlank() && !isUrl(lan)) || (!wan.isBlank() && !isUrl(wan))) {
            throw new IllegalArgumentException("访问地址需以 http:// 或 https:// 开头");
        }
        String icon = JsonUtils.str(b, "icon_url");
        if (!icon.isBlank() && !isUrl(icon) && !icon.startsWith(NavIconStore.URL_PREFIX)) {
            throw new IllegalArgumentException("图标需为上传后的本地路径或以 http(s):// 开头的外部地址");
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
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
