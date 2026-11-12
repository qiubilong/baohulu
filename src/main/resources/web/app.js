'use strict';

/** 列表 DOM 最大行数，超出移除最旧行 */
const MAX_ROWS = 500;

/** 全部流量行（含未渲染字段），key = id */
const rowEntries = new Map();

let paused = false;
let selectedId = null;

const trafficBody = document.getElementById('traffic-body');
const emptyTip = document.getElementById('empty-tip');
const connStatus = document.getElementById('conn-status');
const statTotal = document.getElementById('stat-total');
const statFiltered = document.getElementById('stat-filtered');

/* ---------- WebSocket 实时推送 ---------- */

function connectWs() {
    const ws = new WebSocket(`ws://${location.host}/ws`);
    ws.onopen = () => {
        connStatus.textContent = '实时';
        connStatus.className = 'on';
    };
    ws.onmessage = (event) => {
        const entry = JSON.parse(event.data);
        addRow(entry);
    };
    ws.onclose = () => {
        connStatus.textContent = '已断开，重连中...';
        connStatus.className = 'off';
        setTimeout(connectWs, 3000);
    };
    ws.onerror = () => ws.close();
}

/* ---------- 流量列表 ---------- */

function addRow(entry) {
    rowEntries.set(entry.id, entry);
    if (trafficBody.children.length === 0) {
        emptyTip.classList.add('hidden');
    }
    if (paused) {
        return;
    }
    const tr = buildRow(entry);
    if (!matchFilter(entry)) {
        tr.classList.add('filtered-out');
    }
    trafficBody.insertBefore(tr, trafficBody.firstChild);
    trimRows();
    refreshStat();
}

function buildRow(entry) {
    const tr = document.createElement('tr');
    tr.dataset.id = entry.id;
    tr.dataset.host = entry.host || '';
    const status = entry.status || 0;
    const statusText = entry.error ? 'ERR' : (status || '-');
    tr.innerHTML = `
        <td>${entry.id}</td>
        <td>${formatTime(entry.startTime)}</td>
        <td><span class="method">${entry.method}</span></td>
        <td class="host-cell" title="${escapeHtml(entry.url || '')}">
            <span class="host">${escapeHtml(entry.host || '')}</span>${escapeHtml(entry.path || '')}
        </td>
        <td><span class="status ${statusClass(status, entry.error)}">${statusText}</span></td>
        <td>${entry.durationMs != null ? entry.durationMs + 'ms' : '-'}</td>
        <td>${formatSize(entry.responseSize)}</td>`;
    tr.addEventListener('click', () => selectRow(tr, entry.id));
    return tr;
}

function trimRows() {
    while (trafficBody.children.length > MAX_ROWS) {
        const last = trafficBody.lastChild;
        rowEntries.delete(Number(last.dataset.id));
        trafficBody.removeChild(last);
    }
}

function selectRow(tr, id) {
    document.querySelectorAll('#traffic-body tr.selected').forEach((el) => el.classList.remove('selected'));
    tr.classList.add('selected');
    selectedId = id;
    loadDetail(id);
}

/* ---------- 域名过滤（本地即时过滤） ---------- */

const filterInput = document.getElementById('domain-filter');
filterInput.addEventListener('input', applyFilter);

function matchFilter(entry) {
    const keyword = filterInput.value.trim().toLowerCase();
    if (!keyword) {
        return true;
    }
    return (entry.host || '').toLowerCase().includes(keyword);
}

function applyFilter() {
    let visible = 0;
    trafficBody.querySelectorAll('tr').forEach((tr) => {
        const host = (tr.dataset.host || '').toLowerCase();
        const hit = !filterInput.value.trim() || host.includes(filterInput.value.trim().toLowerCase());
        tr.classList.toggle('filtered-out', !hit);
        if (hit) {
            visible++;
        }
    });
    statFiltered.textContent = filterInput.value.trim() ? `过滤命中 ${visible} 条` : '';
}

/* ---------- 暂停 / 清空 ---------- */

document.getElementById('btn-pause').addEventListener('click', function () {
    paused = !paused;
    this.textContent = paused ? '继续' : '暂停';
    this.classList.toggle('active', paused);
});

document.getElementById('btn-clear').addEventListener('click', async () => {
    await fetch('/api/clear', { method: 'POST' });
    trafficBody.innerHTML = '';
    rowEntries.clear();
    emptyTip.classList.remove('hidden');
    document.getElementById('detail').classList.add('hidden');
    document.getElementById('detail-empty').classList.remove('hidden');
    refreshStat();
});

/* ---------- 详情加载与 Tab 渲染 ---------- */

const TAB_DEFS = [
    { key: 'general', label: '概览' },
    { key: 'req-headers', label: '请求头' },
    { key: 'req-body', label: '请求体' },
    { key: 'resp-headers', label: '响应头' },
    { key: 'resp-body', label: '响应体' },
];
let activeTab = 'general';

async function loadDetail(id) {
    const detail = document.getElementById('detail');
    const detailEmpty = document.getElementById('detail-empty');
    try {
        const resp = await fetch(`/api/traffic/${id}`);
        if (!resp.ok) {
            throw new Error(`HTTP ${resp.status}`);
        }
        const entry = await resp.json();
        detailEmpty.classList.add('hidden');
        detail.classList.remove('hidden');
        renderTabs(entry);
        renderTabBody(entry, activeTab);
    } catch (e) {
        detailEmpty.textContent = `加载失败：${e.message}（流量可能已被淘汰）`;
        detailEmpty.classList.remove('hidden');
        detail.classList.add('hidden');
    }
}

