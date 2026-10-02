// 앱 탭 세션 관리 (모든 화면 공통, layout.html 에서 로드)
// - 공유 워커(app-live-worker.js)에 접속: 서버 재시작 감지 → 새로고침
// - 상단 메뉴 오른쪽 "탭 N/M" 배지 + 패널: 열린 앱 탭 목록, 선택 종료, 최대 개수 설정
// - 최대 개수를 넘겨 새 탭을 열면 그 탭에 경고 패널 자동 표시
(function () {
    if (!window.SharedWorker) return;

    var WORKER_URL = '/js/app-live-worker.js?v=2';
    var CLOSED_URL = '/session-closed.html';
    var MAX_KEY = 'app-max-tabs';
    var DEFAULT_MAX = 3;
    var MAX_CHOICES = [1, 2, 3, 4, 5, 6, 8, 10];

    var me = {
        id: (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now()) + Math.random(),
        title: pageTitle(),
        path: location.pathname + location.search,
        openedAt: Date.now()
    };
    var port = null;
    var knownInstance = null;
    var sessions = [];
    var max = readMax();
    var ended = false;
    var ui = null;            // { badge, panel }
    var warn = false;         // 초과 경고 상태로 패널을 연 경우

    // ── 워커 연결 ────────────────────────────────────────────
    function connect() {
        var worker = new SharedWorker(WORKER_URL);
        port = worker.port;
        port.onmessage = onMessage;
        port.start();
        send({ type: 'hello', session: me, max: max });
    }

    function send(msg) {
        if (port && !ended) port.postMessage(msg);
    }

    function onMessage(e) {
        var m = e.data;
        if (m.type === 'instance') {
            if (knownInstance === null) knownInstance = m.id;
            else if (knownInstance !== m.id) location.reload(); // 서버 재시작
        } else if (m.type === 'sessions') {
            sessions = m.list;
            max = m.max;
            render();
        } else if (m.type === 'over-limit') {
            openPanel(true);
        } else if (m.type === 'close') {
            endSession();
        }
    }

    // 다른 탭(또는 이 탭)의 요청으로 세션 종료: 닫기 시도 → 안 되면 연결 없는 종료 화면으로
    function endSession() {
        if (ended) return;
        send({ type: 'bye' });
        ended = true;
        try { window.close(); } catch (e) { /* 무시 */ }
        setTimeout(function () { location.replace(CLOSED_URL); }, 150);
    }

    function closeTabs(ids) {
        var others = ids.filter(function (id) { return id !== me.id; });
        if (others.length) send({ type: 'close', ids: others });
        if (ids.indexOf(me.id) >= 0) endSession();
    }

    // ── 페이지 상태 알림 ─────────────────────────────────────
    function pageTitle() {
        return (document.title || location.pathname).replace(/\s*—\s*내 메모\s*$/, '');
    }

    function watchTitle() {
        var t = document.querySelector('title');
        if (!t || !window.MutationObserver) return;
        new MutationObserver(function () {
            me.title = pageTitle();
            send({ type: 'update', fields: { title: me.title } });
        }).observe(t, { childList: true, characterData: true, subtree: true });
    }

    function readMax() {
        try {
            var v = parseInt(localStorage.getItem(MAX_KEY), 10);
            return v >= 1 && v <= 10 ? v : DEFAULT_MAX;
        } catch (e) { return DEFAULT_MAX; }
    }

    function setMax(v) {
        try { localStorage.setItem(MAX_KEY, String(v)); } catch (e) { /* 무시 */ }
        send({ type: 'set-max', max: v });
    }

    // ── UI ────────────────────────────────────────────────────
    var CSS =
        '.appsess-badge{margin-left:auto;display:flex;align-items:center;gap:6px;height:30px;padding:0 12px;' +
        'border-radius:15px;border:1px solid #4A4A4A;background:#2D2D30;color:#BBBBBB;font-size:12px;font-weight:600;cursor:pointer;font-family:inherit}' +
        '.appsess-badge:hover{color:#fff;border-color:#6A6A6A}' +
        '.appsess-badge .dot{width:7px;height:7px;border-radius:50%;background:#3FB950}' +
        '.appsess-badge.over{border-color:#F14C4C;color:#F48771;background:#3A1E1E}' +
        '.appsess-badge.over .dot{background:#F14C4C;animation:appsess-blink 1s infinite}' +
        '@keyframes appsess-blink{50%{opacity:.3}}' +
        '.appsess-panel{position:fixed;top:60px;right:16px;width:380px;max-height:70vh;overflow:auto;z-index:10000;' +
        'background:#252526;border:1px solid #454545;border-radius:8px;box-shadow:0 10px 32px rgba(0,0,0,.7);' +
        'color:#D4D4D4;font-family:"Segoe UI",system-ui,sans-serif;font-size:13px}' +
        '.appsess-head{display:flex;align-items:center;gap:8px;padding:12px 14px;border-bottom:1px solid #3C3C3C;font-weight:700}' +
        '.appsess-head .grow{flex:1}' +
        '.appsess-head select{background:#3C3C3C;color:#D4D4D4;border:1px solid #555;border-radius:4px;font-size:12px;padding:2px 4px}' +
        '.appsess-x{background:none;border:none;color:#858585;font-size:15px;cursor:pointer}' +
        '.appsess-x:hover{color:#fff}' +
        '.appsess-warn{margin:10px 14px 0;padding:8px 10px;border-radius:5px;background:#3A1E1E;border:1px solid #5A2D2D;color:#F48771;font-size:12px;line-height:1.5}' +
        '.appsess-list{padding:8px 10px}' +
        '.appsess-row{display:flex;align-items:center;gap:10px;padding:8px;border-radius:5px}' +
        '.appsess-row:hover{background:#2D2D30}' +
        '.appsess-row.me{background:#1E3A5F33}' +
        '.appsess-info{flex:1;min-width:0}' +
        '.appsess-title{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
        '.appsess-meta{font-size:11px;color:#858585;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
        '.appsess-tag{font-size:10px;font-weight:700;color:#4FC1FF;margin-left:6px}' +
        '.appsess-btn{flex-shrink:0;height:26px;padding:0 10px;border-radius:4px;font-size:12px;font-weight:600;cursor:pointer;' +
        'background:#3A1E1E;color:#F48771;border:1px solid #5A2D2D;font-family:inherit}' +
        '.appsess-btn:hover{background:#5A2D2D}' +
        '.appsess-btn.ghost{background:#2D2D30;color:#BBBBBB;border-color:#4A4A4A}' +
        '.appsess-foot{display:flex;gap:8px;justify-content:flex-end;padding:10px 14px;border-top:1px solid #3C3C3C}' +
        '.appsess-hint{padding:0 14px 10px;font-size:11px;color:#6A6A6A;line-height:1.5}';

    function build() {
        var nav = document.querySelector('nav');
        if (!nav) return null;
        var style = document.createElement('style');
        style.textContent = CSS;
        document.head.appendChild(style);

        var badge = document.createElement('button');
        badge.type = 'button';
        badge.className = 'appsess-badge';
        badge.title = '열린 앱 탭 관리';
        badge.onclick = function () { panel.hidden ? openPanel(false) : closePanel(); };
        nav.appendChild(badge);

        var panel = document.createElement('div');
        panel.className = 'appsess-panel';
        panel.hidden = true;
        document.body.appendChild(panel);

        document.addEventListener('mousedown', function (e) {
            if (!panel.hidden && !panel.contains(e.target) && !badge.contains(e.target)) closePanel();
        });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && !panel.hidden) closePanel();
        });
        return { badge: badge, panel: panel };
    }

    function openPanel(asWarning) {
        if (!ui) return;
        warn = asWarning || warn;
        ui.panel.hidden = false;
        render();
    }

    function closePanel() {
        if (!ui) return;
        ui.panel.hidden = true;
        warn = false;
    }

    function render() {
        if (!ui) return;
        var over = sessions.length > max;
        ui.badge.className = 'appsess-badge' + (over ? ' over' : '');
        ui.badge.innerHTML = '<span class="dot"></span>탭 ' + sessions.length + '/' + max;
        if (ui.panel.hidden) return;
        if (!over) warn = false;

        var others = sessions.filter(function (s) { return s.id !== me.id; });
        var html = '';
        html += '<div class="appsess-head"><span class="grow">열린 앱 탭 ' + sessions.length + '개</span>' +
            '최대 <select data-act="max">' + MAX_CHOICES.map(function (v) {
                return '<option value="' + v + '"' + (v === max ? ' selected' : '') + '>' + v + '</option>';
            }).join('') + '</select>' +
            '<button type="button" class="appsess-x" data-act="hide" title="닫기">✕</button></div>';

        if (over) {
            html += '<div class="appsess-warn">⚠️ 열린 탭이 최대(' + max + '개)를 넘었습니다. 종료할 탭을 고르세요' +
                (warn ? ' — 방금 연 이 탭 때문에 초과됐습니다.' : '.') + '</div>';
        }

        html += '<div class="appsess-list">';
        sessions.forEach(function (s) {
            var mine = s.id === me.id;
            html += '<div class="appsess-row' + (mine ? ' me' : '') + '">' +
                '<div class="appsess-info"><div class="appsess-title">' + esc(s.title) +
                (mine ? '<span class="appsess-tag">현재 탭</span>' : '') + '</div>' +
                '<div class="appsess-meta">' + esc(s.path) + ' · ' + (mine ? '열림 ' + clock(s.openedAt) : '마지막 사용 ' + ago(s.lastActive)) + '</div></div>' +
                '<button type="button" class="appsess-btn" data-act="close" data-id="' + esc(s.id) + '">' + (mine ? '이 탭 종료' : '종료') + '</button>' +
                '</div>';
        });
        html += '</div>';

        html += '<div class="appsess-foot">';
        if (over) html += '<button type="button" class="appsess-btn" data-act="trim">오래된 탭부터 ' + (sessions.length - max) + '개 정리</button>';
        if (others.length) html += '<button type="button" class="appsess-btn ghost" data-act="others">다른 탭 모두 종료</button>';
        html += '</div>';
        html += '<div class="appsess-hint">종료한 탭은 닫히거나, 닫을 수 없으면 서버와 연결이 끊긴 "세션 종료" 화면으로 바뀝니다. ' +
            '이 브라우저에서 연 앱 탭만 표시됩니다.</div>';

        ui.panel.innerHTML = html;
        ui.panel.onclick = onPanelClick;
        var sel = ui.panel.querySelector('select[data-act="max"]');
        if (sel) sel.onchange = function () { setMax(parseInt(sel.value, 10)); };
    }

    function onPanelClick(e) {
        var btn = e.target.closest('[data-act]');
        if (!btn) return;
        var act = btn.getAttribute('data-act');
        if (act === 'hide') closePanel();
        else if (act === 'close') closeTabs([btn.getAttribute('data-id')]);
        else if (act === 'others') closeTabs(sessions.filter(function (s) { return s.id !== me.id; }).map(function (s) { return s.id; }));
        else if (act === 'trim') {
            // 현재 탭은 남기고, 가장 오래 안 쓴 탭부터 초과분만큼 종료
            var candidates = sessions.filter(function (s) { return s.id !== me.id; })
                .sort(function (a, b) { return a.lastActive - b.lastActive; });
            closeTabs(candidates.slice(0, sessions.length - max).map(function (s) { return s.id; }));
        }
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }
    function clock(t) {
        var d = new Date(t);
        return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
    }
    function ago(t) {
        var sec = Math.max(0, Math.round((Date.now() - t) / 1000));
        if (sec < 60) return '방금';
        if (sec < 3600) return Math.floor(sec / 60) + '분 전';
        return Math.floor(sec / 3600) + '시간 전';
    }

    // ── 시작 ─────────────────────────────────────────────────
    function start() {
        ui = build();
        watchTitle();
        connect();
        render();
        setInterval(function () { send({ type: 'alive' }); }, 20000);
        setInterval(render, 30000); // "n분 전" 갱신
        window.addEventListener('focus', function () { send({ type: 'active' }); });
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') send({ type: 'active' });
        });
        window.addEventListener('pagehide', function () { send({ type: 'bye' }); });
        // 뒤로/앞으로 가기 캐시에서 복원 → 다시 등록 (그 사이 서버가 재시작됐으면 새로고침)
        window.addEventListener('pageshow', function (e) {
            if (e.persisted) { ended = false; connect(); }
        });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
})();
