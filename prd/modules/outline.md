# 아웃라인 (`OUTLINE`)

## 상태: ✅ 완료 (2026-10-04)

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
| `action` | 작업 | 조회 | | 조회 — 문서 목록 · 검색 — 키워드로 찾기 · 문서 하나 가져오기 · 등록 — 새 문서 · 수정 — 기존 문서 |
| `collection` | 컬렉션 | `""` | 조회·검색·등록 | `outlineCollection` — 이름 또는 ID, 연결 확인으로 받은 목록에서 고르기. 조회·검색은 비우면 전체, 등록은 필수 |
| `query` | 검색어 | `""` | 검색 | `${...}` 가능 |
| `documentId` | 문서 ID 또는 문서 주소 | `""` | 가져오기·수정 | `https://wiki/doc/제목-AbCdEf1234` 주소를 그대로 붙여도 됨 (urlId 추출) |
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

## 한계 / 다음 후보
- 댓글(`comments.*`), 첨부 파일, 하위 문서(parentDocumentId) 등록, 문서 이동·삭제는 미지원
- 회사 서버 주소·키는 노드마다 입력 (공용 "자격 증명" 저장소는 아직 없음)
