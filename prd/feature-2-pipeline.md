# 기능2 — 파이프라인 자동화

## 구현 상태: ✅ 완료 (2026-06-24, 업데이트 2026-07-10, 2026-07-11)

## 한 줄 요약

n8n 스타일 시각적 노드 에디터로 파이프라인을 만들고, HTTP 요청 → AI 질의 → MD 저장, 화면 클릭 → 텍스트 입력 등의 자동화를 실행하는 기능.

---

## 기획 배경

- `feature-1-macro.md` 에서 설계한 순차 스텝 방식을 발전시켜 **시각적 노드 그래프**로 구현
- 모듈(노드)을 드래그앤드롭으로 캔버스에 배치하고 화살표로 연결
- 파이프라인 파일을 ZIP으로 내보내고 다른 사람과 공유 가능
- Claude / Gemini / 커스텀 AI 노드로 데이터 처리, 요약, 선별 자동화 지원

---

## 사용자 시나리오

1. 상단 네비게이션 → **파이프라인** 클릭
2. **+ 새 파이프라인** 클릭 → 에디터 화면 이동
3. 왼쪽 팔레트에서 노드를 드래그하거나 클릭으로 캔버스에 추가
4. 노드 클릭 → 오른쪽 패널에서 파라미터 설정
   - 이미지 파라미터는 **📁 이미지 업로드**(파일 선택) 또는 **📷 화면 캡처**(앱이 잠깐 숨고 화면을 드래그로 영역 선택) 두 가지 방식으로 지정 가능
5. 노드의 출력 포트(오른쪽 점)를 클릭 → 다음 노드 입력 포트(왼쪽 점) 클릭으로 연결
6. **실행** 버튼 클릭 시 편집기의 현재 상태를 항상 먼저 자동 저장한 뒤 실행
7. **하단 패널**에서 노드별 성공/실패 실시간 확인, **캔버스 노드**에 상태 색상 표시
8. 노드 클릭 → 오른쪽 패널 **결과 탭**에서 해당 노드의 상세 출력 조회 및 경로 복사
9. **내보내기** 버튼으로 `.zip` 파일 다운로드 → 다른 PC에서 **가져오기**로 재사용

### 대표 활용 시나리오

| 시나리오 | 연결 흐름 |
|---------|----------|
| API 데이터 요약 | `HTTP_REQUEST` → `CALL_AI` (요약) → `SAVE_MD` |
| Outline 문서 선별 | `OUTLINE_EXPORT` → `CALL_AI` (필요 항목 선별, JSON 출력) → `SAVE_MD` |
| 화면 자동화 | `OPEN_URL` → `WAIT_IMAGE` → `CLICK_IMAGE` → `TYPE_TEXT` |
| AI 에이전트 | `HTTP_REQUEST` → `AI_AGENT` (도구 사용 자율 실행) → `SAVE_MD` |

---

## 지원 노드 타입

### 아웃라인 그룹

| 타입 | 아이콘 | 주요 파라미터 | 설명 |
|------|--------|--------------|------|
| `OUTLINE_COLLECTIONS` | 📋 | limit, outputVar | Outline 컬렉션 목록 조회 |
| `OUTLINE_DOCS` | 📄 | collectionId, limit, outputVar | 특정 컬렉션의 문서 목록 |
| `OUTLINE_SEARCH` | 🔍 | query, limit, outputVar | 키워드로 문서 검색 |
| `OUTLINE_EXPORT` | 📝 | documentId, outputVar | 문서 전체를 Markdown으로 내보내기 |

### AI 그룹

| 타입 | 아이콘 | 주요 파라미터 | 설명 |
|------|--------|--------------|------|
| `CALL_AI` | 🤖 | aiService, apiKey, model, systemPrompt, userInput, outputFormat, outputVar | Claude/Gemini/커스텀 AI 단순 질의 |
| `AI_AGENT` | 🧠 | model, systemPrompt, userInput, tools, maxIter, outputVar | 도구 사용 가능한 Claude 에이전트 |

