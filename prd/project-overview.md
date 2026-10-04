# Workflow Studio — 프로젝트 개요

## 컨셉

**개인용 로컬 업무 도구 — 메모·북마크·달력 + GUI 자동화(n8n 스타일).**
핵심은 파이프라인 자동화: 캔버스에 노드를 끌어다 놓고 연결해 파이프라인을 만들고 실행한다.
노드의 실제 동작은 **모듈(NodeModule)** 단위로 하나씩 설계·추가한다.

- 로컬 PC에서 Spring Boot 서버를 띄우고 브라우저로 `http://localhost:8080` 접속
- 별도 DB 없이 JSON 파일로 저장, 오프라인 동작
- 서버가 도는 PC의 화면/키보드를 제어하는 모듈도 염두에 둠 (AWT headless=false)

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 언어 | Java 21 (빌드·실행 모두 JDK 21 필요) |
| 백엔드 | Spring Boot 3.2.5 + Thymeleaf (목록 화면) |
| 에디터 | React 18 + TypeScript + Vite + React Flow (`frontend/pipeline-editor/`) |
| 빌드 | Gradle (bootJar 시 npm 빌드 자동 실행, `makeDist`로 배포 ZIP) |
| 저장 | JSON 파일 (Jackson) |

---

## 아키텍처

```
브라우저 ── /memos, /bookmarks, /calendar (Thymeleaf, 각 Controller → Service → JSON 파일)
   │
   ├─ /pipelines (Thymeleaf 목록) ── /pipelines/{id} (React 에디터)
   │
   ├─ /http (간이 HTTP 클라이언트) ── HttpClientController ── HttpSender
   │
   └─ REST /api/** ── PipelineApiController
                         ├─ PipelineService        파이프라인 JSON CRUD, ZIP 내보내기/가져오기, 이미지 저장
                         ├─ PipelineRunnerService  실행 엔진: 위상정렬 → 노드 순차 실행 → 로그/상태
                         │     └─ NodeTypeRegistry → NodeModule (노드별 동작, 모듈로 추가 / 부품 노드는 소켓으로 전달)
                         └─ ScreenCaptureService   설정 패널의 '화면 캡처'(image 파라미터용)

외부 ── /webhook-test/{path}, /webhook/{path} ── WebhookController ── runTriggered()
모든 화면 ── SharedWorker(브라우저당 1개) ── /api/app/live (SSE) : 서버 재시작 감지 → 새로고침, 열린 탭 관리
```

- 엔진/에디터 상세: `prd/engine-and-editor.md`
- 모듈 설계 문서: `prd/modules/` (모듈 하나당 문서 하나)

### 설정 (`application.properties`)

| 키 | 기본값 | 설명 |
|----|--------|------|
| `server.port` | `8080` | 접속 포트 |
| `server.address` | `127.0.0.1` | 이 PC에서만 접속 허용. 외부 접속은 `0.0.0.0` (화면 제어 모듈이 생기면 보안 주의) |
| `app.open-browser` | `true` | 기동 시 기본 브라우저 자동 오픈 |
| `app.storage.path` | `./data` | 데이터 저장 경로 |

### 데이터 저장 구조

```
{app.storage.path}/
├── memos.json           # 메모 목록
├── bookmarks.json       # 북마크 목록
├── calendar.json        # 달력 이벤트 목록
├── pipelines/
│   ├── {uuid}.json      # 파이프라인 정의 (노드 + 연결 + 노드 설정 — AI 모델 API 키 포함 시 평문)
│   └── images/          # image 타입 파라미터로 업로드/캡처한 이미지
├── webhooks/            # 웹훅 노드별 수신 기록 ({nodeId}.json, 최근 200건)
├── schedules/           # 타이머 실행 기록 ({파이프라인id}_{노드id}.json, 최근 100건)
└── agent-memory/        # AI 대화 메모리 ({메모리 노드 id}/{대화 ID 해시}.json)
```

> ⚠️ `data/`는 `.gitignore` 대상 (단, 메모·북마크·달력 JSON 3개는 예전부터 git에 추적 중).
> 파이프라인을 다른 PC로 옮길 땐 에디터의 **내보내기(ZIP) → 가져오기** 사용.

---

## 기능

### 파이프라인 자동화 (핵심)
노드 모듈을 처음부터 하나씩 설계 중 → `prd/modules/README.md`
- 현재 모듈: 🪝 웹훅 · ⏰ 타이머 (트리거), 🤖 AI 에이전트 (+ 부품: ✨ Gemini 모델 · 🧠 대화 메모리 · 📜 지시문), 🌐 HTTP 요청, 💾 파일 쓰기 (Windows 전용)

### HTTP (간이 HTTP 클라이언트)
- `/http` — 메소드·URL·헤더·바디로 요청을 보내고 응답(상태·시간·크기·바디·헤더)을 확인. 보낸 요청 기록(브라우저 저장)
- 서버가 대신 요청을 보내므로 외부 API도 호출 가능. 웹훅 노드의 "🚀 HTTP로 테스트" 버튼으로 테스트 URL이 채워진 채 열림

### 메모
- 시스템명(제목) + 카테고리 + 내용(자유 텍스트), 카드 그리드 UI (시스템별 색상 띠/아이콘 8색 순환)
- 내용 전체 노출 (최대 높이 180px 스크롤), 클립보드 복사 버튼, 검색 (시스템명 + 내용)

