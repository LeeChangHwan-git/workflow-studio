# 웹훅 (`WEBHOOK`)

## 상태: ✅ 완료 (2026-10-01)

## 목적
외부 시스템(다른 서비스, 스크립트, curl 등)이 **고정 URL로 HTTP 요청**을 보내면 파이프라인을 시작하고,
요청 내용(바디·쿼리·헤더)을 화살표로 연결된 다음 노드에 넘긴다. 첫 번째 **트리거** 모듈.

## URL — 테스트 / 운영 두 가지

| 구분 | URL | 실행 결과 표시 |
|------|-----|----------------|
| 테스트 | `http://localhost:8080/webhook-test/{path}` | 해당 파이프라인 에디터가 열려 있으면 **실시간으로** 캔버스·하단 패널에 표시 |
| 운영 | `http://localhost:8080/webhook/{path}` | 백그라운드 실행 (에디터에 자동 표시 안 됨) |

- 두 URL은 노드 설정에서 **각각 켜짐/꺼짐** (기본: 둘 다 꺼짐). 꺼진 URL은 404
- **테스트 URL = 수신 대기 상태** (2026-10-01 추가): 웹훅이 있는 파이프라인에선 툴바 실행 버튼이
  **▶ 수신 대기 / ■ 중지** 토글로 바뀜. 누르면 모든 웹훅 노드의 `testEnabled`를 켜짐/꺼짐으로 바꾸고 즉시 저장
  - 켜져 있으면 노드가 **초록 테두리 + ● LIVE 테스트** (운영 URL이 켜져 있으면 "운영"도 표시), 버튼도 초록
  - 하단 패널에 "● 수신 대기 중" 표시 → 요청이 오면 실행 과정이 실시간으로 이어서 표시
  - 수신 대기 중이 아닐 때 ▶ 1회 실행은 툴바에서 할 수 없음 (목록 화면의 ▶ 실행은 마지막 데이터로 1회 실행)
- `{path}` = 설정의 **경로**, 비우면 **노드 ID** → 노드를 지우지 않는 한 URL이 바뀌지 않음(고정)
- 경로를 직접 지정하면 노드를 새로 만들어도 같은 URL을 유지할 수 있음
- 설정 패널 맨 위에 두 URL이 ON/OFF 상태와 함께 표시되고 **복사** 버튼 제공
- 그 아래 **🚀 HTTP로 테스트 요청 보내기**: 상단 메뉴 HTTP 화면을 새 탭으로 열어 테스트 URL·메소드·예시 바디를 채워 줌
  (curl 없이 테스트 가능, 에디터 탭에서 실행 과정 실시간 확인)
- **파이프라인을 저장해야** URL이 동작 (서버는 저장된 파이프라인 JSON에서 웹훅 노드를 찾음)

## 파라미터

| name | label | type | 기본값 | 설명 |
|------|-------|------|--------|------|
| `name` | 노드 이름 | text | `""` | 캔버스 카드·설정 패널 헤더·하단 로그에 표시. 비우면 "웹훅" (웹훅 여러 개 구분용) |
| `urls` | 웹훅 URL | webhookUrls | - | 표시 전용 (값 저장 안 함) |
| `path` | 경로 | text | `""` | 비우면 노드 ID. 앞뒤 `/`는 제거 |
| `httpMethod` | HTTP 메소드 | select | `POST` | POST / GET / PUT / PATCH / DELETE / 모두. 다르면 405 |
| `testEnabled` | 테스트 URL (수신 대기) | select | `꺼짐` | 켜짐 / 꺼짐 — 툴바 ▶ 수신 대기 / ■ 중지와 연동 |
| `prodEnabled` | 운영 URL | select | `꺼짐` | 켜짐 / 꺼짐 |
| `outputTemplate` | 전달 내용 구성 | webhookTemplate | `""` | 비우면 요청 전체 JSON, 채우면 템플릿 결과를 전달 (아래 참고) |
| `outputVar` | 출력 변수명 | text | `webhook` | 다음 노드에서 `${webhook...}` 로 참조 |

