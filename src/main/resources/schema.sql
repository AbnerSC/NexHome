-- =============================================================
-- NexHome SQLite 建表脚本（程序启动时自动执行，均为 IF NOT EXISTS）
-- 数据库文件位于程序运行目录：./data/nexhome.db
-- =============================================================

-- 全局配置表（登录密码哈希、ZeroSSL EAB 凭证、ACME 邮箱等）
CREATE TABLE IF NOT EXISTS app_config (
    key   TEXT PRIMARY KEY,
    value TEXT
);

-- 服务商凭证配置表（云服务商 AccessKey 等，DDNS / SSL 证书 DNS01 按名称选择引用）
-- provider_type : 服务商类型（ALIYUN 等，清单见 ProviderConfigService.PROVIDER_TYPES，后续可扩展）
-- esa_site_id   : 可选，仅阿里云 ESA 使用；DDNS 任务选择配置后自动带出（可按任务覆盖），
--                  证书 DNS01 在云解析定位不到域名（托管在 ESA）时自动用该站点写入 TXT
CREATE TABLE IF NOT EXISTS provider_config (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    name              TEXT NOT NULL,
    provider_type     TEXT NOT NULL DEFAULT 'ALIYUN',
    access_key_id     TEXT NOT NULL,
    access_key_secret TEXT NOT NULL,
    esa_site_id       TEXT,
    created_at        TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- 全局操作日志表（所有模块统一写入，支持分页查询）
CREATE TABLE IF NOT EXISTS op_log (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    time    TEXT NOT NULL,
    module  TEXT NOT NULL,
    level   TEXT NOT NULL,
    message TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_op_log_id ON op_log (id DESC);

-- DDNS 同步任务表
-- provider : ALIYUN_DNS（云解析） / ALIYUN_ESA（边缘安全加速）
-- ip_mode  : LOCAL（本机网卡） / MANUAL（手动输入） / PUBLIC（公网接口）
-- provider_config_id : 引用服务商凭证配置（provider_config.id）；为空时使用任务内手动填写的 AccessKey
CREATE TABLE IF NOT EXISTS ddns_task (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    name               TEXT NOT NULL,
    provider           TEXT NOT NULL,
    domain             TEXT NOT NULL,
    rr                 TEXT NOT NULL,
    type               TEXT NOT NULL DEFAULT 'A',
    ttl                INTEGER NOT NULL DEFAULT 600,
    ip_mode            TEXT NOT NULL DEFAULT 'PUBLIC',
    manual_ip          TEXT,
    local_nic          TEXT,
    provider_config_id INTEGER,
    access_key_id      TEXT,
    access_key_secret  TEXT,
    esa_site_id        TEXT,
    record_id          TEXT,
    interval_sec       INTEGER NOT NULL DEFAULT 300,
    enabled            INTEGER NOT NULL DEFAULT 1,
    last_ip            TEXT,
    last_sync          TEXT,
    last_status        TEXT,
    created_at         TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- STUN 穿透任务表
-- protocol     : TCP / UDP
-- status       : STOPPED / RUNNING / ERROR
-- peer_addr    : TCP 打洞对端公网地址（ip:port，可空）
-- upnp_enabled : 是否启用 UPnP 端口映射（路由器不支持 UPnP 时可关闭，避免无谓的 SSDP 发现等待）
-- punched_at   : 穿透成功时间（本次运行首次取得外网映射地址的时刻）
-- check_time / check_result : 可用性自测（穿透后测试一次）的时间与结果
CREATE TABLE IF NOT EXISTS stun_task (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    protocol      TEXT NOT NULL DEFAULT 'UDP',
    target_ip     TEXT NOT NULL,
    target_port   INTEGER NOT NULL,
    bind_port     INTEGER NOT NULL DEFAULT 0,
    stun_host     TEXT NOT NULL DEFAULT 'stun.l.google.com',
    stun_port     INTEGER NOT NULL DEFAULT 19302,
    keepalive_sec INTEGER NOT NULL DEFAULT 25,
    peer_addr     TEXT,
    upnp_enabled  INTEGER NOT NULL DEFAULT 1,
    enabled       INTEGER NOT NULL DEFAULT 0,
    status        TEXT NOT NULL DEFAULT 'STOPPED',
    mapped_addr   TEXT,
    nat_type      TEXT,
    punched_at    TEXT,
    check_time    TEXT,
    check_result  TEXT,
    created_at    TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- STUN 服务器维护表（穿透任务新增/编辑时下拉选择，按 sort_order 排序展示）
-- tcp_support : 是否支持 STUN-over-TCP（TCP 穿透任务需经支持 TCP 的服务器出站，在 CGNAT 上建立真实 TCP 映射）
CREATE TABLE IF NOT EXISTS stun_server (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    host        TEXT NOT NULL,
    port        INTEGER NOT NULL DEFAULT 3478,
    tcp_support INTEGER NOT NULL DEFAULT 0,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- WOL 唤醒设备表
-- mac : MAC 地址，多网口设备可配置多个，以逗号/分号/空白分隔
CREATE TABLE IF NOT EXISTS wol_device (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    mac        TEXT NOT NULL,
    broadcast  TEXT NOT NULL DEFAULT '255.255.255.255',
    port       INTEGER NOT NULL DEFAULT 9,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- SSL 证书任务表
-- provider       : LETSENCRYPT / ZEROSSL
-- challenge_type : HTTP01（自动，需本机 80 端口可被公网访问） / DNS01（手动添加 TXT 或引用凭证配置自动添加）
-- provider_config_id : DNS01 自动验证引用的服务商凭证配置（阿里云，云解析/ESA 自动探测）；为空时手动添加 TXT
-- status         : IDLE / PENDING_VALIDATION / ISSUED / ERROR
-- save_dir       : 可选，签发成功后额外保存证书的目录（以主域名作为文件名）；为空时仅保存到默认任务目录
-- webhook_url    : 可选，签发成功后将证书完整内容（证书/私钥/证书链 PEM）POST 推送到该地址，用于同步到其他系统
CREATE TABLE IF NOT EXISTS cert_task (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    name               TEXT NOT NULL,
    provider           TEXT NOT NULL DEFAULT 'LETSENCRYPT',
    domains            TEXT NOT NULL,
    challenge_type     TEXT NOT NULL DEFAULT 'HTTP01',
    provider_config_id INTEGER,
    status             TEXT NOT NULL DEFAULT 'IDLE',
    message        TEXT,
    dns_hint       TEXT,
    not_after      TEXT,
    auto_renew     INTEGER NOT NULL DEFAULT 1,
    cert_dir       TEXT,
    save_dir       TEXT,
    webhook_url    TEXT,
    created_at     TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- 网站导航条目表（同时配置内网 / 外网两套地址）
CREATE TABLE IF NOT EXISTS nav_item (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    icon_url    TEXT,
    description TEXT,
    lan_url     TEXT NOT NULL,
    wan_url     TEXT NOT NULL,
    weight      INTEGER NOT NULL DEFAULT 0,
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
