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

/** 测速时长：延迟/下载/上传三个阶段统一各测 10 秒 */
const SPEED_TEST_MS = 10000;

/**
 * 测速并发流数：每条 HTTP/1.1 连接的吞吐受服务端单线程 TLS 加解密与 socket 流控限制，
 * 跑不满千兆链路，多路并发才能利用多核压出真实带宽（与主流测速站点做法一致）。
 * 取 6 —— 浏览器同源 HTTP/1.1 连接数上限即 6，测速期间已暂停其他轮询，可全部用于测速；
 * 实测 localhost fetch 4 路 149MB/s、6 路 173MB/s，NAS 上多核空闲时提升更明显。
 */
const SPEED_STREAMS = 6;

/** 取“好看”的纵轴上限（1/2/2.5/5 × 10^n），网格刻度避免零碎小数 */
function dvNiceMax(v) {
    const exp = Math.pow(10, Math.floor(Math.log10(Math.max(v, 1))));
    for (const f of [1, 2, 2.5, 5, 10]) if (v <= f * exp * 1.0001) return f * exp;
    return 10 * exp;
}

/**
 * 每日总访问折线图：Catmull-Rom 平滑曲线 + 主题色渐变描边与面积 + 虚线网格
 * + 悬浮最近点吸附气泡，最新一天末端常亮高亮点；尺寸按容器实测绘制（纵向填满所在栅格行）。
 */
