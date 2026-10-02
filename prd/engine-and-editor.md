# 실행 엔진 & 에디터

모듈과 무관하게 유지되는 **뼈대** 문서. 노드 모듈 개별 설계는 `prd/modules/` 참고.

---

## 모듈 추가 방법

`node/NodeModule`을 구현해 `@Component`로 등록하면 끝. 팔레트·설정 패널·실행 엔진에 자동 반영된다.

```java
package com.app.workflowstudio.node.modules;

@Component
@Order(10) // 팔레트 표시 순서 (작을수록 위)
public class DelayModule implements NodeModule {

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo("DELAY", "딜레이", "⏱️", "#95A5A6", "흐름 제어", List.of(
                new NodeParam("seconds", "대기 시간(초)", "number", 1, null)
        ));
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        int sec = ctx.intVal("seconds", 1);
        Thread.sleep(sec * 1000L);          // interrupt 시 InterruptedException → '실행 취소됨'
        return sec + "초 대기 완료";          // 로그 출력 + 다음 노드에서 ${outputVar}로 참조
    }
}
```

### 규칙
- 모듈 코드는 `node/modules/<모듈명>/` 패키지에 모아 둔다 (모듈 전용 컨트롤러·저장소 포함, 예: `node/modules/webhook/`)
- `info().id()`는 전체에서 유일 (중복 시 기동 실패). 저장된 파이프라인 JSON의 `type`과 매칭되므로 **바꾸지 말 것**
- **트리거 노드**는 `NodeTypeInfo`의 마지막 인자 `trigger=true`로 선언 → 캔버스에서 입력 핸들이 없고(왼쪽 둥근 카드),
  외부 이벤트로 시작될 땐 `runner.runTriggered(pipeline, nodeId, mode, data)` 호출. 모듈은 `ctx.triggerData()`로 데이터를 받음
- `category`가 `null`이면 팔레트에 노출되지 않음 (구버전 호환용 숨김 노드 등에 사용)
- 파라미터 `type`: `text` | `textarea` | `select`(options 필수) | `number` | `password` | `image` | `webhookUrls`(표시 전용) | `webhookTemplate`(템플릿 + 키 칩 + 미리보기) | `folder` · `file`(경로 입력 + 📁 찾아보기 선택 창) | `geminiModel`(입력 + 자동 연결 검증) | `memoryInfo`(표시 전용) | `httpOpen`(HTTP 화면 열기 버튼)
- 조건부 표시: `new NodeParam(..., showWhen)` — `"source=파일"`(같을 때) / `"bodyType!=없음"`(다를 때)
- 출력을 다음 노드로 넘기려면 `outputVar` 파라미터를 두는 것을 관례로 함
  (엔진이 `outputVar` 값 또는 `{nodeId}.output` 키로 출력을 저장)
- 실패는 예외로 던질 것 → 로그에 `[TYPE] 오류: 메시지`, 파이프라인 중단
- 오래 걸리는 루프는 `ctx.isCancelled()` 확인 또는 `Thread.sleep`으로 취소에 반응하게 할 것

### 부품 노드와 소켓 (sub-node)
- 소켓을 가진 노드: `NodeTypeInfo.withSockets(..., List<NodeSocket>)` — `NodeSocket{id, label, accepts, required, multiple}`
- 부품 노드: `NodeTypeInfo.part(..., provides)` — 단독 실행되지 않으며 `execute`는 호출되지 않음
- 연결: `PipelineConnection.toHandle` = 소켓 id (부품 연결). 부품 연결은 실행 순서·`${input}`·트리거 도달 범위와 무관
- 소켓을 가진 모듈은 `ctx.parts("소켓id")`로 끼워진 부품 노드 목록(연결 순서)을 받고, `NodeContext.configOf(part, key, def)`로 설정을 읽음
- 에디터 연결 규칙: 부품의 `provides` == 소켓의 `accepts`일 때만 연결, `multiple=false`면 교체
- 예: `node/modules/ai/` (AI 에이전트 + Gemini 모델 · 대화 메모리 · 지시문)

### NodeContext API
| 메서드 | 설명 |
|--------|------|
| `str(key)` / `str(key, def)` | 설정값 + `${변수}` 치환 |
| `raw(key)` / `raw(key, def)` | 설정값 원본 (치환 없음) |
| `intVal(key, def)` | 숫자 설정값 (문자열도 파싱) |
| `resolve(text)` | 임의 텍스트의 `${변수}` 치환 |
| `resolve(text, extra)` | 모듈 전용 변수 맵(`extra`)을 가장 먼저 찾고 치환 (예: 파일 쓰기의 `${date}`) |
| `input()` | 화살표로 연결된 직전 노드(들)의 출력 (여러 개면 줄바꿈으로 합침, 없으면 null) |
| `vars()` | 이전 노드 출력 변수 맵 (모듈이 직접 값을 추가해도 됨) |
| `mode()` | 실행 경로: `MANUAL`(▶ 버튼) / `TEST`(웹훅 테스트 URL) / `PROD`(웹훅 운영 URL) |
| `triggerData()` | 이 노드가 이번 실행을 시작시킨 트리거라면 외부 이벤트 데이터, 아니면 null |
| `node()` | 현재 노드 (id, type, config) |
| `objectMapper()` | JSON 직렬화용 |
| `isCancelled()` | 실행 취소 여부 |

