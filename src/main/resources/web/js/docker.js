/* ============ Docker 容器管理模块（只读观测） ============ */
'use strict';

let dockerTab = 'containers';        // containers | compose

/* ---------------- 格式化工具 ---------------- */

function fmtBytes(n) {
    if (n == null || isNaN(n) || n <= 0) return '0 B';
    const u = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return n.toFixed(n >= 100 || i === 0 ? 0 : 1) + ' ' + u[i];
}

function fmtPct(n) { return n == null ? '-' : n + '%'; }

function dockerStateBadge(state) {
    const map = { running: 'ok', created: 'info', restarting: 'warn',
        paused: 'warn', exited: 'err', dead: 'err' };
    return badge(state || '-', map[state] || 'gray');
}

/* ---------------- 主渲染 ---------------- */

async function renderDocker() {
    let ov;
    try {
        ov = await api('GET', '/api/docker/overview');
    } catch (e) {
        $('#pageBody').innerHTML = `<div class="tip" style="background:#fef2f2;border-color:#fecaca;color:var(--red)">Docker 状态获取失败：${esc(e.message)}</div>`;
        setRefresh(renderDocker, 10000);
        return;
    }
    if (!ov.connected) {
        $('#pageBody').innerHTML = `
          <div class="panel">
            <h3 style="margin-bottom:10px">🐳 Docker 未连接</h3>
            <p class="muted" style="line-height:2">${esc(ov.error || '')}</p>
            <p class="muted" style="margin-top:10px;line-height:2">
              Docker 部署本服务时挂载宿主机 socket 文件并授予访问权限：<br>
              <code class="docker-mono">docker run -d -p 8090:8090 -v /var/run/docker.sock:/var/run/docker.sock -v nexhome-data:/app/data nex-home</code><br>
              裸机 / 群晖等环境可通过环境变量 <b>DOCKER_HOST</b> 指定
              <code>unix:///var/run/docker.sock</code> 或 <code>tcp://127.0.0.1:2375</code>
            </p>
          </div>`;
        setRefresh(renderDocker, 10000);
        return;
    }
    if (dockerTab === 'compose') await renderDockerCompose(dockerHeader(ov), ov);
    else await renderDockerContainers(dockerHeader(ov), ov);
}

/** 顶部统计卡（Docker 版本 / 宿主 / 容器 / 镜像 / 卷） */
function dockerHeader(ov) {
    const v = ov.version || {}, info = ov.info || {}, df = ov.df || {};
    const card = (val, label) => `
      <div class="docker-stat"><div class="v">${val}</div><div class="l">${label}</div></div>`;
    return `
      <div class="docker-stats">
        ${card(`🐳 v${esc(v.version || '-')}`, `API ${esc(v.apiVersion || '-')} · ${esc(v.os || '')}/${esc(v.arch || '')}`)}
        ${card(`${info.running ?? 0}<span class="muted" style="font-size:13px"> / ${info.containers ?? 0}</span>`, '运行 / 总容器')}
        ${card(`${df.imagesCount ?? (info.images ?? 0)}<span class="muted" style="font-size:13px"> · ${fmtBytes(df.imagesSize)}</span>`, '镜像')}
        ${card(`${df.volumesCount ?? 0}<span class="muted" style="font-size:13px"> · ${fmtBytes(df.volumesSize)}</span>`, '卷')}
        ${card(esc(info.os || '-'), `内核 ${esc(info.kernel || '-')} · 内存 ${fmtBytes(info.memTotal)}`)}
      </div>`;
}

function dockerToolbar(ov) {
    return `
      <div class="toolbar">
        <button class="btn ${dockerTab === 'containers' ? 'primary' : ''}" onclick="dockerSwitchTab('containers')">📦 容器列表</button>
        <button class="btn ${dockerTab === 'compose' ? 'primary' : ''}" onclick="dockerSwitchTab('compose')">🧩 Compose 项目</button>
        <div class="spacer"></div>
        <span class="muted small">连接：${esc(ov.host)}</span>
        <button class="btn" onclick="renderDocker()">刷新</button>
      </div>`;
}

/* ---------------- 容器列表 ---------------- */

let dockerListCache = [];       // 容器列表缓存：点击排序时本地重排，不重拉 stats
let dockerViewCache = null;     // { head, ov }：排序后用缓存直接重绘表格
let dockerSortKey = '';         // 排序列（空 = 服务端默认创建时间倒序）：name/image/state/mem/sizeRw/ip
let dockerSortDir = 1;          // 排序方向：1 升序，-1 降序

async function renderDockerContainers(head, ov) {
    dockerListCache = await api('GET', '/api/docker/containers?stats=1');
    dockerViewCache = { head, ov };
    paintDockerContainers();
    setRefresh(renderDocker, 10000);
}

