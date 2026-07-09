# 기능2 — 파이프라인 자동화

## 구현 상태: ✅ 완료 (2026-06-24, 업데이트 2026-07-10)

## 한 줄 요약

n8n 스타일 시각적 노드 에디터로 파이프라인을 만들고, 브라우저 열기 → 화면 클릭 → AI 질의 등을 자동 실행하는 기능.

---

## 기획 배경

- `feature-1-macro.md` 에서 설계한 순차 스텝 방식을 발전시켜 **시각적 노드 그래프**로 구현
- 모듈(노드)을 드래그앤드롭으로 캔버스에 배치하고 화살표로 연결
- 파이프라인 파일을 ZIP으로 내보내고 다른 사람과 공유 가능
- AI 질의(Claude API) 노드로 화면 내용을 분석하는 고급 자동화 지원

---

## 사용자 시나리오

1. 상단 네비게이션 → **파이프라인** 클릭
2. **+ 새 파이프라인** 클릭 → 에디터 화면 이동
3. 왼쪽 팔레트에서 노드를 드래그하거나 클릭으로 캔버스에 추가
4. 노드 클릭 → 오른쪽 패널에서 파라미터 설정
   - 이미지 파라미터는 **📁 이미지 업로드**(파일 선택) 또는 **📷 화면 캡처**(앱이 잠깐 숨고 화면을 드래그로 영역 선택) 두 가지 방식으로 지정 가능
5. 노드의 출력 포트(오른쪽 점)를 클릭 → 다음 노드 입력 포트(왼쪽 점) 클릭으로 연결
6. **실행** 버튼 클릭 시 편집기의 현재 상태를 항상 먼저 자동 저장한 뒤 실행 (저장 누락으로 옛 버전이 실행되는 문제 방지)
7. 실행 모달에서 노드별 성공/실패 로그 실시간 확인
8. **내보내기** 버튼으로 `.zip` 파일 다운로드 → 다른 PC에서 **가져오기**로 재사용

---

## 지원 노드 타입

| 타입 | 아이콘 | 색상 | 주요 파라미터 | 설명 |
|------|--------|------|--------------|------|
| `HTTP_REQUEST` | 🔗 | 파랑 | method, url, headers, bodyType, body, outputVar | REST API 호출 |
| `OPEN_URL` | 🌐 | 파랑 | url, browser | 지정 브라우저로 URL 열기 |
| `WAIT_IMAGE` | ⏳ | 주황 | imagePath, timeoutSec | 이미지가 화면에 나타날 때까지 대기 |
| `CLICK_IMAGE` | 🖱️ | 빨강 | imagePath, timeoutSec, offsetX, offsetY | 이미지를 찾아 중심(+오프셋) 클릭 |
| `TYPE_TEXT` | ⌨️ | 초록 | text | 클립보드 경유 텍스트 입력 (한글 지원) |
| `KEY_PRESS` | 🔑 | 보라 | key | 특수키 입력 (ENTER/TAB/ESC 등) |
| `DELAY` | ⏱️ | 회색 | seconds | N초 대기 |
| `SCREENSHOT` | 📸 | 청록 | outputVar | 전체 화면 캡처 후 변수 저장 |
| `CALL_AI` | 🤖 | 보라 | prompt, model, outputVar | Claude API 단순 질의 (도구 없음) |
| `OUTLINE` | 📋 | 하늘 | operation, collectionId, documentId, query, limit, outputVar | Outline Wiki API 조회 (→ 아래 상세) |
| `AI_AGENT` | 🧠 | 진보라 | model, systemPrompt, userInput, tools, maxIter, outputVar | 도구 사용 가능한 Claude 에이전트 |

### OUTLINE 노드 상세

Outline Wiki REST API를 호출해 결과를 Markdown 문자열로 변수에 저장한다.

**사전 설정 (설정 화면)**: `outlineUrl`, `outlineToken` 필드 필수.

