/* ============ 首页：网站导航模块 ============ */
'use strict';

let navSmartMode = null;          // null=自动, 'lan'/'wan' 手动

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
