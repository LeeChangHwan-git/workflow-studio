// 여러 단계(컬렉션 → 문서 → 하위 문서) 샘플 만들기: node seed-tree.js
// 이미 있는 컬렉션·문서는 그대로 두고 없는 것만 만든다 (여러 번 실행해도 됨). OUTLINE_API_KEY 는 .env 에서 읽음.
const fs = require('fs');
const key = fs.readFileSync(__dirname + '/.env', 'utf8').match(/OUTLINE_API_KEY=(\S+)/)[1];
const call = (m, b) => fetch('http://localhost:3000/api/' + m, {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
  body: JSON.stringify(b),
}).then(r => r.json()).then(j => { if (j.ok === false) throw new Error(m + ': ' + j.message); return j; });

// { 컬렉션: { 문서: { 하위 문서: … } } } — 값이 문자열이면 그 문서의 본문(하위 없음)
const TREE = {
  'FW팀': {
    '회의록': {
      '2026년': {
        '09월': {
          'FW 주간 회의 (09-22)': '- 0.9.7 필드 불량 1차 분석\n- 빌드 서버 디스크 증설 요청',
          'FW 주간 회의 (09-29)': '- DHCP 응답 지연 확인\n- v2.3 서명 검증 설계 리뷰',
        },
        '10월': {
          'FW 주간 회의 (10-05)': '- DHCP 타임아웃 15초 + 재시도 3회\n- v2.3 배포는 QA 회귀 후',
        },
      },
    },
    '설계 문서': {
      '부트로더': {
        'v2.3 서명 검증 설계': '## 개요\nECDSA P-256 서명으로 OTA 이미지를 검증한다.\n\n## 실패 시\n이전 이미지로 롤백.',
        'v2.2 OTA 구조': 'A/B 파티션 구조, 다운로드 후 CRC 확인.',
      },
      'Wi-Fi': {
        '재연결 로직': 'DHCP 15초 대기, 재시도 3회, 대기 중 워치독 갱신.',
      },
    },
    '릴리스 노트': {
      '0.9.7': '- 로그 수집 기능\n- 알려진 문제: Wi-Fi 재연결 실패',
      '0.9.8': '- DHCP 재시도 보강',
    },
  },
  'HW팀': {
    '회로 변경': {
      'Rev.C': {
        'GPIO 맵 변경': 'LED 핀 PA5 → PB3, 버튼 핀 PC13 유지.',
        'PMIC 교체': '슬립 전류 목표 50uA.',
      },
      'Rev.B': {
        '전원부 수정': '입력 커패시터 용량 증설 (GPIO 영향 없음).',
      },
    },
  },
  '공지사항': {
    '2026년': {
      '보안 점검 안내': '양산 FW 는 RDP Level 1 필수.',
      '사내 교육 신청 안내': '10/10까지 신청.',
    },
  },
  '보관함': {},
};

async function ensureDocs(colId, parentId, nodes, kids, depth) {
  for (const [title, value] of Object.entries(nodes)) {
    let found = kids.find(k => k.title === title);
    if (!found) {
      const body = { collectionId: colId, title, text: typeof value === 'string' ? value : '', publish: true };
      if (parentId) body.parentDocumentId = parentId;
      const d = (await call('documents.create', body)).data;
      found = { id: d.id, title, children: [] };
      console.log('  '.repeat(depth) + '+ ' + title);
    } else {
      console.log('  '.repeat(depth) + '= ' + title);
    }
    if (typeof value !== 'string') await ensureDocs(colId, found.id, value, found.children || [], depth + 1);
  }
}

(async () => {
  const cols = (await call('collections.list', { limit: 100 })).data;
  for (const [name, docs] of Object.entries(TREE)) {
    let col = cols.find(c => c.name === name);
    if (!col) col = (await call('collections.create', { name, description: '여러 단계 위치 테스트용 (샘플)' })).data;
    console.log((col ? '■ ' : '') + name);
    const tree = (await call('collections.documents', { id: col.id })).data;
    await ensureDocs(col.id, null, docs, tree, 1);
  }
})().catch(e => { console.error(e.message); process.exit(1); });