| operation | 필수 파라미터 | 반환 형식 |
|-----------|--------------|----------|
| 컬렉션 목록 | — | `## 컬렉션 목록\n- ID: name\n...` |
| 문서 목록 | collectionId | `## 문서 목록 (컬렉션 {id})\n- ID: title\n...` |
| 문서 검색 | query | `## 검색 결과: {query}\n- ID: title\n  > context\n...` |
| 문서 내보내기(MD) | documentId | Outline 문서 원문 Markdown |

- `collectionId`, `documentId`, `query` 모두 `${변수명}` 참조 가능
- `limit` 기본값 50 (컬렉션/문서 목록 적용)
- 결과는 `outputVar` 로 지정한 변수명에 저장 → 다음 `CALL_AI` / `AI_AGENT` 노드에서 `${변수명}` 으로 참조

### AI_AGENT 노드 상세

Claude API를 도구(Tool Use) 포함 모드로 호출해 자율적으로 작업을 수행한다.

| 파라미터 | 설명 |
|---------|------|
| model | 사용할 Claude 모델 |
| systemPrompt | 에이전트 역할 정의 |
| userInput | 사용자 입력 (`${변수}` 참조 가능) |
| tools | `없음` / `HTTP 요청` / `앱 검색 (메모/북마크)` / `전체 (HTTP + 앱 검색)` |
| maxIter | 최대 도구 호출 횟수 (기본 5회, 루프 방지) |
| outputVar | 최종 결과 저장 변수명 |

---

## 변수 참조

노드 간 데이터 전달은 `${변수명}` 문법으로 처리:

```
SCREENSHOT → outputVar: "capture"
CALL_AI    → prompt: "이 화면을 분석해줘: ${capture}"
TYPE_TEXT  → text: "AI 답변: ${aiResult}"
```

`outputVar`를 설정하면 해당 이름으로, 설정하지 않으면 `${nodeId.output}` 으로 참조.

---

## 화면 캡처 (인앱, 드래그 선택)

`WAIT_IMAGE`/`CLICK_IMAGE` 등 이미지 파라미터를 파일 업로드 없이 **앱에서 직접 드래그로 캡처**할 수 있다.

1. 노드 설정 패널에서 **📷 화면 캡처** 클릭
2. 앱 창이 잠깐 숨겨지고, 그 순간의 화면 스냅샷 위에 반투명 오버레이가 뜸
3. 마우스 드래그로 캡처할 영역 선택 (ESC로 취소 가능)
4. 선택 즉시 이미지가 저장되고 미리보기에 반영, 앱 창 복귀

- `ScreenCaptureService`가 `Robot.createScreenCapture()`로 전체 화면을 먼저 찍어 고정한 뒤, 그 위에서 드래그를 받기 때문에 앱 창이 다시 보이기 전에 좌표가 어긋나는 문제가 없음
- `POST /api/capture` → `PipelineService.saveImage()`로 저장 → 기존 이미지 업로드와 동일한 `images/{uuid}.png` 포맷 사용 (내보내기/가져오기 ZIP과도 호환)

---

## 이미지 탐색 성능 개선 (2026-07-08)

**문제**: `CLICK_IMAGE`/`WAIT_IMAGE` 실행 시 화면에서 캡처 이미지를 찾는 데 체감상 오래 걸림 (실측 1920x1080 화면, 120x50 템플릿 기준 검색에만 ~1.1초).

**원인**: `ImageMatcherUtil.findImage`가 화면의 모든 픽셀 위치에서 템플릿과 픽셀 단위 완전 비교를 하는 브루트포스 방식이었고, 1차 필터링용 샘플 포인트를 템플릿 전역에 **균일한 격자**로 뽑다 보니 버튼처럼 배경(단색) 비중이 큰 이미지에서는 화면의 무관한 단색 영역과도 우연히 대부분 일치해버려 조기 탈락이 거의 안 되고 비싼 전체 비교까지 넘어가는 위치가 화면 전체에 걸쳐 많았음.