function renderTabs(entry) {
    const tabs = document.getElementById('detail-tabs');
    tabs.innerHTML = '';
    TAB_DEFS.forEach((tab) => {
        const btn = document.createElement('button');
        btn.textContent = tab.label;
        btn.className = tab.key === activeTab ? 'active' : '';
        btn.addEventListener('click', () => {
            activeTab = tab.key;
            tabs.querySelectorAll('button').forEach((b) => b.classList.remove('active'));
            btn.classList.add('active');
            renderTabBody(entry, tab.key);
        });
        tabs.appendChild(btn);
    });
}

function renderTabBody(entry, tab) {
    const body = document.getElementById('detail-body');
    switch (tab) {
        case 'general':
            body.innerHTML = kvTable([
                ['URL', entry.url],
                ['方法', entry.method],
                ['状态', entry.status || '-'],
                ['耗时', entry.durationMs != null ? entry.durationMs + 'ms' : '-'],
                ['开始时间', entry.startTime],
                ['响应类型', entry.responseContentType || '-'],
                ['响应大小', formatSize(entry.responseSize)],
                ['错误', entry.error || '-'],
            ]);
            break;
        case 'req-headers':
            body.innerHTML = headersTable(entry.requestHeaders);
            break;
        case 'req-body':
            body.innerHTML = bodyView(entry.requestBody, entry.requestTruncated, entry.requestContentType);
            break;
        case 'resp-headers':
            body.innerHTML = headersTable(entry.responseHeaders);
            break;
        case 'resp-body':
            if (entry.responseBinary) {
                body.innerHTML = `<div class="binary-tip">二进制内容，类型 ${escapeHtml(entry.responseContentType || '未知')}，大小 ${formatSize(entry.responseSize)}</div>`;
            } else {
                body.innerHTML = bodyView(entry.responseBody, entry.responseTruncated, entry.responseContentType);
            }
            break;
        default:
            body.innerHTML = '';
    }
}

function bodyView(text, truncated, contentType) {
    if (!text) {
        return '<div class="binary-tip">（无内容）</div>';
    }
    const isJson = contentType && contentType.includes('json');
    const tip = truncated ? '<div class="truncated-tip">内容超过 1MB 已截断</div>' : '';
    if (isJson) {
        const pretty = prettyJson(text);
        if (pretty !== null) {
            return tip + `<pre class="json-view">${pretty}</pre>`;
        }
    }
    return tip + `<pre class="plain-view">${escapeHtml(text)}</pre>`;
}

/** JSON 格式化 + 语法高亮（零依赖自实现） */
function prettyJson(text) {
    let obj;
    try {
        obj = JSON.parse(text);
    } catch (e) {
        return null;
    }
    const pretty = JSON.stringify(obj, null, 2);
    return pretty
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/"(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?/g, (match) => {
            let cls = 'json-string';
            if (/:\s*$/.test(match)) {
                cls = 'json-key';
            } else if (/^"/.test(match)) {
                cls = /true|false|null/.test(match) ? 'json-literal' : 'json-string';
            }
            return `<span class="${cls}">${match}</span>`;
        })
        .replace(/\b(true|false|null)\b(?!<\/span>)/g, '<span class="json-literal">$1</span>')
        .replace(/(:\s)(-?\d+\.?\d*(e[+-]?\d+)?)/gi, '$1<span class="json-number">$2</span>');
}

function headersTable(headers) {
    if (!headers || Object.keys(headers).length === 0) {
        return '<div class="binary-tip">（无）</div>';
    }
    return kvTable(Object.entries(headers));
}

function kvTable(pairs) {
    let html = '<table class="kv-table">';
    pairs.forEach(([key, value]) => {
        html += `<tr><td class="kv-key">${escapeHtml(String(key))}</td><td class="kv-value">${escapeHtml(String(value))}</td></tr>`;
    });
    return html + '</table>';
}

/* ---------- 工具函数 ---------- */

function formatTime(startTime) {
    if (!startTime) {
        return '-';
    }
    // startTime 形如 2026-11-12 10:00:00.123，仅展示时分秒
    const match = startTime.match(/\d{2}:\d{2}:\d{2}/);
    return match ? match[0] : startTime;
}

function formatSize(bytes) {
    if (bytes == null || bytes === 0) {
        return '-';
    }
    if (bytes < 1024) {
        return bytes + 'B';
    }
    if (bytes < 1024 * 1024) {
        return (bytes / 1024).toFixed(1) + 'KB';
    }
    return (bytes / 1024 / 1024).toFixed(2) + 'MB';
}

function statusClass(status, error) {
    if (error) {
        return 's-err';
    }
    if (status >= 200 && status < 300) {
        return 's-ok';
    }
    if (status >= 400) {
        return 's-bad';
    }
    return '';
}

function escapeHtml(text) {
    if (text == null) {
        return '';
    }
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function refreshStat() {
    statTotal.textContent = `共 ${rowEntries.size} 条`;
}

/* ---------- 初始化：拉取存量流量 + 建立 WS ---------- */

async function loadExisting() {
    try {
        const resp = await fetch('/api/traffic?page=1');
        if (!resp.ok) {
            return;
        }
        const data = await resp.json();
        // 列表为最新在前，逐条插入（保持顺序）
        data.list.forEach((entry) => addRow(entry));
    } catch (e) {
        console.warn('加载存量流量失败', e);
    }
}

loadExisting().then(connectWs);
