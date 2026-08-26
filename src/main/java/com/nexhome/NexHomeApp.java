package com.nexhome;

import com.nexhome.auth.AuthService;
import com.nexhome.core.AppConfig;
import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import com.nexhome.core.Tasks;
import com.nexhome.module.cert.CertService;
import com.nexhome.module.ddns.DdnsService;
import com.nexhome.module.nav.NavService;
import com.nexhome.module.stun.StunService;
import com.nexhome.module.wol.WolService;
import com.nexhome.web.SystemRoutes;
import com.nexhome.web.WebServer;

/**
 * NexHome（联枢）主程序入口。
 * <p>
 * 一站式内网节点守护工具：DDNS 域名同步 / STUN 端口穿透 / WOL 网络唤醒 /
 * SSL 证书自动续期 / 网站导航，前后端一体化单进程部署。
 */
public final class NexHomeApp {

    private NexHomeApp() {
    }

    static void main(String[] args) {
        System.out.println("""
                ========================================
                  NexHome 联枢 · 内网节点守护工具
                ========================================""");
        try {
            // 1. 加载基础配置（端口等），不存在则自动生成配置文件
            AppConfig.load();

            // 2. 初始化 SQLite 数据库（运行目录 data/nexhome.db）并执行建表脚本
            Database.init();

            // 3. 初始化登录密码（首次启动默认 admin）
            AuthService.init();

            // 4. 注册全部 REST 接口
            SystemRoutes.register();
            DdnsService.registerRoutes();
            StunService.registerRoutes();
            WolService.registerRoutes();
            CertService.registerRoutes();
            NavService.registerRoutes();

            // 5. 启动业务调度与后台任务
            CertService.init();      // 证书自动续期检查
            DdnsService.init();      // 恢复 DDNS 定时同步
            StunService.init();      // 恢复运行中的穿透任务

            // 6. 启动内置 Web 服务器（前端静态资源 + API 同一端口）
            WebServer.start(AppConfig.port());

            Logs.info(Logs.SYS, "NexHome 启动完成，数据目录: " + AppConfig.DATA_DIR);

            // 7. 优雅退出
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Logs.info(Logs.SYS, "正在关闭...");
                Tasks.shutdown();
                Database.close();
            }));
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
