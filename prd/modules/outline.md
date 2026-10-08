# 아웃라인 (`OUTLINE`)

## 상태: ✅ 완료 (2026-10-04) · 여러 단계 위치·이동 (2026-10-08)

## 목적
회사 게시판(Outline 위키)과 API로 연동 — 글 **조회 · 검색 · 가져오기 · 등록 · 수정**.
예: 매일 아침 게시판 제목을 받아 AI가 내 담당(FW)에 맞는 글을 골라 주기 / 대충 쓴 메모를 AI가 정리해 게시판에 등록.

## 파라미터

| name | label | 기본값 | 표시 조건 | 설명 |
|------|-------|--------|-----------|------|
| `name` | 노드 이름 | `""` | | |
| `baseUrl` | 아웃라인 주소 | `http://localhost:3000` | | 끝의 `/`, `/api`는 자동 제거 |
| `apiKey` | API 키 | `""` | | 비우면 `OUTLINE_API_KEY` 환경 변수. 발급: 아웃라인 설정 → API 액세스 |
| `connection` | 연결 | - | | `outlineConnection` — 주소·키가 바뀌면 0.8초 뒤 자동 확인 (워크스페이스·사용자·컬렉션 수) |
| `action` | 작업 | 조회 | | 조회 — 문서 목록 · 검색 — 키워드로 찾기 · 문서 하나 가져오기 · 등록 — 새 문서 · 수정 — 기존 문서 · **이동 — 다른 위치로** |
| `collection` | 위치 | `""` | 조회·검색·등록·이동 | `outlineCollection` — **컬렉션 또는 `컬렉션/상위 문서/하위 문서` 경로** (예: `FW팀/회의록/${year}년/${month}월`). 자동완성에 컬렉션과 그 아래 문서 경로가 모두 나옴. 조회·검색은 비우면 전체, 등록·이동은 필수 |
| `query` | 검색어 | `""` | 검색 | `${...}` 가능 |
| `documentId` | 문서 | `""` | 가져오기·수정·이동 | ID · 주소(`https://wiki/doc/제목-AbCdEf1234`) · **경로**(`FW팀/설계 문서/부트로더/v2.3 서명 검증 설계`) |
| `depth` | 범위 | 하위 문서까지 모두 | 조회 | 위치 아래 전체(손자 문서까지) · 바로 아래 문서만 |
| `createParents` | 위치의 상위 문서가 없으면 | 없으면 오류 | 등록·이동 | 없으면 오류(있는 문서 목록 안내) · **없는 상위 문서는 만들기**(빈 문서로 차례로 생성 — 날짜 폴더 자동 생성에 사용) |
| `ifExists` | 같은 제목 문서가 있으면 | 새로 만들기 | 등록 | 같은 위치 바로 아래 기준: 새로 만들기 · **내용 바꾸기**(반복 실행해도 문서가 늘지 않음) · 건너뛰기 |
| `include` | 가져올 내용 | 제목만 | 조회·검색 | 제목만 / 제목 + 내용 |
| `limit` | 최대 개수 | 50 | 조회·검색 | 1~1000 (100개씩 나눠 받음) |
| `recentDays` | 최근 N일 | 0 | 조회 | 0 = 기간 제한 없음 |
| `title` | 제목 | `""` | 등록·수정 | 비우면 등록: 내용 첫 `# 제목` 줄을 제목으로 떼어 씀(없으면 첫 줄) / 수정: 제목 유지 |
| `text` | 내용 | `${input}` | 등록·수정 | 마크다운. AI가 전체를 ```` ```markdown ```` 으로 감싸 줘도 벗겨서 저장 |
| `updateMode` | 수정 방식 | 내용 바꾸기 | 수정 | 내용 바꾸기 / 끝에 덧붙이기 |
| `publish` | 게시 | 바로 게시 | 등록 | 바로 게시 / 초안으로 저장 |
| `outputVar` | 출력 변수명 | `outline` | | |

`showWhen`에 `action=조회|검색` 처럼 `|`로 여러 값을 쓸 수 있게 확장 (에디터 `paramVisible`).

## 출력
조회 / 검색:
```json
{
  "count": 12, "collection": "사내 게시판",
  "titles": "1. [FW] 부트로더 v2.3 배포 (2026-10-04)\n2. ...",
  "documents": [ { "id": "...", "title": "...", "url": "http://.../doc/...", "collection": "사내 게시판",
                   "author": "홍길동", "updatedAt": "2026-10-04T22:38:18", "text": "(제목 + 내용일 때)",
                   "context": "(검색일 때 일치 부분)" } ]
}
```
- AI에게 넘길 때: `${outline.titles}` (번호 목록 텍스트) 또는 `${outline.documents}` (링크 포함 JSON)
- 가져오기: `{id, title, url, collection, author, updatedAt, text}`
- 등록 / 수정: `{action: created|updated, id, title, url}` → 다음 노드에서 `${outline.url}`

## API 사용 (Outline)
모두 `POST {주소}/api/{메소드}`, `Authorization: Bearer <키>`, JSON 바디.
`auth.info`(연결 확인) · `collections.list` · `documents.list`(sort=updatedAt DESC, 100개씩) · `documents.search` ·
`documents.info` · `documents.create` · `documents.update` (덧붙이기는 info로 현재 내용을 받아 합쳐서 저장 — 버전별 `append` 차이 회피)

## 오류 처리
- 401 "API 키가 올바르지 않거나 만료" · 403 권한 · 404 대상 없음 / 주소 확인 · 429 요청 과다 · 연결 불가 · 30초 무응답
- 없는 컬렉션 이름이면 있는 컬렉션 목록을 함께 알려 줌, 문서 ID에 해석 안 된 `${...}`가 남으면 보내기 전에 중단
- 제목을 끝내 못 정하면(내용이 비면) 오류

## 설정 화면 API
| Method | Path | 설명 |
|--------|------|------|
| `POST` | `/api/outline/verify` | `{baseUrl, apiKey}` → `{ok, message, keySource, user, team, collections:[{id,name}]}` |

## 로컬 샘플 서버 (`docker/outline/`)
- `docker compose up -d` → http://localhost:3000 (Outline + Postgres + Redis + Dex)
- 로그인: Dex OIDC의 **mock 커넥터** — 비밀번호 없이 자동 로그인(사용자 Kilgore Trout, 관리자). 로컬 테스트 전용
- 비밀값은 `docker/outline/.env` (git 제외): `SECRET_KEY`, `UTILS_SECRET`, `OIDC_CLIENT_SECRET`, `OUTLINE_API_KEY`(샘플용 발급 키)
- `node seed.js`: "사내 게시판" 컬렉션 + FW/HW/SW/인사/총무 등 샘플 글 12개 (이미 있으면 건너뜀)
- `restart: unless-stopped` → Docker Desktop이 켜지면 같이 켜짐. 완전 삭제는 `docker compose down -v`

## 예시 파이프라인
1. **FW 관심 글 추천 (매일 07:30)**: 타이머(매일 07:30, 켜짐) → 아웃라인 조회(사내 게시판, 제목만, 100개)
   → AI 에이전트(지시문: FW 담당자 관심사 기준 "꼭 읽기 / 참고" 표, 링크 포함) → 파일 쓰기(`바탕화면\workflow-studio\FW추천_${date}.md`, 덮어쓰기)
2. **메모 → 아웃라인 게시글 등록**: 웹훅(`/webhook-test/outline-memo`, body `{"text":"메모"}`) → AI 에이전트(지시문: `# [분류] 제목` + 요약·내용·할 일)
   → 아웃라인 등록(사내 게시판, 제목 비움 → AI가 쓴 첫 줄 제목 사용, 바로 게시)