function drawVisitChart(host, days) {
    if (!host) return;
    if (!days.length) { host.innerHTML = '<p class="muted small" style="margin:0">暂无访问数据</p>'; return; }
    const W = Math.max(host.clientWidth || 0, 240), H = Math.max(host.clientHeight || 0, 140);
    const P = { l: 42, r: 14, t: 14, b: 20 };
    const iw = W - P.l - P.r, ih = H - P.t - P.b;
    const peak = Math.max(...days.map(x => x.hits || 0));
    const nice = dvNiceMax(peak);
    const X = i => P.l + (days.length < 2 ? iw / 2 : (iw * i) / (days.length - 1));
    const Y = v => P.t + ih - (Math.max(v, 0) / nice) * ih;
    const pts = days.map((x, i) => ({ x: +X(i).toFixed(1), y: +Y(x.hits || 0).toFixed(1), day: x.day, hits: x.hits || 0 }));

    // Catmull-Rom 转三次贝塞尔（控制点钳制在绘图区内，防平滑越界穿过基线）
    const line = (() => {
        if (pts.length === 1) return `M${pts[0].x},${pts[0].y}`;
        const cl = y => Math.min(P.t + ih, Math.max(P.t, y));
        let d = `M${pts[0].x},${pts[0].y}`;
        for (let i = 0; i < pts.length - 1; i++) {
            const p0 = pts[i - 1] || pts[i], p1 = pts[i], p2 = pts[i + 1], p3 = pts[i + 2] || p2;
            d += `C${(p1.x + (p2.x - p0.x) / 6).toFixed(1)},${cl(p1.y + (p2.y - p0.y) / 6).toFixed(1)} `
                + `${(p2.x - (p3.x - p1.x) / 6).toFixed(1)},${cl(p2.y + (p3.y - p1.y) / 6).toFixed(1)} `
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
            + `<text x="${P.l - 7}" y="${+y + 3.5}" text-anchor="end" font-size="9.5" fill="#aab6d8">${fmtCount((nice * g) / 4)}</text>`;
    }
    // 横轴日期刻度（点少全显，点多隔取样且末尾必显）
    let ticks = '';
    const step = Math.max(1, Math.ceil(pts.length / 5));
    pts.forEach((p, i) => {
        if (i % step && i !== pts.length - 1) return;
        ticks += `<text x="${p.x}" y="${H - 6}" text-anchor="middle" font-size="9.5" fill="#aab6d8">${esc((p.day || '').slice(5))}</text>`;
    });

    const last = pts[pts.length - 1];
    host.innerHTML = `
      <svg width="${W}" height="${H}" style="display:block">
        <defs>
          <linearGradient id="dvc-line" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stop-color="#38bdf8"/><stop offset=".55" stop-color="#6d8bff"/><stop offset="1" stop-color="#a855f7"/>
          </linearGradient>
          <linearGradient id="dvc-area" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#6d8bff" stop-opacity=".30"/>
            <stop offset=".65" stop-color="#6d8bff" stop-opacity=".07"/>
            <stop offset="1" stop-color="#6d8bff" stop-opacity="0"/>
          </linearGradient>
          <filter id="dvc-glow" x="-30%" y="-30%" width="160%" height="160%">
            <feDropShadow dx="0" dy="2" stdDeviation="2.5" flood-color="#6d8bff" flood-opacity=".35"/>
          </filter>
        </defs>
        ${grid}${ticks}
        ${area ? `<path d="${area}" fill="url(#dvc-area)"/>` : ''}
        <path d="${line}" fill="none" stroke="url(#dvc-line)" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" filter="url(#dvc-glow)"/>
        ${peak > 0 && last ? `<circle cx="${last.x}" cy="${last.y}" r="3.4" fill="#fff" stroke="#6d8bff" stroke-width="2.2"/>` : ''}
        ${peak === 0 ? `<text x="${P.l + iw / 2}" y="${P.t + ih / 2}" text-anchor="middle" font-size="11" fill="#b7c1cf">该时段暂无访问</text>` : ''}
        <line id="dvc-guide" y1="${P.t}" y2="${P.t + ih}" stroke="#6d8bff" stroke-width="1.2" stroke-dasharray="4 3" opacity="0"/>
        <circle id="dvc-cursor" r="4.2" fill="#6d8bff" stroke="#fff" stroke-width="1.6" opacity="0"/>
        <rect id="dvc-hit" x="${P.l}" y="${P.t}" width="${iw}" height="${ih}" fill="transparent" style="cursor:crosshair"/>
      </svg>
      <div class="tf-tip" id="dvc-tip" style="display:none"></div>`;

    // 悬浮交互：最近点吸附 + 十字引导线 + 深色气泡提示
    const svg = host.querySelector('svg');
    const guide = host.querySelector('#dvc-guide');
    const cursor = host.querySelector('#dvc-cursor');
    const tip = host.querySelector('#dvc-tip');
    const move = e => {
        const mx = e.clientX - svg.getBoundingClientRect().left;
        let best = 0, dist = Infinity;
        pts.forEach((p, i) => { const dd = Math.abs(p.x - mx); if (dd < dist) { dist = dd; best = i; } });
        const p = pts[best];
        guide.setAttribute('x1', p.x); guide.setAttribute('x2', p.x); guide.setAttribute('opacity', '.55');
        cursor.setAttribute('cx', p.x); cursor.setAttribute('cy', p.y); cursor.setAttribute('opacity', '1');
        tip.style.display = '';
        tip.innerHTML = `${esc(p.day)}<br><b>${fmtCount(p.hits)} 次</b>`;
        const half = tip.offsetWidth / 2 || 40; // 靠近左右边缘时钳制，防气泡溢出
        tip.style.left = Math.max(half + 2, Math.min(W - half - 2, p.x)) + 'px';
        tip.style.top = Math.max(p.y - 10, 30) + 'px';
    };
    const leave = () => {
        guide.setAttribute('opacity', '0'); cursor.setAttribute('opacity', '0'); tip.style.display = 'none';
    };
    const hit = host.querySelector('#dvc-hit');
    hit.addEventListener('mousemove', move);
    hit.addEventListener('mouseleave', leave);
}

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

    // 代理访问统计：每日总访问折线 + 网站/地区 TOP10（横向条形）
    const visitDays = visits.days || [];

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
          <div class="dash-visit-col">
            <div class="dash-visit-sub">每日总访问次数</div>
            <div class="dash-visit-chart" id="dashVisitChart"></div>
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
          <div class="dash-speed-tile"><div class="v" id="st-ping">${speedState.ping || '--'}</div><div class="l">延迟（中位往返）</div></div>
          <div class="dash-speed-tile"><div class="v" id="st-loss">${speedState.loss || '--'}</div><div class="l">丢包率（10 秒探测）</div></div>
          <div class="dash-speed-tile"><div class="v" id="st-down">${speedState.down || '--'}</div><div class="l">下载速率</div></div>
          <div class="dash-speed-tile"><div class="v" id="st-up">${speedState.up || '--'}</div><div class="l">上传速率</div></div>
        </div>
        <div class="dash-speed-actions">
          <button class="btn primary" id="stBtn" onclick="runSpeedTest()"${speedState.running ? ' disabled' : ''}>
            ${speedState.running ? '测速中…' : '开始测速'}</button>
          <span class="dash-speed-note">测量浏览器到本系统的网络质量：延迟/下载/上传各测 10 秒（速率阶段 ${SPEED_STREAMS} 路并发），
            测速期间已暂停页面自动刷新以免干扰，请避免其他占用带宽的任务。</span>
        </div>
      </div>
      <div class="tip">数据每 15 秒自动刷新一次；点击顶部指标卡可跳转到对应模块查看详情。</div>`;
    drawVisitChart($('#dashVisitChart'), visitDays);
    // TOP10 名称等宽：以两列全部名称中最长为准统一列宽，比例条起点与次数纵向对齐
    // （scrollWidth 含被 ellipsis 截断的完整文本宽；超出 max-width 时全体一致截断，仍保持对齐）
    const nameEls = $$('.dash-top-name');
    if (nameEls.length) {
        let maxW = 0;
        nameEls.forEach(el => { maxW = Math.max(maxW, el.scrollWidth); });
        nameEls.forEach(el => { el.style.width = maxW + 1 + 'px'; });  // +1 防亚像素取整误差深出省略号
    }
    setRefresh(renderDashboard, 15000);
}

/**
 * 网络测速：延迟/丢包、下载、上传三个阶段各测 10 秒。
 * 速率阶段多路并发拉/推流；测量前先短时预热全部并发连接，
 * 避免新建连接的握手与 TCP 慢启动计入测量窗口；上传优先流式推流（fetch duplex），
 * 让连接全程满载而非整片上传后空等响应往返；测速期间暂停仪表盘自动刷新与顶栏监控轮询，
 * 避免额外请求抢占浏览器同源连接数与服务端 CPU 干扰测量。
 */
async function runSpeedTest() {
    if (speedState.running) return;
    speedState.running = true;
    const syncBtn = () => {
        const b = document.getElementById('stBtn');
        if (b) { b.disabled = speedState.running; b.textContent = speedState.running ? '测速中…' : '开始测速'; }
    };
    const setV = (k, v) => {
        speedState[k] = v;
        const el = document.getElementById('st-' + k);
        if (el) el.textContent = v;
    };
    ['ping', 'loss', 'down', 'up'].forEach(k => setV(k, '--'));
    syncBtn();
    setRefresh(null);    // 停掉 15 秒整页重绘（其聚合请求会抢连接与 CPU）
    stopSysMetrics();    // 停掉顶栏 5 秒监控轮询
    let seq = 0, lastUi = 0;
    const tick = () => { // 高频回包按 200ms 节流刷新，避免 DOM 写入拖慢测量本身
        const t = performance.now();
        if (t - lastUi < 200) return false;
        lastUi = t;
        return true;
    };
    const rate = (bytes, since) => fmtMbps(bytes / ((performance.now() - since) / 1000));
    try {
        // 1) 延迟与丢包：小载荷连续往返，5 秒未回计一次丢失
        const probe = async () => {
            const t0 = performance.now();
            const ctrl = new AbortController();
            const timer = setTimeout(() => ctrl.abort(), 5000);
            try {
                const r = await fetch(API_BASE + '/api/system/speedtest/ping?n=' + Date.now() + '_' + (++seq),
                    { headers: { 'X-Token': TOKEN }, cache: 'no-store', signal: ctrl.signal });
                clearTimeout(timer);
                if (!r.ok) throw new Error('HTTP ' + r.status);
                await r.arrayBuffer();
                return performance.now() - t0;
            } catch (e) {
                clearTimeout(timer);
                return null;
            }
        };
        // 预热两次不计入：首请求含连接建立/TLS 握手与冷启动路径，会显著抬高结果
        await probe();
        await probe();
        // 取中位数而非平均值：少量尖峰（NAS 掉频/GC/Docker 抖动）会把均值大幅拉高，
        // 中位数才代表链路的典型往返（LibreSpeed 等也只报低分位/最小值）
        const samples = [];
        let lost = 0;
        const median = () => {
            if (!samples.length) return null;
            const a = samples.slice().sort((x, y) => x - y);
            return a[a.length >> 1];
        };
        const tP = performance.now();
        while (performance.now() - tP < SPEED_TEST_MS) {
            const dt = await probe();
            if (dt === null) lost++;
            else samples.push(dt);
            if (tick()) {
                const m = median();
                if (m != null) setV('ping', Math.round(m) + ' ms');
                setV('loss', Math.round(lost * 100 / (samples.length + lost)) + ' %');
            }
            await new Promise(r => setTimeout(r, 10)); // 小间隙：避免连续自压抬高 RTT，又不至于让 CPU 掉频
        }
        const mp = median();
        setV('ping', mp != null ? Math.round(mp) + ' ms' : '失败');
        setV('loss', Math.round(lost * 100 / (samples.length + lost)) + ' %');

        // 2) 下载速率：先预热全部并发连接（延迟阶段只用过 1 条连接，其余 5 条的
        //    TLS 建连与慢启动开销不应计入测量窗口），再计时多路并发拉流，
        //    服务端按时长跑满 10 秒后正常收尾（无需中断请求）
        await Promise.all(Array.from({ length: SPEED_STREAMS }, () =>
            fetch(API_BASE + '/api/system/speedtest/down?ms=600',
                { headers: { 'X-Token': TOKEN }, cache: 'no-store' })
                .then(async r => {
                    if (!r.ok) throw new Error('下载测速请求失败: HTTP ' + r.status);
                    await r.arrayBuffer();
                })));
        let got = 0;
        const tD = performance.now();
        const pull = async () => {
            const resp = await fetch(API_BASE + '/api/system/speedtest/down?ms=' + SPEED_TEST_MS,
                { headers: { 'X-Token': TOKEN }, cache: 'no-store' });
            if (!resp.ok) throw new Error('下载测速请求失败: HTTP ' + resp.status);
            const reader = resp.body.getReader();
            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                got += value.length;
                if (tick()) setV('down', rate(got, tD));
            }
        };
        await Promise.all(Array.from({ length: SPEED_STREAMS }, pull));
        if (!got) throw new Error('下载测速未收到数据');
        setV('down', rate(got, tD));

        // 3) 上传速率：https 页面优先流式推流（fetch + duplex:'half'）——整条连接 10 秒连续满载。
        //    旧“整片 POST 等响应再发下一片”的模式，每片之间连接空转一个往返周期，
        //    千兆链路实测只能压出约 1/3 带宽；流式模式由浏览器按网络节奏回调 pull 入块
        //    （默认高水位 1 块，天然背压，推流节奏即网络真实吞吐节奏），到时 close() 结束，
        //    服务端读到 EOF 返回实收字节数作为权威计量。
        //    注意：浏览器的流式请求体只在 HTTP/2、HTTP/3 上可用，而这两种协议只跑在 TLS
        //    之上——明文 http:// 页面里发流式 POST，浏览器会对同端口发起 TLS/h2 协商，
        //    失败即整路 "Failed to fetch"（net::ERR_ALPN_NEGOTIATION_FAILED）。
        //    因此 http:// 下直接走 8MB 大分片整片 POST（分片越大，每片往返空转的占比越小）；
        //    https 下若流式实际不可用（如服务端未启用 h2）同样降级到分片模式。
        const UP_URL = API_BASE + '/api/system/speedtest/up';
        const UP_BLOCK = 1024 * 1024;
        const upBlock = new Uint8Array(UP_BLOCK); // getRandomValues 单次上限 65536 字节，需分片填充
        for (let off = 0; off < UP_BLOCK; off += 65536) {
            crypto.getRandomValues(upBlock.subarray(off, Math.min(off + 65536, UP_BLOCK)));
        }
        // 流式上传能力探测：除浏览器需支持 duplex 外，还要求页面本身是 https——
        // 不满足时直接走定长分片，避免流式请求在明文端口上触发注定失败的 TLS 协商；
        // 不支持流式请求体的浏览器 Request 构造器会直接抛 TypeError
        const canStreamUp = location.protocol === 'https:' && (() => {
            try { new Request('', { method: 'POST', body: new ReadableStream(), duplex: 'half' }); return true; }
            catch (e) { return false; }
        })();
        // 兜底：流式请求卡死时中止全部上传，避免测速永久挂起
        const upAbort = new AbortController();
        const upGuard = setTimeout(() => upAbort.abort(), SPEED_TEST_MS + 20_000);
        let sent = 0;
        let tU = performance.now();
        const streamUp = ms => { // 单路流式推流 ms 毫秒，返回该路服务端实收字节数
            const end = performance.now() + ms;
            return fetch(UP_URL, {
                method: 'POST',
                headers: { 'X-Token': TOKEN, 'Content-Type': 'application/octet-stream' },
                body: new ReadableStream({
                    pull(controller) {
                        if (performance.now() >= end) controller.close();
                        else {
                            controller.enqueue(upBlock);
                            sent += UP_BLOCK;
                            if (tick()) setV('up', rate(sent, tU));
                        }
                    }
                }),
                duplex: 'half',
                signal: upAbort.signal
            }).then(async r => {
                if (!r.ok) throw new Error('上传测速请求失败: HTTP ' + r.status);
                return (await r.json()).data.bytes;
            });
        };
        // 定长分片轮：8MB 大分片整片 POST，是 http:// 下的唯一上传通道，
        // 也是 https 下流式失败时的降级路径；窗口从各自超时点重新起算
        const chunkRound = async () => {
            const CHUNK = 8 * 1024 * 1024;
            const chunk = new Uint8Array(CHUNK);
            for (let off = 0; off < CHUNK; off += 65536) {
                crypto.getRandomValues(chunk.subarray(off, Math.min(off + 65536, CHUNK)));
            }
            let total = 0;
            const tC = performance.now();
            const push = async () => {
                while (performance.now() - tC < SPEED_TEST_MS) {
                    const r = await fetch(UP_URL, {
                        method: 'POST',
                        headers: { 'X-Token': TOKEN, 'Content-Type': 'application/octet-stream' },
                        body: chunk,
                        signal: upAbort.signal
                    });
                    if (!r.ok) throw new Error('上传测速请求失败: HTTP ' + r.status);
                    const j = await r.json();
                    total += (j.data && j.data.bytes) || CHUNK;
                    if (tick()) setV('up', rate(total, tC));
                }
            };
            await Promise.all(Array.from({ length: SPEED_STREAMS }, push));
            setV('up', rate(total, tC));
        };
        try {
            if (canStreamUp) {
                try {
                    // 预热：下载阶段的连接对发送方向未经考验，先让 6 条连接度过 TCP 慢启动（不计入测量）
                    await Promise.all(Array.from({ length: SPEED_STREAMS }, () => streamUp(600)));
                    sent = 0;
                    tU = performance.now();
                    const ups = await Promise.all(Array.from({ length: SPEED_STREAMS }, () => streamUp(SPEED_TEST_MS)));
                    const upTotal = ups.reduce((a, b) => a + b, 0);
                    if (!upTotal) throw new Error('上传测速未发出数据');
                    setV('up', rate(upTotal, tU));
                } catch (se) {
                    // 流式实际不可用（如 https 站点未启用 HTTP/2）：降级为定长分片重测
                    setV('up', '--');
                    await chunkRound();
                }
            } else {
                await chunkRound();
            }
        } finally {
            clearTimeout(upGuard);
        }
        toast('测速完成');
    } catch (e) {
        toast('测速失败: ' + e.message, 'err');
    } finally {
        speedState.running = false;
        // 仍处于已登录的主视图才恢复轮询与重绘（测速中途退出登录时不应把请求重新拉起）
        if (!$('#mainView').classList.contains('hidden')) {
            startSysMetrics();
            if (currentPage === 'dashboard') {
                // 测速耗时 30 秒且期间未刷新，回到总览重绘各面板并恢复自动刷新（按钮状态由模板按 running 渲染）
                renderDashboard().catch(() => { });
            } else {
                syncBtn(); // 已切走页面：仅同步按钮状态，不覆盖当前页内容
            }
        }
    }
}