---

## 실행 엔진 (`PipelineRunnerService`)

1. 시작 경로 두 가지 — 별도 스레드에서 실행, `executionId` 즉시 반환
   - **수동** `POST /api/pipelines/{id}/run` → 모든 노드 실행 (mode `MANUAL`)
   - **트리거** `runTriggered(pipeline, triggerNodeId, mode, data)` → 그 트리거에서 **화살표로 도달 가능한 노드만** 실행 (mode `TEST`/`PROD`)
2. 연결(connections) 기준 **위상정렬** 후 순서대로 실행 (연결이 없으면 노드 배열 순서)
3. 노드마다 `RunLog`(running → success/error) 기록, 출력은 변수 맵 + 노드별 출력 맵에 저장
4. 하나라도 실패하면 즉시 중단 (status `error`), 모두 성공하면 `done`
5. 프론트는 `GET /api/executions/{execId}`를 폴링해 상태/로그 표시
6. 취소: `POST /api/executions/{execId}/cancel` → 스레드 interrupt
7. 실행 기록은 메모리에 최대 200개 (초과 시 끝난 실행부터 오래된 순 제거)

> 현재 한계: 분기(if/else)·반복·병렬 없음. 실행 상태는 메모리에만 있어 서버 재시작 시 사라짐.
> 운영(PROD) 실행 이력을 볼 화면은 아직 없음 (API로만 조회 가능).

### 변수 참조 (`${...}`)

```
${input}                 → 화살표로 바로 이어진 직전 노드의 출력 (노드마다 다름)
${input.body.name}       → 직전 출력이 JSON이면 필드 접근
${webhook}               → outputVar=webhook 인 노드의 출력 (어느 노드에서든)
${post.title}            → 값이 JSON이면 필드 접근
${items[0].name}         → 배열 인덱스 + 중첩 필드
${n1.output}             → outputVar가 없는 노드의 출력
```
- `${input}`이 전역 변수보다 우선. 따라서 `input`은 outputVar 이름으로 쓰지 말 것
- 해석하지 못한 참조는 원문(`${...}`) 그대로 남는다
- 구현: `node/Templates.resolve(text, scopes, objectMapper)` — scope(Map) 목록을 순서대로 탐색.
  값이 문자열이면 JSON 파싱 후 경로 탐색, Map/List면 그대로 탐색. 치환 시 객체/배열은 JSON, null은 빈 문자열.
  모듈이 자기만의 변수 범위로 템플릿을 렌더링할 때도 이것을 쓴다 (예: 웹훅 전달 내용 구성)

### 노드 표시 이름
설정에 `name` 파라미터가 있고 값이 있으면 캔버스·설정 패널·하단 로그에 그 이름을 표시한다 (프론트 `nodeDisplayName()`).
여러 개 둘 법한 모듈은 첫 파라미터로 `name`을 두는 것을 관례로 한다.

---

## 에디터 GUI (`frontend/pipeline-editor/`)

| 영역 | 컴포넌트 | 내용 |
|------|----------|------|
| 왼쪽 | `Palette.tsx` | 맨 위 "← 파이프라인 목록", `/api/node-types` 기반 노드 목록, 검색, 그룹 접기. 마우스 드래그로 캔버스에 추가 (`usePaletteDrag.ts`) |
| 가운데 | `PipelineCanvas.tsx`, `PipelineNodeCard.tsx` | React Flow 캔버스. 실행 상태에 따라 노드 테두리(파랑 pulse / 초록 / 빨강) |
| 오른쪽 | `ConfigPanel.tsx` | **설정** 탭: 파라미터 타입별 입력 필드 (image는 업로드 + 📷 화면 캡처, webhookUrls는 테스트/운영 URL + 복사). **결과** 탭: 마지막 실행 출력. 웹훅 노드는 **수신 기록** 탭 추가. 왼쪽 가장자리 드래그로 너비 조절(더블클릭 초기화, localStorage 저장) |
| 아래 | `BottomPanel.tsx` | 실행 로그 (접기/펼치기, 성공/실패 수), 행 클릭 시 노드 선택. 위쪽 가장자리 드래그로 높이 조절(더블클릭 초기화, localStorage 저장, 툴바가 함께 이동). 웹훅으로 시작된 실행은 제목에 "🪝 웹훅 테스트 수신" 표시 |

