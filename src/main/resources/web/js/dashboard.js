/* ============ 统一状态仪表盘（总览） ============ */
'use strict';

/** 秒数转可读运行时长 */
function fmtUptime(sec) {
    sec = sec || 0;
    const d = Math.floor(sec / 86400), h = Math.floor(sec % 86400 / 3600), m = Math.floor(sec % 3600 / 60);
    return (d > 0 ? d + ' 天 ' : '') + h + ' 小时 ' + m + ' 分钟';
}

/** 数字千分位 */
function fmtCount(n) { return (n || 0).toLocaleString(); }

/** 字节/秒 -> Mbps 可读 */
function fmtMbps(bytesPerSec) { return (bytesPerSec * 8 / 1e6).toFixed(1) + ' Mbps'; }

/** 测速结果缓存：页面 15 秒自动刷新时保留结果，测速中不中断展示 */
const speedState = { running: false, ping: '', loss: '', down: '', up: '' };

async function renderDashboard() {
    let d;
    try { d = await api('GET', '/api/dashboard'); }
    catch (e) { $('#pageBody').innerHTML = `<p style="color:var(--red)">${esc(e.message)}</p>`; return; }
    const sys = d.system || {}, ddns = d.ddns || {}, stun = d.stun || {}, cert = d.cert || {};
    const mon = d.monitor || {}, docker = d.docker || {}, proxy = d.proxy || {}, visits = d.visits || {};

    // 顶部关键指标卡：健康项绿色、告警项红色/橙色
    const card = (icon, val, label, tone, onclick) => `
      <div class="dash-stat${tone ? ' ' + tone : ''}"${onclick ? ` onclick="setPage('${onclick}')" style="cursor:pointer"` : ''}>
        <div class="dash-stat-icon">${icon}</div>
        <div class="dash-stat-body"><div class="v">${val}</div><div class="l">${label}</div></div>
      </div>`;

    const monDown = mon.down || 0, ddnsFail = ddns.failed || 0, stunFail = stun.failed || 0, certSoon = cert.expiringSoon || 0;
    let stats = '<div class="dash-stats">';
    stats += card('🌐', `${ddns.enabled ?? 0}<span class="muted" style="font-size:13px"> / ${ddns.total ?? 0}</span>`,
        'DDNS 启用/总数' + (ddnsFail ? ` · ${ddnsFail} 失败` : ''), ddnsFail ? 'warn' : 'ok', 'ddns');
    stats += card('🔓', `${stun.running ?? 0}<span class="muted" style="font-size:13px"> / ${stun.total ?? 0}</span>`,
        'STUN 运行/总数' + (stunFail ? ` · ${stunFail} 异常` : ''), stunFail ? 'warn' : 'ok', 'stun');
    stats += card('📡', monDown ? `${mon.up ?? 0}<span class="muted" style="font-size:13px"> UP · ${monDown} DOWN</span>`
        : `${mon.up ?? 0}<span class="muted" style="font-size:13px"> / ${mon.total ?? 0}</span>`,
        '可用性监控', monDown ? 'err' : (mon.total ? 'ok' : ''), 'monitor');
    stats += card('🔐', `${cert.issued ?? 0}<span class="muted" style="font-size:13px"> / ${cert.total ?? 0}</span>`,
        '证书 已签发/总数' + (certSoon ? ` · ${certSoon} 临期` : ''), certSoon ? 'warn' : 'ok', 'cert');
    const proxyTotal = proxy.total ?? 0, proxyOn = proxy.enabled ?? 0;
    stats += card('🔀', `${proxyOn}<span class="muted" style="font-size:13px"> / ${proxyTotal}</span>`,
        proxyTotal > 0 && !proxy.listening ? '代理站点 · 未监听' : '代理站点 启用/总数',
        proxyTotal > 0 && !proxy.listening ? 'warn' : (proxyTotal > 0 ? 'ok' : ''), 'proxy');
    stats += card('📈', fmtCount(visits.total), '代理访问 · 近 7 天', '', 'proxy');
    stats += card('🐳', docker.connected
        ? `${docker.running ?? 0}<span class="muted" style="font-size:13px"> / ${docker.containers ?? 0}</span>`
        : '<span class="muted" style="font-size:15px">未连接</span>',
        'Docker 运行/总容器', docker.connected ? ((docker.stopped ?? 0) > 0 ? 'warn' : 'ok') : '', 'docker');
    stats += card('⏱️', fmtUptime(sys.uptimeSec), `端口 ${sys.port || '-'}${sys.httpsActive ? ' · HTTPS' : ''}`, '', '');
    stats += '</div>';

    // 证书临期明细
    const certList = (cert.expiringList || []).map(c => `
      <tr><td>${esc(c.name)}</td><td class="small muted">${esc((c.not_after || '').slice(0, 10))}</td>
        <td>${badge(c.daysLeft <= 7 ? `仅剩 ${c.daysLeft} 天` : `${c.daysLeft} 天后到期`, c.daysLeft <= 7 ? 'err' : 'warn')}</td></tr>`).join('');

    // 代理访问统计：每日总访问柱状 + 网站/地区 TOP10（横向条形）
    const visitDays = visits.days || [];
    const maxDay = Math.max(1, ...visitDays.map(x => x.hits || 0));
    const dayBars = visitDays.map(x => `
      <div class="dash-visit-item" title="${esc(x.day)}：${fmtCount(x.hits)} 次">
        <span class="dash-visit-num">${fmtCount(x.hits)}</span>
        <div class="dash-visit-barwrap"><i class="dash-visit-bar" style="height:${Math.max(2, Math.round((x.hits || 0) / maxDay * 100))}%"></i></div>
        <span class="dash-visit-day">${esc((x.day || '').slice(5))}</span>
      </div>`).join('');

    const topList = items => (items || []).length ? items.map((it, i) => {
        const top = items[0].hits || 1;
        return `<div class="dash-top-item">
          <span class="dash-top-rank">${i + 1}</span>
          <span class="dash-top-name" title="${esc(it.name)}">${esc(it.name)}</span>
          <span class="dash-top-bar"><i style="width:${(it.hits / top * 100).toFixed(1)}%"></i></span>
          <span class="dash-top-hits">${fmtCount(it.hits)}</span>
        </div>`;
    }).join('') : '<p class="muted small">暂无访问数据</p>';

    $('#pageBody').innerHTML = `
      ${stats}
      ${certSoon ? `
      <div class="panel">
        <h3 class="dash-panel-title">🔐 证书临期提醒（≤ ${cert.warnDays} 天）</h3>
        <table><thead><tr><th>任务</th><th>到期日</th><th>剩余</th></tr></thead><tbody>${certList}</tbody></table>
      </div>` : ''}
      <div class="panel">
        <h3 class="dash-panel-title">📈 代理访问统计
          <span class="muted small" style="font-weight:400">（最近 7 天 · 共 ${fmtCount(visits.total)} 次）</span></h3>
        <div class="dash-visit-grid">
          <div>
            <div class="dash-visit-sub">每日总访问次数</div>
            <div class="dash-visit-chart">${dayBars}</div>
          </div>
          <div>
            <div class="dash-visit-sub">网站访问 TOP10</div>
            ${topList(visits.topSites)}
          </div>
          <div>
            <div class="dash-visit-sub">来源地区 TOP10</div>
            ${topList(visits.topRegions)}
          </div>
        </div>
      </div>
      <div class="panel">
        <h3 class="dash-panel-title">⚡ 网络测速
          <span class="muted small" style="font-weight:400">（浏览器 ↔ 本系统链路，不经外部测速服务器）</span></h3>
        <div class="dash-speed-grid">
          <div class="dash-speed-tile"><div class="v" id="st-ping">${speedState.ping || '--'}</div><div class="l">延迟（平均往返）</div></div>
          <div class="dash-speed-tile"><div class="v" id="st-loss">${speedState.loss || '--'}</div><div class="l">丢包率（10 次探测）</div></div>
          <div class="dash-speed-tile"><div class="v" id="st-down">${speedState.down || '--'}</div><div class="l">下载速率</div></div>
          <div class="dash-speed-tile"><div class="v" id="st-up">${speedState.up || '--'}</div><div class="l">上传速率</div></div>
        </div>
        <div class="dash-speed-actions">
          <button class="btn primary" id="stBtn" onclick="runSpeedTest()"${speedState.running ? ' disabled' : ''}>
            ${speedState.running ? '测速中…' : '开始测速'}</button>
          <span class="dash-speed-note">测量浏览器到本系统的网络质量；测速期间请避免占用带宽的其他任务，结果不受页面自动刷新影响。</span>
        </div>
      </div>
      <div class="tip">数据每 15 秒自动刷新一次；点击顶部指标卡可跳转到对应模块查看详情。</div>`;
    setRefresh(renderDashboard, 15000);
}