/** 可排序表头：点击切换该列升/降序 */
function dockerSortTh(key, label, style) {
    const arrow = dockerSortKey === key ? (dockerSortDir === 1 ? ' ▲' : ' ▼') : '';
    return `<th class="docker-sort"${style ? ` style="${style}"` : ''} onclick="dockerSortBy('${key}')">${label}${arrow}</th>`;
}

/** 本地排序：内存/磁盘按数值、IP 按段数值比较，其余按本地化字符串序 */
function dockerSorted() {
    if (!dockerSortKey) return dockerListCache;
    const k = dockerSortKey, d = dockerSortDir;
    return [...dockerListCache].sort((a, b) => {
        let r;
        if (k === 'mem') r = (a.memUsed ?? -1) - (b.memUsed ?? -1);   // 未采样（非运行中）升序恒在前
        else if (k === 'sizeRw') r = (a.sizeRw ?? 0) - (b.sizeRw ?? 0);
        else if (k === 'ip') r = dockerCmpIp(a.ip, b.ip);
        else r = String(a[k] || '').localeCompare(String(b[k] || ''));
        return r * d;
    });
}

/** IPv4 按段数值比较；空地址升序恒在前 */
function dockerCmpIp(x, y) {
    if (!x && !y) return 0;
    if (!x) return -1;
    if (!y) return 1;
    const px = x.split('.'), py = y.split('.');
    for (let i = 0; i < 4; i++) {
        const d = (parseInt(px[i], 10) || 0) - (parseInt(py[i], 10) || 0);
        if (d) return d;
    }
    return 0;
}

window.dockerSortBy = key => {
    if (dockerSortKey === key) dockerSortDir = -dockerSortDir;
    else { dockerSortKey = key; dockerSortDir = 1; }
    paintDockerContainers();
};

/** 用缓存重绘容器表格（排序操作不重新请求 stats） */
function paintDockerContainers() {
    const { head, ov } = dockerViewCache;
    const rows = dockerSorted().map(c => {
        const mem = c.memLimit != null && c.memLimit > 0
            ? `${fmtBytes(c.memUsed)} / ${fmtBytes(c.memLimit)}<div class="docker-mem-bar"><i style="width:${Math.min(100, (c.memUsed * 100 / c.memLimit)).toFixed(1)}%"></i></div>`
            : '<span class="muted">-</span>';
        const ports = (c.ports || []).map(p => p.publicPort != null
            ? badge(`${p.ip || '0.0.0.0'}:${p.publicPort}→${p.privatePort}/${p.type}`, 'info')
            : badge(`${p.privatePort}/${p.type}`, 'gray')).join(' ');
        return `
      <tr>
        <td><div style="font-weight:600">${esc(c.name)}</div>
          <div class="muted small">${esc(c.shortId)} · ${esc(c.status)}</div></td>
        <td style="word-break:break-all">${esc(c.image)}</td>
        <td>${dockerStateBadge(c.state)}</td>
        <td>${mem}</td>
        <td title="可写层">${fmtBytes(c.sizeRw)}</td>
        <td class="docker-mono">${esc(c.ip || '-')}
          ${c.network ? `<div class="muted small">${esc(c.network)}</div>` : ''}</td>
        <td>${ports || '<span class="muted">-</span>'}</td>
        <td>${c.composeProject
            ? badge(esc(c.composeProject) + (c.composeService ? '/' + esc(c.composeService) : ''), 'info')
            : '<span class="muted">-</span>'}</td>
        <td><button class="btn small" onclick="dockerDetail('${esc(c.id)}')">详情</button></td>
      </tr>`;
    }).join('');
    $('#pageBody').innerHTML = `
      ${head}
      ${dockerToolbar(ov)}
      <div class="panel" style="padding:6px 10px"><table>
        <thead><tr>
          ${dockerSortTh('name', '容器名称', 'width:180px')}
          ${dockerSortTh('image', '镜像')}
          ${dockerSortTh('state', '状态', 'width:80px')}
          ${dockerSortTh('mem', '内存', 'width:130px')}
          ${dockerSortTh('sizeRw', '磁盘(可写)', 'width:90px')}
          ${dockerSortTh('ip', '容器 IP', 'width:120px')}
          <th>暴露端口</th><th style="width:150px">Compose</th>
          <th style="width:70px">操作</th>
        </tr></thead>
        <tbody>${rows || '<tr><td colspan="9" class="muted">暂无容器</td></tr>'}</tbody>
      </table></div>`;
}

/* ---------------- 容器详情 ---------------- */