### 북마크
- 이름 + URL + 카테고리 + 메모 + **열기 브라우저 지정** (기본 / Edge / Chrome / IE)
- 서버 측에서 브라우저 실행 (`ProcessBuilder("cmd", "/c", "start", <브라우저>, <url>)`) → 서버가 도는 PC에서 열림
- URL 형식 검증 없음 (`google.com`, IP 등 허용), 검색 (이름 + URL + 설명)

### 달력
- 월별 뷰 (일요일 시작), 날짜 셀에 이벤트 미리보기 (최대 2개 + 초과 표시)
- 날짜 클릭 → 우측 패널에서 당일 이벤트 추가/수정/삭제, 이벤트 = 제목(선택) + 내용(필수)

---

## 실행 / 빌드

```bash
./gradlew bootRun                              # 개발 실행 (Windows: run.bat)
./gradlew bootJar                              # 빌드 (npm 빌드 포함)
java -jar build/libs/workflow-studio-1.0.0.jar # jar 실행
./gradlew makeDist                             # dist/workflow-studio.zip 생성
```

- JDK 21 필요. `JAVA_HOME` 미설정 PC에선 직접 지정 (예: `~/.jdks/ms-21.0.7`) — bat 스크립트는 자동으로 찾음
- PowerShell에서 java 실행 시 `"-Djava.awt.headless=false"`처럼 **따옴표 필수** (안 그러면 인자가 쪼개져 실행 실패). bat 파일(cmd)은 문제없음
- 8080 포트가 사용 중이면 기동 실패 → `server.port` 변경

### 항상 켜 두기 (타이머용) — `dist/` 스크립트

| 스크립트 | 하는 일 |
|----------|---------|
| `백그라운드실행.bat` | **콘솔 창 없이**(`javaw`) 실행. 이미 실행 중이면 브라우저만 열고 끝 |
| `서버종료.bat` | 백그라운드 서버 종료 (`POST /api/app/shutdown` + 보호 헤더) |
| `상태확인.bat` | 실행 여부·PID·가동 시간·메모리·등록된 타이머 수 (`GET /api/app/info`) |
| `자동시작등록.bat` / `자동시작해제.bat` | 윈도우 로그인 시 자동 실행 (시작프로그램 폴더 바로가기, 브라우저는 열지 않음, 관리자 권한 불필요) |
| `실행.bat` | 콘솔 창으로 실행 (창 닫으면 종료) |
| `_env.bat`, `autostart.ps1` | 공통 설정 / 자동 시작 등록 도우미 |

