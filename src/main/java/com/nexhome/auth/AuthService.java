package com.nexhome.auth;

import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import com.nexhome.module.notify.NotifyService;

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
    /** 安全配置：最大连续失败次数（默认 3） */
    public static final String KEY_MAX_FAILS = "security.login.maxFails";
    /** 安全配置：达到上限后的锁定分钟数（默认 30） */
    public static final String KEY_LOCK_MINUTES = "security.login.lockMinutes";
    private static final int INIT_PASSWORD_LENGTH = 16;
    private static final long TOKEN_TTL_MS = 24L * 3600 * 1000;
    private static final int DEFAULT_MAX_FAILS = 3;
    private static final int DEFAULT_LOCK_MINUTES = 30;

    /** token -> 过期时间戳 */
    private static final Map<String, Long> TOKENS = new ConcurrentHashMap<>();
    /** 客户端 IP -> 失败/锁定状态（进程内存态，重启清零） */
    private static final Map<String, FailState> FAILS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 单 IP 的失败计数与锁定窗口 */
    private static final class FailState {
        int count;
        long lockUntilMs;
    }

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

    /**
     * 登录校验（含失败锁定与单点登录）。
     * <p>成功：清除该 IP 失败计数、踢掉其余全部会话（仅保留本次新 token）后颁发 token；
     * 失败：累计该 IP 失败次数，达到阈值即锁定并触发告警。调用方应先经 {@link #lockRemainingMs} 拦截锁定态。
     *
     * @param ip 客户端来源 IP，用于失败计数与锁定
     * @return 成功返回 token；密码错误返回 null
     */
    public static String login(String password, String ip) throws SQLException {
        String hash = Database.getConfig(KEY_PASSWORD);
        if (hash != null && hash.equalsIgnoreCase(sha256(password))) {
            byte[] buf = new byte[24];
            RANDOM.nextBytes(buf);
            String token = HexFormat.of().formatHex(buf);
            FAILS.remove(ip);
            // 单点登录：新登录自动注销其余会话
            TOKENS.clear();
            TOKENS.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
            Logs.info(Logs.AUTH, "用户登录成功");
            return token;
        }
        recordFailure(ip);
        Logs.warn(Logs.AUTH, "登录失败：密码错误 (IP=" + ip + ")");
        return null;
    }

    /** 记录一次失败，达到阈值时进入锁定窗口并广播告警 */
    private static void recordFailure(String ip) throws SQLException {
        int max = getMaxFails();
        FailState st = FAILS.computeIfAbsent(ip, k -> new FailState());
        synchronized (st) {
            long now = System.currentTimeMillis();
            if (st.lockUntilMs > now) return; // 已锁定不重复计数
            st.count++;
            if (st.count >= max) {
                int minutes = getLockMinutes();
                st.lockUntilMs = now + minutes * 60_000L;
                st.count = 0;
                Logs.warn(Logs.AUTH, "IP " + ip + " 连续登录失败达 " + max + " 次，锁定 " + minutes + " 分钟");
                try {
                    NotifyService.broadcast("auth.lockout", "登录安全锁定",
                            "来源 IP " + ip + " 连续登录失败 " + max + " 次，已锁定 " + minutes + " 分钟。");
                } catch (Exception ignored) {
                    // 告警失败不影响锁定本身
                }
            }
        }
    }

    /** 该 IP 剩余锁定毫秒数，0 表示未锁定 */
    public static long lockRemainingMs(String ip) {
        FailState st = FAILS.get(ip);
        if (st == null) return 0;
        long remain = st.lockUntilMs - System.currentTimeMillis();
        return Math.max(0, remain);
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

    /** 最大连续失败次数（配置缺失/非法回退默认 3） */
    public static int getMaxFails() throws SQLException {
        return readIntConfig(KEY_MAX_FAILS, DEFAULT_MAX_FAILS, 1, 100);
    }

    /** 锁定时长（分钟，配置缺失/非法回退默认 30） */
    public static int getLockMinutes() throws SQLException {
        return readIntConfig(KEY_LOCK_MINUTES, DEFAULT_LOCK_MINUTES, 1, 1440);
    }

    /** 设置登录安全参数：最大失败次数与锁定分钟数 */
    public static void setSecurity(int maxFails, int lockMinutes) throws SQLException {
        if (maxFails < 1 || maxFails > 100) throw new IllegalArgumentException("最大失败次数需在 1~100 之间");
        if (lockMinutes < 1 || lockMinutes > 1440) throw new IllegalArgumentException("锁定时间需在 1~1440 分钟之间");
        Database.setConfig(KEY_MAX_FAILS, String.valueOf(maxFails));
        Database.setConfig(KEY_LOCK_MINUTES, String.valueOf(lockMinutes));
    }

    private static int readIntConfig(String key, int def, int min, int max) throws SQLException {
        String v = Database.getConfig(key);
        if (v == null || v.isBlank()) return def;
        try {
            int n = Integer.parseInt(v.trim());
            return Math.min(Math.max(n, min), max);
        } catch (NumberFormatException e) {
            return def;
        }
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
