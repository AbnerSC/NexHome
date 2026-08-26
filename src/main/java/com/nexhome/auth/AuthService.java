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
 * 首次启动随机生成高强度初始密码并打印到控制台，必须登录后在"设置"中修改。
 * 登录成功后颁发随机 Token（内存保存，有效期 24 小时），
 * 前端通过请求头 X-Token 携带。
 */
public final class AuthService {

    public static final String KEY_PASSWORD = "auth.password";
    private static final int INIT_PASSWORD_LENGTH = 16;
    private static final long TOKEN_TTL_MS = 24L * 3600 * 1000;

    /** token -> 过期时间戳 */
    private static final Map<String, Long> TOKENS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthService() {
    }

    /** 初始化随机密码（仅首次启动），明文只打印到控制台，不落日志表 */
    public static void init() throws SQLException {
        if (Database.getConfig(KEY_PASSWORD) == null) {
            String password = randomPassword(INIT_PASSWORD_LENGTH);
            Database.setConfig(KEY_PASSWORD, sha256(password));
            System.out.println("============================================================");
            System.out.println(" 首次启动，已生成随机初始密码，请登录后尽快在设置中修改！");
            System.out.println(" 初始密码: " + password);
            System.out.println("============================================================");
        }
    }

    /** 随机生成高强度密码：大小写字母 + 数字 + 特殊符号，且各类至少出现一次 */
    private static String randomPassword(int length) {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String digit = "23456789";
        String symbol = "!@#$%^&*-_=+";
        String all = upper + lower + digit + symbol;
        char[] buf = new char[Math.max(length, 4)];
        buf[0] = upper.charAt(RANDOM.nextInt(upper.length()));
        buf[1] = lower.charAt(RANDOM.nextInt(lower.length()));
        buf[2] = digit.charAt(RANDOM.nextInt(digit.length()));
        buf[3] = symbol.charAt(RANDOM.nextInt(symbol.length()));
        for (int i = 4; i < buf.length; i++) {
            buf[i] = all.charAt(RANDOM.nextInt(all.length()));
        }
        // 打乱顺序，避免前几位固定为"大小写+数字+符号"
        for (int i = buf.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = buf[i];
            buf[i] = buf[j];
            buf[j] = t;
        }
        return new String(buf);
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
