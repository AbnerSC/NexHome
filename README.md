# NexHome｜联枢

## GitHub
https://github.com/AbnerSC/NexHome.git

[![GitHub Stars](https://img.shields.io/github/stars/AbnerSC/NexHome?style=flat&label=Stars)](https://github.com/AbnerSC/NexHome/stargazers)
[![Docker Pulls](https://img.shields.io/docker/pulls/babyfly/nex-home?label=Docker%20Pulls)](https://hub.docker.com/r/babyfly/nex-home)

> 一站式内网节点守护工具，面向家庭 / 小型机房服务器，把内网能力安全对外打通。

NexHome 是一款单进程、前后端一体化的轻量 Java 服务，内置 Web 服务器与 Web 管理界面，
集成 **DDNS 域名同步、STUN 端口穿透、WOL 网络唤醒、SSL 证书自动申请续期、网站导航、Docker 容器观测** 六大功能。
只启动一个 Java 程序、只占用一个端口，配置与日志全部持久化在 SQLite（程序运行目录 `data/`）。

---

## 一、Docker Compose 部署与使用说明

compose 部署示例（host 网络 + docker.sock 挂载）：

```yaml
services:
  nex-home:
    image: scdm/nex-home:latest
    container_name: nex-home
    restart: unless-stopped
    network_mode: host
    volumes:
      - ./data:/app/data
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      - TZ=Asia/Shanghai
      - DOCKER_HOST=unix:///var/run/docker.sock
    mem_limit: 512m          # 256m→512m：容纳 -Xmx256m + Metaspace128m + CodeCache64m，否则 OOM Kill
    healthcheck:
      # 镜像无 curl，改用 bash /dev/tcp 探测端口（仅验证端口可连）
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/8090 && echo -n '' >&3"]
      interval: 10s
      timeout: 3s
      retries: 3
      start_period: 20s
```

启动：

```bash
docker compose up -d
```

使用说明：

- 启动后访问：**http://127.0.0.1:8090**（默认端口）
- 首次启动自动生成配置文件 `nexhome.properties`、数据库 `data/nexhome.db`
- **默认登录密码：`admin`**，登录后请立即在「系统设置」中修改
- **访问端口 / HTTPS / 安全入口**：登录后在「系统设置 → 访问与安全设置」中修改，保存后自动重启生效（绑定失败自动回滚）；
  HTTPS 证书自动适配「SSL 证书管理」中已签发的证书；开启安全入口后必须通过 `http://地址:端口/入口路径/` 访问面板
- 挂载 `/var/run/docker.sock` 后，「Docker 容器」模块即可查看宿主机上的容器与 Compose 项目；
  容器内进程需有 socket 读写权限（默认 root 满足，非 root 用户需将其加入宿主 `docker` 组对应的 gid）

> 注意：运行镜像基于 eclipse-temurin，**不含 curl/wget**，healthcheck 切勿使用 `curl -f`，否则容器将始终处于 unhealthy。

## 二、功能简介

- **DDNS 域名同步**：阿里云云解析 DNS / ESA 直连，多模式 IP 来源，定时自动同步域名解析记录。
- **STUN 端口穿透**：标准 STUN 协议建立/保活 NAT 映射，支持 UDP、TCP/HTTP 任务转发与打洞，无需公网 IP 即可对外提供服务。
- **WOL 网络唤醒**：管理多台机器，一键/批量发送 UDP 魔术包唤醒内网设备。
- **SSL 证书申请与自动续期**：支持 Let's Encrypt / ZeroSSL，HTTP01 全自动、DNS01 半自动，到期前 21 天自动续期。
- **网站导航**：卡片式内/外网双地址入口，自动识别访问来源智能切换，支持排序与拖拽。
- **Docker 容器观测**：直连 docker.sock，只读展示容器、Compose 项目、资源占用与整体概况。
- **其他**：全模块操作日志、内置登录鉴权、异常统一捕获提示。

## 三、常见问题

1. **忘记登录密码**：停止程序，删除 `data/nexhome.db` 中 `app_config` 表的 `auth.password` 行（或整库备份后删除），重启将重置为默认密码 `admin`（注意会丢失全部配置）。
2. **HTTP01 证书申请失败**：确认域名已解析到本机公网 IP，且公网 80 端口能转发到本服务端口；否则改用 DNS01。
3. **DDNS 同步报权限错误**：检查 AccessKey 权限策略是否包含对应产品的解析读写权限。
4. **STUN 探测无响应**：更换 STUN 服务器（如 `stun.qq.com:3478`、`stun.miwifi.com:3478`），并确认本机可访问公网 UDP。
5. **TCP 任务运行中但外网仍无法访问映射地址**：依次排查——① 探测 NAT 类型，对称型无法纯 STUN 穿透，改用路由器端口转发；
   ② 受限锥形需填写「对端公网地址」由本端主动打洞，或在对端配合下互打；③ 确认主机防火墙已放行监听端口（Windows：允许 Java 通过专用/公用网络的入站规则）。
6. **Docker 模块提示未连接**：容器部署需 `-v /var/run/docker.sock:/var/run/docker.sock` 挂载；
   报权限错误（Permission denied）说明容器内用户无 socket 读写权限；远程 Docker 检查 `DOCKER_HOST` 与网络连通性。
