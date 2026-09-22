package com.nexhome.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 全局应用配置。
 * <p>
 * 配置文件为数据目录下的 {@code nexhome.properties}，首次启动自动生成默认文件。
 * 仅包含少量基础项（端口等），业务配置全部持久化在 SQLite 中。
 */
public final class AppConfig {

    /** 运行目录（jar 所在目录） */
    public static final Path WORK_DIR = Path.of("").toAbsolutePath();
    /** 数据目录：数据库与证书文件均存放于此 */
    public static final Path DATA_DIR = WORK_DIR.resolve("data");
    /** 配置文件路径（位于数据目录下） */
    public static final Path CONFIG_FILE = DATA_DIR.resolve("nexhome.properties");

    private static final Properties props = new Properties();

    private AppConfig() {
    }

    /** 启动时加载配置，文件不存在则写入默认配置 */
    public static synchronized void load() throws IOException {
        Files.createDirectories(DATA_DIR);
        if (!Files.exists(CONFIG_FILE)) {
            props.setProperty("server.port", "8090");
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "NexHome configuration. server.port: Web service port. server.web.dir: optional external web static dir.");
            }
            return;
        }
        try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
            props.load(in);
        }
    }

    /** Web 服务端口，默认 8090 */
    public static int port() {
        try {
            return Integer.parseInt(props.getProperty("server.port", "8090").trim());
        } catch (NumberFormatException e) {
            return 8090;
        }
    }

    /**
     * 外部前端资源目录（可选配置项 server.web.dir）。
     * 配置且目录存在时，静态资源优先从该目录读取而非 jar 内置资源；
     * 相对路径基于运行目录解析。
     */
    public static Path webDir() {
        String v = props.getProperty("server.web.dir", "").trim();
        if (v.isEmpty()) return null;
        Path p = Path.of(v);
        return p.isAbsolute() ? p : WORK_DIR.resolve(p);
    }
}
