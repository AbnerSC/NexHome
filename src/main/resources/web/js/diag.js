/* ============ 内存诊断模块 ============ */
/* 实时内存分解 + 时间线曲线 + 内存跳变点与 op_log 时间线的关联分析。
   数据来源：/api/diag/snapshot（采样时间线）、/api/diag/analysis（跳变检测 + 日志关联）、/api/diag/nmt（按需堆外明细）。 */
'use strict';

let diagAutoRefresh = true;
let diagTimer = null;

/** MB 数值（后端已给 MB 浮点）-> 人类可读；-1 表示不可用 */
function fmtDiagMB(mb) {
    if (mb == null || mb < 0) return '--';
    return mb >= 1024 ? (mb / 1024).toFixed(2) + ' GB' : mb.toFixed(1) + ' MB';
}

/** 分解表的一行：标签 + 进度条（used/total）+ 文本值 */
function diagBar(label, used, total, text, cls) {
    const pct = total > 0 ? Math.min(100, Math.round(used * 100 / total)) : 0;
    const color = pct >= 85 ? 'var(--red)' : pct >= 60 ? 'var(--orange)' : 'var(--cyan)';
    return `<tr>
      <th>${esc(label)}</th>
      <td><div class="diag-track"><i style="width:${pct}%;background:${color}"></i></div></td>
      <td class="diag-val ${cls || ''}">${text}</td>
    </tr>`;
}

