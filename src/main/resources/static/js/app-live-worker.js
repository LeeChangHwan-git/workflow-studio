// 앱 화면(탭)들이 공유하는 워커 — 브라우저당 1개 (app-session.js 가 /js/app-live-worker.js?v=N 으로 접속).
// 1) 서버 상시 연결(/api/app/live)을 탭 대신 1개만 유지 → 서버 실행 id(instance)를 모든 탭에 전달
//    (탭마다 연결하면 브라우저의 "같은 주소 동시 연결 6개" 제한에 걸려 화면이 멈춤)
// 2) 열린 앱 탭 목록(세션) 관리 — 목록 공유, 최대 개수 초과 경고, 선택 종료 요청 전달
//
// 메시지 (탭 → 워커): hello{session,max} · update{fields} · active · alive · set-max{max} · close{ids} · bye
// 메시지 (워커 → 탭): instance{id} · sessions{list,max} · over-limit · close
// 프로토콜을 바꾸면 app-session.js 의 WORKER_URL 버전(?v=)을 올릴 것 (이미 떠 있는 예전 워커와 섞이지 않도록)

const STALE_MS = 150_000; // 이 시간 동안 alive 가 없으면 죽은 탭으로 보고 목록에서 제거 (숨은 탭 타이머는 1분 단위로 느려짐)

const ports = new Map(); // MessagePort → session (hello 전엔 null)
let source = null;
let instance = null;
let max = 3;

function sessions() {
    return [...ports.values()].filter(Boolean).sort((a, b) => a.openedAt - b.openedAt);
}

function broadcast() {
    const list = sessions();
    for (const port of ports.keys()) port.postMessage({ type: 'sessions', list, max });
}

function ensureConnected() {
    if (source) return;
    source = new EventSource('/api/app/live');
    source.addEventListener('instance', (e) => {
        instance = e.data;
        for (const port of ports.keys()) port.postMessage({ type: 'instance', id: instance });
    });
}

function handle(port, msg) {
    const now = Date.now();
    const s = ports.get(port);
    switch (msg.type) {
        case 'hello':
            ports.set(port, { ...msg.session, lastActive: now, lastSeen: now });
            if (msg.max) max = msg.max;
            broadcast();
            if (sessions().length > max) port.postMessage({ type: 'over-limit' });
            break;
        case 'update':
            if (s) { Object.assign(s, msg.fields, { lastSeen: now }); broadcast(); }
            break;
        case 'active':
            if (s) { s.lastActive = now; s.lastSeen = now; broadcast(); }
            break;
        case 'alive':
            if (s) s.lastSeen = now;
            break;
        case 'set-max':
            max = msg.max;
            broadcast();
            if (sessions().length > max) port.postMessage({ type: 'over-limit' });
            break;
        case 'close':
            for (const [p, sess] of ports) {
                if (sess && msg.ids.includes(sess.id)) p.postMessage({ type: 'close' });
            }
            break;
        case 'bye':
            ports.delete(port);
            broadcast();
            break;
    }
}

onconnect = (e) => {
    const port = e.ports[0];
    ports.set(port, null);
    port.onmessage = (m) => handle(port, m.data);
    port.start();
    ensureConnected();
    if (instance) port.postMessage({ type: 'instance', id: instance });
};

// 비정상 종료(브라우저 강제 종료 등)로 bye 없이 사라진 탭 정리
setInterval(() => {
    const now = Date.now();
    let removed = false;
    for (const [port, s] of ports) {
        if (s && now - s.lastSeen > STALE_MS) { ports.delete(port); removed = true; }
    }
    if (removed) broadcast();
}, 30_000);
