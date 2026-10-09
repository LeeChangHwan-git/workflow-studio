/*
 * 🙋 확인 요청 창 — 모든 화면. 파이프라인이 "확인 요청" 노드에서 멈추면 이 창이 저절로 떠서
 * 내용(마크다운)을 보여 주고 ✅ 승인 / ✏️ 고쳐서 승인 / 🔁 다시(의견) / ❌ 거절 을 받는다.
 * 2초마다 /api/approvals 를 확인, 대기 중이면 상단 메뉴에 배지.
 */
(function () {
    'use strict';
    var pending = [];
    var seen = {};          // 이미 알린 요청 id
    var idx = 0;
    var els = {};
    var editing = false;
    var snoozed = false;    // "나중에" 누르면 새 요청이 오기 전까지 자동으로 안 띄움

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    /** 작은 마크다운 → HTML: 제목, 표, 목록(들여쓰기), 인용, 구분선, 코드, 굵게, 기울임, 링크 */
    function md(src) {
        var lines = String(src || '').replace(/\r\n/g, '\n').split('\n');
        var out = [], i = 0;
        function inline(t) {
            return esc(t)
                .replace(/`([^`]+)`/g, '<code>$1</code>')
                .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
                .replace(/(^|[^*])\*([^*\s][^*]*)\*/g, '$1<i>$2</i>')
                .replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
        }
        while (i < lines.length) {
            var l = lines[i];
            if (/^```/.test(l)) {
                var code = [];
                i++;
                while (i < lines.length && !/^```/.test(lines[i])) code.push(lines[i++]);
                i++;
                out.push('<pre>' + esc(code.join('\n')) + '</pre>');
                continue;
            }
            var h = l.match(/^(#{1,6})\s+(.*)$/);
            if (h) { out.push('<h' + h[1].length + '>' + inline(h[2]) + '</h' + h[1].length + '>'); i++; continue; }
            if (/^\s*(---|\*\*\*|___)\s*$/.test(l)) { out.push('<hr>'); i++; continue; }
            if (/^\s*\|.*\|\s*$/.test(l) && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
                var head = l.trim().replace(/^\||\|$/g, '').split('|');
                i += 2;
                var rows = [];
                while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) rows.push(lines[i++].trim().replace(/^\||\|$/g, '').split('|'));
                out.push('<table><tr>' + head.map(function (c) { return '<th>' + inline(c.trim()) + '</th>'; }).join('') + '</tr>' +
                    rows.map(function (r) { return '<tr>' + r.map(function (c) { return '<td>' + inline(c.trim()) + '</td>'; }).join('') + '</tr>'; }).join('') + '</table>');
                continue;
            }
            if (/^\s*>/.test(l)) {
                var q = [];
                while (i < lines.length && /^\s*>/.test(lines[i])) q.push(lines[i++].replace(/^\s*>\s?/, ''));
                out.push('<blockquote>' + inline(q.join(' ')) + '</blockquote>');
                continue;
            }
            if (/^\s*([-*+]|\d+\.)\s+/.test(l)) {
                var items = [];
                while (i < lines.length && /^\s*([-*+]|\d+\.)\s+/.test(lines[i])) {
                    var m = lines[i].match(/^(\s*)([-*+]|\d+\.)\s+(.*)$/);
                    items.push({ depth: Math.floor(m[1].replace(/\t/g, '  ').length / 2), ordered: /\d/.test(m[2]), text: m[3] });
                    i++;
                }
                var html = '', stack = [];
                items.forEach(function (it) {
                    while (stack.length > it.depth + 1) html += '</' + stack.pop() + '>';
                    while (stack.length < it.depth + 1) { var tag = it.ordered ? 'ol' : 'ul'; stack.push(tag); html += '<' + tag + '>'; }
                    html += '<li>' + inline(it.text) + '</li>';
                });
                while (stack.length) html += '</' + stack.pop() + '>';
                out.push(html);
                continue;
            }
            if (!l.trim()) { i++; continue; }
            var para = [];
            while (i < lines.length && lines[i].trim() && !/^(#{1,6}\s|```|\s*>|\s*([-*+]|\d+\.)\s|\s*\|)/.test(lines[i])) para.push(lines[i++]);
            if (!para.length) para.push(lines[i++]);
            out.push('<p>' + inline(para.join('\n')).replace(/\n/g, '<br>') + '</p>');
        }
        return out.join('');
    }

    function css() {
        var s = document.createElement('style');
        s.textContent = '' +
            '.apv-btn{display:none;align-items:center;gap:6px;height:32px;padding:0 12px;margin-left:8px;border-radius:16px;border:1px solid #DB2777;' +
            'background:rgba(219,39,119,.22);color:#FFD6E8;font-size:13px;font-weight:700;cursor:pointer;font-family:inherit;animation:apv-blink 1.4s ease-in-out infinite}' +
            '.apv-btn.on{display:flex}@keyframes apv-blink{50%{background:rgba(219,39,119,.5)}}' +
            '.apv-back{position:fixed;inset:0;z-index:2000;background:rgba(0,0,0,.55);display:none;align-items:center;justify-content:center;padding:20px}' +
            '.apv-back.on{display:flex}' +
            '.apv{width:min(860px,100%);max-height:100%;display:flex;flex-direction:column;background:#1F1F22;border:1px solid #DB2777;border-radius:12px;' +
            'box-shadow:0 24px 60px rgba(0,0,0,.6);color:#D4D4D4;font-family:"Segoe UI","Malgun Gothic",sans-serif;overflow:hidden}' +
            '.apv-head{padding:12px 16px;border-bottom:1px solid #333;display:flex;align-items:center;gap:10px}' +
            '.apv-head .t{flex:1;min-width:0}.apv-head .k{font-size:11.5px;color:#F9A8D4;font-weight:700}' +
            '.apv-head h2{font-size:17px;color:#fff;margin:2px 0 0}' +
            '.apv-nav{font-size:12px;color:#999;display:flex;gap:4px;align-items:center}' +
            '.apv-nav button,.apv-x{background:#2D2D30;border:1px solid #444;color:#ccc;border-radius:5px;height:26px;min-width:26px;cursor:pointer}' +
            '.apv-body{flex:1;overflow:auto;padding:14px 20px;background:#252526;font-size:14px;line-height:1.7}' +
            '.apv-body h1,.apv-body h2,.apv-body h3,.apv-body h4{color:#fff;margin:14px 0 6px}.apv-body h1{font-size:20px}.apv-body h2{font-size:17px}.apv-body h3{font-size:15px}' +
            '.apv-body p{margin:6px 0}.apv-body ul,.apv-body ol{padding-left:22px;margin:4px 0}.apv-body li{margin:2px 0}' +
            '.apv-body table{border-collapse:collapse;margin:8px 0;font-size:13px;width:100%}.apv-body th,.apv-body td{border:1px solid #444;padding:5px 9px;text-align:left}' +
            '.apv-body th{background:#2D2D30;color:#fff}.apv-body blockquote{border-left:3px solid #DB2777;margin:8px 0;padding:4px 12px;color:#bbb;background:#2A2A2E}' +
            '.apv-body code{background:#151515;padding:1px 5px;border-radius:3px;font-size:12.5px}.apv-body pre{background:#151515;padding:10px;border-radius:6px;overflow:auto}' +
            '.apv-body a{color:#7FB8F7}.apv-body hr{border:none;border-top:1px solid #444;margin:12px 0}' +
            '.apv-edit{width:100%;min-height:300px;height:100%;resize:vertical;background:#1A1A1D;color:#E8E8E8;border:1px solid #DB2777;border-radius:6px;padding:10px;font:13px/1.6 Consolas,"Malgun Gothic",monospace}' +
            '.apv-hist{font-size:12px;color:#F9A8D4;padding:6px 20px;background:#2A1F27;border-bottom:1px solid #3a2a33}' +
            '.apv-foot{border-top:1px solid #333;padding:12px 16px;display:flex;flex-direction:column;gap:8px;background:#1F1F22}' +
            '.apv-fb{width:100%;height:54px;resize:vertical;background:#2A2A2E;color:#E8E8E8;border:1px solid #454545;border-radius:6px;padding:7px 9px;font:13px "Malgun Gothic",sans-serif}' +
            '.apv-row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.apv-row .sp{flex:1}' +
            '.apv-b{height:36px;padding:0 16px;border-radius:7px;border:1px solid #555;background:#2D2D30;color:#ddd;font-size:13.5px;font-weight:700;cursor:pointer}' +
            '.apv-b:hover{filter:brightness(1.15)}.apv-b.ok{background:#16A34A;border-color:#16A34A;color:#fff}' +
            '.apv-b.re{background:#2563EB;border-color:#2563EB;color:#fff}.apv-b.no{background:transparent;border-color:#EF4444;color:#FCA5A5}' +
            '.apv-b.ed.on{border-color:#DB2777;color:#F9A8D4}.apv-note{font-size:11.5px;color:#888}.apv-msg{font-size:12.5px;color:#FCA5A5}';
        document.head.appendChild(s);
    }

    function build() {
        css();
        var nav = document.querySelector('nav');
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'apv-btn';
        btn.title = '확인을 기다리는 파이프라인';
        btn.addEventListener('click', function () { snoozed = false; open(0); });
        if (nav) {
            var anchor = nav.querySelector('.asst-btn') || nav.querySelector('.appsess-badge');
            if (anchor) nav.insertBefore(btn, anchor); else nav.appendChild(btn);
        }
        els.btn = btn;

        var back = document.createElement('div');
        back.className = 'apv-back';
        back.innerHTML =
            '<div class="apv" role="dialog" aria-modal="true">' +
            '<div class="apv-head"><div class="t"><div class="k"></div><h2></h2></div>' +
            '<div class="apv-nav"><button type="button" data-a="prev">‹</button><span class="pos"></span><button type="button" data-a="next">›</button></div>' +
            '<button type="button" class="apv-x" data-a="later" title="나중에 (대기는 유지)">✕</button></div>' +
            '<div class="apv-hist"></div>' +
            '<div class="apv-body"></div>' +
            '<div class="apv-foot">' +
            '<textarea class="apv-fb" placeholder="🔁 다시 요청할 때 의견 (예: 결정 사항을 표로 정리해 줘, 담당자별로 묶어 줘)"></textarea>' +
            '<div class="apv-row">' +
            '<button type="button" class="apv-b ok" data-a="approve">✅ 승인</button>' +
            '<button type="button" class="apv-b ed" data-a="edit">✏️ 직접 고치기</button>' +
            '<button type="button" class="apv-b re" data-a="retry">🔁 다시</button>' +
            '<button type="button" class="apv-b no" data-a="reject">❌ 거절</button>' +
            '<span class="sp"></span><span class="apv-msg"></span><span class="apv-note"></span></div>' +
            '</div></div>';
        document.body.appendChild(back);
        els.back = back;
        els.k = back.querySelector('.k');
        els.title = back.querySelector('h2');
        els.pos = back.querySelector('.pos');
        els.hist = back.querySelector('.apv-hist');
        els.body = back.querySelector('.apv-body');
        els.fb = back.querySelector('.apv-fb');
        els.msg = back.querySelector('.apv-msg');
        els.note = back.querySelector('.apv-note');
        els.retry = back.querySelector('[data-a=retry]');
        els.edit = back.querySelector('[data-a=edit]');
        els.approve = back.querySelector('[data-a=approve]');
        back.addEventListener('click', function (e) {
            var a = e.target.getAttribute && e.target.getAttribute('data-a');
            if (!a) return;
            if (a === 'later') { snoozed = true; close(); }
            else if (a === 'prev') { idx = (idx - 1 + pending.length) % pending.length; render(); }
            else if (a === 'next') { idx = (idx + 1) % pending.length; render(); }
            else if (a === 'edit') { editing = !editing; render(); }
            else decide(a);
        });
        poll();
        setInterval(poll, 2000);
    }

    function poll() {
        fetch('/api/approvals').then(function (r) { return r.json(); }).then(function (r) {
            var before = pending.map(function (p) { return p.id; }).join();
            pending = r.pending || [];
            els.btn.textContent = '🙋 확인 요청 ' + pending.length;
            els.btn.classList.toggle('on', pending.length > 0);
            var fresh = pending.filter(function (p) { return !seen[p.id]; });
            fresh.forEach(function (p) { seen[p.id] = true; });
            if (!pending.length) { close(); return; }
            if (fresh.length) {
                snoozed = false;
                ding();
                if (!isOpen()) open(pending.indexOf(fresh[0]));
                else if (before !== pending.map(function (p) { return p.id; }).join()) render();
            } else if (isOpen() && before !== pending.map(function (p) { return p.id; }).join()) {
                idx = Math.min(idx, pending.length - 1);
                render();
            }
        }).catch(function () {});
    }

    function isOpen() { return els.back.classList.contains('on'); }
    function open(i) {
        if (!pending.length) return;
        idx = Math.max(0, i);
        editing = false;
        els.fb.value = '';
        render();
        els.back.classList.add('on');
    }
    function close() { els.back.classList.remove('on'); }

    function render() {
        var p = pending[idx];
        if (!p) { close(); return; }
        els.k.textContent = '🙋 확인 요청 · ' + (p.pipelineName || '') + ' › ' + (p.nodeName || '') + (p.round > 1 ? ' · ' + p.round + '번째' : '');
        els.title.textContent = p.title || '확인 요청';
        els.pos.textContent = (idx + 1) + ' / ' + pending.length;
        els.back.querySelector('.apv-nav').style.display = pending.length > 1 ? '' : 'none';
        var hist = (p.history || []).map(function (h) { return h.round + '번째 의견: ' + h.feedback; });
        els.hist.style.display = hist.length ? '' : 'none';
        els.hist.textContent = '🔁 지금까지 반영한 의견 — ' + hist.join(' · ');
        if (editing) {
            if (!els.body.querySelector('textarea')) {
                els.body.innerHTML = '<textarea class="apv-edit" spellcheck="false"></textarea>';
                els.body.querySelector('textarea').value = p.content || '';
            }
        } else {
            els.body.innerHTML = md(p.content);
        }
        els.edit.classList.toggle('on', editing);
        els.edit.textContent = editing ? '👁 미리보기' : '✏️ 직접 고치기';
        els.approve.textContent = editing ? '✅ 고친 내용으로 승인' : '✅ 승인';
        els.retry.textContent = p.aiRevise ? '🔁 다시 (AI가 의견대로 고쳐서 다시 보여 줌)' : '🔁 다시 (의견과 함께 "다시" 쪽으로)';
        els.msg.textContent = '';
        els.note.textContent = p.deadline ? '⏰ ' + p.deadline.replace('T', ' ').slice(5, 16) + '까지' : '';
    }

    function decide(kind) {
        var p = pending[idx];
        if (!p) return;
        var body = { decision: kind, feedback: els.fb.value.trim() };
        if (kind === 'retry' && !body.feedback) {
            els.msg.textContent = '어떻게 고칠지 의견을 적어 주세요';
            els.fb.focus();
            return;
        }
        if (kind === 'approve' && editing) {
            var ta = els.body.querySelector('textarea');
            if (ta) body.content = ta.value;
        }
        fetch('/api/approvals/' + encodeURIComponent(p.id), {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        }).then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
            .then(function (res) {
                if (!res.ok) { els.msg.textContent = res.j.error || '처리하지 못했습니다'; return; }
                pending.splice(idx, 1);
                editing = false;
                els.fb.value = '';
                if (!pending.length) { close(); els.btn.classList.remove('on'); } else { idx = Math.min(idx, pending.length - 1); render(); }
            })
            .catch(function () { els.msg.textContent = '서버에 연결할 수 없습니다'; });
    }

    /** 알림음 (짧은 두 음) */
    function ding() {
        try {
            var ctx = new (window.AudioContext || window.webkitAudioContext)();
            [660, 880].forEach(function (f, i) {
                var o = ctx.createOscillator(), g = ctx.createGain();
                o.frequency.value = f;
                g.gain.setValueAtTime(0.0001, ctx.currentTime + i * 0.15);
                g.gain.exponentialRampToValueAtTime(0.15, ctx.currentTime + i * 0.15 + 0.02);
                g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + i * 0.15 + 0.25);
                o.connect(g); g.connect(ctx.destination);
                o.start(ctx.currentTime + i * 0.15); o.stop(ctx.currentTime + i * 0.15 + 0.3);
            });
        } catch (e) { /* 소리 못 내도 무시 */ }
    }

    /** 다른 화면(에디터)에서 열기: workflowApprovals.open(nodeId?) — 그 노드의 요청을 먼저 보여 줌 */
    window.workflowApprovals = {
        open: function (nodeId) {
            fetch('/api/approvals').then(function (r) { return r.json(); }).then(function (r) {
                pending = r.pending || [];
                if (!pending.length) return;
                var i = 0;
                pending.forEach(function (p, k) { if (nodeId && p.nodeId === nodeId && i === 0) i = k; });
                snoozed = false;
                open(i);
            });
        }
    };

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build); else build();
})();
