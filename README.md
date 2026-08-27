# NexHome｜联枢

## GitHub
https://github.com/AbnerSC/NexHome.git

> 一站式内网节点守护工具，面向家庭 / 小型机房服务器，把内网能力安全对外打通。

NexHome 是一款单进程、前后端一体化的轻量 Java 服务，内置 Web 服务器与 Web 管理界面，
集成 **DDNS 域名同步、STUN 端口穿透、WOL 网络唤醒、SSL 证书自动申请续期、网站导航** 五大功能。
只启动一个 Java 程序、只占用一个端口，配置与日志全部持久化在 SQLite（程序运行目录 `data/`）。

---

## 一、环境要求

| 项目 | 要求 |
|---|---|
| 运行环境 | **JDK 25**（编译与运行均需要；项目以 `--release 25` 编译） |
| 构建工具 | Maven 3.9+ |
| 数据库 | SQLite（随程序内置，无需安装，文件位于 `data/nexhome.db`） |
| 操作系统 | Windows / Linux / macOS 均可 |

依赖说明（刻意保持轻量，未引入 Spring 等重型框架）：

- Web 服务器：JDK 内置 `com.sun.net.httpserver`
- 任务调度：JDK 内置 `ScheduledExecutorService`
- JSON：Gson（单 jar）
- 数据库：sqlite-jdbc
- 证书：acme4j（ACME 协议客户端）+ BouncyCastle（仅 CSR 构造使用）

## 二、编译命令

```bash
# 在项目根目录执行，产物为 target/nexhome.jar（含全部依赖与前端资源的可执行 fat-jar）
mvn clean package
```

## 三、启动方式

```bash
# --enable-native-access=ALL-UNNAMED：sqlite-jdbc 需加载本地库，Java 22+ 不加此参数会输出 restricted method 警告
java --enable-native-access=ALL-UNNAMED -jar target/nexhome.jar
```

启动后访问：**http://127.0.0.1:8090**（默认端口）

- 首次启动自动生成配置文件 `nexhome.properties`、数据库 `data/nexhome.db`
- **默认登录密码：`admin`**，登录后请立即在「系统设置」中修改
- 自定义端口：修改运行目录 `nexhome.properties` 中的 `server.port`，重启生效

低内存环境可选：`java -Xmx128m --enable-native-access=ALL-UNNAMED -jar nexhome.jar`

## 四、功能说明

### 1. DDNS 域名同步
- 服务商：阿里云 **云解析 DNS**、阿里云 **ESA**（边缘安全加速），均为官方 OpenAPI 直连
- IP 来源三种模式：**公网接口自动获取**（多接口容错）/ **本机网卡**（可指定网卡）/ **手动输入**
- 可配置域名、主机记录、记录类型（A/AAAA）、TTL、同步间隔；支持定时自动同步与手动触发
- 同步结果（成功/失败、当前 IP、时间）实时展示并写入日志

### 2. STUN 端口穿透（通道承载 UDP / TCP / HTTP 数据传输）
- 标准 STUN 协议（RFC 5389）实现 Binding 请求，建立/保活 NAT 映射，展示映射后的外网地址端口；保活响应实时刷新映射地址
- 内置 RFC 3489 风格 NAT 类型探测（Full Cone / Restricted / Symmetric），界面展示穿透成功率说明
- **UDP 任务**：外网发入映射端口的数据包按会话转发到内网目标服务，响应原路返回，可承载任意 UDP 业务数据
- **TCP/HTTP 任务**：同一端口监听入站连接并双向透传到内网目标（HTTP 等应用层协议直接可用）；
  支持填写「对端公网地址」由本端周期性主动打洞（锥形 NAT 下建立双向过滤条目），连接建立后同样并入转发管道；
  通过 STUN-over-TCP（RFC 5389 §7.1）探测真实的 TCP 映射地址（可能与 UDP 映射不同）并周期保活，优先展示；
  出站探测建立的 TCP 映射经实测可被外网设备主动连入，自测通过即代表互联网可达，无需更换端口重新穿透
- 支持自定义 STUN 服务器、本地绑定端口、保活间隔；任务启停与配置持久化，重启自动恢复；旧库自动迁移新增对端地址字段
- **限制说明**：任务运行中/探测成功 ≠ 外网可访问，外网能否主动连入取决于 NAT 过滤行为：
  Full Cone 可直接访问；受限锥形仅允许已打洞对端；对称型（Symmetric）纯 STUN 无法穿透，
  此时建议路由器端口转发或改用中继方案（TCP 任务经出站探测建立的映射不受此限，见上）。另请确认主机防火墙已放行监听端口（Windows 需允许 Java 入站连接）

