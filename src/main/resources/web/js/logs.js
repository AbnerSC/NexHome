/* ============ 操作日志模块 ============ */
'use strict';

let logPage = 1, logModule = '';
let logAutoRefresh = true;

async function renderLogs() {
    const r = await api('GET', `/api/logs?page=${logPage}&size=50&module=${encodeURIComponent(logModule)}`);
    const total = Number(r.total);
    const pages = Math.max(1, Math.ceil(total / 50));
    const rows = r.list.map(l => `
      <tr class="${l.level === 'ERROR' ? 'log-row-error' : l.level === 'WARN' ? 'log-row-warn' : ''}">
        <td class="log-cell-time">${esc(l.time)}</td>
        <td>${badge(l.module, 'info')}</td>
        <td>${esc(l.level)}</td>
        <td class="log-cell-msg">${esc(l.message)}</td>
      </tr>`).join('');
    $('#pageBody').innerHTML = `
      <div class="toolbar">
        <select id="logModuleSel" class="btn" style="padding:6px 10px">
          <option value="">全部模块</option>
          ${['SYSTEM', 'AUTH', 'DDNS', 'STUN', 'WOL', 'CERT', 'NAV'].map(m =>
              `<option value="${m}" ${logModule === m ? 'selected' : ''}>${m}</option>`).join('')}
        </select>
        <button class="btn" onclick="logPage=1;renderLogs()">刷新</button>
        <label class="small muted"><input type="checkbox" id="logAuto" ${logAutoRefresh ? 'checked' : ''}> 自动刷新</label>
        <div class="spacer"></div>
        <span class="muted small">共 ${total} 条</span>
      </div>
      <div class="panel"><table>
        <thead><tr><th style="width:150px">时间</th><th style="width:90px">模块</th><th style="width:70px">级别</th><th>内容</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="4" class="muted">暂无日志</td></tr>'}</tbody>
      </table>
      <div class="pager">
        <button class="btn small" ${logPage <= 1 ? 'disabled' : ''} onclick="logPage--;renderLogs()">上一页</button>
        <span class="small muted">第 ${logPage} / ${pages} 页</span>
        <button class="btn small" ${logPage >= pages ? 'disabled' : ''} onclick="logPage++;renderLogs()">下一页</button>
      </div></div>`;
    $('#logModuleSel').addEventListener('change', e => { logModule = e.target.value; logPage = 1; renderLogs(); });
    $('#logAuto').addEventListener('change', e => { logAutoRefresh = e.target.checked; });
    if (logAutoRefresh) setRefresh(renderLogs, 5000); else setRefresh(null);
}