### 통합 그룹

| 타입 | 아이콘 | 주요 파라미터 | 설명 |
|------|--------|--------------|------|
| `HTTP_REQUEST` | 🔗 | method, url, headers, bodyType, body, outputVar | REST API 호출 |

### 화면 자동화 그룹

| 타입 | 아이콘 | 주요 파라미터 | 설명 |
|------|--------|--------------|------|
| `OPEN_URL` | 🌐 | url, browser | 지정 브라우저로 URL 열기 |
| `WAIT_IMAGE` | ⏳ | imagePath, timeoutSec | 이미지가 화면에 나타날 때까지 대기 |
| `CLICK_IMAGE` | 🖱️ | imagePath, timeoutSec, offsetX, offsetY | 이미지를 찾아 중심(+오프셋) 클릭 |
| `TYPE_TEXT` | ⌨️ | text | 클립보드 경유 텍스트 입력 (한글 지원) |
| `KEY_PRESS` | 🔑 | key | 특수키 입력 (ENTER/TAB/ESC 등) |
| `DELAY` | ⏱️ | seconds | N초 대기 |
| `SCREENSHOT` | 📸 | outputVar | 전체 화면 캡처 후 변수 저장 |

### 파일 출력 그룹

| 타입 | 아이콘 | 주요 파라미터 | 설명 |
|------|--------|--------------|------|
| `SAVE_MD` | 💾 | content, outputDir, saveMode, filename, fileExists, outputVar | 변수 내용을 Markdown 파일로 저장 |

---

## CALL_AI 노드 상세 (2026-07-10 재설계)

멀티 AI 서비스를 지원하는 단순 질의 노드. 이전 노드 출력(`${변수}`)을 받아 처리 후 결과를 저장.

### 파라미터

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `aiService` | select | `Claude (Anthropic)` / `Gemini (Google)` / `커스텀 (OpenAI 호환)` |
| `apiKey` | password | 노드별 API 키. 비워두면 앱 설정값 사용. 보기/숨기기 토글 제공 |
| `model` | text | 모델명 직접 입력. 비워두면 서비스별 기본값 사용 |
| `endpointUrl` | text | 커스텀 서비스 전용. OpenAI 호환 Chat Completions URL |
| `systemPrompt` | textarea | AI 역할·지시사항. `${변수}` 참조 가능 |
| `userInput` | textarea | 실제 입력. `${변수}` 참조 가능 |
| `outputFormat` | select | `자유 텍스트` / `마크다운` / `JSON` — 선택 시 AI에게 형식 지시 자동 추가 |
| `outputVar` | text | 결과를 저장할 변수명 (기본값: `aiResult`) |

### 서비스별 기본 모델

| 서비스 | 비었을 때 사용되는 모델 | 추천 모델 |
|--------|----------------------|----------|
| Claude (Anthropic) | 앱 설정의 `claudeModel` | `claude-sonnet-4-6` |
| Gemini (Google) | `gemini-2.5-flash` (2026-07-11부터, 이전 기본값 `gemini-2.0-flash`는 폐기됨) | `gemini-2.5-flash` |
| 커스텀 | `gpt-4o-mini` | 서버에 따라 다름 |

### Gemini 무료 티어 사용 방법