### 3. WOL 网络唤醒
- 管理多台机器（名称 / MAC / 广播地址 / 端口），一键唤醒与批量唤醒，全部操作记入日志
- 底层实现：向广播地址连发 3 次 UDP 魔术包（6×0xFF + MAC×16）

### 4. SSL 证书申请与自动续期
- 服务商：**Let's Encrypt**（免凭证）、**ZeroSSL**（需 EAB 凭证，见下文）
- 验证方式：**HTTP01** 全自动（需公网可访问本机 80 端口，可用路由器转发到本服务端口）；
  **DNS01** 半自动（界面给出需添加的 TXT 记录，添加后点击"完成验证"）
- 定时检查证书有效期，**到期前 21 天自动续期**；支持多套证书管理
- 证书详情（域名/颁发者/有效期/指纹）查看；私钥、证书、证书链（PEM）下载
- 证书文件保存于 `data/certs/task-{id}/`，ACME 账号密钥保存于 `data/certs/acme/`

### 5. 网站导航
- 每个条目同时配置 **内网地址 + 外网地址** 两套访问入口
- 前端自动识别访问来源（内网 IP 段 / 公网）智能优先选择地址，也支持手动切换
- 卡片式展示，支持增删改、启停、权重排序与拖拽排序

### 6. 其他
- 全模块操作日志（SQLite 持久化，界面分页查询、模块过滤、自动刷新）
- 内置登录鉴权（密码登录，会话 Token 24 小时有效）
- 全部异常统一捕获并在界面提示

## 五、第三方 API 密钥配置位置

| 服务 | 需要的凭证 | 获取方式 | 在系统中的配置位置 |
|---|---|---|---|
| 阿里云云解析 / ESA | AccessKey ID + AccessKey Secret（ESA 另需站点 SiteId） | 阿里云控制台 → RAM 访问控制 → 创建子用户，建议仅授予 `AliyunDNSFullAccess` / ESA 解析相关权限 | **DDNS 页面 → 新增/编辑任务表单**（随任务保存） |
| ZeroSSL | EAB KID + EAB HMAC Key | ZeroSSL 控制台 → Developer → *EAB Credentials for ACME Clients* 生成 | **SSL 证书页面顶部「服务商凭证设置」** |
| Let's Encrypt | 无需凭证（可填联系邮箱） | - | **SSL 证书页面顶部「服务商凭证设置」** |
| 公网 IP 查询接口 | 无需凭证 | 内置 ipify / ifconfig.me / 3322 等多源容错 | 无需配置 |

> 安全提示：AccessKey 等密钥保存在本地 SQLite（`data/nexhome.db`）中，请妥善保管该文件与 `data/` 目录；
> 证书私钥文件（`*.key.pem`）下载后请勿泄露。

## 六、目录结构

```
运行目录/
├── nexhome.jar              # 程序（含前端资源）
├── nexhome.properties       # 端口等基础配置（自动生成）
└── data/
    ├── nexhome.db           # SQLite 数据库（配置/任务/日志）
    └── certs/               # ACME 账号密钥与证书文件
        ├── acme/{letsencrypt|zerossl}/account.pem
        └── task-{id}/domain.key.pem | cert.pem | fullchain.pem
```

## 七、常见问题

1. **忘记登录密码**：停止程序，删除 `data/nexhome.db` 中 `app_config` 表的 `auth.password` 行（或整库备份后删除），重启将重置为默认密码 `admin`（注意会丢失全部配置）。
2. **HTTP01 证书申请失败**：确认域名已解析到本机公网 IP，且公网 80 端口能转发到本服务端口；否则改用 DNS01。
3. **DDNS 同步报权限错误**：检查 AccessKey 权限策略是否包含对应产品的解析读写权限。
4. **STUN 探测无响应**：更换 STUN 服务器（如 `stun.qq.com:3478`、`stun.miwifi.com:3478`），并确认本机可访问公网 UDP。
5. **TCP 任务运行中但外网仍无法访问映射地址**：依次排查——① 探测 NAT 类型，对称型无法纯 STUN 穿透，改用路由器端口转发；
   ② 受限锥形需填写「对端公网地址」由本端主动打洞，或在对端配合下互打；③ 确认主机防火墙已放行监听端口（Windows：允许 Java 通过专用/公用网络的入站规则）。
