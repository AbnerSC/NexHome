/* ============ NexHome 前端逻辑（原生 JS，无框架） ============ */
'use strict';

const $ = s => document.querySelector(s);
const $$ = s => document.querySelectorAll(s);

let TOKEN = localStorage.getItem('nx_token') || '';
let currentPage = 'home';
let refreshTimer = null;          // 状态实时刷新定时器
let logAutoRefresh = true;
let navSmartMode = null;          // null=自动, 'lan'/'wan' 手动

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

/* ---------------- 首页：网站导航 ---------------- */

function isLanVisit() {
    const h = location.hostname;
    return /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h);
}

function currentNetMode() {
    if (navSmartMode) return navSmartMode;
    return isLanVisit() ? 'lan' : 'wan';
}

async function renderHome() {
    const items = await api('GET', '/api/nav/items');
    const mode = currentNetMode();
    const modeLabel = navSmartMode ? (navSmartMode === 'lan' ? '内网（手动）' : '外网（手动）')
        : (isLanVisit() ? '内网（自动识别）' : '外网（自动识别）');
    let html = `
      <div class="toolbar">
        <span class="muted small">当前访问来源：${esc(modeLabel)}</span>
        <button class="btn small" onclick="switchNetMode('lan')">切到内网地址</button>
        <button class="btn small" onclick="switchNetMode('wan')">切到外网地址</button>
        <button class="btn small" onclick="switchNetMode(null)">恢复自动</button>
        <div class="spacer"></div>
        <button class="btn small" onclick="renderNavManage()">🛠 管理导航</button>
        <button class="btn primary small" onclick="navForm()">＋ 新增导航</button>
      </div>`;
    if (!items.length) {
        html += '<div class="panel"><p class="muted">暂无导航条目，点击右上角「新增导航」添加。</p></div>';
    } else {
        html += '<div class="nav-cards">';
        for (const it of items.filter(i => i.enabled === 1)) {
            const url = mode === 'lan' ? it.lan_url : it.wan_url;
            const icon = it.icon_url
                ? `<img src="${esc(it.icon_url)}" onerror="this.replaceWith(document.createTextNode('🌍'))">`
                : '🌍';
            html += `
            <div class="nav-card" onclick="window.open('${esc(url)}','_blank')">
              <div class="card-actions">
                <button class="btn small" onclick="event.stopPropagation();navForm(${it.id})">编辑</button>
                <button class="btn small danger" onclick="event.stopPropagation();navDelete(${it.id})">删除</button>
              </div>
              <div class="icon">${icon}</div>
              <h3>${esc(it.name)}</h3>
              <p>${esc(it.description || '')}</p>
              <div class="addr-row">${badge(mode === 'lan' ? '内网地址' : '外网地址', 'info')}
                <span class="muted small" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(url)}</span></div>
            </div>`;
        }
        html += '</div>';
    }
    $('#pageBody').innerHTML = html;
}

window.switchNetMode = m => { navSmartMode = m; renderHome(); };

