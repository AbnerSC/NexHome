package com.nexhome.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SQLite 数据访问层。
 * <p>
 * 面向轻量场景：单连接 + 方法级同步，避免多线程写冲突（SQLite 单写者模型）。
 * 所有模块通过本类的静态方法访问数据库。
 */
public final class Database {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static Connection conn;

    private Database() {
    }

    /** 初始化数据库连接并执行建表脚本 */
    public static synchronized void init() throws SQLException, IOException {
        String url = "jdbc:sqlite:" + AppConfig.DATA_DIR.resolve("nexhome.db");
        conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            // WAL 模式提升并发读写性能；外键开启
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        execScript(loadResource("/schema.sql"));
    }

    /** 关闭数据库连接 */
    public static synchronized void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }

    /** 执行建表脚本：按分号拆分逐条执行（忽略注释行与空语句） */
    public static synchronized void execScript(String script) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String part : script.split(";")) {
                StringBuilder sb = new StringBuilder();
                for (String line : part.split("\n")) {
                    String t = line.trim();
                    if (!t.startsWith("--")) sb.append(line).append('\n');
                }
                String sql = sb.toString().trim();
                if (!sql.isEmpty()) {
                    st.execute(sql);
                }
            }
        }
    }

    /** 执行 INSERT，返回自增主键 */
    public static synchronized long insert(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    /** 执行 UPDATE / DELETE，返回影响行数 */
    public static synchronized int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** 查询多行，每行转为 列名->值 的有序 Map */
    public static synchronized List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** 查询单行，无结果返回 null */
    public static synchronized Map<String, Object> queryOne(String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询单个标量值（第一行第一列） */
    public static synchronized Object scalar(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    // ---------- app_config 便捷读写 ----------

    public static synchronized String getConfig(String key) throws SQLException {
        Object v = scalar("SELECT value FROM app_config WHERE key=?", key);
        return v == null ? null : v.toString();
    }

    public static synchronized void setConfig(String key, String value) throws SQLException {
        update("INSERT INTO app_config(key, value) VALUES(?, ?) " +
               "ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value);
    }

    /** 当前本地时间字符串，用于日志与状态时间 */
    public static String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    // ---------- 内部工具 ----------

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(Database.class.getResourceAsStream(path))) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8);
        }
    }
}