## 동작
1. `/webhook-test/{path}` 또는 `/webhook/{path}`로 요청 수신 (모든 HTTP 메소드 매핑)
2. 저장된 모든 파이프라인에서 `type=WEBHOOK` 이고 경로가 일치하는 노드 검색
   - 없음 → 404, 2개 이상 → 409 (경로 중복), URL 꺼짐 → 404, 메소드 불일치 → 405
3. 요청을 payload로 변환해 **수신 기록**에 추가 (URL 꺼짐·메소드 불일치로 거부된 요청도 기록)
4. **이 웹훅 노드에서 화살표로 도달 가능한 노드만** 연결 순서대로 실행 (다른 트리거 갈래는 실행 안 함)
5. 즉시 응답 (실행 완료를 기다리지 않음):
   ```json
   { "message": "워크플로우 실행을 시작했습니다", "mode": "test", "pipeline": "이름", "executionId": "..." }
   ```

## 출력 (다음 노드로 전달되는 값)

```json
{
  "receivedAt": "2026-10-01T23:01:47.06",
  "mode": "test",
  "method": "POST",
  "path": "demo",
  "query":   { "x": "1", "tag": ["a", "b"] },
  "headers": { "content-type": "application/json", "...": "..." },
  "body":    { "hello": "world" }
}
```
- `body`: JSON이면 객체, `application/x-www-form-urlencoded`면 맵, 그 외는 원문 문자열, 비어 있으면 `null`
- `query`: 같은 키가 여러 번 오면 배열
- `headers`: 키는 소문자

다음 노드에서 참조:
```
${webhook.body.hello}        → world        (outputVar 기준)
${input.body.hello}          → world        (화살표로 바로 이어진 노드라면)
${webhook.query.x}           → 1
${webhook.headers.user-agent}
```

## 전달 내용 구성 (2026-10-01 추가)

받은 요청에서 필요한 값만 골라 **원하는 형태로 조합**해 다음 노드에 넘긴다. `${...}` 문법은 엔진과 같다.

```
바디 {"a":"사과","b":"바나나","c":3,"items":[{"id":7}]}, 쿼리 ?src=q

${a}==${b}==${c}                → 사과==바나나==3
${body.a} / ${query.src}        → 사과 / q
${items[0].id}                  → 7
{"fruit":"${a}","n":${c}}       → {"fruit":"사과","n":3}   (JSON 모양도 가능, 값 이스케이프는 안 함)
${nope}                         → ${nope}                  (없는 키는 원문 유지)
```

- 참조 순서: 요청 필드(`body`, `query`, `headers`, `method`, `path`, `mode`, `receivedAt`) → **바디 바로 아래 키 축약**(`${a}` = `${body.a}`)
- 값 변환: 문자열은 그대로, 숫자/불리언은 문자, 객체/배열은 JSON, null은 빈 문자열
- 구성하면 노드 출력 = 구성 결과 문자열 (`${webhook}`, 다음 노드의 `${input}`).
  요청 전체는 `${webhook.request.body.a}` 처럼 `{출력변수}.request` 로 계속 참조 가능
- **에디터 지원**: 입력칸 아래에 마지막 수신 요청 기준
  - **키 칩**(바디 키 축약 + `query.*`, `method` 등): 클릭하면 커서 위치에 `${키}` 삽입 (입력 포커스 유지)
  - **미리보기**: 입력할 때마다(0.3초 뒤) + 3초마다 서버에서 렌더링 → 실제 실행과 같은 결과
  - 받은 요청이 없으면 "테스트 요청을 한 번 보내면 키 목록과 결과가 표시됩니다"
- API: `POST /api/webhooks/{nodeId}/preview {template}` → `{hasSample, sampleAt, keys, result}`
- 구현: 공용 치환기 `node/Templates.java` (NodeContext도 이것을 사용하도록 리팩터링),
  `WebhookModule.compose()` / `availableKeys()`, 프론트 `WebhookTemplateField.tsx`

