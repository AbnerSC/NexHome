/* ============ 首页：网站导航模块 ============ */
'use strict';

let navSmartMode = null;          // null=自动, 'lan'/'wan' 手动
let netSourceLan = null;          // 服务端按连接源 IP 识别的访问来源（null=未知，回退 hostname 判断）

/** 图标展示地址：本地图标存的是相对 URL（/nav-icons/xxx），安全入口模式下需补上 API 前缀才能命中路由 */
function iconSrc(u) {
    return u && u.startsWith('/') ? API_BASE + u : u;
}

function isLanVisit() {
    if (netSourceLan !== null) return netSourceLan;
    const h = location.hostname;
    return /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h);
}

function currentNetMode() {
    if (navSmartMode) return navSmartMode;
    return isLanVisit() ? 'lan' : 'wan';
}

async function renderHome() {
    const [items, src] = await Promise.all([
        api('GET', '/api/nav/items'),
        // 服务端按连接源 IP 识别访问来源（内外网两套域名也不影响判断），失败时回退 hostname 判断
        api('GET', '/api/net/source').catch(() => null)
    ]);
    if (src && typeof src.lan === 'boolean') netSourceLan = src.lan;
    const mode = currentNetMode();
    const autoLan = isLanVisit();
    const modeLabel = navSmartMode ? (navSmartMode === 'lan' ? '内网（手动）' : '外网（手动）')
        : (autoLan ? '内网（自动识别）' : '外网（自动识别）');
    let html = `
      <div class="toolbar">
        <span class="muted small" title="${src && src.ip ? '识别依据来源 IP：' + esc(src.ip) : ''}">当前访问来源：${esc(modeLabel)}</span>
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
            html += `
            <div class="nav-card" onclick="window.open('${esc(url)}','_blank')">
              <div class="card-actions">
                <button class="btn small" onclick="event.stopPropagation();navForm(${it.id})">编辑</button>
                <button class="btn small danger" onclick="event.stopPropagation();navDelete(${it.id})">删除</button>
              </div>
              <div class="icon">${icon}</div>
              <h3>${esc(it.name)}</h3>
              <p>${esc(it.description || '')}</p>
              <div class="addr-row">${badge(kind + (fallback ? '·回退' : ''), fallback ? 'warn' : 'info')}
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