/** 网络测速：延迟/丢包（10 次探测）、下载（32MB 流式读取）、上传（4MB 分片 × 6 次累计） */
async function runSpeedTest() {
    if (speedState.running) return;
    speedState.running = true;
    const btn = document.getElementById('stBtn');
    if (btn) { btn.disabled = true; btn.textContent = '测速中…'; }
    const setV = (k, v) => {
        speedState[k] = v;
        const el = document.getElementById('st-' + k);
        if (el) el.textContent = v;
    };
    ['ping', 'loss', 'down', 'up'].forEach(k => setV(k, '--'));
    try {
        // 1) 延迟与丢包：10 次小载荷往返，5 秒超时计为丢失
        let ok = 0, lost = 0, sum = 0;
        for (let i = 0; i < 10; i++) {
            const t0 = performance.now();
            try {
                const ctrl = new AbortController();
                const timer = setTimeout(() => ctrl.abort(), 5000);
                const r = await fetch(API_BASE + '/api/system/speedtest/ping?n=' + Math.round(t0) + '_' + i,
                    { headers: { 'X-Token': TOKEN }, cache: 'no-store', signal: ctrl.signal });
                clearTimeout(timer);
                if (!r.ok) throw new Error('HTTP ' + r.status);
                await r.arrayBuffer();
                sum += performance.now() - t0;
                ok++;
            } catch (e) { lost++; }
            setV('ping', ok ? Math.round(sum / ok) + ' ms' : '失败');
            setV('loss', Math.round(lost * 100 / (i + 1)) + ' %');
            await new Promise(r => setTimeout(r, 80));
        }

        // 2) 下载速率：边读边统计，实时刷新显示
        const DOWN_BYTES = 32 * 1024 * 1024;
        let got = 0;
        const t0 = performance.now();
        const resp = await fetch(API_BASE + '/api/system/speedtest/down?bytes=' + DOWN_BYTES,
            { headers: { 'X-Token': TOKEN }, cache: 'no-store' });
        if (!resp.ok) throw new Error('下载测速请求失败: HTTP ' + resp.status);
        const reader = resp.body.getReader();
        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            got += value.length;
            setV('down', fmtMbps(got / ((performance.now() - t0) / 1000)));
        }
        if (!got) throw new Error('下载测速未收到数据');

        // 3) 上传速率：4MB 随机分片多次上传（单请求体积受面板限制，累计 24MB）
        const CHUNK = 4 * 1024 * 1024;
        const chunk = new Uint8Array(CHUNK);
        for (let off = 0; off < CHUNK; off += 65536) {
            crypto.getRandomValues(chunk.subarray(off, Math.min(off + 65536, CHUNK)));
        }
        let sent = 0;
        const t1 = performance.now();
        while (sent < CHUNK * 6) {
            const r = await fetch(API_BASE + '/api/system/speedtest/up', {
                method: 'POST',
                headers: { 'X-Token': TOKEN, 'Content-Type': 'application/octet-stream' },
                body: chunk
            });
            if (!r.ok) throw new Error('上传测速请求失败: HTTP ' + r.status);
            await r.json();
            sent += CHUNK;
            setV('up', fmtMbps(sent / ((performance.now() - t1) / 1000)));
        }
        toast('测速完成');
    } catch (e) {
        toast('测速失败: ' + e.message, 'err');
    } finally {
        speedState.running = false;
        // 页面 15 秒自动刷新会重建按钮节点，结束时重新查询当前节点同步状态
        const b = document.getElementById('stBtn');
        if (b) { b.disabled = false; b.textContent = '开始测速'; }
    }
}
