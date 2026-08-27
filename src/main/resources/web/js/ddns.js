/* ============ DDNS 域名同步模块 ============ */
'use strict';

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
