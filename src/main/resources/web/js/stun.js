/* ============ STUN 端口穿透模块 ============ */
'use strict';

async function renderStun() {
    const tasks = await api('GET', '/api/stun/tasks');
    const rows = tasks.map(t => `
      <tr>
        <td>${esc(t.name)} <div class="small muted">${esc(t.protocol)}</div></td>
        <td>${esc(t.target_ip)}:${t.target_port}${t.peer_addr ? `<div class="small muted">对端: ${esc(t.peer_addr)}</div>` : ''}</td>
        <td class="small">${esc(t.stun_host)}:${t.stun_port}${t.upnp_enabled === 0 ? '<div class="small muted">UPnP 已关闭</div>' : ''}</td>
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
                <th style="width: 110px">任务</th>
                <th style="width: 160px">内网目标</th>
                <th style="width: 180px">STUN服务器</th>
                <th style="width: 110px">状态</th>
                <th style="width: 130px">NAT类型</th>
                <th style="width: 160px">外网映射地址</th>
                <th style="width: 150px">穿透成功时间</th>
                <th style="width: 100px">流量</th>
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
          <div class="small muted" style="margin-top:10px;padding:10px;background:var(--code-bg);border-radius:8px;border:1px solid var(--border)">
            <b>可用占位符（在 URL、请求头、参数值、JSON 请求体中引用）：</b>
            <div style="display:flex;gap:16px;align-items:flex-start;margin-top:6px">
              <table style="flex:1">
                <thead><tr><th style="width:38%">占位符</th><th>说明</th></tr></thead>
                <tbody>
                  <tr><td><code>\${event}</code></td><td>事件类型（stun.punched / stun.mapped_changed）</td></tr>
                  <tr><td><code>\${id}</code></td><td>任务 ID（如 1）</td></tr>
                  <tr><td><code>\${name}</code></td><td>任务名称（如 Local-https）</td></tr>
                  <tr><td><code>\${protocol}</code></td><td>穿透协议（UDP / TCP）</td></tr>
                  <tr><td><code>\${target_ip}</code></td><td>内网目标 IP（如 172.17.1.213）</td></tr>
                  <tr><td><code>\${target_port}</code></td><td>内网目标端口（如 443）</td></tr>
                  <tr><td><code>\${bind_port}</code></td><td>本地绑定端口（0=随机）</td></tr>
                  <tr><td><code>\${stun_host}</code></td><td>STUN 服务器地址（如 stun.nextcloud.com）</td></tr>
                  <tr><td><code>\${stun_port}</code></td><td>STUN 服务器端口（如 3478）</td></tr>
                  <tr><td><code>\${peer_addr}</code></td><td>对端公网地址（如 203.0.113.5:8080）</td></tr>
                </tbody>
              </table>
              <table style="flex:1">
                <thead><tr><th style="width:38%">占位符</th><th>说明</th></tr></thead>
                <tbody>
                  <tr><td><code>\${mapped_addr}</code></td><td>穿透后外网映射地址（如 1.2.3.4:5678）</td></tr>
                  <tr><td><code>\${mapped_ip}</code></td><td>穿透后外网 IP（如 1.2.3.4）</td></tr>
                  <tr><td><code>\${mapped_port}</code></td><td>穿透后外网端口（如 5678）</td></tr>
                  <tr><td><code>\${nat_type}</code></td><td>NAT 类型（如 受限锥形、对称型）</td></tr>
                  <tr><td><code>\${punched_at}</code></td><td>穿透成功时间（如 2026-10-08 13:50:49）</td></tr>
                  <tr><td><code>\${check_result}</code></td><td>自测结果（如 OK(TCP映射保活存活...)）</td></tr>
                  <tr><td><code>\${status}</code></td><td>任务状态（RUNNING / STOPPED / ERROR）</td></tr>
                </tbody>
              </table>
            </div>
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
    row.style.cssText = 'display:flex;gap:8px;align-items:flex-start;margin-bottom:12px;flex-wrap:wrap;padding:12px;border:1px solid var(--border);border-radius:8px;background:var(--code-bg)';
    const headersText = Object.entries(w.headers || {}).map(([k, v]) => k + ': ' + v).join('\n');
    const paramsText = Object.entries(w.params || {}).map(([k, v]) => k + '=' + v).join('\n');
    const bodyText = w.body || '';
    const st = status && status.result
        ? `上次调用：<span>${esc(status.time || '')}</span> `
          + `<b style="color:${String(status.result).startsWith('OK') ? '#34d399' : '#fb7185'}">${esc(status.result)}</b>`
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

/* ---------- 流量折线图（纯 SVG 实现，无第三方依赖） ---------- */

/** 三个维度的主题色与标题（小时蓝 / 天紫 / 月绿，避免同色单调） */
const TF_THEMES = {
    hour: { color: '#0ea5e9', label: '按小时（近 24 小时）' },
    day: { color: '#8b5cf6', label: '按天（近 30 天）' },
    month: { color: '#10b981', label: '按月（近 12 月）' },
};

const tfPad = n => String(n).padStart(2, '0');
const tfDateKey = d => `${d.getFullYear()}-${tfPad(d.getMonth() + 1)}-${tfPad(d.getDate())}`;

/**
 * 倒序明细序列 → 升序完整时间窗（缺失时段补 0）：
 * 折线图横轴连续、空档归零，比仅画有流量的孤立点更直观。
 */
function tfFillWindow(list, span, keyOf) {
    const m = new Map((list || []).map(x => [x.key, x.bytes]));
    const out = [];
    for (let i = span - 1; i >= 0; i--) {
        const k = keyOf(i);
        out.push({ key: k, bytes: m.get(k) || 0 });
    }
    return out;
}

/** 取“好看”的纵轴上限（1/2/2.5/5 × 10^n），网格刻度避免零碎小数 */
function tfNiceMax(v) {
    const exp = Math.pow(10, Math.floor(Math.log10(Math.max(v, 1))));
    for (const f of [1, 2, 2.5, 5, 10]) if (v <= f * exp * 1.0001) return f * exp;
    return 10 * exp;
}

/**
 * 渲染单个流量折线图：Catmull-Rom 平滑曲线 + 主题色渐变面积 + 虚线网格
 * + 悬浮十字引导线与最近点吸附气泡，最新时段末端常亮高亮点。
 */
function tfRenderChart(host, name, points, theme, tickFmt, tipFmt) {
    const W = Math.max(host.clientWidth || 0, 240), H = 170;
    const P = { l: 46, r: 12, t: 14, b: 22 };
    const iw = W - P.l - P.r, ih = H - P.t - P.b;
    const peak = Math.max(...points.map(p => p.bytes));
    const nice = tfNiceMax(peak);
    const c = theme.color;
    const X = i => P.l + (points.length < 2 ? iw / 2 : (iw * i) / (points.length - 1));
    const Y = v => P.t + ih - (Math.max(v, 0) / nice) * ih;
    const pts = points.map((p, i) => ({ x: +X(i).toFixed(1), y: +Y(p.bytes).toFixed(1), key: p.key, bytes: p.bytes }));

    // Catmull-Rom 转三次贝塞尔（控制点钳制在绘图区内，防平滑越界穿过基线）
    const line = (() => {
        if (!pts.length) return '';
        if (pts.length === 1) return `M${pts[0].x},${pts[0].y}`;
        const cl = y => Math.min(P.t + ih, Math.max(P.t, y));
        let d = `M${pts[0].x},${pts[0].y}`;
        for (let i = 0; i < pts.length - 1; i++) {
            const p0 = pts[i - 1] || pts[i], p1 = pts[i], p2 = pts[i + 1], p3 = pts[i + 2] || p2;
            d += `C${(p1.x + (p2.x - p0.x) / 6).toFixed(1)},${cl(p1.y + (p2.y - p0.y) / 6).toFixed(1)} `
                + `${(p2.x - (p3.x - p1.x) / 6).toFixed(1)},${cl(p2.y - (p3.y - p1.y) / 6).toFixed(1)} `
                + `${p2.x},${p2.y}`;
        }
        return d;
    })();
    const area = pts.length > 1 ? `${line}L${pts[pts.length - 1].x},${P.t + ih}L${pts[0].x},${P.t + ih}Z` : '';

    // 网格 + 纵轴刻度（基线实线，其余虚线）
    let grid = '';
    for (let g = 0; g <= 4; g++) {
        const y = (P.t + ih - (ih * g) / 4).toFixed(1);
        grid += `<line x1="${P.l}" y1="${y}" x2="${W - P.r}" y2="${y}" stroke="${g ? 'rgba(148,163,200,.10)' : 'rgba(148,163,200,.24)'}" stroke-dasharray="${g ? '3 4' : ''}"/>`
            + `<text x="${P.l - 7}" y="${+y + 3.5}" text-anchor="end" font-size="9.5" fill="#94a3b8">${fmtTrafficBytes((nice * g) / 4)}</text>`;
    }
    // 横轴刻度（最多 ~6 个，末尾必显）
    let ticks = '';
    const step = Math.max(1, Math.ceil(pts.length / 5));
    pts.forEach((p, i) => {
        if (i % step && i !== pts.length - 1) return;
        ticks += `<text x="${p.x}" y="${H - 6}" text-anchor="middle" font-size="9.5" fill="#94a3b8">${esc(tickFmt(p.key))}</text>`;
    });

    const last = pts[pts.length - 1];
    host.innerHTML = `
      <svg width="${W}" height="${H}" style="display:block;overflow:visible">
        <defs>
          <linearGradient id="tfa-${name}" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="${c}" stop-opacity=".30"/>
            <stop offset=".65" stop-color="${c}" stop-opacity=".07"/>
            <stop offset="1" stop-color="${c}" stop-opacity="0"/>
          </linearGradient>
          <filter id="tfg-${name}" x="-30%" y="-30%" width="160%" height="160%">
            <feDropShadow dx="0" dy="2" stdDeviation="2.5" flood-color="${c}" flood-opacity=".35"/>
          </filter>
        </defs>
        ${grid}${ticks}
        ${area ? `<path d="${area}" fill="url(#tfa-${name})"/>` : ''}
        <path d="${line}" fill="none" stroke="${c}" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" filter="url(#tfg-${name})"/>
        ${peak > 0 && last ? `<circle cx="${last.x}" cy="${last.y}" r="3.4" fill="#fff" stroke="${c}" stroke-width="2.2"/>` : ''}
        ${peak === 0 ? `<text x="${P.l + iw / 2}" y="${P.t + ih / 2}" text-anchor="middle" font-size="11" fill="#b7c1cf">该时段暂无流量</text>` : ''}
        <line id="tf-guide-${name}" y1="${P.t}" y2="${P.t + ih}" stroke="${c}" stroke-width="1.2" stroke-dasharray="4 3" opacity="0"/>
        <circle id="tf-cursor-${name}" r="4.2" fill="${c}" stroke="#fff" stroke-width="1.6" opacity="0"/>
        <rect id="tf-hit-${name}" x="${P.l}" y="${P.t}" width="${iw}" height="${ih}" fill="transparent" style="cursor:crosshair"/>
      </svg>
      <div class="tf-tip" id="tf-tip-${name}" style="display:none"></div>`;

    // 悬浮交互：最近点吸附 + 十字引导线 + 深色气泡提示
    const svg = host.querySelector('svg');
    const guide = host.querySelector(`#tf-guide-${name}`);
    const cursor = host.querySelector(`#tf-cursor-${name}`);
    const tip = host.querySelector(`#tf-tip-${name}`);
    const move = e => {
        const mx = e.clientX - svg.getBoundingClientRect().left;
        let best = 0, dist = Infinity;
        pts.forEach((p, i) => { const dd = Math.abs(p.x - mx); if (dd < dist) { dist = dd; best = i; } });
        const p = pts[best];
        guide.setAttribute('x1', p.x); guide.setAttribute('x2', p.x); guide.setAttribute('opacity', '.55');
        cursor.setAttribute('cx', p.x); cursor.setAttribute('cy', p.y); cursor.setAttribute('opacity', '1');
        tip.style.display = '';
        tip.innerHTML = `${esc(tipFmt(p.key))}<br><b>${fmtTrafficBytes(p.bytes)}</b>`;
        const half = tip.offsetWidth / 2 || 40; // 靠近左右边缘时钳制，防气泡溢出
        tip.style.left = Math.max(half + 2, Math.min(W - half - 2, p.x)) + 'px';
        tip.style.top = Math.max(p.y - 10, 30) + 'px';
    };
    const leave = () => {
        guide.setAttribute('opacity', '0'); cursor.setAttribute('opacity', '0'); tip.style.display = 'none';
    };
    const hit = host.querySelector(`#tf-hit-${name}`);
    hit.addEventListener('mousemove', move);
    hit.addEventListener('mouseleave', leave);
}

/** 单任务流量明细弹窗：总量 + 按小时/天/月三张折线图 */
window.stunTraffic = async id => {
    try {
        const d = await api('GET', `/api/stun/tasks/${id}/traffic`);
        const now = new Date();
        const hours = tfFillWindow(d.hours, 24, i => {
            const t = new Date(now); t.setHours(t.getHours() - i);
            return tfDateKey(t) + ' ' + tfPad(t.getHours());
        });
        const days = tfFillWindow(d.days, 30, i => {
            const t = new Date(now); t.setDate(t.getDate() - i);
            return tfDateKey(t);
        });
        const months = tfFillWindow(d.months, 12, i => {
            const t = new Date(now); t.setMonth(t.getMonth() - i);
            return `${t.getFullYear()}-${tfPad(t.getMonth() + 1)}`;
        });
        const data = { hour: hours, day: days, month: months };
        const peak = k => fmtTrafficBytes(Math.max(...data[k].map(p => p.bytes), 0));
        modal('流量统计', `
          <div class="tip">总流量：<b>${fmtTrafficBytes(d.total)}</b>（连接重建不清零，统计含双向转发字节；按小时/天/月归档，数值为已落库 + 实时增量）</div>
          <div class="tf-grid">
            ${['hour', 'day', 'month'].map(k => `
              <div class="tf-card">
                <div class="tf-head"><span class="tf-dot" style="background:${TF_THEMES[k].color};box-shadow:0 0 0 3px ${TF_THEMES[k].color}22"></span>${TF_THEMES[k].label}
                  <span class="tf-peak">峰值 ${peak(k)}</span></div>
                <div class="tf-body" id="tf-chart-${k}"></div>
              </div>`).join('')}
          </div>`, 'wide');
        tfRenderChart($('#tf-chart-hour'), 'hour', hours, TF_THEMES.hour,
            k => k.slice(11) + '时', k => k + '时');
        tfRenderChart($('#tf-chart-day'), 'day', days, TF_THEMES.day,
            k => k.slice(5), k => k);
        tfRenderChart($('#tf-chart-month'), 'month', months, TF_THEMES.month,
            k => k.slice(5) + '月', k => k.replace('-', '年') + '月');
    } catch (e) { toast(e.message, 'err'); }
};
