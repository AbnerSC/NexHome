-- =============================================================
-- NexHome SQLite 建表脚本（程序启动时自动执行，均为 IF NOT EXISTS）
-- 数据库文件位于程序运行目录：./data/nexhome.db
-- =============================================================

-- 全局配置表（登录密码哈希、ZeroSSL EAB 凭证、ACME 邮箱等）
CREATE TABLE IF NOT EXISTS app_config (
    key   TEXT PRIMARY KEY,
    value TEXT
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
-- challenge_type : HTTP01（自动，需本机 80 端口可被公网访问） / DNS01（手动添加 TXT 记录）
-- status         : IDLE / PENDING_VALIDATION / ISSUED / ERROR
CREATE TABLE IF NOT EXISTS cert_task (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT NOT NULL,
    provider       TEXT NOT NULL DEFAULT 'LETSENCRYPT',
    domains        TEXT NOT NULL,
    challenge_type TEXT NOT NULL DEFAULT 'HTTP01',
    status         TEXT NOT NULL DEFAULT 'IDLE',
    message        TEXT,
    dns_hint       TEXT,
    not_after      TEXT,
    auto_renew     INTEGER NOT NULL DEFAULT 1,
    cert_dir       TEXT,
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
