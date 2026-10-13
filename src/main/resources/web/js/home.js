/* ============ 首页：网站导航模块 ============ */
'use strict';

let navSmartMode = null;          // null=自动, 'lan'/'wan' 手动
let netSourceLan = null;          // 服务端按连接源 IP 识别的访问来源（null=未知，回退 hostname 判断）
let navProbeUrl = '';             // 内网探测链接（如路由器管理地址），空=未启用
let navProbeLan = null;           // 浏览器探测内网链接的结果（true=可达/处于内网，false=不可达，null=未探测）

/** 图标展示地址：本地图标存的是相对 URL（/nav-icons/xxx），安全入口模式下需补上 API 前缀才能命中路由 */
function iconSrc(u) {
    return u && u.startsWith('/') ? API_BASE + u : u;
}

function isLanVisit() {
    // 探测结果优先：直接反映浏览器所在网络，不受访问链路（域名/反代/回流）干扰
    if (navProbeLan !== null) return navProbeLan;
    if (netSourceLan !== null) return netSourceLan;
    const h = location.hostname;
    return /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h);
}

/** 浏览器端探测内网链接是否可达：no-cors 只测连通不读内容，缓存穿透；失败/超时均视为不可达，返回 null 表示未配置不探测 */
function probeLanUrl(timeoutMs = 2000) {
    if (!navProbeUrl) return Promise.resolve(null);
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    const url = navProbeUrl + (navProbeUrl.includes('?') ? '&' : '?') + '_nxprobe=' + Date.now();
    return fetch(url, { mode: 'no-cors', cache: 'no-store', signal: ctrl.signal })
        .then(() => true, () => false)
        .finally(() => clearTimeout(timer));
}

function currentNetMode() {
    if (navSmartMode) return navSmartMode;
    return isLanVisit() ? 'lan' : 'wan';
}

async function renderHome() {
    const [items, src, cfg] = await Promise.all([
        api('GET', '/api/nav/items'),
        // 服务端按连接源 IP 识别访问来源（内外网两套域名也不影响判断），失败时回退 hostname 判断
        api('GET', '/api/net/source').catch(() => null),
        api('GET', '/api/nav/settings').catch(() => ({}))
    ]);
    if (src && typeof src.lan === 'boolean') netSourceLan = src.lan;
    navProbeUrl = (cfg && cfg.lanProbeUrl) || '';
    // 自动模式下先探测内网链接再渲染：探测超时/失败按不可达处理，不影响正常出图时间（最坏多等一个超时时长）
    navProbeLan = (!navSmartMode && navProbeUrl) ? await probeLanUrl() : null;
    const mode = currentNetMode();
    const autoLan = isLanVisit();
    // 分段按钮悬停提示：自动模式展示识别依据，手动模式提示如何恢复
    const basis = navProbeLan !== null ? '识别依据：浏览器探测 ' + navProbeUrl + (autoLan ? ' 可达' : ' 不可达')
        : (src && src.ip ? '识别依据来源 IP：' + src.ip : '');
    const segTitle = navSmartMode ? '已手动指定地址类型，点「自动」恢复自动识别'
        : (basis || '自动模式：按访问来源 / 内网探测结果自动选择内网或外网地址');
    // 背景图：系统内相对路径补 API 前缀（安全入口模式），外部直链原样使用；经 CSS 变量交给 ::before 模糊渲染
    const bg = (cfg && cfg.bgUrl) || '';
    const bgAttr = bg ? ` style="--nav-bg:url('${esc(iconSrc(bg))}')"` : '';
    let html = `
      <div class="nav-page${bg ? ' has-bg' : ''}"${bgAttr}>
      <div class="toolbar">
        <div class="seg" title="${esc(segTitle)}">
          <button class="seg-btn${navSmartMode ? '' : ' active'}" onclick="switchNetMode(null)">自动</button>
          <button class="seg-btn${navSmartMode === 'lan' ? ' active' : ''}" onclick="switchNetMode('lan')">内网</button>
          <button class="seg-btn${navSmartMode === 'wan' ? ' active' : ''}" onclick="switchNetMode('wan')">外网</button>
        </div>
        <div class="spacer"></div>
        <button class="btn small" onclick="renderNavManage()">🛠 管理导航</button>
        <button class="btn primary small" onclick="navForm()">＋ 新增导航</button>
      </div>`;
    if (!items.length) {
        html += '<div class="panel"><p class="muted">暂无导航条目，点击右上角「新增导航」添加。</p></div>';
    } else {
        html += '<div class="nav-grid">';
        for (const it of items.filter(i => i.enabled === 1)) {
            const lanFirst = mode === 'lan';
            // 当前模式无对应地址时回退到另一地址（两地址允许只填其一）
            const preferred = lanFirst ? it.lan_url : it.wan_url;
            const url = preferred || (lanFirst ? it.wan_url : it.lan_url);
            if (!url) continue;
            const fallback = !preferred;
            const kind = (preferred ? lanFirst : !lanFirst) ? '内网地址' : '外网地址';
            const icon = it.icon_url
                ? `<img src="${esc(iconSrc(it.icon_url))}" onerror="this.replaceWith(document.createTextNode('🌍'))">`
                : '🌍';
            const tip = [it.description, (fallback ? '回退' : '') + kind + '：' + url].filter(Boolean).join('\n');
            html += `
            <div class="nav-tile" title="${esc(tip)}" onclick="window.open('${esc(url)}','_blank')">
              <div class="tile-actions">
                <button class="btn small" onclick="event.stopPropagation();navForm(${it.id})">编辑</button>
                <button class="btn small danger" onclick="event.stopPropagation();navDelete(${it.id})">删除</button>
              </div>
              <div class="tile-icon">${icon}</div>
              <div class="tile-name">${esc(it.name)}</div>
            </div>`;
        }
        html += '</div>';
    }
    html += '</div>';
    $('#pageBody').innerHTML = html;
}

