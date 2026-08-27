/* ============ WOL 网络唤醒模块 ============ */
'use strict';

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
