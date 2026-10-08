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
        <td>${fmtTrafficBytes(t.total_bytes)}</td>
        <td>${t.check_result
            ? `<div>${badge(t.check_result, t.check_result.startsWith('OK') ? 'ok'
                : t.check_result.startsWith('FAIL') ? 'err' : 'warn')}</div>
               ${t.check_time ? `<div class="small muted">${esc(t.check_time)}</div>` : ''}`
            : '<span class="muted small">未自测</span>'}</td>
        <td>
          ${t.status === 'RUNNING'
            ? `<button class="btn small danger" onclick="stunCmd(${t.id},'stop')">停止</button>`
            : `<button class="btn small success" onclick="stunCmd(${t.id},'start')">启动</button>`}
          <button class="btn small" onclick="stunCmd(${t.id},'test')">探测</button>
          ${t.status === 'RUNNING' ? `<button class="btn small" onclick="stunCmd(${t.id},'verify')">自测</button>` : ''}
          <button class="btn small" onclick="stunTraffic(${t.id})">流量</button>
          <button class="btn small" onclick="stunForm(${t.id})">编辑</button>
          <button class="btn small danger" onclick="stunDelete(${t.id})">删除</button>
        </td>
      </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="tip">
        <b>穿透通道支持承载 UDP / TCP / HTTP 数据传输；任务运行中 ≠ 外网可访问。</b>
        受限锥形 NAT 仅允许已打洞的对端连入，<b>对称型（Symmetric）NAT 纯 STUN 无法穿透</b>，请改用路由器端口转发，并确认防火墙已放行监听端口。
        穿透启动时自动尝试 <b>UPnP 端口映射</b>（WAN 口为公网时即权威入站通道）；CGNAT 或未启用 UPnP 时，TCP 任务按优先级自动选择出站通道：STUN 长连接精确映射 →
        <b>双链路模式</b>（参考 natmap：公共端点出站保活 + 同端口 STUN 短连接探测精确映射，单事务型 STUN 服务器亦可复用）→ 端口保留兜底。
        穿透成功后系统自动自测一次（映射保活 + 公网入站可达），可在操作列点「自测」手动复测。
      </div>
      <div class="toolbar"><div class="spacer"></div>
        <button class="btn" onclick="renderStunServers()">🛠 管理STUN服务器</button>
        <button class="btn primary" onclick="stunForm()">＋ 新增穿透任务</button></div>
      <div class="panel"><table>
        <thead>
            <tr>
                <th style="width: 8%">任务</th>
                <th style="width: 10%">内网目标</th>
                <th style="width: 12%">STUN服务器</th>
                <th style="width: 7%">状态</th>
                <th style="width: 7%">NAT类型</th>
                <th style="width: 13%">外网映射地址</th>
                <th style="width: 13%">穿透成功时间</th>
                <th style="width: 9%">流量</th>
                <th>可用性自测</th>
                <th style="width: 320px">操作</th>
            </tr>
        </thead>
        <tbody>${rows || '<tr><td colspan="10" class="muted">暂无任务</td></tr>'}</tbody>
      </table></div>`;
    setRefresh(renderStun, 5000);
}

window.stunCmd = async (id, cmd) => {
    try {
        if (cmd === 'test') {
            toast('正在探测 NAT 类型...');
            const r = await api('POST', `/api/stun/tasks/${id}/test`);
            modal('STUN 探测结果', `<p>NAT 类型：<b>${esc(r.natType)}</b></p>
              <p style="margin-top:8px">外网映射地址(UDP)：<b>${esc(r.mapped || '无')}</b>
                ${r.live ? '<span class="muted small">（任务运行中，为任务实际映射地址）</span>'
                  : '<span class="muted small">（临时端口探测结果，对称型 NAT 下与任务端口映射不同）</span>'}</p>
              ${r.tcpMapped ? `<p style="margin-top:8px">TCP 映射地址：<b>${esc(r.tcpMapped)}</b>
                <span class="muted small">${r.live ? '（任务运行中，为任务实际映射地址）' : '（TCP 入站以此为准）'}</span></p>` : ''}
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
    if (!(await confirmBox({ title: '删除穿透任务', message: '确定删除该穿透任务？', confirmText: '删除', danger: true }))) return;
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
        <div class="field"><label>对端公网地址（TCP 打洞选填）</label><input name="peer_addr" value="${esc(t.peer_addr || '')}" placeholder="如 203.0.113.5:8080，留空则仅接受入站连接"></div>
        <div class="field"><label>UPnP 端口映射</label>
          <select name="upnp_enabled">
            <option value="true" ${t.upnp_enabled !== 0 ? 'selected' : ''}>启用（需路由器支持UPnP）</option>
            <option value="false" ${t.upnp_enabled === 0 ? 'selected' : ''}>停用（路由器不支持UPnP）</option>
          </select></div>
        <div class="field"><label>STUN 服务器 <b>*</b></label>
          <select name="stun_server" onchange="stunServerChanged(this.value)">
            ${serverOpts}
            <option value="custom" ${custom ? 'selected' : ''}>自定义...</option>
          </select></div>
        <div class="field ${custom ? '' : 'hidden'}" id="fStunHost"><label>STUN 地址 <b>*</b></label><input name="stun_host" value="${esc(t.stun_host || '')}" placeholder="stun.miwifi.com"></div>
        <div class="field ${custom ? '' : 'hidden'}" id="fStunPort"><label>STUN 端口</label><input name="stun_port" type="number" min="1" max="65535" value="${t.stun_port ?? 3478}"></div>
        <div class="field full">
          <label>Webhook 同步（可选，穿透成功 / 映射地址变化时将结果推送到以下地址，支持多个）</label>
          <div id="stunWhList"></div>
          <div style="margin-top:6px"><button type="button" class="btn small" onclick="stunWhAdd()">＋ 添加 Webhook</button></div>
          <div class="small muted" style="margin-top:10px;line-height:1.8;padding:10px;background:#f8fafc;border-radius:8px;border:1px solid #e2e8f0">
            <b>可用占位符（在 URL、请求头、参数值、JSON 请求体中引用）：</b><br>
            <code>\${event}</code> —— 事件类型（如 stun.punched / stun.mapped_changed）<br>
            <code>\${id}</code> —— 任务 ID（如 1）<br>
            <code>\${name}</code> —— 任务名称（如 Local-https）<br>
            <code>\${protocol}</code> —— 穿透协议（UDP 或 TCP）<br>
            <code>\${target_ip}</code> —— 内网目标 IP（如 172.17.1.213）<br>
            <code>\${target_port}</code> —— 内网目标端口（如 443）<br>
            <code>\${bind_port}</code> —— 本地绑定端口（0=随机）<br>
            <code>\${stun_host}</code> —— STUN 服务器地址（如 stun.nextcloud.com）<br>
            <code>\${stun_port}</code> —— STUN 服务器端口（如 3478）<br>
            <code>\${peer_addr}</code> —— 对端公网地址（如 203.0.113.5:8080）<br>
            <code>\${mapped_addr}</code> —— 穿透后外网映射地址（如 1.2.3.4:5678）<br>
            <code>\${mapped_ip}</code> —— 穿透后外网 IP（如 1.2.3.4）<br>
            <code>\${mapped_port}</code> —— 穿透后外网端口（如 5678）<br>
            <code>\${nat_type}</code> —— NAT 类型（如 受限锥形、对称型）<br>
            <code>\${punched_at}</code> —— 穿透成功时间（如 2026-10-08 13:50:49）<br>
            <code>\${check_result}</code> —— 自测结果（如 OK(TCP映射保活存活...)）<br>
            <code>\${status}</code> —— 任务状态（RUNNING / STOPPED / ERROR）
          </div>
          <div class="small muted" style="margin-top:6px;line-height:1.7">
            每条可选 <b>GET / POST</b>：<b>POST</b> 支持填写自定义 JSON 请求体（占位符会被替换为实际值）；<b>GET</b> 将结果字段+自定义参数拼为查询串。
            <b>请求头</b>每行一个 <code>key: value</code>（如 <code>Authorization: Bearer xxx</code>）。触发时机：首次穿透成功、外网映射地址变化。
            推送失败仅记日志，不影响穿透。</div></div>
        <div class="form-foot full">
          <button type="button" class="btn" onclick="closeModal()">取消</button>
          <button class="btn primary">保存</button>
        </div>
      </form>`, 'xwide');
    window.stunServerChanged = v => {
        $('#fStunHost').classList.toggle('hidden', v !== 'custom');
        $('#fStunPort').classList.toggle('hidden', v !== 'custom');
    };
    let whs = [], whst = {};
    try { whs = t.webhook_config ? JSON.parse(t.webhook_config) : []; } catch (e) { whs = []; }
    try { whst = t.webhook_status ? JSON.parse(t.webhook_status) : {}; } catch (e) { whst = {}; }
    whs.forEach(w => window.stunWhAdd(w, whst[w.url]));
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
        body.webhooks = [...document.querySelectorAll('#stunWhList .wh-row')].map(r => {
            const method = r.querySelector('.wh-method').value;
            const headers = {};
            r.querySelector('.wh-headers').value.split(/\r?\n/).forEach(line => {
                const i = line.indexOf(':');
                if (i > 0) headers[line.slice(0, i).trim()] = line.slice(i + 1).trim();
            });
            const w = { method, url: r.querySelector('.wh-url').value.trim(), headers };
            if (method === 'POST') {
                // POST 模式：取 JSON 请求体
                const bodyText = r.querySelector('.wh-body').value.trim();
                if (bodyText) w.body = bodyText;
            } else {
                // GET 模式：保留 key=value 参数
                const params = {};
                r.querySelector('.wh-params').value.split(/\r?\n/).forEach(line => {
                    const i = line.indexOf('=');
                    if (i > 0) params[line.slice(0, i).trim()] = line.slice(i + 1).trim();
                });
                if (Object.keys(params).length) w.params = params;
            }
            return w;
        }).filter(w => w.url);
        try {
            if (id) await api('PUT', '/api/stun/tasks/' + id, body);
            else await api('POST', '/api/stun/tasks', body);
            closeModal(); toast('保存成功'); renderStun();
        } catch (err) { toast(err.message, 'err'); }
    });
};

/** 新增一条 Webhook 编辑行（w 为空时为空白新行；编辑时回填已存配置；status 为上次调用结果 {time,result}） */
window.stunWhAdd = (w, status) => {
    w = w || { url: '', method: 'POST', headers: {}, params: {}, body: '' };
    const list = document.getElementById('stunWhList');
    if (!list) return;
    const row = document.createElement('div');
    row.className = 'wh-row';
    row.style.cssText = 'display:flex;gap:8px;align-items:flex-start;margin-bottom:12px;flex-wrap:wrap;padding:12px;border:1px solid #e2e8f0;border-radius:8px;background:#fafbfc';
    const headersText = Object.entries(w.headers || {}).map(([k, v]) => k + ': ' + v).join('\n');
    const paramsText = Object.entries(w.params || {}).map(([k, v]) => k + '=' + v).join('\n');
    const bodyText = w.body || '';
    const st = status && status.result
        ? `上次调用：<span>${esc(status.time || '')}</span> `
          + `<b style="color:${String(status.result).startsWith('OK') ? '#16a34a' : '#dc2626'}">${esc(status.result)}</b>`
        : '尚未调用';
    const isPost = w.method !== 'GET';
    row.innerHTML = `
      <div style="display:flex;gap:8px;align-items:center;flex-basis:100%">
        <select class="wh-method" style="width:90px;flex-shrink:0"><option ${w.method !== 'GET' ? 'selected' : ''}>POST</option><option ${w.method === 'GET' ? 'selected' : ''}>GET</option></select>
        <input class="wh-url" style="flex:1;min-width:220px" placeholder="https://example.com/hooks/stun" value="${esc(w.url || '')}">
        <button type="button" class="btn small danger" title="删除此条">✕</button>
      </div>
      <div style="flex:1;display:flex;flex-direction:column;gap:6px;min-width:0;width:100%">
        <div>
          <label class="small muted" style="display:block;margin-bottom:3px">请求头（每行 key: value，如 Authorization: Bearer xxx）</label>
          <textarea class="wh-headers" style="width:100%;min-height:36px;font-size:12px" placeholder="Authorization: Bearer xxx\nX-Custom: value">${esc(headersText)}</textarea>
        </div>
        <div class="wh-body-wrap" style="${isPost ? '' : 'display:none'}">
          <label class="small muted" style="display:block;margin-bottom:3px">JSON 请求体（支持 <code>\${字段}</code> 占位符，留空则自动发送全部结果字段）</label>
          <textarea class="wh-body" style="width:100%;min-height:100px;font-family:Consolas,monospace;font-size:12px" placeholder='{"event":"\${event}","task":"\${name}","ip":"\${mapped_ip}","port":"\${mapped_port}","extra":{"key":"value"}}'>${esc(bodyText)}</textarea>
        </div>
        <div class="wh-params-wrap" style="${!isPost ? '' : 'display:none'}">
          <label class="small muted" style="display:block;margin-bottom:3px">自定义参数（GET 查询串，每行 key=value，支持 <code>\${字段}</code> 占位符）</label>
          <textarea class="wh-params" style="width:100%;min-height:36px;font-size:12px" placeholder="token=abc123\naddr=\${mapped_addr}">${esc(paramsText)}</textarea>
        </div>
      </div>
      <div class="wh-status small muted" style="flex-basis:100%">${st}</div>`;
    row.querySelector('button').addEventListener('click', () => row.remove());
    // 切换 GET/POST 时显示/隐藏对应字段
    row.querySelector('.wh-method').addEventListener('change', e => {
        const post = e.target.value !== 'GET';
        row.querySelector('.wh-body-wrap').style.display = post ? '' : 'none';
        row.querySelector('.wh-params-wrap').style.display = post ? 'none' : '';
    });
    list.appendChild(row);
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
          <button class="btn small" onclick="stunServerMove(${s.id},'up')">↑</button>
          <button class="btn small" onclick="stunServerMove(${s.id},'down')">↓</button>
          <button class="btn small" onclick="stunServerForm(${s.id})">编辑</button>
          <button class="btn small danger" onclick="stunServerDelete(${s.id})">删除</button>
        </td>
      </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="tip">维护常用 STUN 服务器列表，穿透任务新增/编辑时从下拉中选择（按列表顺序展示）。
        可用 <b>↑/↓</b> 调整顺序：排在前面的服务器优先作为穿透探测与保活的兜底候选。
        <b>支持 TCP</b> 表示服务器支持 STUN-over-TCP：TCP 穿透任务经支持 TCP 的服务器出站，才能在运营商 CGNAT 上建立真实 TCP 映射；
        双链路模式下服务器只需响应一次绑定请求即可提供精确映射（不要求保持长连接）。</div>
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
    if (!(await confirmBox({ title: '删除 STUN 服务器', message: '确定删除该 STUN 服务器？已创建的穿透任务不受影响。', confirmText: '删除', danger: true }))) return;
    try { await api('DELETE', '/api/stun/servers/' + id); toast('已删除'); renderStunServers(); }
    catch (e) { toast(e.message, 'err'); }
};

/** 上移/下移调整服务器顺序 */
window.stunServerMove = async (id, dir) => {
    try {
        await api('POST', `/api/stun/servers/${id}/move`, { dir });
        renderStunServers();
    } catch (e) { toast(e.message, 'err'); }
};

/** 字节数转人类可读（B/KB/MB/GB/TB/PB）；独立命名避免与 docker.js 的全局 fmtBytes 相互覆盖 */
function fmtTrafficBytes(b) {
    b = Number(b || 0);
    if (b < 1024) return b + ' B';
    const u = ['KB', 'MB', 'GB', 'TB', 'PB'];
    let i = -1;
    do { b /= 1024; i++; } while (b >= 1024 && i < u.length - 1);
    return b.toFixed(b >= 100 ? 0 : b >= 10 ? 1 : 2) + ' ' + u[i];
}

/** 单任务流量明细弹窗：总量 + 按小时/天/月归档 */
window.stunTraffic = async id => {
    try {
        const d = await api('GET', `/api/stun/tasks/${id}/traffic`);
        const tbl = (list, label) => `
          <table><thead><tr><th>${label}</th><th style="text-align:right">流量</th></tr></thead>
          <tbody>${(list && list.length)
            ? list.map(x => `<tr><td class="small">${esc(x.key)}</td><td style="text-align:right">${fmtTrafficBytes(x.bytes)}</td></tr>`).join('')
            : '<tr><td colspan="2" class="muted">暂无数据</td></tr>'}</tbody></table>`;
        modal('流量统计', `
          <div class="tip">总流量：<b>${fmtTrafficBytes(d.total)}</b>（连接重建不清零，统计含双向转发字节；按小时/天/月归档，数值为已落库 + 实时增量）</div>
          <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:16px;margin-top:12px">
            <div><h4>按小时（近 48）</h4>${tbl(d.hours, '时段')}</div>
            <div><h4>按天（近 60）</h4>${tbl(d.days, '日期')}</div>
            <div><h4>按月（近 24）</h4>${tbl(d.months, '月份')}</div>
          </div>`, 'wide');
    } catch (e) { toast(e.message, 'err'); }
};