1. [Google AI Studio](https://aistudio.google.com) 접속 → API Key 발급 (무료)
2. 앱 **설정** → **Gemini AI 설정** → API 키 입력 저장
3. `CALL_AI` 노드 → `aiService`: `Gemini (Google)` 선택, 모델: `gemini-2.5-flash`
4. 또는 노드의 `apiKey` 필드에 직접 입력 (앱 설정 없이도 사용 가능)

> ⚠️ **AI_AGENT 노드는 Gemini를 지원하지 않는다.** `model` select 옵션이 `claude-*`
> 뿐이라 Gemini는 선택 자체가 불가능하다 (Claude 전용, [알려진 한계](#알려진-한계--향후-개선) 참고).
> Gemini를 쓰려면 반드시 `CALL_AI` 노드를 사용할 것.

### ⚠️ Gemini 모델명 폐기(deprecation) 대응 (2026-07-11 확인)

Google이 구형 모델 alias를 주기적으로 폐기한다. 폐기된 모델로 호출하면 다음과 같이 실패한다.

```
[CALL_AI] 오류: 404 Not Found: "{ "error": { "code": 404,
  "message": "This model models/gemini-2.0-flash is no longer available. ...",
  "status": "NOT_FOUND" } }"
```

실제로 2026-07-11 테스트에서 앱의 기존 기본값이었던 `gemini-2.0-flash`가 이 오류로 실패했고,
`gemini-2.5-flash`로 교체 후 정상 동작을 확인했다 (`PipelineRunnerService.callGemini`의
기본 모델 fallback, `NodeTypeRegistry`의 CALL_AI 힌트 텍스트 모두 수정).

이런 오류를 만나면 현재 사용 가능한 모델 목록을 직접 조회해서 최신 모델명으로 교체한다.

```bash
curl "https://generativelanguage.googleapis.com/v1beta/models?key=YOUR_KEY" \
  | grep -o '"name": "models/[a-zA-Z0-9.-]*"'
```

### 커스텀 엔드포인트 (OpenAI 호환)

LM Studio, Ollama, OpenAI, Perplexity 등 OpenAI Chat Completions 형식을 지원하는 모든 서비스에 연결 가능.

```
POST {endpointUrl}
Authorization: Bearer {apiKey}
{
  "model": "...",
  "messages": [
    {"role": "system", "content": "{systemPrompt}"},
    {"role": "user", "content": "{userInput}"}
  ]
}
```

---

## AI_AGENT 노드 상세

Claude API를 도구(Tool Use) 포함 모드로 호출해 자율적으로 작업을 수행한다.

| 파라미터 | 설명 |
|---------|------|
| `model` | 사용할 Claude 모델 |
| `apiKey` | 노드별 API 키 (비워두면 앱 설정 Claude API 키 사용) |
| `systemPrompt` | 에이전트 역할 정의 |
| `userInput` | 사용자 입력 (`${변수}` 참조 가능) |
| `tools` | `없음` / `HTTP 요청` / `앱 검색 (메모/북마크)` / `전체 (HTTP + 앱 검색)` |
| `maxIter` | 최대 도구 호출 횟수 (기본 5회, 루프 방지) |
| `outputVar` | 최종 결과 저장 변수명 |

---

## SAVE_MD 노드 상세

이전 노드의 출력 변수를 받아 Markdown 파일로 저장한다.

| 파라미터 | 설명 |
|---------|------|
| `content` | 저장할 내용 (`${변수}` 참조 가능) |
| `outputDir` | 저장 폴더 경로 (기본값: `~/Desktop`) |
| `saveMode` | `단일 파일` / `섹션별 분할 (## 기준)` / `구분자로 분할 (---)` |
| `filename` | 파일명 패턴. 분할 모드에서 `${heading}`, `${index}` 사용 가능 |
| `fileExists` | 동일 파일명 존재 시: `덮어쓰기` / `뒤에 추가` / `오류 발생` |
| `outputVar` | 저장된 파일 경로 목록이 담길 변수명 |

### saveMode 상세

- **단일 파일**: `content` 전체를 `filename` 하나의 파일로 저장
- **섹션별 분할 (## 기준)**: `## 제목` 단위로 분리 → 각각 파일 저장. `filename`에서 `${heading}` = 섹션 제목, `${index}` = 순번
- **구분자로 분할 (---)**: `---` 줄 기준으로 분리 → 각각 파일 저장

### fileExists 동작

| 설정 | 동작 |
|-----|------|
| 덮어쓰기 (기본) | 기존 파일 내용을 새 내용으로 대체 |
| 뒤에 추가 | 기존 내용 뒤에 `\n\n---\n\n`을 구분자로 붙여 추가. 파일 없으면 새로 생성 |
| 오류 발생 | 파일 이미 있으면 즉시 오류 발생, 파이프라인 중단 |

---

## OUTLINE 노드 상세

Outline Wiki REST API를 호출해 결과를 Markdown 문자열로 변수에 저장한다.

**사전 설정 (설정 화면)**: `outlineUrl`, `outlineToken` 필드 필수.

| 노드 타입 | 필수 파라미터 | 반환 형식 |
|----------|--------------|----------|
| `OUTLINE_COLLECTIONS` | — | `## 컬렉션 목록\n- ID: name\n...` |
| `OUTLINE_DOCS` | collectionId | `## 문서 목록 (컬렉션 {id})\n- ID: title\n...` |
| `OUTLINE_SEARCH` | query | `## 검색 결과: {query}\n- ID: title\n  > context\n...` |
| `OUTLINE_EXPORT` | documentId | Outline 문서 원문 Markdown |

- 모든 ID 파라미터에서 `${변수명}` 참조 가능
- `limit` 기본값 50

---

## 변수 참조 (`${...}` 문법)

노드 간 데이터 전달은 `${변수명}` 문법으로 처리한다.

```
HTTP_REQUEST  → outputVar: "post"
CALL_AI       → userInput: "다음을 요약해줘:\n${post}"
SAVE_MD       → content:  "# 요약\n\n${aiResult}"
```

### 점(.) 표기법으로 JSON 필드 접근

이전 노드 출력이 JSON이면 점 표기법으로 특정 필드를 꺼낼 수 있다.

```
HTTP 응답 post = {"userId":1,"id":1,"title":"sunt...","body":"quia..."}

${post.userId}   → "1"
${post.title}    → "sunt..."
${post.body}     → "quia..."

# 배열 인덱스도 지원
${items[0].name}
${items[2].address.city}
```

동작 원리 (`PipelineRunnerService.resolveExpr`):
1. `${expr}` 에서 `expr`을 꺼냄
2. ctx 맵에서 `expr`과 prefix가 일치하는 키를 찾음
3. 해당 값의 raw string에서 HTTP 헤더(`HTTP 200 OK\n...`)를 제거한 뒤 JSON 파싱
4. `jsonPath(parsedObj, ".필드명")` / `jsonPath(parsedObj, "[인덱스]")` 재귀 탐색

---

## 실행 결과 UI (2026-07-08 도입)

기존 팝업 모달 방식을 폐기하고 인라인 패널 방식으로 교체.

### 하단 패널 (BottomPanel)

- 높이 200px, 헤더 클릭으로 36px로 접기/펼치기
- 헤더: 전체 상태 뱃지, 성공 수(✓N), 실패 수(✕N)
- 노드별 로그 행 클릭 → 해당 노드 선택 (오른쪽 패널 연동)

### 캔버스 노드 상태 시각화

| 상태 | 표시 |
|-----|------|
| 실행 중 | 파란 테두리 pulse 애니메이션, 카드 헤더에 ● |
| 성공 | 초록 테두리 glow, 카드 헤더에 ✓ |
| 실패 | 빨간 테두리 glow, 카드 헤더에 ✕ |

### 오른쪽 패널 — 결과 탭

노드 클릭 시 오른쪽 패널에 **설정** / **결과** 탭이 나타남.
- **설정 탭**: 기존 파라미터 편집
- **결과 탭**: 해당 노드의 마지막 실행 출력 표시 (OutputRenderer)

### OutputRenderer / JSON 뷰어

출력이 JSON이면 트리 형태로 렌더링. 마크다운이면 HTML로 렌더링.

- JSON 트리: 키/값에 마우스 올리면 상단 경로 바에 `${변수명.path}` 형태로 경로 표시
- 클릭 시 클립보드 복사 + "✓ 복사됨" 피드백
- 복사된 경로를 다음 노드의 `${변수명.path}` 파라미터에 그대로 붙여넣기 가능

---

## 화면 캡처 (인앱, 드래그 선택)

`WAIT_IMAGE`/`CLICK_IMAGE` 등 이미지 파라미터를 파일 업로드 없이 **앱에서 직접 드래그로 캡처**할 수 있다.

1. 노드 설정 패널에서 **📷 화면 캡처** 클릭
2. 앱 창이 잠깐 숨겨지고, 그 순간의 화면 스냅샷 위에 반투명 오버레이가 뜸
3. 마우스 드래그로 캡처할 영역 선택 (ESC로 취소 가능)
4. 선택 즉시 이미지가 저장되고 미리보기에 반영, 앱 창 복귀

---

## 이미지 탐색 성능 개선 (2026-07-08)

`CLICK_IMAGE`/`WAIT_IMAGE` 실행 시 1920x1080 화면, 120x50 템플릿 기준 ~1.1초 → ~8ms (약 130배).

개선 내용 (`ImageMatcherUtil.java`):
1. `getRGB(x,y)` 픽셀 단위 개별 호출 → `getRGB(0,0,w,h,...)` 배열 일괄 읽기
2. 1차 필터 샘플 포인트를 균일 격자 대신 **템플릿 평균색과 가장 차이가 큰 특징 픽셀 최대 64개**로 선정
3. CPU 코어 수만큼 화면을 가로 밴드로 나눠 **병렬 스캔**

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

## 설정 (data/settings.json)

```json
{
  "claudeApiKey": "sk-ant-...",
  "claudeModel": "claude-sonnet-4-6",
  "geminiApiKey": "AIza...",
  "imageMatchThreshold": 0.90,
  "imageMatchRetryMs": 500,
  "outlineUrl": "http://localhost:3000",
  "outlineToken": "ol_api_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
}
```

| 키 | 설명 |
|----|------|
| `claudeApiKey` | Anthropic API 키. `CALL_AI`(Claude), `AI_AGENT` 노드에서 사용 |
| `claudeModel` | Claude 기본 모델. `CALL_AI`/`AI_AGENT` 노드에서 model 미설정 시 사용 |
| `geminiApiKey` | Google AI Studio API 키. `CALL_AI`(Gemini) 노드에서 사용. [무료 발급](https://aistudio.google.com) |
| `imageMatchThreshold` | 픽셀 일치율 임계값 (0.1~1.0). 높을수록 엄격 |
| `imageMatchRetryMs` | `WAIT_IMAGE` 재시도 간격 (ms) |
| `outlineUrl` | Outline 인스턴스 베이스 URL (끝 슬래시 없어도 됨) |
| `outlineToken` | Outline API 토큰. 형식: `ol_api_` + 38자 영숫자 |

### ⚠️ 다른 컴퓨터에서 git clone/pull 후 반드시 알아야 할 것

`data/`, `dist/data/` 전체가 `.gitignore`에 포함되어 있어서 **API 키·Outline 토큰 등
`settings.json`은 Git으로 절대 동기화되지 않는다.** (2026-07-10 이전엔 `dist/data/`가
gitignore 누락 상태였음 — 2026-07-11 수정.)

즉, 새 컴퓨터에서 `git clone`/`git pull`만 하면:
- 메모/북마크/달력 데이터, 파이프라인 정의(`data/pipelines/*.json`), 설정(API 키 등)이 **전부 없음**
- 앱 최초 실행 시 앱 **설정** 화면에서 Claude/Gemini API 키, Outline URL/토큰을 다시 입력해야
  `CALL_AI`/`AI_AGENT`/`OUTLINE` 노드가 동작한다
- 기존 파이프라인을 옮기려면 원본 컴퓨터에서 **내보내기(ZIP)** 후 새 컴퓨터에서 **가져오기**로
  이전해야 함 (자동 동기화 없음)

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
frontend/pipeline-editor/
  src/
    App.tsx                 # 메인: 캔버스, 팔레트, 실행 폴링, 레이아웃
    api.ts                  # REST API 호출 래퍼
    types.ts                # Pipeline, Node, RunLog 등 타입 정의
    RunStatusContext.ts     # 실행 상태 Context (Map<nodeId, RunLog>)
    components/
      PipelineCanvas.tsx    # ReactFlow 캔버스 + RunStatusContext.Provider
      PipelineNodeCard.tsx  # 노드 카드 (실행 상태 테두리/아이콘)
      ConfigPanel.tsx       # 오른쪽 설정/결과 탭 패널
      BottomPanel.tsx       # 하단 실행 결과 패널 (접기/펼치기)
      OutputRenderer.tsx    # JSON 트리 뷰어 + Markdown 렌더러
      Toolbar.tsx           # 저장/실행/내보내기 버튼
      NodePalette.tsx       # 왼쪽 노드 팔레트
```

```bash
# 최초 빌드 (Node.js 필요)
cd frontend/pipeline-editor
npm install
npm run build

# 이후 Gradle 빌드가 자동으로 npm run build 호출
./gradlew bootJar                      # 전체 빌드 (npm + Java)
./gradlew bootJar -x npmBuildFrontend  # 프론트 빌드 스킵 (Java만 재빌드)
```

> ⚠️ **중요**: `npm run build`만 하면 안 됨. 반드시 `./gradlew bootJar`까지 실행해야
> 빌드된 JS/CSS가 jar에 포함되어 실제 앱에 반영된다.

**빌드 결과물 위치**: `src/main/resources/static/pipeline-editor/app.js`, `app.css`

---

## 앱 실행 방법 (Mac Apple Silicon)

JavaFX aarch64 jar가 Gradle 캐시에 있어야 한다. 최초 빌드(`./gradlew bootJar`) 후 아래 스크립트로 실행.

```bash
GRADLE_CACHE=~/.gradle/caches/modules-2/files-2.1/org.openjfx
MPATH="\
$GRADLE_CACHE/javafx-controls/21.0.2/9f25facd5e9c14b18f824bba1f4997b06b707215/javafx-controls-21.0.2-mac-aarch64.jar:\
$GRADLE_CACHE/javafx-fxml/21.0.2/26bbe59c7801cb9306b9adc89edce0e055d3ac5d/javafx-fxml-21.0.2-mac-aarch64.jar:\
$GRADLE_CACHE/javafx-base/21.0.2/3f064723cfab99e00d9b5348199d506be79b43dd/javafx-base-21.0.2-mac-aarch64.jar:\
$GRADLE_CACHE/javafx-graphics/21.0.2/2c47a02ea46c45ee9f751badd528f91b498e3982/javafx-graphics-21.0.2-mac-aarch64.jar:\
$GRADLE_CACHE/javafx-media/21.0.2/1c99ff6b3131314f238967a84504feedc9b7c5/javafx-media-21.0.2-mac-aarch64.jar:\
$GRADLE_CACHE/javafx-web/21.0.2/ec495485f202660a8a2a402b5e99a43ca26350b2/javafx-web-21.0.2-mac-aarch64.jar"

java --module-path "$MPATH" \
  --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.web \
  -jar build/libs/memo-bookmark-app-1.0.0.jar
```

포트는 랜덤 자동 할당됨. 앱 실행 시 자동으로 브라우저가 열린다.  
서버 포트 로그: `Tomcat started on port {N}` — 해당 포트로 브라우저 접속 가능.

### 헤드리스(API 전용) 테스트 실행 — JavaFX GUI 없이

파이프라인 노드 동작만 빠르게 확인하고 싶을 때, JavaFX 창을 띄우는
`Launcher`(springBoot mainClass) 대신 순수 Spring Boot 엔트리포인트인
`MemoBookmarkApplication`을 직접 실행하면 GUI 없이 REST API만으로 테스트 가능하다.

```bash
# JDK 21 필요 (JAVA_HOME 설정)
./gradlew compileJava processResources -x npmBuildFrontend   # 프론트 재빌드 스킵

java -cp "build/classes/java/main;build/resources/main;<런타임 classpath>" \
  com.app.memobookmark.MemoBookmarkApplication \
  --server.port=8099 --app.storage.path=./data
```

- `ScreenCaptureService`가 `javafx.stage.Stage`를 참조하므로 classpath에
  JavaFX jar(win 배포용, `configurations.javafxWin`)가 없으면 Bean 생성 시
  `NoClassDefFoundError: javafx/stage/Stage`로 기동 실패한다 — classpath에
  포함시킬 것 (Stage를 실제로 열지는 않으므로 창은 뜨지 않음).
- 런타임 classpath 전체 목록은 `./gradlew sourceSets.main.runtimeClasspath` 같은
  임시 태스크로 뽑아내거나 IDE에서 확인.
- 이후 `curl`로 `/api/pipelines`, `/api/pipelines/{id}/run`, `/api/executions/{execId}` 등을
  직접 호출해 실제 노드 실행 결과를 확인할 수 있다.
- Git Bash에서 한글 등 비ASCII가 포함된 JSON을 `curl -d '...'`로 직접 넘기면
  인코딩 깨짐(`Invalid UTF-8 middle byte`)이 발생할 수 있다 — 파일로 저장 후
  `curl --data-binary @file.json`로 보낼 것.

---

## 주요 구현 파일

| 파일 | 역할 |
|------|------|
| `service/NodeTypeRegistry.java` | 전체 노드 타입 + 파라미터 스키마 정의 |
| `service/PipelineService.java` | JSON CRUD + ZIP import/export |
| `service/PipelineRunnerService.java` | 실행 엔진. 위상정렬 → 노드 순차 실행. `resolveVars` 점 표기법 변수 치환. `callClaude` / `callGemini` / `callCustomAi` AI 헬퍼 포함 |
| `service/AppSettingsService.java` | `data/settings.json` 읽기/쓰기 |
| `service/ImageMatcherUtil.java` | 화면 픽셀 비교 이미지 탐색 (특징 픽셀 샘플링 + 병렬 스캔) |
| `service/ScreenCaptureService.java` | 앱 창 숨김 → 화면 스냅샷 → 드래그 영역 선택 (인앱 캡처) |
| `controller/PipelineApiController.java` | REST API 전체 |
| `model/AppSettings.java` | 설정 모델 (`claudeApiKey`, `geminiApiKey`, `outlineUrl`, `outlineToken` 등) |
| `frontend/pipeline-editor/src/` | React 18 + TS + Vite 에디터 소스 |
| `templates/settings/index.html` | 앱 설정 화면 (Claude/Gemini API 키, Outline 설정 등) |

---

## 알려진 한계 / 향후 개선

| 항목 | 현재 | 향후 |
|------|------|------|
| 분기(if/else) | 미지원, 직선 파이프라인만 | 조건 노드 추가 예정 |
| AI_AGENT 멀티 프로바이더 | Claude 전용 (tool use API 형식이 제공사마다 다름) | Gemini function calling 추가 검토 |
| 실행 취소 | 스레드 interrupt로 처리 | 더 안정적인 취소 메커니즘 |
| 다중 모니터 | 캡처 대상 모니터 선택 불가 | `GraphicsDevice` 열거로 선택 UI |
| DPI 스케일링 | 오프셋 수동 조정 필요 | 스케일 자동 보정 |
| 웹 DOM 자동화 | Robot API (이미지 기반) | Playwright for Java 도입 검토 |