## 테스트 (`OutlineModuleTest`, 가짜 Outline 서버)
- 조회: 컬렉션 이름 → ID, 제목 목록·URL·작성자, 최근 N일 필터, 내용 포함
- 검색: `${input}` 검색어, 강조 태그 제거 / 등록: 코드블록 벗기기 + 첫 `#` 줄 제목 분리 + 초안
- 수정: 문서 주소로 찾아 끝에 덧붙이기, 제목 유지 / 401·없는 컬렉션 메시지, 연결 확인 API
- 실제 Outline(도커)에서 두 예시 파이프라인 끝까지 실행 확인 (게시판 12개 중 8개 추천 md 저장, 메모 → `[FW] …` 글 게시)

## 함께 고친 것
- `HttpSender`: 평문 `http://` 요청을 **HTTP/1.1로 고정**. Java HttpClient 기본값(HTTP/2)이 보내는 h2c 업그레이드 헤더를
  Outline(Node.js)이 400 빈 응답으로 거절함 → HTTP 요청 노드·HTTP 메뉴도 같은 서버에서 실패하던 문제. https는 그대로 HTTP/2 협상

## 여러 단계 위치 (2026-10-08)
아웃라인 컬렉션은 단계가 없고, **컬렉션 안에서 문서 아래 문서**로 단계를 만든다 → 위치를 `컬렉션/문서/문서…` 경로로 지정.
- 해석(`OutlineClient.resolve`): 컬렉션 이름에 `/`가 있어도 가장 긴 이름부터 맞춤(첫 조각이 UUID 면 ID), 이어서 `collections.documents` 문서 트리를
  제목으로 따라 내려감(정확히 → 대소문자 무시). 없으면 오류(있는 문서 안내) 또는 빈 문서를 만들며 내려감(`createdParents` 에 기록)
