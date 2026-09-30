package com.nexhome.module.nav;

import com.nexhome.core.AppConfig;
import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import com.nexhome.web.Ctx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 导航图标本地存储。
 * <p>
 * 表单上传的图标落盘 {@code data/nav-icons/} 目录（与数据库同目录，沿用现有备份策略），
 * 文件名为随机 UUID + 白名单扩展名，nav_item.icon_url 存相对 URL {@code /nav-icons/xxx.png}。
 * 展示路由位于 /api/** 之外故免登录（&lt;img&gt; 标签无法携带 X-Token 头），
 * 文件名不可猜测且图标本身不敏感，安全可接受。
 */
public final class NavIconStore {

    /** 图标相对 URL 前缀（nav_item.icon_url 以此开头即为本地图标） */
    public static final String URL_PREFIX = "/nav-icons/";
    /** 图标单文件大小上限 */
    private static final int MAX_SIZE = 2 * 1024 * 1024;
    /** 图标存储目录 */
    private static final Path DIR = AppConfig.DATA_DIR.resolve("nav-icons");
    /** 扩展名白名单 -> Content-Type */
    private static final Map<String, String> TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp", "svg", "image/svg+xml",
            "ico", "image/x-icon");
    /** 本地图标文件名约定：UUID + 白名单扩展名（serve/删除时校验，防路径穿越） */
    private static final Pattern NAME = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(png|jpe?g|gif|webp|svg|ico)");

    private NavIconStore() {
    }

    /** 保存上传字节为本地图标，返回存入 icon_url 的相对 URL */
    public static String save(String originalName, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("图标文件内容为空");
        if (bytes.length > MAX_SIZE) throw new IllegalArgumentException("图标文件不能超过 2MB");
        String ext = extOf(originalName);
        if (!TYPES.containsKey(ext)) {
            throw new IllegalArgumentException("不支持的图标格式，仅支持 png/jpg/gif/webp/svg/ico");
        }
        Files.createDirectories(DIR);
        String name = UUID.randomUUID() + "." + ext;
        Files.write(DIR.resolve(name), bytes);
        return URL_PREFIX + name;
    }

    /** 图标展示路由（免登录）：按文件名输出图片字节，文件名不可变故长缓存 */
    public static void serve(Ctx ctx, String file) throws IOException {
        if (file == null || !NAME.matcher(file).matches()) {
            ctx.fail(404, "图标不存在");
            return;
        }
        Path p = DIR.resolve(file);
        if (!p.startsWith(DIR) || !Files.isRegularFile(p)) {
            ctx.fail(404, "图标不存在");
            return;
        }
        ctx.javalin().header("Cache-Control", "public, max-age=604800");
        ctx.raw(200, Files.readAllBytes(p), TYPES.get(extOf(file)));
    }

    /** 仅当本地图标 URL 不再被任何导航条目引用时删除对应文件（避免误删共享图标） */
    public static void deleteIfUnreferenced(String iconUrl) {
        if (iconUrl == null || !iconUrl.startsWith(URL_PREFIX)) return;
        String name = iconUrl.substring(URL_PREFIX.length());
        if (!NAME.matcher(name).matches()) return;
        try {
            Map<String, Object> row = Database.queryOne("SELECT COUNT(*) AS c FROM nav_item WHERE icon_url=?", iconUrl);
            if (row != null && row.get("c") instanceof Number n && n.longValue() > 0) return;
            Files.deleteIfExists(DIR.resolve(name));
        } catch (Exception e) {
            Logs.warn(Logs.NAV, "删除本地图标失败 " + iconUrl + ": " + e.getMessage());
        }
    }

    /** 启动维护：清理目录中未被任何导航条目引用的残留文件（如上传后取消表单遗留） */
    public static void cleanOrphans() {
        if (!Files.isDirectory(DIR)) return;
        int cleaned = 0;
        try (var stream = Files.list(DIR)) {
            Set<String> referenced = new HashSet<>();
            for (Map<String, Object> row : Database.query("SELECT icon_url FROM nav_item")) {
                Object v = row.get("icon_url");
                if (v != null) referenced.add(v.toString());
            }
            for (Path p : stream.toList()) {
                if (!referenced.contains(URL_PREFIX + p.getFileName()) && Files.deleteIfExists(p)) cleaned++;
            }
        } catch (Exception e) {
            Logs.warn(Logs.NAV, "清理孤儿图标失败: " + e.getMessage());
            return;
        }
        if (cleaned > 0) Logs.info(Logs.NAV, "清理孤儿导航图标 " + cleaned + " 个");
    }

    /** 文件名小写扩展名（不含点），无扩展名返回空串 */
    private static String extOf(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }
}
