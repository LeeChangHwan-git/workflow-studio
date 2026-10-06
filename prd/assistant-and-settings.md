# 💬 파이프라인 도우미 + ⚙ 설정(관리자)

## 상태: ✅ 1차 완료 (2026-10-04)

## 목적
- 말로 요청하면 AI가 **실제로** `data/pipelines`의 파이프라인을 만들고 · 고치고 · (사용자 확인 후) 지운다. 답만 주는 챗봇이 아님.
- AI 연결은 설정으로 바꿀 수 있게: 지금은 **Gemini(무료 API)**, 나중에 **Ollama 등 OpenAI 호환**(오프라인) 그대로 사용.
- 흩어진 관리 기능을 ⚙ 설정 한 곳에: 공용 키, AI 연결, 타이머 목록, 백업·복원, 로그, 서버 상태·종료.

## 구조

```
assistant.js (모든 화면, 상단 💬 도우미 → 오른쪽 서랍)
   │ POST /api/assistant/chat {conversationId, message, currentPipelineId}
   ▼
PipelineAssistantService ── ChatModelFactory(⚙ 설정) ─┬─ GeminiChatModel   (generateContent + functionDeclarations)
   │  도구 호출 루프 (최대 N회)                         └─ OpenAiChatModel   (/chat/completions + tools: Ollama·LM Studio·vLLM·OpenRouter)
   ▼
PipelineToolkit — 노드 설명서 자동 생성 · AI 형식 ↔ 저장 형식 · 검증 · 자동 배치
   ▼
PipelineService (저장 시 PipelineBackupService 가 직전 상태 자동 백업)
```

### "매번 앱을 가르쳐야 하나?" → 자동
- 무료 API는 대화를 기억하지 않으므로 요청마다 **노드 설명서**를 같이 보낸다. 소스 코드가 아니라 `/api/node-types`(NodeTypeRegistry)에서
  자동으로 만든 요약 → **모듈을 추가하면 설명서에 저절로 포함**.
- 설명서가 작으면(14,000자 이하) 전체를 시스템 프롬프트에, 커지면 **노드 목록 한 줄씩만** 넣고 상세는 `get_node_specs` 도구로 필요한 것만
  (모듈이 수십 개로 늘어도 한 번에 보내는 양이 크게 늘지 않음 — RAG 같은 역할).
- 파이프라인도 전부 보내지 않고 `list_pipelines`(요약) → `get_pipeline`(필요한 것 하나)만.

## 도구 (AI가 호출)

| 도구 | 하는 일 |
|------|---------|
| `list_pipelines` | id·이름·설명·노드 요약 목록 |
| `get_pipeline(id)` | 하나 전체 (AI 형식). **비밀값(password 타입)은 `***`** |
| `get_node_specs(types[])` | 노드 설정 항목·선택지·기본값·표시 조건·소켓 |
| `create_pipeline(pipeline_json)` | 검증 → 저장. 오류가 있으면 저장하지 않고 errors 를 돌려줘 AI가 고쳐서 다시 호출 |
| `update_pipeline(id, pipeline_json)` | 통째로 교체 저장 (직전 상태 자동 백업 "AI 수정") |
| `delete_pipeline(id)` | **삭제 요청만** — 화면의 [삭제] 버튼을 눌러야 실제 삭제 (백업 "AI 삭제" 후) |

AI 형식 (저장 형식보다 단순, 위치 없음):
```json
{ "name": "...", "description": "...",
  "nodes": [ { "id": "hook", "type": "WEBHOOK", "config": { "path": "ask" } } ],
  "connections": [ { "from": "hook", "to": "agent" }, { "from": "model", "to": "agent", "socket": "model" } ] }
```

### 검증 (`PipelineToolkit.build`)
- 이름, 노드 id 형식·중복, **없는 타입**, **없는 설정 이름**(가능한 설정 목록 안내), **select 값이 선택지에 없음**(선택지 안내), 숫자 형식
- 연결: 없는 노드, 자기 자신, **트리거로 들어가는 화살표**, 부품을 화살표로 연결, 없는 소켓, **소켓 종류 불일치**, 필수 소켓 누락, 단일 소켓에 여러 개
- 웹훅 path 가 다른 파이프라인과 겹침
- 경고(저장은 함): 비밀값 비어 있음, 연결 안 된 부품, 트리거 없음
- 생략한 설정은 기본값, 화면 표시 전용 파라미터(webhookUrls 등)는 AI에게 숨김

### 수정 시 보존
- 같은 노드 id → **캔버스 위치 유지**, 비밀값 `***` → **원래 값 유지**, 생략한 설정 → 기존 값 유지
- 새 노드 자동 배치: 앞 노드 바로 오른쪽(+340px, 자리가 있으면 아래로), 앞 노드가 없으면 화살표 깊이별 열, 부품은 본체 아래

### 화면 (`static/js/assistant.js`)
- 상단 메뉴 **💬 도우미** → 오른쪽 서랍. Enter 보내기 / Shift+Enter 줄바꿈, 예시 문장 버튼, 새 대화, ⚙ 바로가기
- 결과 카드: ✨ 만듦 / ✏️ 수정 [열기], 🗑 삭제할까요? [삭제] [취소], "작업 과정 N단계"(도구 호출 기록)
- 에디터에서 열면 "지금 보고 있는 파이프라인" 정보를 함께 보냄 → "이거 고쳐줘" 가능
- 대화는 탭별 sessionStorage (화면을 옮겨도 이어짐), 서버 쪽 대화는 메모리(최근 30개, 재시작 시 초기화)
- 변경 알림: `workflow-pipelines-changed` 이벤트 + BroadcastChannel → **열린 에디터가 자동으로 다시 불러옴**
  (편집 중이면 "도우미 버전 불러오기 / 내 편집 유지" 알림), 목록 화면은 새로고침