- 조회: 위치 아래 트리를 펼쳐(하위 전체/바로 아래) `documents.list`(컬렉션, 수정 순) 결과에서 그 문서만 남김 → 각 문서에 `path`·`depth`,
  `titles` 는 **조회 위치 기준 상대 경로**("2026년/10월/회의 A")
- 검색: 위치가 문서면 그 아래(하위 전체) 결과만
- 등록: `parentDocumentId` 로 상위 문서 아래에, 출력 `{action: created|replaced|skipped, path, location, createdParents[]}`
- 이동: `documents.move {id, collectionId, parentDocumentId}` — 다른 컬렉션으로도, 출력에 `from`(원래 컬렉션)
- 기본 변수 `${year}` `${month}` `${day}` 추가 (`FW팀/회의록/${year}년/${month}월`)
- **요청 과다(429)**: `Retry-After`(없으면 3·6·12초)만큼 쉬고 최대 3번 다시 시도 — 문서를 한꺼번에 만들 때 아웃라인이 막는 경우 대비

### 샘플 구조 (`docker/outline/seed-tree.js`, 반복 실행 안전)
```
FW팀 ─ 회의록 ─ 2026년 ─ 09월 ─ FW 주간 회의 (09-22), (09-29)
     │                 └ 10월 ─ FW 주간 회의 (10-05)
     ├ 설계 문서 ─ 부트로더 ─ v2.3 서명 검증 설계, v2.2 OTA 구조
     │          └ Wi-Fi ─ 재연결 로직
     └ 릴리스 노트 ─ 0.9.7, 0.9.8
HW팀 ─ 회로 변경 ─ Rev.C ─ GPIO 맵 변경, PMIC 교체
                └ Rev.B ─ 전원부 수정 (본문에 "GPIO" 포함 → 범위 검색 확인용)
공지사항 ─ 2026년 ─ 보안 점검 안내, 사내 교육 신청 안내
보관함 (이동 대상, 비어 있음)
```

### 예시 파이프라인 "아웃라인 위치 제어 테스트" (▶ 실행, 반복 실행해도 같은 결과)
① 등록 `FW팀/회의록/${year}년/${month}월` (상위 자동 생성, 같은 제목이면 내용 바꾸기) → ② 조회 `FW팀/회의록` 하위 전체
→ ③ 조회 `FW팀` 바로 아래만 → ④ 검색 `HW팀/회로 변경/Rev.C` 안에서 "GPIO" → ⑤ 경로로 가져오기 `FW팀/설계 문서/부트로더/v2.3 서명 검증 설계`
→ ⑥ 이동 `FW팀/릴리스 노트/0.9.7` → `보관함/FW/릴리스 노트`(상위 자동 생성) → ⑦ 원래 자리로 되돌리기 → ⑧ 결과 md 보고서
- 실측(2회): 1회차 created + 상위 2개 생성, 2회차 replaced · 하위 전체 7개 · 바로 아래 3개 · Rev.C 범위 검색 1개(Rev.B 의 "GPIO" 문서는 제외) · 이동/되돌리기 정상

## 한계 / 다음 후보
- 댓글(`comments.*`), 첨부 파일, 문서 삭제·보관(archive)은 미지원
- 같은 위치에 같은 제목 문서가 여러 개면 경로로는 첫 번째를 고름 (ID·주소로 지정하면 정확)
- 회사 서버 주소·키는 노드에 넣거나 ⚙ 설정의 공용 키 사용
