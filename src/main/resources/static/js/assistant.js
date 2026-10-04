/*
 * 💬 파이프라인 도우미 — 모든 화면 상단 메뉴의 "도우미" 버튼 → 오른쪽 서랍 대화창.
 * AI가 파이프라인을 만들거나 고치면 'workflow-pipelines-changed' 이벤트 + BroadcastChannel 로 알려
 * 열려 있는 에디터·목록이 다시 불러오게 한다. 대화는 탭별 sessionStorage 에 보관 (화면을 옮겨도 이어짐).
 */
(function () {
    'use strict';
    var STORE = 'assistant-state-v1';
    var EXAMPLES = [
        '매일 아침 8시에 아웃라인 게시판 제목을 받아서 AI가 FW 관련 글만 골라 md 파일로 저장해줘',
        '웹훅으로 질문(q)을 받으면 AI가 답하고 바탕화면에 txt로 저장하는 파이프라인 만들어줘',
        '지금 있는 파이프라인 목록이랑 각각 뭘 하는지 알려줘',
    ];

    var state = load() || { open: false, conversationId: null, messages: [], model: null };
    var busy = false;
    var els = {};

    function load() {
        try { return JSON.parse(sessionStorage.getItem(STORE)); } catch (e) { return null; }
    }
    function save() {
        try {
            state.messages = state.messages.slice(-60);
            sessionStorage.setItem(STORE, JSON.stringify(state));
        } catch (e) { /* 저장 불가 시 무시 */ }
    }

    function currentPipelineId() {
        var root = document.getElementById('root');
        var id = root && root.getAttribute('data-pipeline-id');
        return id || null;
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }
    /** 아주 작은 마크다운: **굵게**, `코드`, - 목록, 줄바꿈 */
    function md(s) {
        return esc(s)
            .replace(/```([\s\S]*?)```/g, function (_, c) { return '<pre>' + c.replace(/^\w*\n/, '') + '</pre>'; })
            .replace(/`([^`\n]+)`/g, '<code>$1</code>')
            .replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>')
            .replace(/^#{1,4} (.+)$/gm, '<b>$1</b>')
            .replace(/^\s*[-*] (.+)$/gm, '• $1')
            .replace(/\n/g, '<br>');
    }

    function injectStyle() {
        var css = '' +
            '.asst-btn{margin-left:auto;display:flex;align-items:center;gap:6px;height:32px;padding:0 13px;border-radius:16px;' +
            'border:1px solid #4E5BF2;background:linear-gradient(135deg,rgba(78,91,242,.25),rgba(14,99,156,.25));color:#E8E8FF;' +
            'font-size:13px;font-weight:600;cursor:pointer;font-family:inherit;transition:background .15s}' +
            '.asst-btn:hover{background:linear-gradient(135deg,rgba(78,91,242,.45),rgba(14,99,156,.45))}' +
            '.asst-btn.on{background:#4E5BF2;color:#fff}' +
            'nav .appsess-badge{margin-left:8px!important}' +
            '.asst{position:fixed;top:56px;right:0;bottom:0;width:440px;max-width:100vw;z-index:400;display:flex;flex-direction:column;' +
            'background:#1F1F22;border-left:1px solid #3E3E42;box-shadow:-12px 0 32px rgba(0,0,0,.45);' +
            'transform:translateX(100%);transition:transform .22s ease;font-family:"Segoe UI",system-ui,sans-serif;color:#D4D4D4}' +
            '.asst.open{transform:none}' +
            '.asst-head{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid #333}' +
            '.asst-title{font-weight:700;font-size:14px;flex:1}' +
            '.asst-model{font-size:10.5px;color:#888;font-weight:400;margin-left:6px}' +
            '.asst-hbtn{background:none;border:1px solid #444;color:#BBB;border-radius:5px;height:26px;padding:0 9px;font-size:12px;cursor:pointer}' +
            '.asst-hbtn:hover{color:#fff;border-color:#777}' +
            '.asst-body{flex:1;overflow-y:auto;padding:14px 12px;display:flex;flex-direction:column;gap:10px}' +
            '.asst-msg{max-width:92%;padding:9px 12px;border-radius:10px;font-size:13px;line-height:1.6;word-break:break-word}' +
            '.asst-msg pre{background:#151515;padding:6px 8px;border-radius:5px;overflow-x:auto;font-size:12px;margin:4px 0}' +
            '.asst-msg code{background:#151515;padding:1px 4px;border-radius:3px;font-size:12px}' +
            '.asst-user{align-self:flex-end;background:#264F78;color:#fff;border-bottom-right-radius:3px;white-space:pre-wrap}' +
            '.asst-ai{align-self:flex-start;background:#2A2A2E;border:1px solid #3A3A3E;border-bottom-left-radius:3px}' +
            '.asst-err{align-self:flex-start;background:#3A1E1E;border:1px solid #5A2D2D;color:#F48771}' +
            '.asst-acts{display:flex;flex-direction:column;gap:6px;margin-top:8px}' +
            '.asst-act{display:flex;align-items:center;gap:8px;padding:7px 9px;border-radius:7px;background:#1B2B3A;border:1px solid #2E4A63;font-size:12.5px}' +
            '.asst-act.del{background:#3A1E1E;border-color:#6B3434}' +
            '.asst-act.done{opacity:.7}' +
            '.asst-act b{flex:1;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}' +
            '.asst-act a,.asst-act button{font-size:12px;border-radius:5px;padding:3px 9px;cursor:pointer;text-decoration:none;border:1px solid #4FC1FF;color:#4FC1FF;background:none}' +
            '.asst-act button.danger{border-color:#F14C4C;background:#F14C4C;color:#fff}' +
            '.asst-steps{margin-top:6px;font-size:11.5px;color:#999}' +
            '.asst-steps summary{cursor:pointer;user-select:none}' +
            '.asst-steps div{padding:2px 0 2px 10px}' +
            '.asst-empty{color:#999;font-size:13px;line-height:1.7;padding:6px 4px}' +
            '.asst-ex{display:block;width:100%;text-align:left;margin-top:8px;padding:8px 10px;border-radius:8px;border:1px solid #3A3A3E;' +
            'background:#26262A;color:#CCC;font-size:12.5px;cursor:pointer;font-family:inherit;line-height:1.5}' +
            '.asst-ex:hover{border-color:#4E5BF2;color:#fff}' +
            '.asst-foot{border-top:1px solid #333;padding:10px 12px;display:flex;gap:8px;align-items:flex-end}' +
            '.asst-input{flex:1;resize:none;min-height:40px;max-height:160px;padding:9px 11px;border-radius:8px;border:1px solid #454545;' +
            'background:#2A2A2E;color:#E8E8E8;font-size:13px;font-family:inherit;line-height:1.5;outline:none}' +
            '.asst-input:focus{border-color:#4E5BF2}' +
            '.asst-send{height:40px;padding:0 16px;border-radius:8px;border:none;background:#4E5BF2;color:#fff;font-weight:700;cursor:pointer}' +
            '.asst-send:disabled{opacity:.5;cursor:default}' +
            '.asst-busy{align-self:flex-start;font-size:12.5px;color:#9CDCFE;display:flex;align-items:center;gap:8px}' +
            '.asst-dot{width:8px;height:8px;border-radius:50%;background:#4E5BF2;animation:asst-pulse 1s ease-in-out infinite}' +
            '@keyframes asst-pulse{0%,100%{opacity:.3}50%{opacity:1}}' +
            '.asst-ctx{font-size:11px;color:#888;padding:0 12px 6px}';
        var st = document.createElement('style');
        st.textContent = css;
        document.head.appendChild(st);
    }

    function build() {
        var nav = document.querySelector('nav');
        if (!nav) return false;
        injectStyle();

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'asst-btn';
        btn.innerHTML = '💬 도우미';
        btn.title = '파이프라인 도우미 — 말로 파이프라인 만들기·수정·삭제';
        var badge = nav.querySelector('.appsess-badge');
        if (badge) nav.insertBefore(btn, badge); else nav.appendChild(btn);
        btn.addEventListener('click', function () { setOpen(!state.open); });
        els.btn = btn;

        var panel = document.createElement('aside');
        panel.className = 'asst';
        panel.innerHTML =
            '<div class="asst-head"><span class="asst-title">💬 파이프라인 도우미<span class="asst-model"></span></span>' +
            '<button type="button" class="asst-hbtn" data-act="new" title="대화 새로 시작">새 대화</button>' +
            '<a class="asst-hbtn" href="/settings#assistant" title="AI 연결 설정" style="display:flex;align-items:center;text-decoration:none">⚙</a>' +
            '<button type="button" class="asst-hbtn" data-act="close" title="닫기">✕</button></div>' +
            '<div class="asst-body"></div>' +
            '<div class="asst-ctx"></div>' +
            '<div class="asst-foot"><textarea class="asst-input" rows="1" placeholder="예: 웹훅으로 받은 글을 AI가 요약해서 파일로 저장해줘  (Enter 보내기 · Shift+Enter 줄바꿈)"></textarea>' +
            '<button type="button" class="asst-send">보내기</button></div>';
        document.body.appendChild(panel);
        els.panel = panel;
        els.body = panel.querySelector('.asst-body');
        els.input = panel.querySelector('.asst-input');
        els.send = panel.querySelector('.asst-send');
        els.model = panel.querySelector('.asst-model');
        els.ctx = panel.querySelector('.asst-ctx');

        panel.querySelector('[data-act=close]').addEventListener('click', function () { setOpen(false); });
        panel.querySelector('[data-act=new]').addEventListener('click', newConversation);
        els.send.addEventListener('click', function () { send(els.input.value); });
        els.input.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(els.input.value); }
        });
        els.input.addEventListener('input', autosize);
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && state.open && document.activeElement === els.input) setOpen(false);
        });

        var pid = currentPipelineId();
        els.ctx.textContent = pid ? '📌 지금 보고 있는 파이프라인을 기준으로 이해합니다 ("이거 고쳐줘")' : '';
        render();
        setOpen(state.open, true);
        return true;
    }

    function autosize() {
        els.input.style.height = 'auto';
        els.input.style.height = Math.min(160, els.input.scrollHeight) + 'px';
    }

    function setOpen(open, initial) {
        state.open = open;
        els.panel.classList.toggle('open', open);
        els.btn.classList.toggle('on', open);
        save();
        if (open && !initial) setTimeout(function () { els.input.focus(); }, 50);
    }

    function newConversation() {
        if (busy) return;
        if (state.conversationId) {
            fetch('/api/assistant/reset', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ conversationId: state.conversationId }) }).catch(function () {});
        }
        state.conversationId = null;
        state.messages = [];
        save();
        render();
    }

    function render() {
        els.model.textContent = state.model ? '· ' + state.model : '';
        var html = '';
        if (!state.messages.length) {
            html += '<div class="asst-empty">말로 요청하면 파이프라인을 <b>실제로</b> 만들고, 고치고, 지웁니다.<br>' +
                '수정·삭제 전 상태는 자동으로 백업되고, 삭제는 여기서 한 번 더 확인합니다.';
            EXAMPLES.forEach(function (ex) { html += '<button type="button" class="asst-ex">' + esc(ex) + '</button>'; });
            html += '</div>';
        }
        state.messages.forEach(function (m, i) {
            if (m.role === 'user') { html += '<div class="asst-msg asst-user">' + esc(m.text) + '</div>'; return; }
            if (m.role === 'error') { html += '<div class="asst-msg asst-err">⚠️ ' + esc(m.text) + '</div>'; return; }
            html += '<div class="asst-msg asst-ai">' + md(m.text);
            if (m.actions && m.actions.length) {
                html += '<div class="asst-acts">';
                m.actions.forEach(function (a, j) { html += actionHtml(a, i, j); });
                html += '</div>';
            }
            if (m.steps && m.steps.length) {
                html += '<details class="asst-steps"><summary>작업 과정 ' + m.steps.length + '단계</summary>';
                m.steps.forEach(function (s) { html += '<div>' + (s.ok ? '✓' : '✗') + ' ' + esc(s.summary) + '</div>'; });
                html += '</details>';
            }
            html += '</div>';
        });
        if (busy) html += '<div class="asst-busy"><span class="asst-dot"></span><span class="asst-busy-text">작업 중…</span></div>';
        els.body.innerHTML = html;
        els.body.scrollTop = els.body.scrollHeight;
        els.send.disabled = busy;

        els.body.querySelectorAll('.asst-ex').forEach(function (b) {
            b.addEventListener('click', function () { els.input.value = b.textContent; autosize(); els.input.focus(); });
        });
        els.body.querySelectorAll('[data-del]').forEach(function (b) {
            b.addEventListener('click', function () {
                var p = b.getAttribute('data-del').split(':');
                confirmDelete(+p[0], +p[1], b.getAttribute('data-confirm') === '1');
            });
        });
    }

    function actionHtml(a, i, j) {
        var name = esc(a.name || a.pipelineId);
        if (a.type === 'created') return '<div class="asst-act">✨ <b>' + name + '</b> 만듦<a href="/pipelines/' + esc(a.pipelineId) + '">열기</a></div>';
        if (a.type === 'updated') return '<div class="asst-act">✏️ <b>' + name + '</b> 수정<a href="/pipelines/' + esc(a.pipelineId) + '">열기</a></div>';
        if (a.type === 'deleted') return '<div class="asst-act del done">🗑 <b>' + name + '</b> 삭제됨 (⚙ 설정 → 백업에서 복원 가능)</div>';
        if (a.type === 'delete_cancelled') return '<div class="asst-act done">↩ <b>' + name + '</b> 삭제 취소</div>';
        if (a.type === 'delete_pending') return '<div class="asst-act del">🗑 <b>' + name + '</b> 삭제할까요?' +
            '<button type="button" class="danger" data-del="' + i + ':' + j + '" data-confirm="1">삭제</button>' +
            '<button type="button" data-del="' + i + ':' + j + '" data-confirm="0">취소</button></div>';
        return '';
    }

    function notifyChanged(items) {
        if (!items.length) return;
        try { window.dispatchEvent(new CustomEvent('workflow-pipelines-changed', { detail: items })); } catch (e) {}
        try { var bc = new BroadcastChannel('workflow-pipelines'); bc.postMessage(items); bc.close(); } catch (e) {}
        // 파이프라인 목록 화면이면 새로고침 (대화는 sessionStorage 에 남아 있음)
        if (location.pathname === '/pipelines') setTimeout(function () { location.reload(); }, 400);
    }

    function confirmDelete(i, j, yes) {
        var a = state.messages[i].actions[j];
        fetch('/api/assistant/delete', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ conversationId: state.conversationId, pipelineId: a.pipelineId, confirm: yes })
        }).then(function (r) { return r.json().then(function (j2) { return { ok: r.ok, body: j2 }; }); })
            .then(function (res) {
                if (!res.ok) { state.messages.push({ role: 'error', text: res.body.error || '삭제 실패' }); }
                else {
                    a.type = yes ? 'deleted' : 'delete_cancelled';
                    if (yes) notifyChanged([{ id: a.pipelineId, type: 'deleted' }]);
                }
                save(); render();
            })
            .catch(function () { state.messages.push({ role: 'error', text: '서버에 연결할 수 없습니다' }); save(); render(); });
    }

    function send(text) {
        text = (text || '').trim();
        if (!text || busy) return;
        busy = true;
        state.messages.push({ role: 'user', text: text });
        els.input.value = '';
        autosize();
        save();
        render();
        var started = Date.now();
        var timer = setInterval(function () {
            var t = els.body.querySelector('.asst-busy-text');
            if (t) t.textContent = '작업 중… ' + Math.round((Date.now() - started) / 1000) + '초';
        }, 1000);

        fetch('/api/assistant/chat', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ conversationId: state.conversationId, message: text, currentPipelineId: currentPipelineId() })
        }).then(function (r) { return r.json(); })
            .then(function (res) {
                if (res.error) {
                    state.messages.push({ role: 'error', text: res.error });
                } else {
                    state.conversationId = res.conversationId;
                    state.model = res.model;
                    state.messages.push({ role: 'ai', text: res.reply, actions: res.actions, steps: res.steps });
                    notifyChanged((res.actions || []).filter(function (a) { return a.type === 'created' || a.type === 'updated'; })
                        .map(function (a) { return { id: a.pipelineId, type: a.type }; }));
                }
            })
            .catch(function () { state.messages.push({ role: 'error', text: '서버에 연결할 수 없습니다' }); })
            .then(function () {
                clearInterval(timer);
                busy = false;
                save();
                render();
            });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build); else build();
})();
