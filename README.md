
# NexHome｜联枢

> 一站式内网节点守护工具，面向家庭 / 小型机房服务器，把内网能力安全对外打通。
> 
> 

**NexHome（联枢）** 是一款常驻后台的一体化内网运维守护程序，集成动态域名解析、STUN 内网端口穿透、局域网网络唤醒、ACME 自动化 SSL 证书申请能力，可通过 Docker 快速部署运行。

### ✨ Core Features

- **Dynamic DDNS**：自动探测本地公网 IP，同步更新至阿里云 DNS、阿里云 ESA 域名解析记录

- **STUN Intranet Punch**：基于 STUN 协议实现内网端口穿透，无需公网 IP 即可对外暴露内网服务

- **WOL Wake‑on‑LAN**：局域网网络唤醒，远程开机局域网内其他服务器与设备

- **ACME SSL Cert**：对接 ZeroSSL / Let's Encrypt，自动申请、续期 SSL 证书

- **Daemon mode \& Docker ready**：支持容器部署、后台常驻运行，配置驱动，适合 7×24 小时跑在本地网关机器

### 🎯 Use Cases

- 家庭宽带无固定公网 IP，自动同步 IP 到域名

- 内网服务器需要对外提供访问，借助 STUN 做端口穿透

- 远程唤醒局域网中休眠 / 关机的服务器主机

- 自动化获取与续期域名 SSL 证书，免去手动操作

> NexHome = Nexus \(连接枢纽\) \+ Home \(内网 / 本地机房\)，致力于成为内网设备对外连接的轻量枢纽。
> 
> 