**개선 내용** (`ImageMatcherUtil.java`):
1. `BufferedImage.getRGB(x,y)` 픽셀 단위 개별 호출 → `getRGB(0,0,w,h,...)` 배열 일괄 읽기로 변경 (호출 오버헤드 제거)
2. 1차 필터링 샘플 포인트를 균일 격자 대신, **템플릿 평균색과 가장 차이가 큰(=배경이 아닌 글자·아이콘 등 특징적인) 픽셀 최대 64개**로 선정 — 화면의 무관한 배경 영역과 우연히 일치할 확률이 낮아 조기 탈락 효율이 크게 향상
3. CPU 코어 수만큼 화면을 가로 밴드로 나눠 **병렬 스캔**, 한 스레드가 찾으면 나머지는 즉시 중단

매칭 정확도(임계값 로직)는 기존과 동일하게 유지. 실측 결과 검색 시간 **~1.1초 → ~8ms** (약 130배)로 단축, 실제 파이프라인 실행(REST API → `CLICK_IMAGE` → 실제 화면 클릭)으로 종단 검증 완료.

---

## 공유 포맷 (ZIP)

```
pipeline-{id}.zip
├── pipeline.json      # 파이프라인 전체 정의 (노드 + 연결 + 설정)
└── images/            # 참조된 이미지 파일
    ├── abc123.png
    └── def456.png
```

- **내보내기**: `/api/pipelines/{id}/export` → ZIP 다운로드
- **가져오기**: ZIP 업로드 → 새 UUID로 저장 (기존 파이프라인과 충돌 없음)

---

## 데이터 구조

```json
// data/pipelines/{uuid}.json
{
  "id": "uuid",
  "name": "사내 인트라넷 로그인",
  "description": "",
  "nodes": [
    {
      "id": "n1",
      "type": "OPEN_URL",
      "posX": 100,
      "posY": 200,
      "config": { "url": "https://intranet.corp.com", "browser": "Edge" }
    },
    {
      "id": "n2",
      "type": "CLICK_IMAGE",
      "posX": 380,
      "posY": 200,
      "config": { "imagePath": "images/login_btn.png", "offsetX": 0, "offsetY": 0 }
    },
    {
      "id": "n3",
      "type": "CALL_AI",
      "posX": 660,
      "posY": 200,
      "config": {
        "prompt": "다음 화면 분석: ${n2.output}",
        "model": "claude-sonnet-4-6",
        "outputVar": "analysis"
      }
    }
  ],
  "connections": [
    { "fromNodeId": "n1", "toNodeId": "n2" },
    { "fromNodeId": "n2", "toNodeId": "n3" }
  ],
  "createdAt": "...",
  "updatedAt": "..."
}
```

---

## 설정 (data/settings.json)

```json
{
  "claudeApiKey": "sk-ant-...",
  "claudeModel": "claude-sonnet-4-6",
  "imageMatchThreshold": 0.90,
  "imageMatchRetryMs": 500,
  "outlineUrl": "http://localhost:3000",
  "outlineToken": "ol_api_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
}
```

- `imageMatchThreshold`: 픽셀 일치율 임계값 (0.1~1.0). 높을수록 엄격.
- `imageMatchRetryMs`: WAIT_IMAGE 재시도 간격 (ms).
- `outlineUrl`: Outline 인스턴스 베이스 URL (끝 슬래시 없어도 됨). 로컬 Docker = `http://localhost:3000`.
- `outlineToken`: Outline API 토큰. 형식 규칙: `ol_api_` + **정확히 38자** 영숫자 (`[\w]{38}`). 토큰 발급 방법은 `feature-3-outline.md` 참조.

---

