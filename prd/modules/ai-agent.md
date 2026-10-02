# AI 에이전트 (`AI_AGENT`) + 부품 (모델 · 메모리 · 지시문)

## 상태: ✅ 1차 완료 (2026-10-02) — 모델은 Gemini만

## 목적
n8n의 AI Agent처럼, 에이전트 노드 **아래쪽 소켓 3개**에 부품 노드를 끼워 에이전트를 구성한다.
**에이전트 3요소** = 모델(어떤 AI로) · 메모리(무엇을 기억하며) · 지시문(어떤 규칙으로).

```
  [웹훅] ──▶ [🤖 AI 에이전트] ──▶ [파일 쓰기] ...
              모델*  메모리  지시문
               ┆      ┆      ┆  ┆        (점선 = 부품 연결)
          [✨Gemini] [🧠메모리] [📜지시문] [📜지시문 파일]
```

## 노드 구성

### 🤖 AI 에이전트 (`AI_AGENT`, 팔레트 "AI")
| name | label | 기본값 | 설명 |
|------|-------|--------|------|
| `name` | 노드 이름 | `""` | |
| `prompt` | 질문 / 요청 | `${input}` | 앞 노드 출력이 기본. 비어 있거나 `${input}`이 안 풀리면 오류 |
| `outputVar` | 출력 변수명 | `agent` | |

소켓:
| 소켓 id | 표시 | 받는 부품 | 필수 | 여러 개 |
|---------|------|-----------|------|---------|
| `model` | 모델* | `ai_model` | ✅ | ❌ (새로 끼우면 교체) |
| `memory` | 메모리 | `ai_memory` | | ❌ |
| `instruction` | 지시문 | `ai_instruction` | | ✅ (연결 순서대로 이어 붙임, 개수 표시) |

출력: **AI 답변 텍스트**. 부가 정보 `${agent.meta}` = `{model, instructions, sessionKey, historyMessages}`.

### ✨ Gemini 모델 (`GEMINI_MODEL`, 부품 `ai_model`, 팔레트 "AI 부품")
| name | label | 기본값 | 설명 |
|------|-------|--------|------|
| `apiKey` | API 키 | `""` | 비우면 환경 변수 `GEMINI_API_KEY`. 무료 발급: https://aistudio.google.com/apikey |
| `model` | 모델 | `gemini-flash-latest` | 직접 입력 + 자동완성 목록 + **연결 검증** (아래) |

#### 연결 검증 (2026-10-03 추가) — 실행 없이 설정 화면에서
- API 키나 모델을 바꾸면 **0.8초 뒤 자동 검증**, **🔑 연결 확인** 버튼으로 다시 검증
- `POST /api/ai/gemini/verify {apiKey, model}` → `{ok, keyOk, modelOk, keySource, message, models}`
  1. 키 확인: 모델 목록 조회(`GET /v1beta/models`) — **토큰을 쓰지 않음**. 실패 시 Google 메시지 그대로 (`API key not valid` 등)
  2. 모델 확인: 목록에 있는지 → 없으면 개별 조회(`GET /v1beta/models/{model}`)로 별칭(`*-latest`)까지 확인
- 결과 표시: ✅ 연결 정상 / ⚠️ 키는 정상인데 모델 없음 / ❌ 키 없음·키 오류. 키 출처(노드 입력 / `GEMINI_API_KEY` 환경 변수)와
  사용 가능 모델 수 표시, 받은 목록은 모델 입력칸 자동완성에 채움 (기존 "목록 불러오기" 버튼을 대체)
| `temperature` | 온도 | `0.7` | 0~2로 보정 |

### 🧠 대화 메모리 (`CHAT_MEMORY`, 부품 `ai_memory`)
| name | label | 기본값 | 설명 |
|------|-------|--------|------|
| `sessionKey` | 대화 ID | `default` | 같은 ID끼리 대화를 이어서 기억. 예: `${webhook.request.body.userId}` (안 풀리면 오류) |
| `windowTurns` | 기억할 최근 대화 수 | `10` | 질문+답 = 1턴. 이만큼만 모델에 보냄 |
| `memoryInfo` | 저장된 대화 | - | 대화 ID별 메시지 수 표시(5초 갱신) + **대화 모두 지우기** |

저장: `{storage}/agent-memory/{메모리 노드 id}/{대화 ID 해시}.json`, 대화당 최근 200개 메시지 보관 (서버 재시작 후에도 유지).