window.switchNetMode = m => { navSmartMode = m; renderHome(); };

/** 导航管理视图（支持拖拽排序） */
async function renderNavManage() {
    const items = await api('GET', '/api/nav/items');
    let rows = items.map(it => `
        <tr draggable="true" data-id="${it.id}" class="drag-row">
          <td style="cursor:move">⠿</td>
          <td>${it.icon_url ? `<img class="nav-thumb" src="${esc(iconSrc(it.icon_url))}" onerror="this.replaceWith(document.createTextNode('🌍'))">` : ''}</td>
          <td>${esc(it.name)}</td>
          <td class="small muted">${esc(it.lan_url) || '-'}</td>
          <td class="small muted">${esc(it.wan_url) || '-'}</td>
          <td>${it.enabled === 1 ? badge('启用', 'ok') : badge('停用', 'gray')}</td>
          <td>
            <button class="btn small" onclick="navForm(${it.id})">编辑</button>
            <button class="btn small danger" onclick="navDelete(${it.id})">删除</button>
          </td>
        </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="toolbar">
        <button class="btn small" onclick="renderHome()">← 返回卡片</button>
        <button class="btn small" onclick="navProbeForm()">🔌 内网探测</button>
        <button class="btn small" onclick="navBgForm()">🌄 背景设置</button>
        <button class="btn small" onclick="navImageManage()">🖼 图片管理</button>
        <div class="spacer"></div>
        <span class="muted small">拖动行调整顺序，松开自动保存</span>
        <button class="btn primary small" onclick="navForm()">＋ 新增导航</button>
      </div>
      <div class="panel"><table>
        <thead><tr><th></th><th>图标</th><th>名称</th><th>内网地址</th><th>外网地址</th><th>状态</th><th>操作</th></tr></thead>
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

/** 内网探测链接设置：浏览器探测该固定地址（如路由器）可达时判定当前处于内网 */
window.navProbeForm = async () => {
    let cfg = {};
    try { cfg = await api('GET', '/api/nav/settings'); } catch (e) { /* ignore */ }
    modal('内网探测设置', `
      <form id="navProbeFormEl" class="form-grid">
        <div class="field full"><label>内网探测链接（留空关闭）</label>
          <input name="lanProbeUrl" placeholder="如 http://192.168.1.1（路由器管理地址）" value="${esc(cfg.lanProbeUrl || '')}">
          <div class="small muted">配置后首页自动识别将优先在浏览器探测该地址：可达=当前处于内网，导航使用内网地址；不可达=使用外网地址。
            适合始终经互联网域名/反代访问的场景（此时服务端看到的源 IP 都是内网代理，无法判断真实位置）。
            建议：① 填内网特有地址（路由器、NAS 等），其他局域网同样存在 192.168.1.1 时可能误判为内网；
            ② 面板经 HTTPS 访问时浏览器会拦截 http:// 探测链接（混合内容），请改填内网 https 地址或经 http 访问面板。</div>
        </div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    $('#navProbeFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const body = { lanProbeUrl: new FormData(e.target).get('lanProbeUrl').trim() };
        try {
            await api('PUT', '/api/nav/settings', body);
            closeModal(); toast('已保存，返回首页后生效');
        } catch (err) { toast(err.message, 'err'); }
    });
};