**웹훅 테스트 실시간 표시**: 저장된 파이프라인에 트리거 노드가 있으면 에디터가 1.5초마다
`GET /api/pipelines/{id}/executions/latest?mode=test`를 확인 → 새 테스트 실행이 생기면 자동으로 따라가며 표시
(에디터를 연 시점 이전의 실행은 무시).
| 떠 있음 | `Toolbar.tsx` | 이름/설명, 저장(변경 있으면 노란색 ●)·실행(웹훅이 있으면 ▶ 수신 대기 / ■ 중지)·내보내기·가져오기·전체삭제. `⠿` 드래그 이동, `⤡` 크기 조절(60~130%), 더블클릭 리셋, localStorage 저장 |
| 출력 | `OutputRenderer.tsx` | JSON이면 트리 뷰어(경로 hover → `${var.path}` 표시, 클릭 복사), 아니면 간이 Markdown |

### 빌드
```bash
cd frontend/pipeline-editor && npm install   # 최초 1회
./gradlew bootJar                            # npm build → src/main/resources/static/pipeline-editor/ → jar
./gradlew bootJar -x npmBuildFrontend        # Java만 재빌드
```
> `npm run build`만 하면 jar에 반영되지 않음. 반드시 `bootJar`까지.

---

## REST API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/node-types` | 등록된 노드 모듈 메타데이터 목록 |
| `GET` / `POST` | `/api/pipelines` | 목록 / 생성 |
| `GET` / `PUT` / `DELETE` | `/api/pipelines/{id}` | 조회 / 수정 / 삭제 |
| `POST` | `/api/pipelines/{id}/run` | 실행 → `{executionId}` |
| `GET` | `/api/executions/{execId}` | 실행 상태 + 로그 (+ mode, startedAt) |
| `GET` | `/api/pipelines/{id}/executions/latest?mode=test` | 최근 실행 요약 (없으면 204) |
| `*` | `/webhook-test/{path}`, `/webhook/{path}` | 웹훅 수신 → 트리거 실행 (`prd/modules/webhook.md`) |
| `GET` / `DELETE` | `/api/webhooks/{nodeId}/history` | 웹훅 수신 기록 조회(최신순) / 삭제 |
| `POST` | `/api/webhooks/{nodeId}/preview` | 전달 내용 구성 미리보기 `{template}` → `{hasSample, sampleAt, keys, result}` |
| `GET` | `/api/app/live` | SSE 상시 연결 — 서버 실행 id 전송 (재시작 감지 → 탭 자동 새로고침, 기동 시 새 탭 열지 않음 판단). **브라우저당 SharedWorker 1개만 연결** (탭마다 열면 동시 연결 6개 제한에 걸려 멈춤). 워커는 열린 탭 목록도 관리 → 상단 "탭 N/M" 배지·종료 패널 (`static/js/app-session.js`) |
| `POST` | `/api/dialog/folder` · `/api/dialog/file` | 서버 PC에 폴더/파일 선택 창 표시 `{initial}` → `{path}` / `{cancelled}` / `{error}` |
| `POST` | `/api/ai/gemini/models` | `{apiKey}` → `{models}` / `{error}` (generateContent 지원 모델만) |
| `POST` | `/api/ai/gemini/verify` | 모델 부품 검증 `{apiKey, model}` → `{ok, keyOk, modelOk, keySource, message, models}` (토큰 사용 없음) |
| `GET` / `DELETE` | `/api/ai/memory/{nodeId}` | 대화 메모리: 대화 ID별 메시지 수 / 모두 지우기 |
| `POST` | `/api/http-client/send` | HTTP 메뉴용 요청 대행 `{method,url,headers:[{key,value}],body}` → `{status,durationMs,size,headers,body}` 또는 `{error}` |
| `POST` | `/api/executions/{execId}/cancel` | 실행 취소 |
| `GET` | `/api/pipelines/{id}/export` | ZIP 내보내기 (`pipeline.json` + 참조 이미지) |
| `POST` | `/api/pipelines/import` | ZIP 가져오기 (항상 새 UUID) |
| `POST` | `/api/images` | 이미지 업로드 → `{imagePath}` |
| `POST` | `/api/capture` | 화면 드래그 캡처 → `{imagePath}` / `{cancelled}` |
| `GET` | `/api/images/{filename}` | 이미지 서빙 |

ZIP 내보내기는 노드 설정 중 `imagePath` 키를 가진 이미지를 함께 묶는다.
