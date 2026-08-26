package com.nexhome.auth;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易内置鉴权。
 * <p>
 * 密码以 SHA-256 哈希保存在 app_config 表（key=auth.password），
 * 首次启动默认密码为 {@code admin}，必须登录后在"设置"中修改。
 * 登录成功后颁发随机 Token（内存保存，有效期 24 小时），
 * 前端通过请求头 X-Token 携带。
 */
public final class AuthService {

    public static final String KEY_PASSWORD = "auth.password";
    private static final String DEFAULT_PASSWORD = "admin";
    private static final long TOKEN_TTL_MS = 24L * 3600 * 1000;

    /** token -> 过期时间戳 */
    private static final Map<String, Long> TOKENS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthService() {
    }

    /** 初始化默认密码（仅首次启动） */
    public static void init() throws SQLException {
        if (Database.getConfig(KEY_PASSWORD) == null) {
            Database.setConfig(KEY_PASSWORD, sha256(DEFAULT_PASSWORD));
            Logs.warn(Logs.AUTH, "已设置默认登录密码 admin，请登录后尽快在设置中修改");
        }
    }

    /** 登录校验，成功返回 token，失败返回 null */
    public static String login(String password) throws SQLException {
        String hash = Database.getConfig(KEY_PASSWORD);
        if (hash != null && hash.equalsIgnoreCase(sha256(password))) {
            byte[] buf = new byte[24];
            RANDOM.nextBytes(buf);
            String token = HexFormat.of().formatHex(buf);
            TOKENS.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
            Logs.info(Logs.AUTH, "用户登录成功");
            return token;
        }
        Logs.warn(Logs.AUTH, "登录失败：密码错误");
        return null;
    }

    /** 校验 token 是否有效 */
    public static boolean check(String token) {
        if (token == null || token.isBlank()) return false;
        Long expire = TOKENS.get(token);
        if (expire == null) return false;
        if (expire < System.currentTimeMillis()) {
            TOKENS.remove(token);
            return false;
        }
        return true;
    }

    public static void logout(String token) {
        if (token != null) {
            TOKENS.remove(token);
            Logs.info(Logs.AUTH, "用户退出登录");
        }
    }

    /** 修改密码：校验旧密码后更新 */
    public static boolean changePassword(String oldPwd, String newPwd) throws SQLException {
        String hash = Database.getConfig(KEY_PASSWORD);
        if (hash == null || !hash.equalsIgnoreCase(sha256(oldPwd))) {
            return false;
        }
        Database.setConfig(KEY_PASSWORD, sha256(newPwd));
        TOKENS.clear(); // 修改密码后强制全部重新登录
        Logs.info(Logs.AUTH, "登录密码已修改，所有会话已失效");
        return true;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