/** 生成一条折线的 SVG points（等分 x，按 maxV 归一 y；缺值按 0） */
function diagSeriesPoints(history, key, maxV, w, h) {
    const n = history.length;
    if (n === 0) return '';
    const step = n > 1 ? w / (n - 1) : 0;
    return history.map((s, i) => {
        const v = Number(s[key] || 0);
        const y = h - (maxV > 0 ? Math.min(1, v / maxV) * h : 0);
        return `${(i * step).toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
}

/** 内存时间线双折线图（容器内存 + JVM 堆），纯内联 SVG，无第三方图表库 */
function diagChart(history) {
    const w = 900, h = 220, pad = 6;
    let maxV = 1;
    history.forEach(s => {
        maxV = Math.max(maxV, Number(s.mem || 0), Number(s.heapC || 0));
    });
    maxV = Math.ceil(maxV * 1.1);
    const cPts = diagSeriesPoints(history, 'mem', maxV, w - pad * 2, h);
    const hPts = diagSeriesPoints(history, 'heap', maxV, w - pad * 2, h);
    const first = history[0], last = history[history.length - 1];
    return `
      <div class="diag-legend">
        <span><i class="dot" style="background:var(--cyan)"></i>容器内存（当前 ${fmtDiagMB(last?.mem)} / 上限 ${fmtDiagMB(last?.memM)}）</span>
        <span><i class="dot" style="background:var(--green)"></i>JVM 堆（当前 ${fmtDiagMB(last?.heap)}）</span>
        <span class="muted small">纵轴满刻度 ${fmtDiagMB(maxV)}，共 ${history.length} 个样本${first ? `，跨度 ${first.time} ~ ${last.time}` : ''}</span>
      </div>
      <svg class="diag-svg" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none">
        <g transform="translate(${pad},${pad / 2})">
          <line x1="0" y1="${h}" x2="${w - pad * 2}" y2="${h}" stroke="rgba(255,255,255,.15)"/>
          <polyline fill="none" stroke="var(--cyan)" stroke-width="1.6" points="${cPts}"/>
          <polyline fill="none" stroke="var(--green)" stroke-width="1.6" points="${hPts}"/>
        </g>
      </svg>`;
}

/** 日志密度迷你柱状（每分钟条数），横轴为时间线跨度 */
function diagDensity(rows) {
    if (!rows || !rows.length) return '<p class="muted small">该时间跨度内无日志。</p>';
    const maxC = Math.max(...rows.map(r => Number(r.c)), 1);
    const bars = rows.map(r => `<div class="diag-dbar" title="${esc(r.minute)} — ${r.c} 条"
        style="height:${Math.max(2, Math.round(Number(r.c) * 100 / maxC))}%"></div>`).join('');
    return `<div class="diag-dbars">${bars}</div>
      <div class="small muted">共 ${rows.length} 分钟有日志，单分钟峰值 ${maxC} 条（悬停查看具体分钟）</div>`;
}

function diagLogRows(list, emptyText) {
    if (!list || !list.length) return `<tr><td colspan="4" class="muted">${emptyText}</td></tr>`;
    return list.map(l => `
      <tr class="${l.level === 'ERROR' ? 'log-row-error' : l.level === 'WARN' ? 'log-row-warn' : ''}">
        <td class="log-cell-time">${esc(l.time)}</td>
        <td>${badge(l.module, 'info')}</td>
        <td>${esc(l.level || '')}</td>
        <td class="log-cell-msg">${esc(l.message)}</td>
      </tr>`).join('');
}

async function loadDiag() {
    let snap;
    try { snap = await api('GET', '/api/diag/snapshot'); } catch (e) { return; }
    const body = $('#diagLive');
    if (!body) return; // 已离开页面
    const s = snap.latest || {};
    const jvm = snap.jvm || {};
    body.innerHTML = `
      <div class="panel">
        <h3 style="font-size:14px;margin-bottom:12px">实时内存分解</h3>
        <table>
          ${diagBar('容器内存', s.mem, s.memM, `${fmtDiagMB(s.mem)} / ${fmtDiagMB(s.memM)}`)}
          ${diagBar('进程 RSS', s.rss, s.memM, `${fmtDiagMB(s.rss)}${s.rss < 0 ? '（非 Linux 不可读）' : ''}`)}
          ${diagBar('JVM 堆', s.heap, s.heapM, `${fmtDiagMB(s.heap)} / 已提交 ${fmtDiagMB(s.heapC)} / 上限 ${fmtDiagMB(s.heapM)}`)}
          ${diagBar('元空间', s.meta, 256, fmtDiagMB(s.meta))}
          ${diagBar('CodeCache', s.code, 128, fmtDiagMB(s.code))}
          ${diagBar('非堆合计', s.nonHeap, 512, fmtDiagMB(s.nonHeap))}
          ${diagBar('NIO 直接缓冲', s.dir, s.dirCap, `${fmtDiagMB(s.dir)} / 容量 ${fmtDiagMB(s.dirCap)}（${s.dirCnt || 0} 个）`)}
          ${diagBar('映射缓冲', s.mapped, 64, fmtDiagMB(s.mapped))}
          <tr><th>线程 / GC</th><td></td><td class="diag-val">${s.thr || 0} 线程 · GC ${s.gc || 0} 次 / ${s.gcMs || 0} ms</td></tr>
          <tr><th>代理文件缓存</th><td></td><td class="diag-val">${s.cacheN || 0} 项 · ${fmtDiagMB(s.cacheMB)}（磁盘）</td></tr>
        </table>
        <div class="small muted" style="margin-top:8px">
          ${esc(jvm.vm || '')} · 运行 ${Math.floor((jvm.uptimeSec || 0) / 3600)}h${Math.floor((jvm.uptimeSec || 0) % 3600 / 60)}m ·
          堆上限 ${fmtDiagMB(jvm.maxHeapMB)} · NMT ${jvm.nmt ? '已开启' : '未开启（堆外仅能看到 NIO 直接缓冲，Netty 池化堆外需开 NMT 才可见）'}
        </div>
      </div>
      <div class="panel">
        <h3 style="font-size:14px;margin-bottom:12px">内存时间线</h3>
        ${snap.history && snap.history.length >= 2 ? diagChart(snap.history)
              : '<p class="muted small">样本不足（需至少 2 个，每 ' + (snap.intervalSec || 10) + ' 秒采一次），请稍候再刷新。</p>'}
      </div>`;
}

async function runDiagAnalysis() {
    const metric = $('#diagMetric').value;
    const box = $('#diagAnalysis');
    box.innerHTML = '<p class="muted">分析中...</p>';
    let a;
    try { a = await api('GET', '/api/diag/analysis?metric=' + encodeURIComponent(metric)); }
    catch (e) { box.innerHTML = `<p style="color:var(--red)">${esc(e.message)}</p>`; return; }
    const j = a.jump;
    const summary = a.count >= 2
      ? `<div class="tip">时间线跨度内 ${esc(a.metricLabel)} 从 <b>${fmtDiagMB(a.firstMB)}</b> 变化到 <b>${fmtDiagMB(a.lastMB)}</b>，净增 <b>${a.growthMB} MB</b>。
         ${j ? `最大单周期跳升发生在 <b>${esc(j.time)}</b>：${fmtDiagMB(j.fromMB)} → ${fmtDiagMB(j.toMB)}（<b style="color:var(--red)">+${j.deltaMB} MB</b>），下方为跳变点前后各 2 分钟的日志。`
             : '未检测到明显的单周期跳升（内存曲线相对平稳）。'}</div>`
      : `<p class="muted small">采样不足（当前 ${a.count} 个样本），无法定位跳变点；请让服务运行一段时间后再分析。</p>`;
    box.innerHTML = `
      ${summary}
      <h3 style="font-size:14px;margin:14px 0 8px">跳变点前后日志${j ? '' : '（无跳变点，暂空）'}</h3>
      <table>
        <thead><tr><th style="width:150px">时间</th><th style="width:110px">模块</th><th style="width:70px">级别</th><th>内容</th></tr></thead>
        <tbody>${diagLogRows(a.logsAround, '跳变点附近无日志记录')}</tbody>
      </table>
      <h3 style="font-size:14px;margin:14px 0 8px">关键事件（缓存加载 / 重启 / 监听启动）</h3>
      <table>
        <thead><tr><th style="width:150px">时间</th><th style="width:110px">模块</th><th>内容</th></tr></thead>
        <tbody>${(a.notable && a.notable.length) ? a.notable.map(n =>
            `<tr><td class="log-cell-time">${esc(n.time)}</td><td>${badge(n.module, 'info')}</td><td class="log-cell-msg">${esc(n.message)}</td></tr>`).join('')
            : '<tr><td colspan="3" class="muted">暂无关键事件</td></tr>'}</tbody>
      </table>
      <h3 style="font-size:14px;margin:14px 0 8px">日志密度（每分钟条数）</h3>
      ${diagDensity(a.density)}`;
}

async function exportDiag() {
    try {
        const snap = await api('GET', '/api/diag/snapshot');
        const blob = new Blob([JSON.stringify(snap, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'nexhome-memdiag-' + new Date().toISOString().replace(/[:.]/g, '') + '.json';
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 2000);
        toast('已导出诊断快照 JSON');
    } catch (e) { toast(e.message, 'err'); }
}

/** 按需拉取堆外原生内存明细（服务端带缓存，不点不触发） */
async function loadDiagNmt() {
    const box = $('#diagNmt');
    if (!box) return;
    box.innerHTML = '<p class="muted small">正在按需生成 NMT 汇总...</p>';
    let d;
    try { d = await api('GET', '/api/diag/nmt'); }
    catch (e) { box.innerHTML = `<p style="color:var(--red)">${esc(e.message)}</p>`; return; }
    if (!d.available) {
        box.innerHTML = `<p class="muted small">${esc(d.reason || 'NMT 不可用')}</p>`;
        return;
    }
    const rows = (d.categories || []).map(c => `
        <tr><td>${esc(c.name)}</td>
            <td class="diag-val">${fmtDiagMB(c.committedMB)}</td>
            <td class="diag-val muted">${fmtDiagMB(c.reservedMB)}</td></tr>`).join('');
    // 与启动基线的差值：直接指出哪一类原生内存长大了（内存不释放定位关键）
    let diffHtml = '';
    if (d.diff && d.diff.length) {
        const drows = d.diff.map(r => `
            <tr><td>${esc(r.name)}</td>
                <td class="diag-val muted">${fmtDiagMB(r.baseMB)}</td>
                <td class="diag-val">${fmtDiagMB(r.nowMB)}</td>
                <td class="diag-val" style="color:${r.deltaMB > 0 ? 'var(--red)' : 'var(--green)'}">${r.deltaMB > 0 ? '+' : ''}${r.deltaMB} MB</td></tr>`).join('');
        diffHtml = `
          <h3 style="font-size:14px;margin:16px 0 8px">与启动基线的变化（自 ${esc(d.baselineTime || '')}）</h3>
          <div class="tip">原生内存合计变化 <b style="color:${d.totalDeltaMB > 0 ? 'var(--red)' : 'var(--green)'}">${d.totalDeltaMB > 0 ? '+' : ''}${d.totalDeltaMB} MB</b>，下表按增幅排序，增长最多的类别即内存不释放的嫌疑人。</div>
          <table>
            <thead><tr><th>类别</th><th style="width:120px">启动时</th><th style="width:120px">当前</th><th style="width:120px">变化</th></tr></thead>
            <tbody>${drows}</tbody>
          </table>`;
    }
    box.innerHTML = `
        <table>
          <thead><tr><th>类别</th><th style="width:140px">已提交</th><th style="width:140px">保留</th></tr></thead>
          <tbody>${rows || '<tr><td colspan="3" class="muted">无分类数据</td></tr>'}</tbody>
        </table>
        <div class="small muted" style="margin-top:8px">合计已提交 ${fmtDiagMB(d.totalCommittedMB)} · 保留 ${fmtDiagMB(d.totalReservedMB)}（
          JVM 堆只是其中一项；Netty 池化等堆外分配多归在 Internal/Other。结果缓存约 60 秒）</div>
        ${diffHtml}`;
}

function armDiagRefresh() {
    if (diagTimer) { clearInterval(diagTimer); diagTimer = null; }
    if (diagAutoRefresh) diagTimer = setInterval(() => loadDiag().catch(() => { }), 10000);
}

async function renderDiag() {
    $('#pageBody').innerHTML = `
      <div class="toolbar">
        <label class="small muted">分析指标</label>
        <select id="diagMetric">
          <option value="container">容器内存（含堆外/RSS）</option>
          <option value="heap">JVM 堆</option>
        </select>
        <button class="btn" onclick="runDiagAnalysis()">分析内存跳变</button>
        <button class="btn" onclick="loadDiag()">刷新分解</button>
        <button class="btn" onclick="exportDiag()">导出快照 JSON</button>
        <label class="small muted"><input type="checkbox" id="diagAuto" ${diagAutoRefresh ? 'checked' : ''}> 自动刷新</label>
        <div class="spacer"></div>
        <span class="muted small">采样自本次进程启动，运行越久时间线越完整</span>
      </div>
      <div class="tip" style="margin-bottom:12px">用于定位「内存突然上升且不释放」：先看<b>实时分解</b>判断涨在堆还是堆外/RSS，
        再点<b>分析内存跳变</b>自动找出曲线跳升的时间点，并把该时刻前后的 <b>op_log 事件</b>对齐展示。</div>
      <div id="diagLive"><p class="muted">加载中...</p></div>
      <div class="panel">
        <h3 style="font-size:14px;margin-bottom:10px">堆外原生内存明细（NMT）</h3>
        <div class="toolbar" style="margin-bottom:8px">
          <button class="btn" onclick="loadDiagNmt()">加载 / 刷新明细</button>
          <span class="muted small">按需读取（不常驻、带缓存），把 Netty 池化等堆外占用从 Internal/Other 拆出</span>
        </div>
        <div id="diagNmt"><p class="muted small">尚未加载。需启动时开了 -XX:NativeMemoryTracking=summary。</p></div>
      </div>
      <div class="panel">
        <h3 style="font-size:14px;margin-bottom:12px">关联分析</h3>
        <div id="diagAnalysis"><p class="muted small">点击上方「分析内存跳变」生成结论。</p></div>
      </div>`;
    $('#diagAuto').addEventListener('change', e => { diagAutoRefresh = e.target.checked; armDiagRefresh(); });
    await loadDiag();
    armDiagRefresh();
}