- 스크립트는 **배포 폴더(jar가 같은 폴더)** 와 **저장소(`dist\..\build\libs\`)** 둘 다 동작. 저장소에서는 데이터·로그가 저장소 루트(`data/`, `logs/`)에
- Java 찾는 순서: `JAVA_HOME` → `%USERPROFILE%\.jdks\*21*` → PATH
- 항상 켜 두기용 JVM 옵션: `-Xms64m -Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1` (힙 상한 384MB, 가벼운 GC/JIT)
  — 실측: 기동 직후 힙 17~19MB, 프로세스 메모리 약 135MB, 스레드 28개
- **중복 실행 방지**: 기동 전에 같은 포트의 `/api/app/info`를 확인해 이미 떠 있으면 "이미 실행 중" 출력 후 종료
- **로그**: `logs/workflow-studio.log` — 10MB마다 회전, 7일 보관, 전체 100MB 이내 (`.gitignore` 대상)
- 템플릿 캐시 사용 (`spring.thymeleaf.cache=true`), Thymeleaf 문법 경고 로그 억제
- Claude Code 세션에서 띄운 서버는 2시간 제한이 있으므로, 계속 켜 둘 때는 `백그라운드실행.bat` 사용

### 배포 ZIP 구성 (`./gradlew makeDist`)

```
workflow-studio.zip
├── workflow-studio-1.0.0.jar
├── application.properties   # 외부 설정 (포트, 저장 경로, 로그 등)
├── 실행.bat · 백그라운드실행.bat · 서버종료.bat · 상태확인.bat
├── 자동시작등록.bat · 자동시작해제.bat · autostart.ps1 · _env.bat
└── 저장경로변경.bat
```

---

## 소스 구조

```
src/main/java/com/app/workflowstudio/
├── WorkflowStudioApplication.java   # 진입점 (headless=false, 기동 후 브라우저 오픈)
├── config/StorageConfig.java        # 저장 경로 Bean + 디렉토리 생성, ObjectMapper
├── controller/
│   ├── HomeController.java          # / → /memos
│   ├── Memo/Bookmark/CalendarController.java
│   ├── PipelineController.java      # 파이프라인 목록/에디터 화면
│   ├── PipelineApiController.java   # 파이프라인 REST API
│   ├── HttpClientController.java    # /http 화면 + 요청 대행 API
│   ├── AppLiveController.java       # SSE 상시 연결 (재시작 감지·새 탭 방지)
│   ├── AppInfoController.java       # 서버 상태(/api/app/info) · 종료(/api/app/shutdown, 보호 헤더)
│   └── FileDialogController.java    # 폴더/파일 선택 창 (공용)
├── model/                           # Memo, Bookmark, CalendarEvent, Pipeline, PipelineNode,
│                                    # PipelineConnection(toHandle = 소켓), RunLog
├── node/                            # ★ 모듈 시스템
│   ├── NodeModule.java              #   모듈 인터페이스 (info + execute)
│   ├── NodeContext.java             #   설정값 조회, ${변수} 치환, 소켓 부품(parts)
│   ├── Templates.java               #   ${...} 치환 공용 로직
│   ├── NodeTypeInfo / NodeParam / NodeSocket / RunMode
│   ├── NodeTypeRegistry.java        #   @Component 모듈 자동 수집
│   └── modules/                     #   모듈 구현 (모듈당 패키지 하나)
│       ├── webhook/                 #     WEBHOOK (Module, Controller, HistoryStore)
│       ├── schedule/                #     SCHEDULE 타이머 (Module, Spec, Service, HistoryStore, Controller)
│       ├── ai/                      #     AI_AGENT + 부품(GEMINI_MODEL, CHAT_MEMORY, AI_INSTRUCTION),
│       │                            #     GeminiClient, ChatMemoryStore, AiController
│       ├── http/                    #     HTTP_REQUEST
│       ├── outline/                 #     OUTLINE 아웃라인 게시판 (Module, Client, Controller)
│       └── filewrite/               #     FILE_WRITE (Windows 전용)
└── service/
    ├── Memo/Bookmark/CalendarEventService.java
    ├── PipelineService.java
    ├── PipelineRunnerService.java   # 실행 엔진 (실행 흐름 / 소켓 연결 구분)
    ├── HttpSender.java              # 공용 HTTP 요청 전송 (HTTP 메뉴 + HTTP 요청 노드)
    └── ScreenCaptureService.java
src/main/resources/
├── templates/                       # layout(공통 head·세션 스크립트), memo/, bookmark/, calendar/, pipeline/, http/
└── static/
    ├── js/app-live-worker.js        # 공유 워커: 서버 연결 1개 + 열린 탭 목록
    ├── js/app-session.js            # 상단 "탭 N/M" 배지·관리 패널
    └── session-closed.html          # 종료된 탭이 보여주는 화면
frontend/pipeline-editor/            # React 에디터 소스 (빌드 결과 → static/pipeline-editor/)
docker/outline/                      # 아웃라인 로컬 샘플 (docker-compose, Dex 로그인, seed.js — .env는 git 제외)
src/test/java/...                    # 모듈별 테스트 (가짜 Gemini·가짜 API·가짜 Outline 서버 포함, 총 33개)
```

---

## 변경 이력

### 📘 아웃라인(회사 게시판) 연동 모듈 + 예시 파이프라인 2개 (2026-10-04)

→ 상세: `prd/modules/outline.md`

- **아웃라인 노드** (`OUTLINE`, 팔레트 "통합"): 조회(제목만 / 제목+내용, 컬렉션, 최근 N일) · 검색 · 문서 하나 가져오기 ·
  등록(첫 `# 제목` 줄 → 글 제목, 코드블록 벗기기, 바로 게시/초안) · 수정(바꾸기 / 끝에 덧붙이기, 문서 주소로 지정 가능)
- 설정 화면: **연결 확인**(주소·키 바뀌면 자동, 워크스페이스·사용자·컬렉션 수) + 컬렉션 고르기 목록. API 키는 `OUTLINE_API_KEY` 환경 변수로도
- `showWhen`에 `|`로 여러 값 허용 (`action=조회|검색`)
- **로컬 샘플 Outline** (`docker/outline/`): Outline + Postgres + Redis + Dex(비밀번호 없는 mock 로그인), 127.0.0.1에만 열림,
  `seed.js`로 "사내 게시판" 샘플 글 12개
- **예시 파이프라인**: ① FW 관심 글 추천 — 매일 07:30 타이머(켜짐) → 게시판 제목 전체 조회 → AI(FW 담당자 기준 꼭 읽기/참고 + 링크) → md 파일
  ② 메모 → 게시글 등록 — 웹훅 `{"text":"메모"}` → AI(`# [분류] 제목` 형식 정리) → 아웃라인 등록. 둘 다 실제 실행 확인
- **버그 수정**: `HttpSender`가 평문 http에서 HTTP/2 업그레이드 헤더를 보내 Node.js 서버(Outline 등)가 400을 주던 문제 → http는 HTTP/1.1 고정
- 테스트 6개 추가 (총 33개 통과)

### 🖱️ 팔레트 드래그 앤 드롭 개선 (2026-10-03)

배경: 커서 오른쪽 아래에 노드 이름 글자만 따라다녀 부자연스러움 → 요즘 캔버스 도구(n8n·Figma·Linear) 방식으로

- **미리보기 = 실제 노드 카드 모양** (`components/DragGhost.tsx`): 컬러 헤더·아이콘·이름·소켓까지 같은 모양. 커서가 카드의 위쪽 가운데를 잡은 위치로 따라다님
  - 캔버스 밖: 살짝 기울고 반투명 (들고 있는 느낌), 0.9배
  - 캔버스 위: 똑바로 서고 파란 테두리, **현재 확대 배율과 같은 크기**
- **놓일 자리 슬롯**: 캔버스 위에서 실제로 놓일 위치를 점선 카드로 표시 (배경 점 격자 24px에 맞춰 부드럽게 이동) → 놓으면 정확히 그 자리에 생성
- 화면 아래 안내 알약: "캔버스로 끌어다 놓으세요 / 놓으면 이 자리에 추가 · Esc 취소"
- 4px 이상 움직여야 드래그 시작 (클릭과 구분), **Esc 취소**, 드래그 중 커서 전체 grabbing·텍스트 선택 방지
- 캔버스 강조: 굵은 점선 테두리 → 안쪽 은은한 빛
- 팔레트 항목: 컬러 아이콘 타일 + 이름, 호버 시 잡기 손잡이(⋮⋮), 끌고 있는 원본은 점선 자리 표시
- **더블클릭 → 보이는 캔버스 가운데에 추가** (같은 자리에 노드가 있으면 비켜서)
- 새 노드는 살짝 튀어오르며 등장하고 자동 선택 → 오른쪽 설정 패널이 바로 열림
- 변경 파일: `usePaletteDrag.ts`(재작성), `DragGhost.tsx`(신규), `Palette.tsx`, `App.tsx`, `index.css`

### ⏰ 타이머 모듈 + 항상 켜 두기 (2026-10-03)

→ 상세: `prd/modules/schedule.md`, 위 "항상 켜 두기"

- **타이머 노드** (`SCHEDULE`, 팔레트 "트리거"): 일정 간격(최소 1분) · 매일/평일/주말/요일 지정 시각 · cron · 한 번만.
  켜고 저장하면 즉시 등록(저장·삭제 이벤트 + 1분마다 재확인), 이전 실행 중이면 건너뜀, 놓친 회차는 실행 안 함.
  설정 화면에 다음 실행 3개·등록 상태·▶ 지금 한 번 실행·최근 실행 기록(파일, 100건). `RunMode.SCHEDULE` 추가
- **항상 켜 두기**: 백그라운드(창 없는 javaw) 실행·종료·상태확인·자동 시작 등록/해제 스크립트, 메모리 상한 384MB,
  중복 실행 방지, 파일 로그 회전(10MB×7일, 100MB 상한), 템플릿 캐시. 서버 상태/종료 API (`AppInfoController`,
  종료는 사용자 정의 헤더 필수 → 다른 웹사이트가 몰래 끌 수 없음)
- 실측: 백그라운드 실행 시 힙 17~19MB · 프로세스 약 135MB · 스레드 28개, 1분 타이머가 스스로 정시 실행, 두 번째 실행은 "이미 실행 중"으로 거부, 종료 스크립트로 정상 종료
- `FileWriteModule.timeVars` 공개 (타이머도 `${date}` 등 사용), `build.gradle` makeDist에 새 스크립트 포함
- 테스트 6개 추가 (총 27개 통과)
- **자동 시작 등록은 아직 실행하지 않음** (시스템 설정 변경이라 사용자 확인 후)

### 🔑 모델 연결 검증 + 하단 패널 높이 조절 + HTTP 요청 모듈 (2026-10-03)

- **Gemini 모델 연결 검증** (→ `prd/modules/ai-agent.md`): 파이프라인을 실행하지 않고 모델 부품 설정 화면에서
  키·모델을 바꾸면 자동 검증(0.8초 뒤) + 🔑 연결 확인 버튼. 모델 목록 조회로 키 확인(토큰 사용 없음) → 모델 존재 확인(별칭 포함).
  `POST /api/ai/gemini/verify`. 실제 Google로 가짜 키를 넣어 "API key not valid" 즉시 표시되는 것 확인
- **하단 실행 결과 패널 높이 조절**: 위쪽 가장자리 드래그(최소 90px ~ 화면 75%), 더블클릭 초기화(200px),
  localStorage `pipeline-bottom-panel-height`. 툴바(기본 위치)가 패널 높이에 맞춰 함께 이동
- **HTTP 요청 모듈** (`HTTP_REQUEST`, 팔레트 "통합", → `prd/modules/http-request.md`): 메소드·URL·헤더(`이름: 값` 줄)·바디(없음/JSON/Text/Form)·
  제한 시간·실패 응답 처리. 출력 `{status, ok, durationMs, headers, body}`. **🌐 HTTP 화면에서 이 요청 열기** 버튼.
  요청 전송 로직을 `service/HttpSender`로 분리해 상단 HTTP 메뉴와 공유. HTTP 화면이 `headers` 쿼리도 받도록 확장
- `NodeParam.showWhen`에 `!=`(다를 때 표시) 지원
- 테스트 4개 추가 (총 21개 통과)

### 🤖 핵심 모듈: AI 에이전트 + 부품(모델·메모리·지시문) (2026-10-02)

→ 상세: `prd/modules/ai-agent.md`

- **AI 에이전트 노드**: 아래쪽 **소켓 3개**(모델* · 메모리 · 지시문)에 부품 노드를 끼워 구성 (n8n AI Agent 방식)
- **부품 노드**: ✨ Gemini 모델(무료 키, 모델 목록 불러오기, 키 비우면 `GEMINI_API_KEY`), 🧠 대화 메모리(대화 ID별 최근 N턴, 파일 저장, 보기·지우기),
  📜 지시문(직접 입력 또는 파일 — 실행 때마다 다시 읽음, 여러 개 연결 시 순서대로 이어 붙임)
- **엔진 확장 (공통)**: 부품 노드·소켓 개념 (`PipelineConnection.toHandle`, `NodeTypeInfo.sockets/provides`, `NodeContext.parts()`),
  부품은 단독 실행 안 됨, 조건부 설정 칸 `NodeParam.showWhen`, 공용 선택 창 API `/api/dialog/folder|file`
- **에디터**: 소켓·부품 카드 모양, 종류가 맞는 소켓에만 연결되는 규칙, 점선 부품 연결
- **보안 수정**: Chrome이 저장된 로그인 정보를 API 키 칸에 자동으로 채워 넣는 문제 발견 → 에디터·HTTP 화면 입력칸 자동 채우기 차단
- 데모 파이프라인 "AI 에이전트 데모" 추가 (모델 부품에 키 입력 필요). 테스트 3개 추가 (총 17개 통과, 가짜 Gemini 서버 사용)

### 💾 두 번째 모듈: 파일 쓰기 (Windows 전용) (2026-10-02)

→ 상세: `prd/modules/file-write.md`

- **파일 쓰기 노드** (`FILE_WRITE`, 팔레트 "파일" 그룹): 앞 노드에서 받은 내용(기본 `${input}`)을 원하는 폴더에 원하는 확장자로 저장
  - 폴더(`%USERPROFILE%`·`~`·**📁 찾아보기** 폴더 선택 창), 파일명(`${date}` `${time}` `${datetime}` `${timestamp}` 등),
    확장자(txt·md·json·csv·log·html·xml·yaml·직접 입력), 같은 이름 처리(덮어쓰기·이어쓰기·새 이름·오류), 인코딩(UTF-8·BOM·MS949), json 정렬
  - 안전장치: Windows 외 OS 차단, 시스템 폴더 차단, 파일명 금지 문자·예약어 자동 정리
  - 출력: `{path, folder, fileName, extension, mode, encoding, bytes}` → `${file.path}`
- 새 파라미터 타입 `folder` (입력 + 찾아보기), 폴더 선택 API `POST /api/file-write/pick-folder`
- `NodeContext.resolve(text, extra)` 오버로드 — 모듈 전용 변수 우선
- 설정 패널 라벨의 대문자 변환 제거 (`${date}`가 `${DATE}`로 보이던 문제)
- 데모 파이프라인 "파일 쓰기 데모" 추가 (웹훅 `filedemo` → 바탕화면 `workflow-studio` 폴더 txt)
- 테스트 5개 추가 (총 14개 통과)

### 🛡️ 열린 앱 탭(세션) 관리 — 최대 개수 경고 + 선택 종료 (2026-10-02)

- **상단 메뉴 오른쪽 "탭 N/M" 배지** (모든 화면): 이 브라우저에 열린 앱 탭 수 / 최대 개수. 초과 시 빨간색 깜빡임
- **배지 클릭 → 관리 패널**: 열린 탭 목록(화면 이름 · 경로 · 마지막 사용 시각, 현재 탭 표시), 탭별 **종료** / **이 탭 종료**,
  **다른 탭 모두 종료**, **최대 개수** 선택(1·2·3·4·5·6·8·10, 기본 3, localStorage `app-max-tabs`)
- **최대 초과 시**: 새로 연 탭에 경고 패널이 자동으로 열림 + **"오래된 탭부터 N개 정리"**(현재 탭 제외, 가장 오래 안 쓴 순).
  최대 개수를 낮춰 초과돼도 즉시 경고
- **종료 동작**: 대상 탭이 스스로 닫기 시도 → 브라우저가 막으면(사용자/외부에서 연 탭은 보통 막힘)
  `session-closed.html`("이 탭의 세션이 종료되었습니다")로 전환 — 이 페이지는 세션 스크립트가 없어 서버 연결·요청을 하지 않음
- 편집 화면 탭 제목 = 파이프라인 이름 (패널에서 구분용)
- **구현**: 공유 워커(`static/js/app-live-worker.js?v=2`)가 서버 상시 연결과 함께 탭 목록을 관리
  (hello/update/active/alive/bye, 150초간 응답 없는 탭은 정리), 페이지 쪽은 `static/js/app-session.js`(배지·패널 UI).
  `layout.html`의 인라인 연결 스크립트를 이 파일로 교체. 워커 프로토콜을 바꾸면 `?v=` 버전을 올릴 것
- **한계**: 브라우저별로 따로 관리됨 (Edge 탭과 Chrome 탭은 서로 안 보임)
- 브라우저에서 확인: 4번째 탭에서 경고 자동 표시 → 오래된 탭 정리 → 개별 종료 → 최대 1로 낮추면 경고 → 서버 재시작 시 새로고침 유지

### 🐛 목록↔편집 화면을 오가면 화면이 멈추던 문제 수정 (2026-10-02)

- **증상**: 파이프라인 목록 → 항목 클릭 → 목록 … 을 반복하면 화면이 멈춤 (서버는 정상 응답)
- **원인**: 재시작 감지용 SSE 연결(`/api/app/live`)을 **탭/페이지마다** 하나씩 열어서,
  브라우저의 "같은 주소 동시 연결 6개" 제한이 차 버림 → 이후 요청이 빈 연결을 기다리며 멈춤
  (확인 당시 Edge → localhost:8080 연결이 정확히 6개)
- **수정**: 연결을 **SharedWorker 하나**(`static/js/app-live-worker.js`)가 모든 탭 대신 유지 → 브라우저당 상시 연결 1개.
  각 페이지는 워커에서 서버 실행 id만 받아 바뀌면 새로고침 (`layout.html`). 뒤로가기 캐시 복원 시 재연결
- 서버: 30초마다 하트비트로 끊긴 연결 정리 (`AppLiveController`), 기동 로그 문구를 "브라우저 N개"로 변경
- 검증: 목록↔편집 30회 이동 후에도 브라우저 연결 2~3개 유지, 요청 응답 4ms. 재시작 시 기존 탭 새로고침·새 탭 미생성 확인

### 🔁 재시작 시 새 탭 대신 기존 탭 재사용 (2026-10-02)

**문제**: 서버를 켤 때마다 브라우저에 새 탭이 열려 탭이 계속 늘어남. 서버가 연 탭은 브라우저 보안상 스크립트로 닫을 수 없음.

**해결**: 열려 있는 앱 화면과 서버를 SSE로 상시 연결
- `GET /api/app/live` (`AppLiveController`): 연결 시 이번 서버 실행의 고유 id(`instance`) 전송, 끊기면 1초 간격 재연결(`retry`)
- 모든 화면(`layout.html` 공통 스크립트, 에디터 포함): 재연결 시 id가 바뀌었으면 **자동 새로고침** → 새 코드가 그 탭에 반영
- 서버 기동 후 4초 기다렸다가 재연결된 탭이 있으면 **새 탭을 열지 않음**, 없을 때만 브라우저 열기
  (로그: "열려 있던 앱 탭 N개가 다시 연결됨 → 새 탭을 열지 않고 기존 탭을 새로고침합니다")
- 에디터에 저장 안 한 변경이 있으면 새로고침 전에 브라우저 확인창이 뜸
- ~~주의: 탭을 6개 이상 열면 멈출 수 있음~~ → 같은 날 SharedWorker 방식으로 바꿔 해결 (위 🐛 항목)

### 🧭 파이프라인 탭 위치 기억 + 목록 버튼 + 저장 안 됨 표시 (2026-10-02)

- **파이프라인 탭이 마지막 위치 기억**: 다른 메뉴(HTTP·메모 등)에서 상단 "파이프라인"을 누르면 파이프라인 탭에서
  마지막으로 보던 화면으로 이동 (편집 중이던 파이프라인 → 그 편집 화면, 목록에 있었으면 → 목록).
  - localStorage 키 `nav-last-pipelines-url`: 목록 화면은 `layout.html` 스크립트가, 편집 화면은 에디터(React)가 기록
  - 파이프라인 탭 안(목록·편집 화면)에서 누르는 "파이프라인"은 항상 목록으로
  - 삭제된 파이프라인이면 서버가 목록으로 보내고, 그 순간 목록으로 다시 기록됨
- **"← 파이프라인 목록" 버튼**: 왼쪽 노드 팔레트 맨 위 (처음엔 하단 툴바에 뒀다가 보기 안 좋아 같은 날 이동)
- **저장 안 된 변경 표시**: 마지막 저장/불러온 상태와 비교해 바뀌면 저장 버튼이 노란색 + "●".
  저장 안 한 채 페이지를 떠나면(목록 버튼·다른 메뉴·새로고침·닫기) 브라우저가 확인창을 띄움
- 브라우저에서 확인: 편집 화면 → HTTP → "파이프라인" 클릭 시 편집 화면 복귀, "← 목록" 후엔 목록으로, 수정 시 저장 버튼 표시

### 🏷️ 노드 이름 + 웹훅 전달 내용 구성 (2026-10-01)

→ 상세: `prd/modules/webhook.md` "전달 내용 구성"

- **노드 이름**: 웹훅에 `name` 파라미터 추가. 이름을 지정하면 캔버스 카드 제목(헤더에 "트리거 · 웹훅" 원래 타입 표시),
  설정 패널 헤더, 하단 실행 로그에 이름이 표시됨. 웹훅을 여러 개 둘 때 구분용.
  프론트 `nodeDisplayName()` — **모든 모듈 공통 규칙: 설정에 `name`이 있으면 그것을 표시명으로 사용**
- **전달 내용 구성** (`outputTemplate`): `${a}==${b}==${c}` 처럼 받은 JSON 값을 조합해 다음 노드로 전달.
  `${a}`는 바디 키 축약, `${body.x}`·`${query.x}`·`${headers.x}`·`${method}`·배열 인덱스 지원. 비우면 기존처럼 요청 전체 JSON.
  구성 시 요청 전체는 `${webhook.request...}`로 참조
- **에디터**: 마지막 수신 요청 기준 키 칩(클릭 삽입) + 실시간 미리보기 (`POST /api/webhooks/{nodeId}/preview`)
- **리팩터링**: `${...}` 치환 로직을 `node/Templates.java`로 분리 → NodeContext와 웹훅이 같은 규칙 사용.
  Map/List 값도 직접 탐색, null은 빈 문자열로 치환
- 테스트: 조합 결과·`${input}` 전달·`${webhook.request.body.a}`·없는 키 유지·미리보기 API (총 9개 통과).
  브라우저에서 이름 지정 → 칩으로 `${a}==${b}==${c}` 구성 → 미리보기 "사과==바나나==3" → 저장 후 실제 요청 출력 "딸기==포도==99" 확인

### 🌐 HTTP 메뉴 (간이 HTTP 클라이언트) + 웹훅 → HTTP 테스트 연결 (2026-10-01)

- **상단 메뉴 "HTTP"** (메모·북마크·달력·파이프라인과 같은 레벨, `/http`) — Postman처럼 요청을 간단히 보내 보는 화면
  - 메소드(GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS) + URL + 헤더(키/값 행) + 바디(없음/JSON/Text/Form, JSON 정렬)
  - 응답: 상태 코드(색상), 소요 시간, 크기, 바디(JSON이면 정렬+색상), 응답 헤더
  - 왼쪽 **보낸 요청** 기록: 이 브라우저 localStorage에 최근 50건, 클릭 시 다시 불러오기
  - Ctrl+Enter로 보내기. Content-Type이 없으면 바디 타입에 맞춰 자동 추가
  - 요청은 서버가 대신 보냄(`POST /api/http-client/send`, Java HttpClient) → 외부 URL도 CORS 제약 없음.
    Host/Content-Length 등 자동 헤더는 무시하고 응답에 `skippedHeaders`로 알려줌, 응답 바디 2MB까지 표시, 타임아웃 30초
  - `?method=&url=&body=&btype=&from=webhook` 쿼리로 미리 채워서 열 수 있음
- **웹훅 노드 "🚀 HTTP로 테스트 요청 보내기" 버튼** (설정 탭 URL 아래): HTTP 화면을 **새 탭**으로 열고
  테스트 URL·노드의 HTTP 메소드(모두 → POST)·예시 JSON 바디를 채움. 에디터 탭은 그대로라 실행 과정이 실시간으로 보임.
  테스트 URL이 꺼져 있으면 버튼 아래에 "먼저 ▶ 수신 대기를 누르세요" 경고
- 구현: `controller/HttpClientController.java`, `templates/http/index.html`(바닐라 JS), 모든 화면 네비게이션에 HTTP 링크 추가
- 테스트: `HttpClientControllerTest` — 실제 포트로 띄워 HTTP 클라이언트 → 웹훅 테스트 URL 호출 성공, 잘못된 URL 오류 (총 7개 통과).
  브라우저에서 버튼 → HTTP 화면 자동 채움 → 보내기 → 에디터 탭에 "웹훅 테스트 수신 ✓ 완료" 확인

### 🟢 웹훅 수신 대기 / 수신 기록 / 패널 너비 조절 (2026-10-01)

→ 상세: `prd/modules/webhook.md`

- **수신 대기 토글**: 웹훅이 있는 파이프라인에선 툴바 실행 버튼이 **▶ 수신 대기 / ■ 중지**.
  누르면 웹훅 노드의 테스트 URL을 켜고/끄고 즉시 저장. 켜져 있으면 노드가 초록 테두리 + ● LIVE (운영 URL이 켜져도 표시),
  하단 패널에 "● 수신 대기 중" → 요청이 오면 실행 과정을 실시간 표시. 테스트 URL 기본값을 꺼짐으로 변경
- **수신 기록**: 웹훅으로 들어온 모든 요청(거부 포함)을 노드별로 `{storage}/webhooks/{nodeId}.json`에 최근 200건 저장.
  웹훅 노드의 오른쪽 패널 **수신 기록** 탭에서 목록·상세(JSON)·실행 결과 다시 보기·기록 삭제.
  기존 메모리 전용 `WebhookPayloadStore` → 파일 저장 `WebhookHistoryStore`로 교체 (수동 실행 재사용도 기록 기반)
- **오른쪽 패널 너비 조절**: 패널 왼쪽 가장자리를 드래그 (240px ~ 화면 70%), 더블클릭 시 기본(300px)으로,
  너비는 localStorage(`pipeline-config-panel-width`)에 저장
- 테스트 추가: 거부 요청까지 수신 기록에 남는지 확인 (총 5개 테스트 통과), 브라우저에서 토글·실시간 표시·기록 탭·패널 조절 확인

### 🪝 첫 모듈: 웹훅 트리거 (2026-10-01)

→ 상세: `prd/modules/webhook.md`

- **웹훅 노드** (`WEBHOOK`, 팔레트 "트리거" 그룹): 고정 URL로 요청을 받으면 파이프라인 시작
  - 테스트 URL `/webhook-test/{path}` · 운영 URL `/webhook/{path}` — 노드 설정에서 **각각 켜짐/꺼짐**
  - `{path}`는 설정값 또는 노드 ID (고정). 설정 패널에 두 URL + ON/OFF + 복사 버튼 표시
  - 테스트 수신 시 열려 있는 에디터에 실행 과정이 실시간 표시, 운영은 백그라운드 실행
  - 수신 내용(method, query, headers, body)을 JSON으로 출력 → 화살표로 다음 노드에 전달
- **엔진 확장** (모든 모듈 공통)
  - 트리거 개념: `NodeTypeInfo.trigger`, `runTriggered()` — 트리거에서 화살표로 도달 가능한 노드만 실행
  - `${input}`: 화살표로 바로 이어진 직전 노드의 출력 (`NodeContext.input()`)
  - 실행 모드 `MANUAL` / `TEST` / `PROD` (`RunMode`), 실행 기록 최대 200개 유지
  - API: `GET /api/pipelines/{id}/executions/latest?mode=`, 실행 상태에 `mode`·`startedAt` 추가
- **에디터**: 트리거 노드는 입력 핸들 없음(왼쪽 둥근 카드), `webhookUrls` 파라미터 타입,
  하단 패널에 "🪝 웹훅 테스트 수신" 표시
- 모듈 코드는 `node/modules/<모듈명>/` 패키지에 모듈 전용 컨트롤러·저장소까지 함께 두는 구조로 정함
- 테스트: `WebhookModuleTest` (값 전달, 도달 불가 노드 미실행, 켜짐/꺼짐, 메소드 제한, 수동 실행 재사용)

### 🧹 파이프라인 노드 모듈 제로베이스 재시작 (2026-10-01)

**배경**: 앱의 핵심이 메모에서 GUI 자동화(n8n 스타일)로 바뀜. 파이프라인의 노드 모듈을 모두 걷어내고
에디터 GUI + 실행 엔진만 남긴 뒤, 노드 모듈을 처음부터 하나씩 다시 설계하기로 함.
메모·북마크·달력은 그대로 유지.

**삭제**
- 노드 모듈 11종 전부: `HTTP_REQUEST`, `OPEN_URL`, `WAIT_IMAGE`, `CLICK_IMAGE`, `TYPE_TEXT`, `KEY_PRESS`,
  `DELAY`, `SCREENSHOT`, `SAVE_MD`, `CALL_AI`, `AI_AGENT`, `OUTLINE_*` 및 관련 헬퍼(`ImageMatcherUtil`, AI 호출, MD 저장)
- 앱 설정 화면 + `AppSettings`(노드용 API 키, 이미지 매칭 설정, Outline 설정) + `/api/settings` — 네비게이션의 "설정" 메뉴 제거
- Outline Mock 컨트롤러, `outline-local/` 테스트 환경
- 저장된 파이프라인, `settings.json` (data/의 메모·북마크·달력 JSON은 git에서 복원)
- 노드 모듈 관련 PRD (`feature-1-macro.md`, `feature-2-pipeline.md`, `feature-3-outline.md`) — git 이력에 남아 있음

> 작업 중 메모/북마크/달력까지 삭제했다가 같은 날 원복함 (코드·템플릿·`data/*.json` 모두 git HEAD 기준 복원, 패키지만 변경).

**유지**
- 메모 / 북마크 / 달력 기능 (기존과 동일, 홈 `/` → `/memos`)
- React 파이프라인 에디터 GUI 전체 (캔버스, 팔레트, 설정/결과 패널, 하단 로그, 툴바)
- 실행 엔진 (위상정렬, 실행 상태 폴링, 취소, 출력 변수 저장)
- `${변수}` / `${변수.path[0]}` 치환 → `NodeContext`로 이동
- 파이프라인 CRUD, ZIP 내보내기/가져오기, 이미지 업로드/화면 캡처 API

**신규 / 변경**
- 프로젝트명 `memo-bookmark-app` → `workflow-studio`, 패키지 `com.app.memobookmark` → `com.app.workflowstudio`
  (jar: `workflow-studio-1.0.0.jar`, 배포 ZIP: `workflow-studio.zip`)
- 모듈 시스템: `NodeModule` 인터페이스 + `NodeTypeRegistry`가 `@Component` 빈을 자동 수집.
  기존엔 `NodeTypeRegistry`(메타데이터)와 `PipelineRunnerService`(거대한 switch)에 노드가 흩어져 있었음
- 엔진은 등록되지 않은 노드 타입을 만나면 `등록되지 않은 노드 타입: X` 오류로 중단
- 팔레트: 모듈이 없으면 "등록된 노드 모듈이 없습니다" 표시, 그룹 순서는 모듈 등록(@Order) 순서
- 화면 캡처 헬퍼(`getAllScreenBounds`, `captureAllScreens`)를 `ScreenCaptureService` 내부로 이동
- 화면 브랜드("내 메모")와 네비게이션(메모·북마크·달력·파이프라인)은 기존 유지, 설정 메뉴만 제거

> ⚠️ 프로젝트 **폴더명**(`C:\Dev\memo-bookmark-app`)과 `.idea/` 모듈 설정은 아직 옛 이름. 필요하면 IDE를 닫고 직접 변경.

### 🔄 데스크톱 앱 → 로컬 웹앱 전환 (2026-10-01)

**배경**: JavaFX WebView로 창을 띄우는 대신, 서버만 띄우고 브라우저로 localhost에 접속하는 방식이 더 단순하고 범용적이라 전환.

- 삭제: `DesktopApp.java`, `Launcher.java`, `dist/libs/javafx-*.jar`, `build.gradle`의 JavaFX 설정
- 진입점: Spring Boot 메인 클래스 (headless=false, `ApplicationReadyEvent`에서 브라우저 자동 오픈)
- 포트: `server.port=0`(랜덤) → `8080` 고정, `server.address=127.0.0.1`
- `ScreenCaptureService`: JavaFX Stage 숨김/복원 제거 → 오버레이 닫힌 뒤 300ms 대기 후 캡처
- `실행.bat` / `run.bat`: `java -jar` 콘솔 실행, `chcp 65001`
- 사라진 기능: 시스템 트레이, 트레이 최소화, JavaFX Alert 대체 다이얼로그
