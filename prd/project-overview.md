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
| 언어 | Java 17+ (실행은 JDK 21 권장) |
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
   └─ REST /api/** ── PipelineApiController
                         ├─ PipelineService        파이프라인 JSON CRUD, ZIP 내보내기/가져오기, 이미지 저장
                         ├─ PipelineRunnerService  실행 엔진: 위상정렬 → 노드 순차 실행 → 로그/상태
                         │     └─ NodeTypeRegistry → NodeModule (노드별 동작, 모듈로 추가)
                         └─ ScreenCaptureService   설정 패널의 '화면 캡처'(image 파라미터용)

외부 ── /webhook-test/{path}, /webhook/{path} ── WebhookController ── runTriggered()
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
└── pipelines/
    ├── {uuid}.json      # 파이프라인 정의 (노드 + 연결 + 노드 설정)
    └── images/          # image 타입 파라미터로 업로드/캡처한 이미지
```

> ⚠️ `data/`는 `.gitignore` 대상 (단, 메모·북마크·달력 JSON 3개는 예전부터 git에 추적 중).
> 파이프라인을 다른 PC로 옮길 땐 에디터의 **내보내기(ZIP) → 가져오기** 사용.

---

## 기능

### 파이프라인 자동화 (핵심)
노드 모듈을 처음부터 하나씩 설계 중 → `prd/modules/README.md`
- 현재 모듈: 🪝 웹훅 (트리거)

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

- JDK 21 필요. `JAVA_HOME` 미설정 PC에선 직접 지정 (예: `~/.jdks/ms-21.0.7`)
- PowerShell에서 java 실행 시 `"-Djava.awt.headless=false"`처럼 **따옴표 필수** (안 그러면 인자가 쪼개져 실행 실패). bat 파일(cmd)은 문제없음
- 8080 포트가 사용 중이면 기동 실패 → `server.port` 변경

### 배포 ZIP 구성

```
workflow-studio.zip
├── workflow-studio-1.0.0.jar
├── application.properties   # 외부 설정 (포트, 저장 경로 등)
├── 실행.bat                  # 더블클릭 → 서버 기동 + 브라우저 오픈 (창 닫으면 종료)
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
│   └── PipelineApiController.java   # 파이프라인 REST API
├── model/                           # Memo, Bookmark, CalendarEvent, Pipeline*, RunLog
├── node/                            # ★ 모듈 시스템
│   ├── NodeModule.java              #   모듈 인터페이스 (info + execute)
│   ├── NodeContext.java             #   설정값 조회 + ${변수} 치환
│   ├── NodeTypeInfo.java / NodeParam.java / RunMode.java
│   ├── NodeTypeRegistry.java        #   @Component 모듈 자동 수집
│   └── modules/                     #   모듈 구현 (모듈당 패키지 하나)
│       └── webhook/                 #     WEBHOOK 트리거 (Module, Controller, PayloadStore)
└── service/
    ├── Memo/Bookmark/CalendarEventService.java
    ├── PipelineService.java
    ├── PipelineRunnerService.java
    └── ScreenCaptureService.java
src/main/resources/templates/        # layout, memo/, bookmark/, calendar/, pipeline/
frontend/pipeline-editor/            # React 에디터 소스
```

---

## 변경 이력

### 🔁 재시작 시 새 탭 대신 기존 탭 재사용 (2026-10-02)

**문제**: 서버를 켤 때마다 브라우저에 새 탭이 열려 탭이 계속 늘어남. 서버가 연 탭은 브라우저 보안상 스크립트로 닫을 수 없음.

**해결**: 열려 있는 앱 화면과 서버를 SSE로 상시 연결
- `GET /api/app/live` (`AppLiveController`): 연결 시 이번 서버 실행의 고유 id(`instance`) 전송, 끊기면 1초 간격 재연결(`retry`)
- 모든 화면(`layout.html` 공통 스크립트, 에디터 포함): 재연결 시 id가 바뀌었으면 **자동 새로고침** → 새 코드가 그 탭에 반영
- 서버 기동 후 4초 기다렸다가 재연결된 탭이 있으면 **새 탭을 열지 않음**, 없을 때만 브라우저 열기
  (로그: "열려 있던 앱 탭 N개가 다시 연결됨 → 새 탭을 열지 않고 기존 탭을 새로고침합니다")
- 에디터에 저장 안 한 변경이 있으면 새로고침 전에 브라우저 확인창이 뜸
- **주의**: 브라우저는 같은 주소(localhost:8080)에 동시 연결을 6개까지만 허용 → 앱 탭을 6개 이상 열어 두면 화면이 멈출 수 있음.
  탭은 1~2개만 유지할 것

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
