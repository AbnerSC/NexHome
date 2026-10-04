/* ============ 系统设置模块 ============ */
'use strict';

/** AccessKey ID 脱敏展示（前 4 后 4，中间省略） */
function maskAk(id) {
    const s = String(id || '');
    return s.length <= 8 ? s : s.slice(0, 4) + '****' + s.slice(-4);
}

async function renderSettings() {
    let info = {}, configs = [], types = {};
    try { info = await api('GET', '/api/system/info'); } catch (e) { /* ignore */ }
    try { configs = await api('GET', '/api/provider/configs'); } catch (e) { /* ignore */ }
    try { types = await api('GET', '/api/provider/types'); } catch (e) { /* ignore */ }
    const typeName = code => types[code] || code;
    const cfgRows = configs.map(c => `
      <tr>
        <td>${esc(c.name)}</td>
        <td>${esc(typeName(c.provider_type))}</td>
        <td class="small">${esc(maskAk(c.access_key_id))}</td>
        <td class="small">${esc(c.esa_site_id || '-')}</td>
        <td>
          <button class="btn small" onclick="provForm(${c.id})">编辑</button>
          <button class="btn small danger" onclick="provDelete(${c.id})">删除</button>
        </td>
      </tr>`).join('');
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
        <h3 style="font-size:14px;margin-bottom:12px">服务商凭证配置</h3>
        <div class="tip" style="margin-bottom:12px">集中维护云服务商 AccessKey：<b>DDNS 同步</b>与
          <b>SSL 证书 DNS01 自动验证</b>在表单中直接下拉选择引用，无需每次重复输入；
          修改配置后所有引用它的任务自动生效。</div>
        <div class="toolbar" style="margin-bottom:10px"><div class="spacer"></div>
          <button class="btn primary" onclick="provForm()">＋ 新增配置</button></div>
        <table>
          <thead><tr><th>名称</th><th>服务商</th><th>AccessKey ID</th><th>ESA SiteId</th><th>操作</th></tr></thead>
          <tbody>${cfgRows || '<tr><td colspan="5" class="muted">暂无配置，可在 DDNS / 证书表单中手动填写凭证</td></tr>'}</tbody>
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

/** 服务商凭证配置新增/编辑弹窗（服务商清单动态拉取， ESA SiteId 仅阿里云显示） */
window.provForm = async (id) => {
    let c = {}, types = {};
    if (id) c = (await api('GET', '/api/provider/configs')).find(x => x.id === id);
    try { types = await api('GET', '/api/provider/types'); } catch (e) { /* ignore */ }
    const typeOpts = Object.entries(types).map(([code, name]) =>
        `<option value="${esc(code)}" ${(c.provider_type || 'ALIYUN') === code ? 'selected' : ''}>${esc(name)}</option>`).join('');
    modal(id ? '编辑服务商凭证配置' : '新增服务商凭证配置', `
      <form id="provFormEl" class="form-grid">
        <div class="field"><label>配置名称 <b>*</b></label><input name="name" required placeholder="如：阿里云主账号" value="${esc(c.name || '')}"></div>
        <div class="field"><label>服务商 <b>*</b></label>
          <select name="provider_type" onchange="provTypeChanged(this.value)">${typeOpts}</select></div>
        <div class="field hidden" id="fEsaSite"><label>ESA 站点 SiteId（可选）</label><input name="esa_site_id" placeholder="数字站点ID，仅 ESA 使用" value="${esc(c.esa_site_id || '')}"></div>
        <div class="field full"><label>AccessKey ID <b>*</b></label><input name="access_key_id" required value="${esc(c.access_key_id || '')}"></div>
        <div class="field full"><label>AccessKey Secret <b>*</b></label><input name="access_key_secret" required type="password" value="${esc(c.access_key_secret || '')}"></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    window.provTypeChanged = v => {
        // ESA SiteId 为阿里云 ESA 专属，其他服务商隐藏
        $('#fEsaSite').classList.toggle('hidden', v !== 'ALIYUN');
    };
    window.provTypeChanged(c.provider_type || 'ALIYUN');
    $('#provFormEl').addEventListener('submit', async e => {
        e.preventDefault();
        const body = Object.fromEntries(new FormData(e.target).entries());
        try {
            if (id) await api('PUT', '/api/provider/configs/' + id, body);
            else await api('POST', '/api/provider/configs', body);
            closeModal(); toast('保存成功，引用它的任务将自动生效'); renderSettings();
        } catch (err) { toast(err.message, 'err'); }
    });
};

/** 删除服务商凭证配置：引用的 DDNS 任务回填当前凭证，证书任务回到手动 TXT */
window.provDelete = async id => {
    if (!(await confirmBox({
        title: '删除服务商凭证配置',
        message: '确定删除该凭证配置？\n\n引用它的 DDNS 任务将回填当前凭证继续可用，证书任务回到手动添加 TXT 模式。',
        confirmText: '删除', danger: true
    }))) return;
    try {
        const msg = await api('DELETE', '/api/provider/configs/' + id);
        toast(msg); renderSettings();
    } catch (e) { toast(e.message, 'err'); }
};
