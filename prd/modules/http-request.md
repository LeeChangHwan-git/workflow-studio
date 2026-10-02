# HTTP 요청 (`HTTP_REQUEST`)

## 상태: ✅ 완료 (2026-10-03)

## 목적
REST API를 호출하는 노드. 상단 **HTTP** 메뉴(간이 HTTP 클라이언트)와 같은 기능을 파이프라인 안에서 쓴다.
앞 노드 값으로 요청을 만들고(`${...}`), 응답을 다음 노드로 넘긴다.

## 파라미터

| name | label | type | 기본값 | 설명 |
|------|-------|------|--------|------|
| `name` | 노드 이름 | text | `""` | |
| `method` | 메소드 | select | GET | GET · POST · PUT · PATCH · DELETE · HEAD · OPTIONS |
| `url` | URL | text | `https://` | `${...}` 사용 가능. http/https만, 해석 안 된 `${`가 남으면 오류 |
| `headers` | 헤더 | textarea | `""` | 한 줄에 하나씩 `이름: 값`, `#`으로 시작하는 줄은 주석. `${...}` 사용 가능 |
| `bodyType` | 바디 | select | 없음 | 없음 · JSON · Text · Form (a=1&b=2) |
| `body` | 바디 내용 | textarea | `""` | 바디가 "없음"이 아닐 때만 표시 (`showWhen: bodyType!=없음`) |
| `timeoutSec` | 제한 시간(초) | number | 30 | 1~600 |
| `failOnError` | 실패 응답 처리 | select | 예 | 예: 4xx/5xx면 오류로 중단(응답 앞부분 300자 표시) · 아니오: 상태 코드와 상관없이 다음으로 |
| `httpOpen` | 테스트 | httpOpen | - | **🌐 HTTP 화면에서 이 요청 열기** — 현재 설정(메소드·URL·헤더·바디)을 새 탭의 HTTP 화면에 채움 |
| `outputVar` | 출력 변수명 | text | `http` | |

- Content-Type 헤더가 없으면 바디 종류에 맞춰 자동 추가 (JSON → `application/json` 등)
- JSON 바디는 보내기 전에 검사 → `${...}`로 넣은 값에 따옴표·줄바꿈이 있어 깨지면 "올바른 JSON이 아닙니다" 오류
- Host·Content-Length 등 자동 헤더는 무시

## 출력
```json
{
  "status": 201,
  "ok": true,                // 2xx 여부
  "durationMs": 18,
  "headers": { "content-type": "application/json" },
  "body": { "id": 42 }       // JSON이면 객체, 아니면 문자열 (2MB까지, 넘으면 "truncated": true)
}
```
다음 노드에서 `${http.body.id}`, `${http.status}`, 바로 다음 노드라면 `${input.body.id}`.

## 구현
| 파일 | 역할 |
|------|------|
| `node/modules/http/HttpRequestModule.java` | 노드 메타데이터·실행, 헤더 파싱 |
| `service/HttpSender.java` | **공용 요청 전송** (HTTP 메뉴 `HttpClientController`와 함께 사용하도록 분리) |
| 프론트 `ConfigPanel.tsx` `HttpOpenField` | HTTP 화면 열기 버튼 (`/http?from=node&method&url&btype&body&headers`) |
| `templates/http/index.html` | `headers` 쿼리(`이름: 값` 줄) 받아 헤더 행 채움, `from=node` 안내 배너 |

## 테스트 (`HttpRequestModuleTest`, 가짜 API 서버)
- 웹훅 값으로 URL·헤더·JSON 바디를 만들어 POST → 가짜 서버가 받은 메소드·헤더·Content-Type·바디 확인, JSON 응답이 객체로 전달
- 500 응답: "예"면 오류로 중단(응답 내용 포함), "아니오"면 status 500으로 다음 진행
- 헤더 형식 오류, `${...}` 값 때문에 깨진 JSON 바디는 보내기 전에 오류