window.dockerDetail = async id => {
    let d;
    try { d = await api('GET', '/api/docker/containers/' + id); }
    catch (e) { toast(e.message, 'err'); return; }

    const kv = (k, v) => `<tr><td style="width:110px" class="muted">${k}</td><td style="word-break:break-all">${v}</td></tr>`;
    const sec = t => `<h4>${t}</h4>`;
    let live = '<span class="muted">未运行</span>';
    if (d.running && d.memLimit != null && d.memLimit > 0) {
        const pct = (d.memUsed * 100 / d.memLimit).toFixed(1);
        live = `
          <table class="docker-kv">
            ${kv('内存', `${fmtBytes(d.memUsed)} / ${fmtBytes(d.memLimit)}（${pct}%）<div class="docker-mem-bar"><i style="width:${Math.min(100, pct)}%"></i></div>`)}
            ${kv('CPU', fmtPct(d.cpuPercent) + (d.cpus ? ` <span class="muted small">(${d.cpus} 核可见)</span>` : ''))}
            ${kv('网络累计', '↓ ' + fmtBytes(d.netRx) + ' · ↑ ' + fmtBytes(d.netTx))}
            ${kv('进程数', d.pids ?? '-')}
          </table>`;
    }
    const netRows = (d.networks || []).map(n =>
        `<tr><td>${esc(n.name)}</td><td class="docker-mono">${esc(n.ip || '-')}</td><td class="docker-mono">${esc(n.gateway || '-')}</td><td class="docker-mono">${esc(n.mac || '-')}</td></tr>`).join('');
    const mapRows = (d.portMappings || []).map(p =>
        `<tr><td>${p.hostIp ? `${esc(p.hostIp)}:${esc(p.hostPort)} → ` : ''}${esc(p.containerPort)}</td></tr>`).join('');
    const mntRows = (d.mounts || []).map(m =>
        `<tr><td>${badge(esc(m.type), 'gray')}</td><td class="docker-mono">${esc(m.source || '-')}</td><td class="docker-mono">${esc(m.destination || '-')}</td><td class="muted small">${esc(m.mode || '')}</td></tr>`).join('');
    const envRows = (d.env || []).map(e => {
        const i = e.indexOf('=');
        return `<tr><td class="muted" style="width:40%">${esc(i > 0 ? e.substring(0, i) : e)}</td><td class="docker-mono" style="word-break:break-all">${esc(i > 0 ? e.substring(i + 1) : '')}</td></tr>`;
    }).join('');

    modal(`容器详情 · ${d.name || ''}`, `
      <table class="docker-kv">
        ${kv('容器 ID', `<span class="docker-mono">${esc(d.id)}</span>`)}
        ${kv('镜像', esc(d.image))}
        ${kv('状态', dockerStateBadge(d.state) + ' · ' + esc(d.running
            ? '启动于 ' + new Date(d.startedAt).toLocaleString()
            : '退出码 ' + (d.exitCode ?? '-') + ' · ' + new Date(d.finishedAt).toLocaleString()))}
        ${kv('重启次数', d.restartCount ?? 0)}
        ${kv('重启策略', esc(d.restartPolicy || '-'))}
        ${kv('网络模式', esc(d.networkMode || '-'))}
        ${d.composeProject ? kv('Compose', `${esc(d.composeProject)} / ${esc(d.composeService || '')}
          <div class="muted small docker-mono">${esc(d.composeWorkdir || '')} ${esc(d.composeFiles || '')}</div>`) : ''}
      </table>
      ${sec('启动命令')}
      <div class="docker-mono">${esc(d.command || '-')}</div>
      <table class="docker-kv" style="margin-top:6px">
        ${kv('Entrypoint', `<span class="docker-mono">${esc(d.entrypoint || '-')}</span>`)}
        ${kv('Cmd', `<span class="docker-mono">${esc(d.cmd || '-')}</span>`)}
        ${kv('工作目录', `<span class="docker-mono">${esc(d.workingDir || '-')}</span>`)}
        ${kv('资源限制', (d.memoryLimit > 0 ? '内存上限 ' + fmtBytes(d.memoryLimit) : '内存无限制')
          + ' · ' + (d.nanoCpus > 0 ? 'CPU ' + (d.nanoCpus / 1e9) + ' 核' : 'CPU 无限制'))}
      </table>
      ${sec('实时资源')}
      ${live}
      ${sec('网络（容器内 IP）')}
      <table>${netRows ? '<thead><tr><th>网络</th><th>IP</th><th>网关</th><th>MAC</th></tr></thead><tbody>' + netRows + '</tbody>'
        : '<tbody><tr><td class="muted">无</td></tr></tbody>'}</table>
      ${sec('端口映射')}
      <table><tbody>${mapRows || '<tr><td class="muted">未暴露端口</td></tr>'}</tbody></table>
      ${sec('挂载')}
      <table>${mntRows ? '<thead><tr><th style="width:60px">类型</th><th>宿主机路径</th><th>容器内路径</th><th style="width:70px">模式</th></tr></thead><tbody>' + mntRows + '</tbody>'
        : '<tbody><tr><td class="muted">无挂载</td></tr></tbody>'}</table>
      ${sec('环境变量')}
      <table><tbody>${envRows || '<tr><td class="muted">无</td></tr>'}</tbody></table>`, 'wide');
};