## 수신 기록 (2026-10-01 추가)
웹훅 노드를 선택하면 오른쪽 패널에 **수신 기록** 탭이 생긴다 (탭이 열려 있는 동안 2초마다 갱신).
- 최신순 목록: 시각 · 테스트/운영 · 메소드 · 상태(✓ 완료 / ● 실행 중 / ✕ 실행 오류 / ✕ 거부 404·405)
- 행 클릭 → 요청 전체(JSON 트리, 키 클릭 시 `${webhook...}` 경로 복사) + **실행 결과 보기**(해당 실행을 캔버스/하단 패널에 다시 표시)
- **기록 삭제** 버튼
- 저장: `{storage}/webhooks/{nodeId}.json`, 노드당 최근 **200건** (서버 재시작 후에도 유지)
  - 실행 상태(done/error)는 메모리의 실행 기록에서 가져오므로 서버 재시작 후엔 "✓ 실행됨"으로만 표시
- 노드를 특정할 수 없는 요청(경로 없음 404, 경로 중복 409)은 기록되지 않음
- API: `GET /api/webhooks/{nodeId}/history`, `DELETE /api/webhooks/{nodeId}/history`

## 수동 실행 시
트리거 데이터가 없으므로 수신 기록에서 **마지막으로 실행까지 된 요청을 재사용**한다 (n8n의 pinned data와 유사).
받은 적이 없으면 `{"mode":"manual","note":"아직 받은 웹훅 요청이 없어…","body":null,…}` 를 출력.

## 의존성 / 제약
- 서버가 `server.address=127.0.0.1`(기본)이면 **이 PC에서 보낸 요청만** 받을 수 있음.
  외부 서비스에서 호출하려면 `0.0.0.0`으로 바꾸고 방화벽/포트포워딩 또는 터널(ngrok, cloudflared 등) 필요.
  인증이 없으므로 외부 노출 시 경로를 추측하기 어렵게(기본값인 노드 ID 그대로) 두는 것을 권장
- 인증(토큰/서명 검증), 응답 내용 커스터마이즈("실행 완료 후 결과로 응답")는 미지원 → 향후 검토

## 구현 파일 (`node/modules/webhook/`)
| 파일 | 역할 |
|------|------|
| `WebhookModule.java` | 노드 메타데이터, 실행(트리거 데이터 → JSON 출력), 경로/켜짐/메소드 판정 헬퍼 |
| `WebhookController.java` | `/webhook/{path}`, `/webhook-test/{path}` 수신 → 노드 검색 → `runTriggered` |
| `WebhookHistoryStore.java` | 노드별 수신 기록 파일 저장/조회 (수동 실행 재사용 포함) |
| `frontend/.../WebhookHistory.tsx` | 수신 기록 탭 UI |
| `src/test/.../WebhookModuleTest.java` | 값 전달(`${input}`, `${webhook.body...}`), 도달 불가 노드 미실행, 켜짐/꺼짐, 메소드, 수동 재사용, 수신 기록(거부 포함) |

## 테스트 방법
1. 파이프라인 에디터에서 팔레트 **트리거 → 웹훅**을 캔버스에 추가
2. 툴바 **▶ 수신 대기** 클릭 (자동 저장, 노드 초록색) → 설정 패널의 테스트 URL **복사**
3. **🚀 HTTP로 테스트 요청 보내기** → 새 탭에서 **보내기** (또는 터미널에서 직접, 에디터를 열어 둔 상태):
   ```bash
   curl -X POST -H "Content-Type: application/json" -d "{\"hello\":\"world\"}" http://localhost:8080/webhook-test/<경로>
   ```
4. 에디터 하단에 "🪝 웹훅 테스트 수신" 실행 결과가 뜨고, 웹훅 노드 **결과** 탭에서 JSON 트리 확인
   (키 클릭 → `${webhook.body.hello}` 경로 복사). **수신 기록** 탭에 요청이 쌓이는지 확인
5. **■ 중지** 후 다시 요청 → 404 "테스트 URL이 꺼져 있습니다" + 수신 기록에 "✕ 거부 404"