## ⚙ 설정 (`/settings`, `data/settings.json`)

| 탭 | 내용 |
|----|------|
| 📊 상태 | 가동 시간·PID·포트·Java·메모리·스레드·파이프라인/타이머/백업 수·데이터 폴더(크기)·로그 파일, 📁 데이터/로그 폴더 열기, ⏻ 서버 종료 |
| 🔑 공용 키 | Gemini API 키, 아웃라인 주소·API 키 — **노드에서 비우면 이 값** (노드 → 공용 키 → 환경 변수) |
| 💬 AI 도우미 | 제공자(Gemini / OpenAI 호환), 모델, 키, 주소, 온도, 요청당 최대 도구 호출, 🔌 연결 테스트 |
| ⏰ 타이머 | 동작 중인 타이머 전체 (파이프라인 · 일정 · 다음 실행) |
| 🗄 백업 | 파이프라인당 보관 개수, 백업 목록(시각·이유·노드 수·삭제됨 표시), **복원**(같은 id 로, 지금 상태도 먼저 백업) |
| 📜 로그 | 로그 파일 마지막 300줄 |

- 비밀값은 **화면으로 내려보내지 않음**: 끝 4자리만 표시, 입력칸은 새 값 넣을 때만(비우면 유지), [지우기]로 삭제. 입력칸 `autocomplete=new-password`
- 백업: `PipelineService.update/delete` 가 직전 파일을 `data/backups/pipelines/{id}/{시각}_{이유}.json` 으로 복사
  (이유: 저장 / AI 수정 / 삭제 / AI 삭제 / 복원 전), 파이프라인당 최근 N개(기본 20)

## API

| Method | Path | 설명 |
|--------|------|------|
| `POST` | `/api/assistant/chat` | `{conversationId?, message, currentPipelineId?}` → `{conversationId, reply, actions[], steps[], model}` / `{error}` |
| `POST` | `/api/assistant/delete` | `{conversationId, pipelineId, confirm}` — 이 대화에서 삭제 요청된 것만 |
| `POST` | `/api/assistant/reset` · `/api/assistant/test` | 대화 초기화 · 연결 테스트 |
| `GET/PUT` | `/api/settings` | `{settings, secrets}` / 비밀 필드 null=유지, ""=지움 |
| `GET` | `/api/admin/status` · `/api/admin/timers` · `/api/admin/backups` · `/api/admin/logs` | 관리 정보 |
| `POST` | `/api/admin/backups/restore` · `/api/admin/open-folder` | 복원 · 탐색기 열기 |

## 함께 바뀐 것
- **(2026-10-07 버그 수정)** `/api/assistant/chat` 오류 응답이 `Map.of(..., "conversationId", null)`로 만들어져
  (첫 메시지는 `conversationId`가 명시적 `null`이라 `getOrDefault`가 기본값 대신 `null`을 그대로 돌려줌) `Map.of`가 null을 거부하며
  `NullPointerException` → 500으로 **원래 오류 메시지(예: "Gemini API 키가 없습니다")가 가려지던 문제**를 고침. 이제 `HashMap`으로 담아 항상 200 + `{error}`
- **기본 변수**: 모든 노드에서 `${date}`(2026-10-05) `${time}`(09:00) `${datetime}`(2026-10-05 09:00) `${timestamp}` (`NodeContext.builtins`).
  파일 쓰기 노드는 파일명용 형식(`09-00-00`, `20261005_090000`)으로 덮어씀
- 공용 키: `GeminiClient.resolveKey`, `OutlineClient.conn` 이 설정 값을 사용, 연결 확인에 "⚙ 설정의 공용 키" 표시.
  아웃라인 노드의 주소 기본값은 빈 값(= 공용 주소)
- `showWhen` 여러 값(`a=x|y`)은 아웃라인 작업 때 추가됨

## 테스트 (`PipelineAssistantTest`, 가짜 Gemini·OpenAI 서버)
- 검증 오류(선택지 아님) → 오류 내용이 AI에게 전달 → 고쳐서 저장, 기본값 채움·자동 배치, Gemini 생각 서명 보존, 시스템 프롬프트에 설명서
- 수정: `***` → 원래 비밀값, 기존 위치 유지, 새 노드는 앞 노드 오른쪽, "AI 수정" 백업, 보고 있는 파이프라인 전달, 결과에 비밀값 없음
- 삭제: 확인 전엔 안 지워짐, 다른 대화 id 로는 불가, 확인 후 삭제 → 백업에서 복원
- 연결 규칙 5종, OpenAI 호환(tool 메시지 형식, `<think>` 제거), 설정 비밀값 숨김·유지·지우기
- 실제 Gemini(gemini-flash-latest): "매주 월요일 9시 게시판 7일치 → FW 주간 요약 → md" 한 번에 생성(9초) → 지금 실행 성공,
  "파일 저장 다음에 아웃라인에도 등록" 수정(get → update, 8초), 타이머 끄기·이름 바꾸기 → 열린 에디터 자동 갱신, 삭제 요청 → 취소

## 다음 후보
- Ollama 설치 후 오프라인 모델(qwen3:8b 등) 실측 — 코드는 준비됨
- 노드 모듈에 한 줄 설명(description) 필드 추가 → 설명서 품질 향상
- 저장된 파이프라인을 예시로 골라 넣기(RAG), 도우미가 "실행해 보기"까지 하는 도구(run_pipeline)
- 대화 기록 파일 저장