/** 导航管理视图（支持拖拽排序） */
async function renderNavManage() {
    const items = await api('GET', '/api/nav/items');
    let rows = items.map(it => `
        <tr draggable="true" data-id="${it.id}" class="drag-row">
          <td style="cursor:move">⠿</td>
          <td>${esc(it.name)}</td>
          <td class="small muted">${esc(it.lan_url)}</td>
          <td class="small muted">${esc(it.wan_url)}</td>
          <td>${it.enabled === 1 ? badge('启用', 'ok') : badge('停用', 'gray')}</td>
          <td>
            <button class="btn small" onclick="navForm(${it.id})">编辑</button>
            <button class="btn small danger" onclick="navDelete(${it.id})">删除</button>
          </td>
        </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="toolbar">
        <button class="btn small" onclick="renderHome()">← 返回卡片</button>
        <div class="spacer"></div>
        <span class="muted small">拖动行调整顺序，松开自动保存</span>
        <button class="btn primary small" onclick="navForm()">＋ 新增导航</button>
      </div>
      <div class="panel"><table>
        <thead><tr><th></th><th>名称</th><th>内网地址</th><th>外网地址</th><th>状态</th><th>操作</th></tr></thead>
        <tbody id="navTbody">${rows}</tbody>
      </table></div>`;
    bindDragSort();
}
window.renderNavManage = renderNavManage;

/** HTML5 拖拽排序 */
function bindDragSort() {
    const tbody = $('#navTbody');
    let dragRow = null;
    tbody.querySelectorAll('.drag-row').forEach(row => {
        row.addEventListener('dragstart', () => { dragRow = row; row.style.opacity = .4; });
        row.addEventListener('dragend', () => { row.style.opacity = 1; });
        row.addEventListener('dragover', e => {
            e.preventDefault();
            if (row !== dragRow) tbody.insertBefore(dragRow, row);
        });
        row.addEventListener('drop', saveOrder);
    });
    async function saveOrder() {
        const ids = [...tbody.querySelectorAll('.drag-row')].map(r => Number(r.dataset.id));
        try {
            await api('PUT', '/api/nav/reorder', { ids });
            toast('排序已保存');
        } catch (e) { toast(e.message, 'err'); }
    }
}

/** 导航新增/编辑表单 */
window.navForm = async (id) => {
    const it = id ? (await api('GET', '/api/nav/items')).find(x => x.id === id) : {};
    modal(id ? '编辑导航' : '新增导航', `
      <form id="navFormEl" class="form-grid">
        <div class="field"><label>网站名称 <b>*</b></label><input name="name" required value="${esc(it.name || '')}"></div>
        <div class="field"><label>图标 URL（可选）</label><input name="icon_url" value="${esc(it.icon_url || '')}" placeholder="https://.../icon.png"></div>
        <div class="field full"><label>描述文字</label><input name="description" value="${esc(it.description || '')}"></div>
        <div class="field full"><label>内网访问地址 <b>*</b></label><input name="lan_url" required placeholder="http://192.168.1.10:8080" value="${esc(it.lan_url || '')}"></div>
        <div class="field full"><label>外网访问地址 <b>*</b></label><input name="wan_url" required placeholder="https://nas.example.com" value="${esc(it.wan_url || '')}"></div>
        <div class="field"><label>排序权重</label><input name="weight" type="number" value="${it.weight ?? 0}"></div>
        <div class="field"><label>是否启用</label><select name="enabled"><option value="true" ${it.enabled !== 0 ? 'selected' : ''}>启用</option><option value="false" ${it.enabled === 0 ? 'selected' : ''}>停用</option></select></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    $('#navFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const f = new FormData(e.target);
        const body = {
            name: f.get('name').trim(), icon_url: f.get('icon_url').trim(), description: f.get('description').trim(),
            lan_url: f.get('lan_url').trim(), wan_url: f.get('wan_url').trim(),
            weight: Number(f.get('weight') || 0), enabled: f.get('enabled') === 'true'
        };
        try {
            if (id) await api('PUT', '/api/nav/items/' + id, body);
            else await api('POST', '/api/nav/items', body);
            closeModal(); toast('保存成功'); renderHome();
        } catch (err) { toast(err.message, 'err'); }
    });
};

window.navDelete = async id => {
    if (!confirm('确定删除该导航条目？')) return;
    try { await api('DELETE', '/api/nav/items/' + id); toast('已删除'); renderHome(); }
    catch (e) { toast(e.message, 'err'); }
};

/* ---------------- DDNS ---------------- */

async function renderDdns() {
    const tasks = await api('GET', '/api/ddns/tasks');
    const rows = tasks.map(t => `
      <tr>
        <td>${esc(t.name)}<div class="small muted">${esc(t.provider === 'ALIYUN_ESA' ? '阿里云ESA' : '阿里云DNS')}</div></td>
        <td>${esc(t.rr)}.${esc(t.domain)} <span class="muted small">${esc(t.type)}</span></td>
        <td>${badge(t.ip_mode === 'PUBLIC' ? '公网IP' : t.ip_mode === 'LOCAL' ? '本地网卡' : '手动', 'info')}</td>
        <td><label class="switch"><input type="checkbox" ${t.enabled === 1 ? 'checked' : ''} onchange="ddnsToggle(${t.id}, this.checked, ${t.interval_sec})"><span class="slider"></span></label>
            <span class="small muted">${t.enabled === 1 ? '每' + t.interval_sec + 's' : '停用'}</span></td>
        <td>${esc(t.last_ip || '-')}</td>
        <td>${statusBadge(t.last_status)}<div class="small muted">${esc(t.last_sync || '')}</div></td>
        <td>
          <button class="btn small success" onclick="ddnsSync(${t.id})">同步</button>
          <button class="btn small" onclick="ddnsForm(${t.id})">编辑</button>
          <button class="btn small danger" onclick="ddnsDelete(${t.id})">删除</button>
        </td>
      </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="tip">IP 来源支持三种模式：<b>公网接口</b>（自动获取出口 IP）/ <b>本地网卡</b> / <b>手动输入</b>。
        对接阿里云需要使用 RAM 子账号 AccessKey（建议仅授予 AliyunDNSFullAccess 或 ESA 解析权限）。</div>
      <div class="toolbar"><div class="spacer"></div><button class="btn primary" onclick="ddnsForm()">＋ 新增同步任务</button></div>
      <div class="panel"><table>
        <thead><tr><th>任务</th><th>域名</th><th>IP来源</th><th>定时同步</th><th>当前IP</th><th>最近同步</th><th>操作</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="7" class="muted">暂无任务</td></tr>'}</tbody>
      </table></div>`;
    setRefresh(renderDdns, 10000);
}

window.ddnsToggle = async (id, enabled, interval) => {
    const tasks = await api('GET', '/api/ddns/tasks');
    const t = tasks.find(x => x.id === id);
    try {
        await api('PUT', '/api/ddns/tasks/' + id, {
            name: t.name, provider: t.provider, domain: t.domain, rr: t.rr, type: t.type, ttl: t.ttl,
            ip_mode: t.ip_mode, manual_ip: t.manual_ip, local_nic: t.local_nic,
            access_key_id: t.access_key_id, access_key_secret: t.access_key_secret,
            esa_site_id: t.esa_site_id, interval_sec: t.interval_sec, enabled
        });
        toast(enabled ? '已启用定时同步' : '已停用');
        renderDdns();
    } catch (e) { toast(e.message, 'err'); renderDdns(); }
};

window.ddnsSync = async id => {
    try { await api('POST', `/api/ddns/tasks/${id}/sync`); toast('已触发同步'); setTimeout(renderDdns, 2000); }
    catch (e) { toast(e.message, 'err'); }
};

window.ddnsDelete = async id => {
    if (!confirm('确定删除该同步任务？')) return;
    try { await api('DELETE', '/api/ddns/tasks/' + id); toast('已删除'); renderDdns(); }
    catch (e) { toast(e.message, 'err'); }
};

window.ddnsForm = async (id) => {
    let t = {};
    if (id) t = (await api('GET', '/api/ddns/tasks')).find(x => x.id === id);
    let nics = [];
    try { nics = (await api('GET', '/api/ddns/nics')).nics; } catch (e) { /* ignore */ }
    const nicOpts = nics.map(n => `<option value="${esc(n)}" ${t.local_nic === n ? 'selected' : ''}>${esc(n)}</option>`).join('');
    modal(id ? '编辑同步任务' : '新增同步任务', `
      <form id="ddnsFormEl" class="form-grid">
        <div class="field"><label>任务名称 <b>*</b></label><input name="name" required value="${esc(t.name || '')}"></div>
        <div class="field"><label>服务商 <b>*</b></label>
          <select name="provider" onchange="ddnsProviderChanged(this.value)">
            <option value="ALIYUN_DNS" ${t.provider !== 'ALIYUN_ESA' ? 'selected' : ''}>阿里云 云解析DNS</option>
            <option value="ALIYUN_ESA" ${t.provider === 'ALIYUN_ESA' ? 'selected' : ''}>阿里云 ESA</option>
          </select></div>
        <div class="field"><label>主域名 <b>*</b></label><input name="domain" required placeholder="example.com" value="${esc(t.domain || '')}"></div>
        <div class="field"><label>主机记录 <b>*</b></label><input name="rr" required placeholder="www 或 @" value="${esc(t.rr || '')}"></div>
        <div class="field"><label>记录类型</label>
          <select name="type"><option ${t.type !== 'AAAA' ? 'selected' : ''}>A</option><option ${t.type === 'AAAA' ? 'selected' : ''}>AAAA</option></select></div>
        <div class="field"><label>TTL（秒）</label><input name="ttl" type="number" min="60" value="${t.ttl ?? 600}"></div>
        <div class="field"><label>IP 来源 <b>*</b></label>
          <select name="ip_mode" onchange="ddnsModeChanged(this.value)">
            <option value="PUBLIC" ${t.ip_mode !== 'LOCAL' && t.ip_mode !== 'MANUAL' ? 'selected' : ''}>公网接口自动获取</option>
            <option value="LOCAL" ${t.ip_mode === 'LOCAL' ? 'selected' : ''}>本机网卡</option>
            <option value="MANUAL" ${t.ip_mode === 'MANUAL' ? 'selected' : ''}>手动输入</option>
          </select></div>
        <div class="field hidden" id="fNic"><label>网卡</label><select name="local_nic"><option value="">自动选择</option>${nicOpts}</select></div>
        <div class="field hidden" id="fManualIp"><label>手动 IP <b>*</b></label><input name="manual_ip" value="${esc(t.manual_ip || '')}"></div>
        <div class="field"><label>AccessKey ID <b>*</b></label><input name="access_key_id" required value="${esc(t.access_key_id || '')}"></div>
        <div class="field"><label>AccessKey Secret <b>*</b></label><input name="access_key_secret" required type="password" value="${esc(t.access_key_secret || '')}"></div>
        <div class="field hidden" id="fSiteId"><label>ESA 站点 SiteId <b>*</b></label><input name="esa_site_id" value="${esc(t.esa_site_id || '')}" placeholder="数字站点ID"></div>
        <div class="field"><label>同步间隔（秒，最小60）</label><input name="interval_sec" type="number" min="60" value="${t.interval_sec ?? 300}"></div>
        <div class="field"><label>启用定时同步</label><select name="enabled"><option value="true" ${t.enabled !== 0 ? 'selected' : ''}>启用</option><option value="false" ${t.enabled === 0 ? 'selected' : ''}>停用</option></select></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    window.ddnsProviderChanged = p => { $('#fSiteId').classList.toggle('hidden', p !== 'ALIYUN_ESA'); };
    window.ddnsModeChanged = m => {
        $('#fNic').classList.toggle('hidden', m !== 'LOCAL');
        $('#fManualIp').classList.toggle('hidden', m !== 'MANUAL');
    };
    window.ddnsProviderChanged(t.provider || 'ALIYUN_DNS');
    window.ddnsModeChanged(t.ip_mode || 'PUBLIC');
    $('#ddnsFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const f = new FormData(e.target);
        const body = Object.fromEntries(f.entries());
        body.ttl = Number(body.ttl || 600);
        body.interval_sec = Number(body.interval_sec || 300);
        body.enabled = body.enabled === 'true';
        try {
            if (id) await api('PUT', '/api/ddns/tasks/' + id, body);
            else await api('POST', '/api/ddns/tasks', body);
            closeModal(); toast('保存成功'); renderDdns();
        } catch (err) { toast(err.message, 'err'); }
    });
};

/* ---------------- STUN ---------------- */

async function renderStun() {
    const tasks = await api('GET', '/api/stun/tasks');
    const rows = tasks.map(t => `
      <tr>
        <td>${esc(t.name)} <span class="small muted">${esc(t.protocol)}</span></td>
        <td>${esc(t.target_ip)}:${t.target_port}${t.peer_addr ? `<div class="small muted">对端: ${esc(t.peer_addr)}</div>` : ''}</td>
        <td class="small">${esc(t.stun_host)}:${t.stun_port}${t.upnp_enabled === 0 ? '<div class="muted">UPnP 已关闭</div>' : ''}</td>
        <td>${t.status === 'RUNNING' ? badge('运行中', 'ok') : t.status === 'ERROR' ? badge('错误', 'err') : badge('已停止', 'gray')}</td>
        <td>${t.nat_type ? `<div class="small">${esc(t.nat_type)}</div>` : '-'}</td>
        <td>${t.mapped_addr ? badge(t.mapped_addr, 'info') : '-'}</td>
        <td>${t.punched_at ? `<span class="small">${esc(t.punched_at)}</span>` : '-'}</td>
        <td>${t.check_result
            ? `<div>${t.check_result.startsWith('OK') ? badge(t.check_result, 'ok') : badge(t.check_result, 'err')}</div>
               ${t.check_time ? `<div class="small muted">${esc(t.check_time)}</div>` : ''}`
            : '<span class="muted small">未自测</span>'}</td>
        <td>
          ${t.status === 'RUNNING'
            ? `<button class="btn small danger" onclick="stunCmd(${t.id},'stop')">停止</button>`
            : `<button class="btn small success" onclick="stunCmd(${t.id},'start')">启动</button>`}
          <button class="btn small" onclick="stunCmd(${t.id},'test')">探测</button>
          ${t.status === 'RUNNING' ? `<button class="btn small" onclick="stunCmd(${t.id},'verify')">自测</button>` : ''}
          <button class="btn small" onclick="stunForm(${t.id})">编辑</button>
          <button class="btn small danger" onclick="stunDelete(${t.id})">删除</button>
        </td>
      </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="tip">
        <b>穿透通道支持承载 UDP / TCP / HTTP 数据传输。</b>
        UDP 任务：外网数据包经 NAT 映射进入后按会话转发到内网目标，响应原路返回；
        TCP/HTTP 任务：建议填写「对端公网地址」由本端主动打洞，连接建立后双向透传（HTTP 直接可用），
        或依赖路由器端口映射/锥形 NAT 接受入站连接。
        <br><b>注意：任务运行中 ≠ 外网可访问。</b>外网能否主动连入取决于 NAT 过滤行为：
        Full Cone 可直接访问；受限锥形仅允许已打洞的对端访问；<b>对称型（Symmetric）NAT 纯 STUN 无法穿透</b>，
        请改用路由器端口转发。同时请确认主机防火墙已放行监听端口（Windows 需允许 Java 入站连接）。
        <br><b>可用性自测：</b>穿透成功后系统自动测试一次（TCP 连接映射地址 / UDP 发探测包）。
        <b>自测走本机→路由器 WAN 回环路径：路由器不支持 NAT 回流(hairpin)或拦截未请求入站时（企业路由器常见），即使穿透正常自测也会失败</b>
        （典型表现 Connection refused），此时请用外部设备（手机流量等）访问映射地址验证真实可达性。
        自测失败最多自动重新穿透 3 次并复测；连接被拒绝说明映射正常但被路由器拒绝，不会触发重穿。
        <br>穿透启动时同时尝试 <b>UPnP 端口映射</b>（需路由器开启 UPnP，路由器不支持时可在任务中关闭），
        TCP 任务还会从监听端口主动向支持 TCP 的 STUN 服务器出站（配置服务器不支持时自动改用「STUN 服务器维护」中标记支持 TCP 的服务器），
        在运营商 CGNAT 上建立真实 TCP 映射并周期保活——多层 NAT 下外网主动连入的关键；若路由器 WAN 口非公网（CGNAT，如 100.64.x.x），
        展示改用 STUN 出口公网 IP；也可在操作列点「自测」手动复测。
      </div>
      <div class="toolbar"><div class="spacer"></div>
        <button class="btn" onclick="renderStunServers()">🛠 管理STUN服务器</button>
        <button class="btn primary" onclick="stunForm()">＋ 新增穿透任务</button></div>
      <div class="panel"><table>
        <thead><tr><th>任务</th><th>内网目标</th><th>STUN服务器</th><th>状态</th><th>NAT类型</th><th>外网映射地址</th><th>穿透成功时间</th><th>可用性自测</th><th>操作</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="9" class="muted">暂无任务</td></tr>'}</tbody>
      </table></div>`;
    setRefresh(renderStun, 5000);
}

window.stunCmd = async (id, cmd) => {
    try {
        if (cmd === 'test') {
            toast('正在探测 NAT 类型...');
            const r = await api('POST', `/api/stun/tasks/${id}/test`);
            modal('STUN 探测结果', `<p>NAT 类型：<b>${esc(r.natType)}</b></p>
              <p style="margin-top:8px">外网映射地址(UDP)：<b>${esc(r.mapped || '无')}</b></p>
              ${r.tcpMapped ? `<p style="margin-top:8px">TCP 映射地址：<b>${esc(r.tcpMapped)}</b>
                <span class="muted small">（TCP 入站以此为准）</span></p>` : ''}
              <p class="muted small" style="margin-top:10px">对称型 NAT 下每次探测映射端口可能不同，属正常现象；
              任务运行中不代表外网可访问，受限/对称型需端口转发或已打洞对端。</p>`);
        } else if (cmd === 'verify') {
            toast('正在自测外网可达性（TCP 连接外网映射地址）...');
            const r = await api('POST', `/api/stun/tasks/${id}/verify`);
            toast('自测结果: ' + r.result, r.result.startsWith('OK') ? 'ok' : 'err');
        } else {
            await api('POST', `/api/stun/tasks/${id}/${cmd}`);
            toast(cmd === 'start' ? '已启动' : '已停止');
        }
        renderStun();
    } catch (e) { toast(e.message, 'err'); }
};

window.stunDelete = async id => {
    if (!confirm('确定删除该穿透任务？')) return;
    try { await api('DELETE', '/api/stun/tasks/' + id); toast('已删除'); renderStun(); }
    catch (e) { toast(e.message, 'err'); }
};

window.stunForm = async (id) => {
    let t = {};
    if (id) t = (await api('GET', '/api/stun/tasks')).find(x => x.id === id);
    let servers = [];
    try { servers = await api('GET', '/api/stun/servers'); } catch (e) { /* ignore */ }
    const matched = servers.find(s => s.host === t.stun_host && Number(s.port) === Number(t.stun_port));
    const serverOpts = servers.map(s =>
        `<option value="${s.id}" ${matched && matched.id === s.id ? 'selected' : ''}>${esc(s.name)}（${esc(s.host)}:${s.port}${s.tcp_support === 1 ? '，支持TCP' : ''}）</option>`).join('');
    const custom = !matched && (!!t.stun_host || !servers.length);
    modal(id ? '编辑穿透任务' : '新增穿透任务', `
      <form id="stunFormEl" class="form-grid">
        <div class="field"><label>任务名称 <b>*</b></label><input name="name" required value="${esc(t.name || '')}"></div>
        <div class="field"><label>穿透协议 <b>*</b></label>
          <select name="protocol"><option ${t.protocol !== 'TCP' ? 'selected' : ''}>UDP</option><option ${t.protocol === 'TCP' ? 'selected' : ''}>TCP</option></select></div>
        <div class="field"><label>内网目标 IP <b>*</b></label><input name="target_ip" required value="${esc(t.target_ip || '')}" placeholder="192.168.1.10"></div>
        <div class="field"><label>内网目标端口 <b>*</b></label><input name="target_port" required type="number" min="1" max="65535" value="${t.target_port ?? ''}"></div>
        <div class="field"><label>本地绑定端口</label><input name="bind_port" type="number" min="0" max="65535" value="${t.bind_port ?? 0}" placeholder="0=随机"></div>
        <div class="field"><label>保活间隔（秒）</label><input name="keepalive_sec" type="number" min="10" value="${t.keepalive_sec ?? 25}"></div>
        <div class="field full"><label>对端公网地址（TCP 打洞选填）</label><input name="peer_addr" value="${esc(t.peer_addr || '')}" placeholder="如 203.0.113.5:8080，留空则仅接受入站连接"></div>
        <div class="field"><label>STUN 服务器 <b>*</b></label>
          <select name="stun_server" onchange="stunServerChanged(this.value)">
            ${serverOpts}
            <option value="custom" ${custom ? 'selected' : ''}>自定义...</option>
          </select></div>
        <div class="field"><label>UPnP 端口映射</label>
          <select name="upnp_enabled">
            <option value="true" ${t.upnp_enabled !== 0 ? 'selected' : ''}>启用（需路由器支持UPnP）</option>
            <option value="false" ${t.upnp_enabled === 0 ? 'selected' : ''}>停用（路由器不支持UPnP）</option>
          </select></div>
        <div class="field ${custom ? '' : 'hidden'}" id="fStunHost"><label>STUN 地址 <b>*</b></label><input name="stun_host" value="${esc(t.stun_host || '')}" placeholder="stun.miwifi.com"></div>
        <div class="field ${custom ? '' : 'hidden'}" id="fStunPort"><label>STUN 端口</label><input name="stun_port" type="number" min="1" max="65535" value="${t.stun_port ?? 3478}"></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    window.stunServerChanged = v => {
        $('#fStunHost').classList.toggle('hidden', v !== 'custom');
        $('#fStunPort').classList.toggle('hidden', v !== 'custom');
    };
    $('#stunFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const body = Object.fromEntries(new FormData(e.target).entries());
        const sv = body.stun_server;
        delete body.stun_server;
        if (sv && sv !== 'custom') {
            const s = servers.find(x => String(x.id) === String(sv));
            body.stun_host = s.host;
            body.stun_port = Number(s.port);
        } else {
            body.stun_port = Number(body.stun_port || 3478);
        }
        body.target_port = Number(body.target_port); body.bind_port = Number(body.bind_port || 0);
        body.keepalive_sec = Number(body.keepalive_sec || 25);
        body.upnp_enabled = body.upnp_enabled === 'true';
        try {
            if (id) await api('PUT', '/api/stun/tasks/' + id, body);
            else await api('POST', '/api/stun/tasks', body);
            closeModal(); toast('保存成功'); renderStun();
        } catch (err) { toast(err.message, 'err'); }
    });
};

/** STUN 服务器维护视图：穿透任务表单下拉选择的数据源 */
async function renderStunServers() {
    const servers = await api('GET', '/api/stun/servers');
    const rows = servers.map(s => `
      <tr>
        <td>${esc(s.name)}</td>
        <td class="small">${esc(s.host)}:${s.port}</td>
        <td>${s.tcp_support === 1 ? badge('支持', 'ok') : badge('不支持', 'gray')}</td>
        <td>
          <button class="btn small" onclick="stunServerForm(${s.id})">编辑</button>
          <button class="btn small danger" onclick="stunServerDelete(${s.id})">删除</button>
        </td>
      </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="tip">维护常用 STUN 服务器列表，穿透任务新增/编辑时从下拉中选择。
        <b>支持 TCP</b> 表示服务器支持 STUN-over-TCP：TCP 穿透任务需经支持 TCP 的服务器出站，才能在运营商 CGNAT 上建立真实 TCP 映射。</div>
      <div class="toolbar">
        <button class="btn small" onclick="renderStun()">← 返回穿透任务</button>
        <div class="spacer"></div>
        <button class="btn primary small" onclick="stunServerForm()">＋ 新增STUN服务器</button>
      </div>
      <div class="panel"><table>
        <thead><tr><th>名称</th><th>地址</th><th>STUN-over-TCP</th><th>操作</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="4" class="muted">暂无服务器，新增穿透任务时需自定义填写地址</td></tr>'}</tbody>
      </table></div>`;
    setRefresh(null);
}
window.renderStunServers = renderStunServers;

window.stunServerForm = async (id) => {
    let s = {};
    if (id) s = (await api('GET', '/api/stun/servers')).find(x => x.id === id);
    modal(id ? '编辑STUN服务器' : '新增STUN服务器', `
      <form id="stunServerFormEl" class="form-grid">
        <div class="field full"><label>服务器名称 <b>*</b></label><input name="name" required value="${esc(s.name || '')}" placeholder="小米 / 谷歌"></div>
        <div class="field"><label>服务器地址 <b>*</b></label><input name="host" required value="${esc(s.host || '')}" placeholder="stun.miwifi.com"></div>
        <div class="field"><label>端口</label><input name="port" type="number" min="1" max="65535" value="${s.port ?? 3478}"></div>
        <div class="field full"><label>STUN-over-TCP 支持</label>
          <select name="tcp_support">
            <option value="true" ${s.tcp_support === 1 ? 'selected' : ''}>支持（TCP 任务可经其建立 CGNAT 映射）</option>
            <option value="false" ${s.tcp_support !== 1 ? 'selected' : ''}>不支持 / 未知</option>
          </select></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    $('#stunServerFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const f = new FormData(e.target);
        const body = { name: f.get('name').trim(), host: f.get('host').trim(),
            port: Number(f.get('port') || 3478), tcp_support: f.get('tcp_support') === 'true' };
        try {
            if (id) await api('PUT', '/api/stun/servers/' + id, body);
            else await api('POST', '/api/stun/servers', body);
            closeModal(); toast('保存成功'); renderStunServers();
        } catch (err) { toast(err.message, 'err'); }
    });
};

window.stunServerDelete = async id => {
    if (!confirm('确定删除该 STUN 服务器？已创建的穿透任务不受影响。')) return;
    try { await api('DELETE', '/api/stun/servers/' + id); toast('已删除'); renderStunServers(); }
    catch (e) { toast(e.message, 'err'); }
};

/* ---------------- WOL ---------------- */

async function renderWol() {
    const devs = await api('GET', '/api/wol/devices');
    const rows = devs.map(d => `
      <tr>
        <td><input type="checkbox" class="wol-chk" value="${d.id}"></td>
        <td>${esc(d.name)}</td>
        <td>${esc(d.mac)}</td>
        <td>${esc(d.broadcast)}:${d.port}</td>
        <td>
          <button class="btn small success" onclick="wolWake(${d.id})">⚡ 唤醒</button>
          <button class="btn small" onclick="wolForm(${d.id})">编辑</button>
          <button class="btn small danger" onclick="wolDelete(${d.id})">删除</button>
        </td>
      </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="tip">通过局域网广播 UDP 魔术包唤醒设备，被唤醒主机需在网卡与 BIOS 中开启 WOL（Wake on LAN）支持。</div>
      <div class="toolbar">
        <button class="btn" onclick="wolWakeBatch()">⚡ 批量唤醒选中</button>
        <label class="small muted"><input type="checkbox" id="wolAll"> 全选</label>
        <div class="spacer"></div>
        <button class="btn primary" onclick="wolForm()">＋ 新增设备</button>
      </div>
      <div class="panel"><table>
        <thead><tr><th style="width:30px"></th><th>设备名称</th><th>MAC 地址</th><th>广播地址</th><th>操作</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="5" class="muted">暂无设备</td></tr>'}</tbody>
      </table></div>`;
    $('#wolAll').addEventListener('change', e =>
        $$('.wol-chk').forEach(c => c.checked = e.target.checked));
    setRefresh(null);
}

window.wolWake = async id => {
    try { await api('POST', `/api/wol/devices/${id}/wake`); toast('唤醒魔术包已发送'); }
    catch (e) { toast(e.message, 'err'); }
};

window.wolWakeBatch = async () => {
    const ids = [...$$('.wol-chk:checked')].map(c => Number(c.value));
    if (!ids.length) { toast('请先勾选设备', 'err'); return; }
    try {
        const r = await api('POST', '/api/wol/wake-batch', { ids });
        toast('批量唤醒完成: ' + r.results.join('；'));
    } catch (e) { toast(e.message, 'err'); }
};

window.wolDelete = async id => {
    if (!confirm('确定删除该设备？')) return;
    try { await api('DELETE', '/api/wol/devices/' + id); toast('已删除'); renderWol(); }
    catch (e) { toast(e.message, 'err'); }
};

window.wolForm = async (id) => {
    let d = {};
    if (id) d = (await api('GET', '/api/wol/devices')).find(x => x.id === id);
    modal(id ? '编辑设备' : '新增唤醒设备', `
      <form id="wolFormEl" class="form-grid">
        <div class="field full"><label>机器名称 <b>*</b></label><input name="name" required value="${esc(d.name || '')}"></div>
        <div class="field full"><label>MAC 地址 <b>*</b></label><input name="mac" required placeholder="00:11:22:33:44:55" value="${esc(d.mac || '')}"></div>
        <div class="field"><label>广播地址 <b>*</b></label><input name="broadcast" required value="${esc(d.broadcast || '255.255.255.255')}"></div>
        <div class="field"><label>WOL 端口</label><input name="port" type="number" value="${d.port ?? 9}"></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    $('#wolFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const body = Object.fromEntries(new FormData(e.target).entries());
        body.port = Number(body.port || 9);
        try {
            if (id) await api('PUT', '/api/wol/devices/' + id, body);
            else await api('POST', '/api/wol/devices', body);
            closeModal(); toast('保存成功'); renderWol();
        } catch (err) { toast(err.message, 'err'); }
    });
};

/* ---------------- SSL 证书 ---------------- */

async function renderCert() {
    const tasks = await api('GET', '/api/cert/tasks');
    let s = {};
    try { s = await api('GET', '/api/cert/settings'); } catch (e) { /* ignore */ }
    const rows = tasks.map(t => {
        let actions = `<button class="btn small success" onclick="certIssue(${t.id})">${t.status === 'ISSUED' ? '续期' : '申请'}</button>`;
        if (t.status === 'PENDING_VALIDATION') {
            actions += ` <button class="btn small" onclick="certShowHint(${t.id})">查看TXT</button>
              <button class="btn small primary" onclick="certValidate(${t.id})">完成验证</button>`;
        }
        if (t.status === 'ISSUED') {
            actions += ` <button class="btn small" onclick="certDetail(${t.id})">详情</button>
              <button class="btn small" onclick="certDownload(${t.id})">下载</button>`;
        }
        return `<tr>
          <td>${esc(t.name)}<div class="small muted">${t.provider === 'ZEROSSL' ? 'ZeroSSL' : "Let's Encrypt"} · ${esc(t.challenge_type)}</div></td>
          <td class="small">${esc(t.domains)}</td>
          <td>${statusBadge(t.status)}${t.message ? `<div class="small muted" style="max-width:260px;word-break:break-all">${esc(t.message)}</div>` : ''}</td>
          <td class="small">${t.not_after ? esc(t.not_after.substring(0, 10)) : '-'}</td>
          <td><label class="switch"><input type="checkbox" ${t.auto_renew === 1 ? 'checked' : ''} onchange="certAutoRenew(${t.id}, this.checked)"><span class="slider"></span></label></td>
          <td>${actions} <button class="btn small" onclick="certForm(${t.id})">编辑</button>
            <button class="btn small danger" onclick="certDelete(${t.id})">删除</button></td>
        </tr>`;
    }).join('');
    $('#pageBody').innerHTML = `
      <div class="tip">
        Let's Encrypt 免费无需凭证；<b>ZeroSSL 需要在下方填写 EAB 凭证</b>（ZeroSSL 控制台 → Developer → EAB Credentials 生成）。
        HTTP01 验证要求公网可访问本机 80 端口（可路由器转发）；无法开放 80 端口时请选择 DNS01 手动添加 TXT 记录。
      </div>
      <div class="panel">
        <h3 style="font-size:14px;margin-bottom:12px">服务商凭证设置</h3>
        <form id="certSettingForm" class="form-grid">
          <div class="field"><label>联系邮箱（可选）</label><input name="email" type="email" value="${esc(s.email || '')}"></div>
          <div class="field"><label>ZeroSSL EAB KID</label><input name="zerossl_eab_kid" value="${esc(s.zerossl_eab_kid || '')}"></div>
          <div class="field"><label>ZeroSSL EAB HMAC Key</label><input name="zerossl_eab_hmac" type="password" value="${esc(s.zerossl_eab_hmac || '')}"></div>
          <div class="field" style="align-self:end"><button class="btn primary">保存设置</button></div>
        </form>
      </div>
      <div class="toolbar"><div class="spacer"></div><button class="btn primary" onclick="certForm()">＋ 新增证书任务</button></div>
      <div class="panel"><table>
        <thead><tr><th>任务</th><th>域名</th><th>状态</th><th>到期时间</th><th>自动续期</th><th>操作</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="6" class="muted">暂无证书任务</td></tr>'}</tbody>
      </table></div>`;
    $('#certSettingForm').addEventListener('submit', async e => {
        e.preventDefault();
        const body = Object.fromEntries(new FormData(e.target).entries());
        try { await api('PUT', '/api/cert/settings', body); toast('设置已保存'); }
        catch (err) { toast(err.message, 'err'); }
    });
    setRefresh(renderCert, 8000);
}

window.certIssue = async id => {
    try { await api('POST', `/api/cert/tasks/${id}/issue`); toast('申请已发起，请稍候刷新查看进度'); renderCert(); }
    catch (e) { toast(e.message, 'err'); }
};

window.certValidate = async id => {
    try { await api('POST', `/api/cert/tasks/${id}/validate`); toast('已提交验证，等待 CA 校验'); renderCert(); }
    catch (e) { toast(e.message, 'err'); }
};

window.certShowHint = async id => {
    const t = (await api('GET', '/api/cert/tasks')).find(x => x.id === id);
    modal('DNS 验证提示', `<p class="muted small" style="margin-bottom:10px">请到域名解析服务商添加以下 TXT 记录，生效后点击「完成验证」：</p>
      <pre style="background:#f1f5f9;padding:12px;border-radius:8px;white-space:pre-wrap;font-size:13px">${esc(t.dns_hint || '无')}</pre>`);
};

window.certAutoRenew = async (id, checked) => {
    const tasks = await api('GET', '/api/cert/tasks');
    const t = tasks.find(x => x.id === id);
    try {
        await api('PUT', '/api/cert/tasks/' + id, {
            name: t.name, provider: t.provider, domains: t.domains,
            challenge_type: t.challenge_type, auto_renew: checked
        });
        toast(checked ? '已开启自动续期' : '已关闭自动续期');
    } catch (e) { toast(e.message, 'err'); }
};

window.certDetail = async id => {
    try {
        const d = await api('GET', `/api/cert/tasks/${id}/detail`);
        if (!d.hasCert) { toast('证书尚未签发', 'err'); return; }
        modal('证书详情', `<table>
          <tr><th style="width:110px">主体</th><td class="small">${esc(d.subject)}</td></tr>
          <tr><th>颁发者</th><td class="small">${esc(d.issuer)}</td></tr>
          <tr><th>域名</th><td class="small">${d.domains.map(esc).join('，')}</td></tr>
          <tr><th>生效时间</th><td class="small">${esc(d.notBefore)}</td></tr>
          <tr><th>到期时间</th><td class="small">${esc(d.notAfter)}</td></tr>
          <tr><th>序列号</th><td class="small">${esc(d.serial)}</td></tr>
          <tr><th>SHA-256 指纹</th><td class="small" style="word-break:break-all">${esc(d.sha256)}</td></tr>
        </table>`);
    } catch (e) { toast(e.message, 'err'); }
};

window.certDownload = id => {
    const t = TOKEN;
    modal('下载证书文件', `
      <p class="muted small" style="margin-bottom:12px">私钥文件请妥善保管，切勿泄露。</p>
      <div style="display:flex;gap:10px;flex-wrap:wrap">
        <a class="btn" href="/api/cert/tasks/${id}/download?file=fullchain&token=${t}">fullchain.pem（证书链）</a>
        <a class="btn" href="/api/cert/tasks/${id}/download?file=cert&token=${t}">cert.pem（证书）</a>
        <a class="btn" href="/api/cert/tasks/${id}/download?file=key&token=${t}">domain.key.pem（私钥）</a>
      </div>`);
};

window.certDelete = async id => {
    if (!confirm('确定删除该证书任务？磁盘上的证书文件也会被清理。')) return;
    try { await api('DELETE', '/api/cert/tasks/' + id); toast('已删除'); renderCert(); }
    catch (e) { toast(e.message, 'err'); }
};

window.certForm = async (id) => {
    let t = {};
    if (id) t = (await api('GET', '/api/cert/tasks')).find(x => x.id === id);
    modal(id ? '编辑证书任务' : '新增证书任务', `
      <form id="certFormEl" class="form-grid">
        <div class="field"><label>任务名称 <b>*</b></label><input name="name" required value="${esc(t.name || '')}"></div>
        <div class="field"><label>证书服务商 <b>*</b></label>
          <select name="provider">
            <option value="LETSENCRYPT" ${t.provider !== 'ZEROSSL' ? 'selected' : ''}>Let's Encrypt</option>
            <option value="ZEROSSL" ${t.provider === 'ZEROSSL' ? 'selected' : ''}>ZeroSSL</option>
          </select></div>
        <div class="field full"><label>域名列表 <b>*</b>（逗号分隔，支持 *.example.com 通配，仅 DNS01）</label>
          <input name="domains" required placeholder="example.com, www.example.com" value="${esc(t.domains || '')}"></div>
        <div class="field"><label>验证方式 <b>*</b></label>
          <select name="challenge_type">
            <option value="HTTP01" ${t.challenge_type !== 'DNS01' ? 'selected' : ''}>HTTP01（自动，需80端口）</option>
            <option value="DNS01" ${t.challenge_type === 'DNS01' ? 'selected' : ''}>DNS01（手动添加TXT记录）</option>
          </select></div>
        <div class="field"><label>自动续期</label>
          <select name="auto_renew"><option value="true" ${t.auto_renew !== 0 ? 'selected' : ''}>开启（到期前21天）</option><option value="false" ${t.auto_renew === 0 ? 'selected' : ''}>关闭</option></select></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    $('#certFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const body = Object.fromEntries(new FormData(e.target).entries());
        body.auto_renew = body.auto_renew === 'true';
        try {
            if (id) await api('PUT', '/api/cert/tasks/' + id, body);
            else await api('POST', '/api/cert/tasks', body);
            closeModal(); toast('保存成功'); renderCert();
        } catch (err) { toast(err.message, 'err'); }
    });
};

/* ---------------- 日志 ---------------- */

let logPage = 1, logModule = '';

async function renderLogs() {
    const r = await api('GET', `/api/logs?page=${logPage}&size=50&module=${encodeURIComponent(logModule)}`);
    const total = Number(r.total);
    const pages = Math.max(1, Math.ceil(total / 50));
    const rows = r.list.map(l => `
      <tr class="${l.level === 'ERROR' ? 'log-row-error' : l.level === 'WARN' ? 'log-row-warn' : ''}">
        <td class="log-cell-time">${esc(l.time)}</td>
        <td>${badge(l.module, 'info')}</td>
        <td>${esc(l.level)}</td>
        <td class="log-cell-msg">${esc(l.message)}</td>
      </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="toolbar">
        <select id="logModuleSel" class="btn" style="padding:6px 10px">
          <option value="">全部模块</option>
          ${['SYSTEM', 'AUTH', 'DDNS', 'STUN', 'WOL', 'CERT', 'NAV'].map(m =>
              `<option value="${m}" ${logModule === m ? 'selected' : ''}>${m}</option>`).join('')}
        </select>
        <button class="btn" onclick="logPage=1;renderLogs()">刷新</button>
        <label class="small muted"><input type="checkbox" id="logAuto" ${logAutoRefresh ? 'checked' : ''}> 自动刷新</label>
        <div class="spacer"></div>
        <span class="muted small">共 ${total} 条</span>
      </div>
      <div class="panel"><table>
        <thead><tr><th style="width:150px">时间</th><th style="width:90px">模块</th><th style="width:70px">级别</th><th>内容</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="4" class="muted">暂无日志</td></tr>'}</tbody>
      </table>
      <div class="pager">
        <button class="btn small" ${logPage <= 1 ? 'disabled' : ''} onclick="logPage--;renderLogs()">上一页</button>
        <span class="small muted">第 ${logPage} / ${pages} 页</span>
        <button class="btn small" ${logPage >= pages ? 'disabled' : ''} onclick="logPage++;renderLogs()">下一页</button>
      </div></div>`;
    $('#logModuleSel').addEventListener('change', e => { logModule = e.target.value; logPage = 1; renderLogs(); });
    $('#logAuto').addEventListener('change', e => { logAutoRefresh = e.target.checked; });
    if (logAutoRefresh) setRefresh(renderLogs, 5000); else setRefresh(null);
}

/* ---------------- 设置 ---------------- */

async function renderSettings() {
    let info = {};
    try { info = await api('GET', '/api/system/info'); } catch (e) { /* ignore */ }
    $('#pageBody').innerHTML = `
      <div class="panel">
        <h3 style="font-size:14px;margin-bottom:12px">系统信息</h3>
        <table>
          <tr><th style="width:120px">版本</th><td>${esc(info.version || '')}</td></tr>
          <tr><th>Java 版本</th><td>${esc(info.javaVersion || '')}</td></tr>
          <tr><th>操作系统</th><td>${esc(info.os || '')}</td></tr>
          <tr><th>服务端口</th><td>${info.port ?? ''}（修改请编辑运行目录 nexhome.properties 后重启）</td></tr>
          <tr><th>运行时长</th><td>${Math.floor((info.uptimeSec || 0) / 3600)} 小时 ${Math.floor((info.uptimeSec || 0) % 3600 / 60)} 分钟</td></tr>
          <tr><th>内存占用</th><td>${info.usedMemoryMB ?? ''} MB / 上限 ${info.maxMemoryMB ?? ''} MB</td></tr>
        </table>
      </div>
      <div class="panel">
        <h3 style="font-size:14px;margin-bottom:12px">修改登录密码</h3>
        <form id="pwdForm">
          <div class="field" style="width: 200px"><label>原密码 <b>*</b></label><input name="old_password" type="password" required></div>
          <div class="field" style="width: 200px"><label>新密码 <b>*</b>（至少12位）</label><input name="new_password" type="password" required minlength="12"></div>
          <div class="field" style="width: 200px"><label>确认新密码 <b>*</b></label><input name="confirm" type="password" required></div>
          <div class="field" style="align-self:end; margin-top: 10px"><button class="btn primary">修改密码</button></div>
        </form>
      </div>`;
    $('#pwdForm').addEventListener('submit', async e => {
        e.preventDefault();
        const f = new FormData(e.target);
        if (f.get('new_password') !== f.get('confirm')) { toast('两次输入的新密码不一致', 'err'); return; }
        try {
            await api('POST', '/api/auth/change-password', {
                old_password: f.get('old_password'), new_password: f.get('new_password')
            });
            toast('密码已修改，请重新登录');
            TOKEN = '';
            localStorage.removeItem('nx_token');
            showLogin();
        } catch (err) { toast(err.message, 'err'); }
    });
}

/* ---------------- 启动 ---------------- */
checkLogin();
