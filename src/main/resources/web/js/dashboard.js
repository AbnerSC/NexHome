/* ============ 统一状态仪表盘（总览） ============ */
'use strict';

/** 秒数转可读运行时长 */
function fmtUptime(sec) {
    sec = sec || 0;
    const d = Math.floor(sec / 86400), h = Math.floor(sec % 86400 / 3600), m = Math.floor(sec % 3600 / 60);
    return (d > 0 ? d + ' 天 ' : '') + h + ' 小时 ' + m + ' 分钟';
}

async function renderDashboard() {
    let d;
    try { d = await api('GET', '/api/dashboard'); }
    catch (e) { $('#pageBody').innerHTML = `<p style="color:var(--red)">${esc(e.message)}</p>`; return; }
    const sys = d.system || {}, ddns = d.ddns || {}, stun = d.stun || {}, cert = d.cert || {};
    const mon = d.monitor || {}, docker = d.docker || {}, alerts = d.alerts || [];

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

    // 最近告警/错误日志摘要
    const alertRows = alerts.map(a => `
      <tr class="log-row-${String(a.level || '').toLowerCase()}">
        <td class="log-cell-time">${esc(a.time || '')}</td>
        <td>${badge(esc(a.module || ''), 'gray')}</td>
        <td>${badge(esc(a.level || ''), a.level === 'ERROR' ? 'err' : 'warn')}</td>
        <td class="log-cell-msg">${esc(a.message || '')}</td></tr>`).join('');

    $('#pageBody').innerHTML = `
      ${stats}
      ${certSoon ? `
      <div class="panel">
        <h3 class="dash-panel-title">🔐 证书临期提醒（≤ ${cert.warnDays} 天）</h3>
        <table><thead><tr><th>任务</th><th>到期日</th><th>剩余</th></tr></thead><tbody>${certList}</tbody></table>
      </div>` : ''}
      <div class="panel">
        <h3 class="dash-panel-title">📋 最近告警 / 错误
          <span class="muted small" style="font-weight:400">（最多 10 条）</span></h3>
        ${alerts.length ? `<table><thead><tr><th style="width:150px">时间</th><th style="width:110px">模块</th>
          <th style="width:100px">级别</th><th>信息</th></tr></thead><tbody>${alertRows}</tbody></table>`
          : '<p class="muted">暂无告警或错误记录，系统运行正常。</p>'}
      </div>
      <div class="tip">数据每 15 秒自动刷新一次；点击顶部指标卡可跳转到对应模块查看详情。</div>`;
    setRefresh(renderDashboard, 15000);
}
