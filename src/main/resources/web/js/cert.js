/* ============ SSL 证书管理模块 ============ */
'use strict';

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