/* ---------------- Compose 项目 ---------------- */

async function renderDockerCompose(head, ov) {
    const projects = await api('GET', '/api/docker/compose');
    const rows = projects.map(p => `
      <tr>
        <td style="width:150px;font-weight:600">🧩 ${esc(p.name)}</td>
        <td class="docker-mono small" style="word-break:break-all">${esc(p.workdir || '-')}</td>
        <td class="small" style="word-break:break-all">${esc(p.configFiles || '-')}</td>
        <td style="width:80px">${p.running}/${p.containers}</td>
        <td>${(p.services || []).map(s => badge(esc(s), 'gray')).join(' ')}</td>
        <td style="width:60px">${p.images.length}</td>
        <td style="width:170px"><div style="display:flex;gap:6px;flex-wrap:wrap">
          <button class="btn small" onclick="dockerProjectDetail('${esc(p.name)}')">查看容器</button>
          <button class="btn small" onclick="dockerComposeFile('${esc(p.name)}')">查看编排</button>
        </div></td>
      </tr>`).join('');
    $('#pageBody').innerHTML = `
      ${head}
      ${dockerToolbar(ov)}
      <div class="tip">按容器标签 <b>com.docker.compose.project</b> 分组聚合（docker compose up 部署的项目自动携带）。</div>
      <div class="panel" style="padding:6px 10px"><table>
        <thead><tr>
          <th style="width:150px">项目</th><th>工作目录</th><th>配置文件</th>
          <th style="width:80px">运行/总数</th><th>服务</th><th style="width:60px">镜像数</th>
          <th style="width:170px">操作</th>
        </tr></thead>
        <tbody>${rows || '<tr><td colspan="7" class="muted">未发现 Compose 项目（仅统计携带 compose 标签的容器）</td></tr>'}</tbody>
      </table></div>`;
    setRefresh(renderDocker, 15000);
}

/** 项目详情弹窗：宽模态框列出该 Compose 项目下全部容器；下钻容器详情关闭后自动回到本列表 */
window.dockerProjectDetail = async name => {
    let list;
    try { list = await api('GET', '/api/docker/containers?stats=0'); }
    catch (e) { toast(e.message, 'err'); return; }
    const rows = list.filter(c => c.composeProject === name).map(c => `
      <tr>
        <td><div style="font-weight:600">${esc(c.name)}</div>
          <div class="muted small docker-mono">${esc(c.shortId)}</div></td>
        <td>${esc(c.composeService || '-')}</td>
        <td>${dockerStateBadge(c.state)}</td>
        <td class="small">${esc(c.status)}</td>
        <td style="word-break:break-all">${esc(c.image)}</td>
        <td class="docker-mono">${esc(c.ip || '-')}</td>
        <td><button class="btn small" onclick="dockerDetail('${esc(c.id)}')">详情</button></td>
      </tr>`).join('');
    modal(`Compose 项目 · ${name}`, `
      <table>
        <thead><tr><th style="width:170px">容器</th><th style="width:110px">服务</th><th style="width:86px">状态</th>
          <th style="width:140px">运行时长</th><th>镜像</th><th style="width:110px">容器 IP</th><th style="width:70px">操作</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="7" class="muted">该项目当前无容器</td></tr>'}</tbody>
      </table>`, 'wide');
};

/** 编排脚本弹窗：后端按项目标签定位并读取 compose 配置文件内容 */
window.dockerComposeFile = async name => {
    let r;
    try { r = await api('GET', '/api/docker/compose/file?project=' + encodeURIComponent(name)); }
    catch (e) { toast(e.message, 'err'); return; }
    const blocks = (r.files || []).map(f => f.content != null
        ? `<h4>${esc(f.path)}</h4><div class="docker-mono docker-yaml">${esc(f.content)}</div>`
        : `<h4>${esc(f.path || f.name)}</h4>
           <div class="tip" style="background:#fef2f2;border-color:#fecaca;color:var(--red)">${esc(f.error || '读取失败')}</div>`).join('');
    modal(`编排脚本 · ${name}`, `
      <div class="muted small" style="margin-bottom:10px">工作目录：<span class="docker-mono">${esc(r.workdir || '-')}</span></div>
      ${r.error ? `<div class="tip">${esc(r.error)}</div>` : ''}
      ${blocks || '<div class="tip">未发现该项目的编排文件</div>'}
      ${r.hint ? `<div class="tip" style="background:#fef2f2;border-color:#fecaca;color:var(--red)">${esc(r.hint)}</div>` : ''}`, 'wide');
};

window.dockerSwitchTab = t => {
    dockerTab = t;
    renderDocker().catch(e => toast(e.message, 'err'));
};