/** 导航背景设置：系统内路径（/images/xx、/nav-icons/xx）或外部 http(s) 图片直链 */
window.navBgForm = async () => {
    let cfg = {};
    try { cfg = await api('GET', '/api/nav/settings'); } catch (e) { /* ignore */ }
    modal('导航背景设置', `
      <form id="navBgFormEl" class="form-grid">
        <div class="field full"><label>背景图地址（留空关闭）</label>
          <div style="display:flex;gap:8px;align-items:center">
            <input name="bgUrl" id="navBgUrl" placeholder="系统内：/images/bg.png 或 /nav-icons/xxx.png；外链：https://..." value="${esc(cfg.bgUrl || '')}" style="flex:1">
            <button type="button" class="btn small" onclick="navImageManage(navBgPick)">从图库选择</button>
          </div>
          <div class="small muted">系统内地址两种来源：①「图片管理」中上传的图片（/nav-icons/xxx.png）；② 自行放入程序 data/images 目录的文件，以 /images/bg.png 引用。
            外部地址为任意 http(s) 图片直链。背景经模糊处理后铺满导航首页，仅影响首页观感；留空则恢复默认浅色底。</div>
        </div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    $('#navBgFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const body = { bgUrl: new FormData(e.target).get('bgUrl').trim() };
        try {
            await api('PUT', '/api/nav/settings', body);
            closeModal(); toast('已保存'); renderHome();
        } catch (err) { toast(err.message, 'err'); }
    });
};

/** 图库选择回调：把选中图片路径回填到背景地址输入框（此时图片库弹窗已关闭回落到背景表单） */
window.navBgPick = url => { const el = $('#navBgUrl'); if (el) el.value = url; };

/** 复制文本到剪贴板：优先 navigator.clipboard，非 HTTPS 环境回退 textarea 方案 */
window.copyText = async t => {
    try {
        await navigator.clipboard.writeText(t);
    } catch (e) {
        const ta = document.createElement('textarea');
        ta.value = t;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        ta.remove();
    }
    toast('已复制：' + t);
};

/** 字节数 -> 人类可读（≥1MB 显示一位小数 MB，否则取整 KB）；独立命名避免与 docker.js 的全局 fmtBytes 冲突 */
const fmtImgSize = b => b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB';

let imgPickCb = null;   // 图片库选择回调（背景设置选图模式）；null=纯管理模式

/** 图片管理：导航菜单上传的本地图片清单，支持预览/复制路径/删除/继续上传；传 onPick 进入选图模式 */
window.navImageManage = async (onPick) => {
    imgPickCb = onPick || null;
    const data = await api('GET', '/api/nav/images');
    const imgs = data.images || [];
    const cells = imgs.map(im => `
      <div class="img-cell">
        <img class="img-thumb" src="${esc(iconSrc(im.url))}" title="点击预览" onclick="navImagePreview('${esc(im.url)}')">
        <div class="img-path small muted" title="${esc(im.url)}">${esc(im.url)}</div>
        <div class="small muted">${fmtImgSize(im.size)}${im.refs && im.refs.length ? ' · 被引用：' + esc(im.refs.join('、')) : ''}</div>
        <div class="img-ops">
          ${imgPickCb ? `<button class="btn small primary" onclick="navImagePick('${esc(im.url)}')">选用</button>` : ''}
          <button class="btn small" onclick="copyText('${esc(im.url)}')">复制路径</button>
          <button class="btn small danger" onclick="navImageDelete('${esc(im.url)}')">删除</button>
        </div>
      </div>`).join('');
    modal('图片管理', `
      <div class="toolbar">
        <button class="btn small primary" id="imgUploadBtn">⬆ 上传图片</button>
        <input type="file" id="imgUploadFile" accept="image/png,image/jpeg,image/gif,image/webp,image/svg+xml,image/x-icon" hidden>
        <div class="spacer"></div>
        <span class="muted small">共 ${imgs.length} 张图片${imgPickCb ? ' · 点「选用」作为背景图' : ' · 可用于导航图标与背景图'}</span>
      </div>
      ${imgs.length ? `<div class="img-grid">${cells}</div>`
        : '<p class="muted">暂无上传图片，点左上「上传图片」添加；上传后可用于导航图标与背景图。</p>'}`, 'wide');
    $('#imgUploadBtn').addEventListener('click', () => $('#imgUploadFile').click());
    $('#imgUploadFile').addEventListener('change', async e => {
        const file = e.target.files[0];
        e.target.value = '';   // 清空以允许重复选择同一文件
        if (!file) return;
        try {
            const fd = new FormData();
            fd.append('file', file);
            const resp = await fetch(API_BASE + '/api/nav/icons', { method: 'POST', headers: { 'X-Token': TOKEN }, body: fd });
            const d = await resp.json().catch(() => ({}));
            if (!d.ok) throw new Error(d.error || ('上传失败 ' + resp.status));
            toast('上传成功：' + d.data.url);
            navImageManage(imgPickCb);
        } catch (err) { toast(err.message, 'err'); }
    });
};

/** 图库选图：关闭图片库弹窗并把路径交还调用方（如背景表单回填） */
window.navImagePick = url => {
    const cb = imgPickCb;
    imgPickCb = null;
    closeModal();
    if (cb) cb(url);
};

/** 图片预览：叠层弹窗展示原图，可顺手复制路径 */
window.navImagePreview = url => {
    modal('图片预览', `
      <div class="img-preview-wrap"><img src="${esc(iconSrc(url))}" alt=""></div>
      <div class="form-foot full" style="margin-top:10px">
        <button type="button" class="btn" onclick="copyText('${esc(url)}')">复制路径</button>
        <button type="button" class="btn" onclick="closeModal()">关闭</button>
      </div>`);
};

/** 删除图片：被导航/背景引用时后端会拒绝并给出提示，此处仅做确认 */
window.navImageDelete = async url => {
    if (!(await confirmBox({
        title: '删除图片',
        message: `确定删除该图片？\n${url}\n\n若仍被导航图标或背景引用将无法删除。`,
        confirmText: '删除', danger: true
    }))) return;
    try {
        await api('DELETE', '/api/nav/images/' + encodeURIComponent(url.substring('/nav-icons/'.length)));
        toast('已删除');
        navImageManage(imgPickCb);
    } catch (e) { toast(e.message, 'err'); }
};

/** 导航新增/编辑表单 */
window.navForm = async (id) => {
    const it = id ? (await api('GET', '/api/nav/items')).find(x => x.id === id) : {};
    modal(id ? '编辑导航' : '新增导航', `
      <form id="navFormEl" class="form-grid">
        <div class="field"><label>网站名称 <b>*</b></label><input name="name" required value="${esc(it.name || '')}"></div>
        <div class="field full"><label>图标（可选）</label>
          <div class="icon-picker">
            <div class="icon-preview" id="navIconPreview">🌍</div>
            <div class="icon-picker-body">
              <input name="icon_url" id="navIconUrl" value="${esc(it.icon_url || '')}" placeholder="上传本地图标或填写外部 URL">
              <div class="icon-picker-btns">
                <button type="button" class="btn small" id="navIconUploadBtn">上传图标</button>
                <button type="button" class="btn small danger" id="navIconClearBtn">移除</button>
              </div>
            </div>
            <input type="file" id="navIconFile" accept="image/png,image/jpeg,image/gif,image/webp,image/svg+xml,image/x-icon" hidden>
          </div>
        </div>
        <div class="field full"><label>描述文字</label><input name="description" value="${esc(it.description || '')}"></div>
        <div class="field full"><label>内网访问地址 <span class="muted small">（与外网地址至少填一项）</span></label><input name="lan_url" placeholder="http://192.168.1.10:8080" value="${esc(it.lan_url || '')}"></div>
        <div class="field full"><label>外网访问地址 <span class="muted small">（与内网地址至少填一项）</span></label><input name="wan_url" placeholder="https://nas.example.com" value="${esc(it.wan_url || '')}"></div>
        <div class="field"><label>排序权重</label><input name="weight" type="number" value="${it.weight ?? 0}"></div>
        <div class="field"><label>是否启用</label><select name="enabled"><option value="true" ${it.enabled !== 0 ? 'selected' : ''}>启用</option><option value="false" ${it.enabled === 0 ? 'selected' : ''}>停用</option></select></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    // 图标选择区：预览随输入联动，上传成功后回填相对路径到 icon_url
    const iconInput = $('#navIconUrl');
    const iconPreview = $('#navIconPreview');
    const syncIconPreview = () => {
        const v = iconInput.value.trim();
        iconPreview.innerHTML = v
            ? `<img src="${esc(iconSrc(v))}" onerror="this.replaceWith(document.createTextNode('🌍'))">`
            : '🌍';
    };
    syncIconPreview();
    iconInput.addEventListener('input', syncIconPreview);
    $('#navIconUploadBtn').addEventListener('click', () => $('#navIconFile').click());
    $('#navIconClearBtn').addEventListener('click', () => { iconInput.value = ''; syncIconPreview(); });
    $('#navIconFile').addEventListener('change', async e => {
        const file = e.target.files[0];
        e.target.value = '';   // 清空以允许重复选择同一文件
        if (!file) return;
        try {
            const fd = new FormData();
            fd.append('file', file);
            const resp = await fetch(API_BASE + '/api/nav/icons', { method: 'POST', headers: { 'X-Token': TOKEN }, body: fd });
            const data = await resp.json().catch(() => ({}));
            if (!data.ok) throw new Error(data.error || ('上传失败 ' + resp.status));
            iconInput.value = data.data.url;
            syncIconPreview();
            toast('图标已上传');
        } catch (err) { toast(err.message, 'err'); }
    });
    $('#navFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const f = new FormData(e.target);
        const body = {
            name: f.get('name').trim(), icon_url: f.get('icon_url').trim(), description: f.get('description').trim(),
            lan_url: f.get('lan_url').trim(), wan_url: f.get('wan_url').trim(),
            weight: Number(f.get('weight') || 0), enabled: f.get('enabled') === 'true'
        };
        if (!body.lan_url && !body.wan_url) { toast('内网地址与外网地址至少填写一个', 'err'); return; }
        try {
            if (id) await api('PUT', '/api/nav/items/' + id, body);
            else await api('POST', '/api/nav/items', body);
            closeModal(); toast('保存成功'); renderHome();
        } catch (err) { toast(err.message, 'err'); }
    });
};

window.navDelete = async id => {
    if (!(await confirmBox({ title: '删除导航条目', message: '确定删除该导航条目？', confirmText: '删除', danger: true }))) return;
    try { await api('DELETE', '/api/nav/items/' + id); toast('已删除'); renderHome(); }
    catch (e) { toast(e.message, 'err'); }
};
