/* ============ 系统设置模块 ============ */
'use strict';

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
