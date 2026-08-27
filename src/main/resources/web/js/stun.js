/* ============ STUN 端口穿透模块 ============ */
'use strict';

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
          <div style="display:flex;gap:8px;align-items:center">
            <select name="tcp_support" style="flex:1">
              <option value="true" ${s.tcp_support === 1 ? 'selected' : ''}>支持</option>
              <option value="false" ${s.tcp_support !== 1 ? 'selected' : ''}>不支持 / 未知</option>
            </select>
            <button type="button" class="btn small" id="btnProbeTcp">探测</button>
          </div>
          <div id="probeTcpTip" class="muted small" style="margin-top:6px">探测：经 TCP 向上述地址发送 STUN 绑定请求，自动判定并回填支持状态。</div></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`);
    $('#btnProbeTcp').addEventListener('click', async () => {
        const f = $('#stunServerFormEl');
        const host = f.host.value.trim();
        const port = Number(f.port.value || 3478);
        if (!host) { toast('请先填写服务器地址', 'err'); return; }
        const btn = $('#btnProbeTcp'), tip = $('#probeTcpTip');
        btn.disabled = true; btn.textContent = '探测中…';
        tip.textContent = '正在经 TCP 发送 STUN 绑定请求，请稍候（最长约 3 秒）…';
        try {
            const r = await api('POST', '/api/stun/servers/probe-tcp', { host, port });
            f.tcp_support.value = r.supported ? 'true' : 'false';
            tip.innerHTML = r.supported
                ? `探测结果：<b>支持</b> STUN-over-TCP，外网映射地址 ${esc(r.mapped)}`
                : '探测结果：<b>不支持</b> STUN-over-TCP（连接失败或无有效响应）';
        } catch (e) {
            tip.textContent = '探测失败: ' + e.message;
        } finally {
            btn.disabled = false; btn.textContent = '探测';
        }
    });
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