## REST API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/node-types` | 노드 타입 목록 |
| `GET` | `/api/pipelines` | 전체 파이프라인 목록 |
| `POST` | `/api/pipelines` | 파이프라인 생성 |
| `GET` | `/api/pipelines/{id}` | 파이프라인 조회 |
| `PUT` | `/api/pipelines/{id}` | 파이프라인 수정 |
| `DELETE` | `/api/pipelines/{id}` | 파이프라인 삭제 |
| `POST` | `/api/pipelines/{id}/run` | 실행 → `{executionId}` |
| `GET` | `/api/executions/{execId}` | 실행 상태 + 로그 폴링 |
| `POST` | `/api/executions/{execId}/cancel` | 실행 중단 |
| `GET` | `/api/pipelines/{id}/export` | ZIP 내보내기 |
| `POST` | `/api/pipelines/import` | ZIP 가져오기 |
| `POST` | `/api/images` | 이미지 업로드 |
| `POST` | `/api/capture` | 화면 영역 드래그 캡처 → 이미지 저장 |
| `GET` | `/api/images/{filename}` | 이미지 서빙 |
| `GET` | `/api/settings` | 설정 조회 |
| `PUT` | `/api/settings` | 설정 저장 |

---

## React 프론트엔드 빌드 (파이프라인 에디터)

파이프라인 에디터는 React 18 + TypeScript + Vite로 구현되며, 빌드 결과물을 Spring Boot static 디렉토리에 배치해 서빙한다.

```
frontend/pipeline-editor/        # React 소스
  src/
    App.tsx                      # 메인 컴포넌트 (캔버스, 팔레트, 파라미터 패널)
    api.ts                       # REST API 호출 래퍼
    types.ts                     # Pipeline, Node, Connection 타입
    components/                  # NodeCard, ParamPanel, Arrow 등
    usePaletteDrag.ts            # 드래그앤드롭 Hook
  vite.config.ts                 # outDir → ../../src/main/resources/static/pipeline-editor
```

```
# 최초 빌드 (Node.js 필요)
cd frontend/pipeline-editor
npm install
npm run build

# 이후 Gradle 빌드가 자동으로 npm run build 호출
./gradlew bootJar                      # 전체 빌드 (npm 포함)
./gradlew bootJar -x npmBuildFrontend  # 프론트 빌드 스킵 (빠른 Java만 재빌드)
```

**빌드 결과물 위치**: `src/main/resources/static/pipeline-editor/app.js`, `app.css`

---

## 주요 구현 파일

| 파일 | 역할 |
|------|------|
| `service/NodeTypeRegistry.java` | 11종 노드 타입 정의. 파라미터 스키마 포함. |
| `service/PipelineService.java` | JSON CRUD + ZIP import/export |
| `service/PipelineRunnerService.java` | 실행 엔진. 위상정렬 → 순서 보장. OUTLINE/AI_AGENT 포함. |
| `service/AppSettingsService.java` | `data/settings.json` 읽기/쓰기 (outlineUrl, outlineToken 포함) |
| `service/ImageMatcherUtil.java` | 화면 픽셀 비교 이미지 탐색 (특징 픽셀 샘플링 + 병렬 스캔) |
| `service/ScreenCaptureService.java` | 앱 창 숨김 → 화면 스냅샷 → 드래그 영역 선택 (인앱 캡처) |
| `controller/PipelineApiController.java` | REST API 전체 |
| `controller/OutlineMockController.java` | Outline Mock 서버 (`/outline-mock/api/*`) — 테스트 전용 |
| `model/AppSettings.java` | 설정 모델 (`outlineUrl`, `outlineToken` 필드 포함) |
| `frontend/pipeline-editor/` | React 18 + TS + Vite 에디터 소스 |
| `templates/pipeline/editor.html` | React 앱 진입 HTML (빌드된 app.js 로드) |

---

## 알려진 한계 / 향후 개선

| 항목 | 현재 | 향후 |
|------|------|------|
| 이미지 캡처 | 파일 업로드 + 인앱 드래그 캡처 지원 (완료) | 다중 모니터 환경에서 캡처 대상 모니터 선택 |
| DPI 스케일링 | 오프셋 수동 조정 필요 | `GraphicsDevice` 스케일 자동 보정 |
| 분기(if/else) | 미지원, 직선 파이프라인만 | 조건 노드 추가 예정 |
| 실행 취소 | 스레드 interrupt로 처리 | 더 안정적인 취소 메커니즘 |
| 웹 DOM 자동화 | Robot API (이미지 기반) | Playwright for Java 도입 검토 |
