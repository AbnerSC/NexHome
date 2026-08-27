/* ============ NexHome 前端逻辑（原生 JS，无框架） ============ */
/* 主入口：通用工具、登录鉴权、页面路由。
   各菜单模块的逻辑拆分到 js/ 目录（须在 index.html 中先于本文件加载）：
   home=网站导航 ddns=DDNS stun=STUN wol=WOL cert=SSL证书 logs=日志 settings=设置 */
'use strict';

const $ = s => document.querySelector(s);
const $$ = s => document.querySelectorAll(s);

let TOKEN = localStorage.getItem('nx_token') || '';
let currentPage = 'home';
let refreshTimer = null;          // 状态实时刷新定时器

/* ---------------- 基础工具 ---------------- */

function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function toast(msg, type = 'ok') {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast ' + type;
    setTimeout(() => t.classList.add('hidden'), 2600);
}

/** 统一 API 调用：携带 token，统一错误处理 */
async function api(method, path, body) {
    const opt = { method, headers: { 'X-Token': TOKEN } };
    if (body !== undefined) {
        opt.headers['Content-Type'] = 'application/json';
        opt.body = JSON.stringify(body);
    }
    const resp = await fetch(path, opt);
    let data = {};
    try { data = await resp.json(); } catch (e) { /* 非 JSON 响应 */ }
    if (resp.status === 401 && !path.endsWith('/login') && !path.endsWith('/check')) {
        TOKEN = '';
        localStorage.removeItem('nx_token');
        showLogin();
        throw new Error('登录已过期，请重新登录');
    }
    if (!data.ok) throw new Error(data.error || ('请求失败 ' + resp.status));
    return data.data;
}

function modal(title, bodyHtml) {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = bodyHtml;
    $('#modalMask').classList.remove('hidden');
}

function closeModal() { $('#modalMask').classList.add('hidden'); }

function badge(text, cls) { return `<span class="badge ${cls}">${esc(text)}</span>`; }

function statusBadge(status) {
    if (!status) return badge('-', 'gray');
    if (status.startsWith('SUCCESS') || status === 'RUNNING' || status === 'ISSUED') return badge(status, 'ok');
    if (status.startsWith('FAIL') || status === 'ERROR' || status === 'STOPPED') return badge(status, 'err');
    return badge(status, 'warn');
}

function setRefresh(fn, ms) {
    if (refreshTimer) clearInterval(refreshTimer);
    refreshTimer = null;
    if (fn) refreshTimer = setInterval(() => fn().catch(() => { }), ms);
}

/* ---------------- 登录鉴权 ---------------- */

async function checkLogin() {
    try {
        const r = await api('GET', '/api/auth/check');
        if (r.loggedIn) enterApp(); else showLogin();
    } catch (e) { showLogin(); }
}

function showLogin() {
    $('#mainView').classList.add('hidden');
    $('#loginView').classList.remove('hidden');
}

async function enterApp() {
    $('#loginView').classList.add('hidden');
    $('#mainView').classList.remove('hidden');
    setPage('home');
    loadSysInfo();
}

$('#loginForm').addEventListener('submit', async e => {
    e.preventDefault();
    try {
        const r = await api('POST', '/api/auth/login', { password: $('#loginPwd').value });
        TOKEN = r.token;
        localStorage.setItem('nx_token', TOKEN);
        $('#loginPwd').value = '';
        enterApp();
    } catch (err) { toast(err.message, 'err'); }
});

$('#btnLogout').addEventListener('click', async () => {
    try { await api('POST', '/api/auth/logout'); } catch (e) { /* ignore */ }
    TOKEN = '';
    localStorage.removeItem('nx_token');
    showLogin();
});

$('#modalClose').addEventListener('click', closeModal);
$('#modalMask').addEventListener('click', e => { if (e.target === $('#modalMask')) closeModal(); });

async function loadSysInfo() {
    try {
        const i = await api('GET', '/api/system/info');
        $('#sysInfo').textContent = `v${i.version} · Java ${i.javaVersion} · 内存 ${i.usedMemoryMB}MB`;
    } catch (e) { /* ignore */ }
}

/* ---------------- 页面路由 ---------------- */

const PAGE_TITLES = {
    home: '网站导航', ddns: 'DDNS 域名同步', stun: 'STUN 端口穿透',
    wol: 'WOL 网络唤醒', cert: 'SSL 证书管理', logs: '操作日志', settings: '系统设置'
};

const RENDER = {
    home: renderHome, ddns: renderDdns, stun: renderStun,
    wol: renderWol, cert: renderCert, logs: renderLogs, settings: renderSettings
};

function setPage(page) {
    currentPage = page;
    setRefresh(null);
    $$('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.page === page));
    $('#pageTitle').textContent = PAGE_TITLES[page];
    $('#pageBody').innerHTML = '<p class="muted">加载中...</p>';
    RENDER[page]().catch(e => { $('#pageBody').innerHTML = `<p style="color:var(--red)">${esc(e.message)}</p>`; });
}

$$('.nav-item').forEach(n => n.addEventListener('click', () => setPage(n.dataset.page)));

/* ---------------- 启动 ---------------- */
checkLogin();
