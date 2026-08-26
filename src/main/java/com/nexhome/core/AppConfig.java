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
 * 配置文件为程序运行目录下的 {@code nexhome.properties}，首次启动自动生成默认文件。
 * 仅包含少量基础项（端口等），业务配置全部持久化在 SQLite 中。
 */
public final class AppConfig {

    /** 运行目录（jar 所在目录） */
    public static final Path WORK_DIR = Path.of("").toAbsolutePath();
    /** 数据目录：数据库与证书文件均存放于此 */
    public static final Path DATA_DIR = WORK_DIR.resolve("data");
    /** 配置文件路径 */
    public static final Path CONFIG_FILE = WORK_DIR.resolve("nexhome.properties");

    private static final Properties props = new Properties();

    private AppConfig() {
    }

    /** 启动时加载配置，文件不存在则写入默认配置 */
    public static synchronized void load() throws IOException {
        Files.createDirectories(DATA_DIR);
        if (!Files.exists(CONFIG_FILE)) {
            props.setProperty("server.port", "8090");
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "NexHome configuration. server.port: Web service port.");
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
}
