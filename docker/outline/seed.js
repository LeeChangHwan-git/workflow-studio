// 샘플 게시판 데이터 넣기: node seed.js  (OUTLINE_API_KEY는 .env에서 읽음)
const fs = require('fs');
const key = fs.readFileSync(__dirname + '/.env', 'utf8').match(/OUTLINE_API_KEY=(\S+)/)[1];
const call = (m, b) => fetch('http://localhost:3000/api/' + m, {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
  body: JSON.stringify(b),
}).then(r => r.json()).then(j => { if (!j.ok && j.ok !== undefined) throw new Error(m + ': ' + j.message); return j; });

const posts = [
  ['[FW] STM32 부트로더 v2.3 배포 안내', 'OTA 업데이트 시 서명 검증 단계가 추가되었습니다. 기존 v2.2 장비는 10월 중 순차 업데이트 예정입니다.\n\n- 변경: ECDSA P-256 서명 검증\n- 영향: 펌웨어 이미지 헤더 포맷 변경'],
  ['[FW] RTOS 태스크 스택 오버플로 재현 사례 공유', 'FreeRTOS 기반 센서 보드에서 BLE 태스크 스택 오버플로가 간헐적으로 발생했습니다. configCHECK_FOR_STACK_OVERFLOW=2 로 원인을 잡은 과정을 정리합니다.'],
  ['[HW] 신규 메인보드 Rev.C 회로도 리뷰 일정', '10/8(수) 14시 3층 회의실. 전원부 변경사항(PMIC 교체) 위주로 검토합니다. FW팀은 GPIO 맵 변경 확인 부탁드립니다.'],
  ['[SW] 사내 앱 3.4 릴리스 노트', '다크 모드, 알림 묶음 기능 추가. 장비 연결 화면 개선.'],
  ['[인사] 10월 사내 교육 신청 안내', '임베디드 리눅스 기초, 리더십, 보안 교육이 열립니다. 10/10까지 신청.'],
  ['[FW] 전력 소모 측정 자동화 스크립트 요청', '슬립 모드 전류 측정을 매 빌드마다 자동으로 돌리고 싶습니다. Power Monitor 연동 경험 있으신 분 연락 부탁드립니다.'],
  ['[총무] 주차장 도색 공사 안내', '10/11(토) 지하 2층 주차장 이용 불가.'],
  ['[품질] 9월 필드 불량 분석 결과', '불량 1위는 Wi-Fi 재연결 실패(펌웨어 0.9.7). 2위는 배터리 커넥터 접촉 불량. FW팀 재연결 로직 검토 요청.'],
  ['[SW] 백엔드 API 인증 방식 변경 예고', '11월부터 장비 토큰이 JWT로 바뀝니다. 장비 쪽(FW)도 토큰 갱신 로직 대응이 필요합니다.'],
  ['[보안] 디버그 포트(JTAG/SWD) 잠금 정책', '양산 펌웨어는 RDP Level 1 이상 필수. 예외 신청은 보안팀 승인 필요.'],
  ['[마케팅] 신제품 런칭 행사 자원봉사자 모집', '11/2 코엑스. 부스 운영 인원 6명 모집합니다.'],
  ['[FW] 코드 리뷰 가이드 개정 (MISRA-C 체크 추가)', 'PR 머지 전 정적 분석(cppcheck + MISRA 룰셋) 통과가 필수가 됩니다.'],
];

(async () => {
  const cols = await call('collections.list', { limit: 100 });
  let col = cols.data.find(c => c.name === '사내 게시판');
  if (!col) col = (await call('collections.create', { name: '사내 게시판', description: '사내 공지·기술 공유 게시판 (샘플)' })).data;
  const existing = await call('documents.list', { collectionId: col.id, limit: 100 });
  const have = new Set(existing.data.map(d => d.title));
  let n = 0;
  for (const [title, text] of posts) {
    if (have.has(title)) continue;
    await call('documents.create', { collectionId: col.id, title, text, publish: true });
    n++;
  }
  console.log(`컬렉션 '${col.name}' (${col.id}) — 새 글 ${n}개, 전체 ${have.size + n}개`);
})().catch(e => { console.error(e.message); process.exit(1); });