### 📜 지시문 (`AI_INSTRUCTION`, 부품 `ai_instruction`)
| name | label | 기본값 | 설명 |
|------|-------|--------|------|
| `source` | 지시문 가져오기 | 직접 입력 | 직접 입력 / 파일 |
| `text` | 지시문 | "당신은 친절하고 정확한 한국어 비서입니다." | 직접 입력일 때만 표시. `${...}` 사용 가능 |
| `filePath` | 지시문 파일 | `""` | 파일일 때만 표시. **📁 찾아보기**(txt/md). **실행할 때마다 다시 읽음** → 파일만 고치면 반영. UTF-8(BOM 포함)/MS949 자동 인식, 512KB 제한, 파일 안에서도 `${...}` 사용 가능 |

## 동작 (에이전트 실행 시)
1. 모델 소켓 확인 (없으면 "'모델' 소켓에 모델 부품을 연결하세요")
2. 지시문들을 연결 순서대로 `\n\n`으로 이어 **시스템 지시(systemInstruction)** 로
3. 메모리가 있으면 대화 ID를 풀고 최근 N턴을 대화 기록으로
4. `POST {base}/v1beta/models/{model}:generateContent` — 키는 **`x-goog-api-key` 헤더**(URL에 안 남김)
5. 응답 텍스트 반환, 메모리가 있으면 질문·답 저장
- 오류: HTTP 오류는 `Gemini API 오류 HTTP 404 NOT_FOUND: ...`처럼 Google 메시지 그대로, 차단 시 `차단 사유` 표시

## 엔진 공통 변경 — "부품 노드(sub-node)"와 소켓
- `PipelineConnection.toHandle`: 소켓 id. 값이 있으면 **부품 연결**, 없으면 일반 실행 흐름
- `NodeTypeInfo.sockets` (`NodeSocket{id,label,accepts,required,multiple}`) / `NodeTypeInfo.provides` (부품 종류)
- 실행 엔진: 부품 노드는 단독 실행하지 않음, 부품 연결은 실행 순서·`${input}`·트리거 도달 범위에서 제외,
  각 노드에 `NodeContext.parts(socketId)`로 끼워진 부품 전달. `NodeContext.configOf(node, key, def)`로 부품 설정 읽기
- `NodeParam.showWhen` ("source=파일"): 다른 설정값에 따라 칸 표시/숨김 — 파일 쓰기의 "확장자 직접 입력"에도 적용
- 공용 선택 창 API: `POST /api/dialog/folder`, `POST /api/dialog/file` (기존 `/api/file-write/pick-folder` 대체)

## 에디터
- 에이전트 카드 아래 소켓 3칸: 라벨(필수 `*`, 여러 개면 개수), 마름모 연결점. 채워지면 보라색, 필수인데 비면 빨간색
- 부품 카드: 둥근 모양, 위쪽 마름모 연결점 하나 (좌우 연결점 없음)
- 연결 규칙: 부품 → **종류가 맞는 소켓에만**(다른 소켓·일반 입력으로는 연결 안 됨), 일반 노드 ↔ 일반 노드만.
  1개짜리 소켓에 새로 끼우면 기존 연결 교체. 부품 연결선은 점선(화살표 없음)
- 부품 설정 패널 상단에 "🔌 부품 노드입니다 — 에이전트 소켓에 연결하세요" 안내
- 팔레트 순서: 트리거 → AI → AI 부품 → 파일

## 보안 메모
- 브라우저(Chrome)가 저장된 로그인 정보를 API 키·이름 칸에 자동으로 채운 사례가 있어(2026-10-02),
  에디터의 모든 입력칸에 `autocomplete="off"`, 비밀번호 칸에 `autocomplete="new-password"` 적용
- API 키는 파이프라인 JSON(`data/pipelines/*.json`)에 평문 저장됨 → **내보내기(ZIP)에도 포함**. 공유 시 키를 지우거나
  환경 변수 `GEMINI_API_KEY`를 쓰는 것을 권장

## 테스트
- `AiAgentModuleTest` — 가짜 Gemini 서버(`app.gemini.base-url`로 주소 교체)로 검증:
  키 헤더·모델 경로·지시문 순서(직접 입력 + MS949 파일)·온도·메모리(대화 ID별, 최근 1턴 창)·메모리 API·
  모델 소켓 누락 오류·HTTP 404 오류·모델 목록 필터, 부품 노드 단독 실행 안 됨
- 실제 Gemini 호출은 미확인 (API 키 필요) → 데모 파이프라인 **"AI 에이전트 데모"**에서 키 입력 후 확인

## 다음 후보
- 모델 추가 (Claude, OpenAI 호환, Ollama), 도구(Tool) 소켓 — 에이전트가 HTTP 요청·파일 쓰기 등을 스스로 호출
