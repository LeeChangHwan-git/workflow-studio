# 메모 & 북마크 앱 — 프로젝트 개요

## 컨셉

회사 생활 편의를 위한 **개인용 로컬 데스크톱 앱**.  
별도 DB 없이 JSON 파일로 데이터를 저장하고, 인터넷 없는 오프라인 환경에서도 동작.  
Spring Boot 웹앱을 JavaFX WebView로 감싸서 브라우저 없이 단독 실행되는 형태.

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 3.2.5 + Thymeleaf |
| 데스크톱 래퍼 | JavaFX 21.0.2 (WebView) |
| 빌드 | Gradle (bootJar + 커스텀 makeDist 태스크) |
| 데이터 저장 | JSON 파일 (Jackson) — DB 없음 |
| 배포 | 자체 완결 ZIP (JAR + JavaFX Win 네이티브 JARs) |

---

## 아키텍처 핵심

```
실행.bat
  └─ java --module-path libs --add-modules javafx.* -jar memo-bookmark-app.jar
        ├─ Launcher.main()
        │    └─ Application.launch(DesktopApp.class)
        ├─ DesktopApp.init()  → 별도 스레드에서 Spring Boot 기동 (classloader 수동 상속)
        └─ DesktopApp.start() → 로딩 화면 → portFuture 완료 시 WebView로 교체
```

- `server.port=0` 으로 랜덤 포트 사용 (고정 포트 불필요)
- Spring Boot classloader를 명시적으로 자식 스레드에 상속해야 AutoConfiguration 정상 동작
- `target="_blank"` 링크는 `setCreatePopupHandler`로 가로채 OS 기본/지정 브라우저로 열기
- `confirm()` / `alert()` 는 `setConfirmHandler` / `setOnAlert`로 JavaFX Alert으로 대체
- 클립보드 복사는 `execCommand('copy')` 폴백 사용 (WebView는 secure context 아님)
- 시스템 트레이 아이콘으로 최소화/복원, 창 닫아도 트레이로 이동

---

## 데이터 저장 구조

저장 경로: `application.properties`의 `app.storage.path` (기본 `./data`)

```
data/
├── memos.json       # 메모 목록
├── bookmarks.json   # 북마크 목록
└── calendar.json    # 달력 이벤트 목록
```

각 JSON 파일은 해당 모델 객체의 List를 직렬화한 배열.  
LocalDate / LocalDateTime은 `jackson-datatype-jsr310` 모듈로 처리.

---

## 현재 구현된 기능

### 1. 메모 관리
- 시스템명(제목) + 카테고리 + 내용(자유 텍스트)으로 구성
- 카드 그리드 UI — 시스템별로 색상 띠/아이콘 자동 배정 (8색 순환)
- 내용 전체 노출 (클릭 없이 바로 보임), 최대 높이 180px 스크롤
- 클립보드 복사 버튼 (비밀번호 등 한 번에 복사)
- 검색 (시스템명 + 내용)

### 2. 북마크 관리
- 이름 + URL + 카테고리 + 메모 + **열기 브라우저 지정**
- 브라우저 선택: 기본 브라우저 / Edge / Chrome / IE
  - 서버 사이드에서 `ProcessBuilder("cmd", "/c", "start", <브라우저>, <url>)` 실행
- URL은 `type="text"` (형식 검증 없음 — `google.com`, IP 등 모두 허용)
- 검색 (이름 + URL + 설명)

### 3. 달력
- 월별 캘린더 뷰 (일~토, 일요일 기준)
- 날짜 셀에 이벤트 pill 미리보기 (최대 2개 + 초과 표시)
- 날짜 클릭 → 우측 패널에 당일 이벤트 목록 + 인라인 추가/수정/삭제
- 이벤트: 제목(선택) + 내용(필수)
- 오늘 날짜 강조, 일/토 색상 구분

---

## 배포 구조

```
memo-bookmark-app.zip
├── memo-bookmark-app-1.0.0.jar   # Spring Boot fat JAR (앱 전체 포함)
├── libs/
│   ├── javafx-base-21.0.2-win.jar
│   ├── javafx-controls-21.0.2-win.jar
│   ├── javafx-graphics-21.0.2-win.jar
│   ├── javafx-web-21.0.2-win.jar
│   ├── javafx-fxml-21.0.2-win.jar
│   └── javafx-media-21.0.2-win.jar
├── application.properties        # 외부 설정 (저장 경로 등 변경 가능)
├── 실행.bat                       # 더블클릭으로 실행
└── 저장경로변경.bat
```

**실행 조건**: Windows + JDK 21 이상 필요  
JDK 17이 기본인 경우 `실행.bat`에서 `javaw` 대신 JDK 21 전체 경로 지정 가능.

---

## 소스 경로

```
/Users/changhwanlee/01.private/02.toy/memo-bookmark-app/
├── src/main/java/com/app/memobookmark/
│   ├── Launcher.java           # 진입점 (Application.launch 위임)
│   ├── DesktopApp.java         # JavaFX Application (Spring Boot 기동 + WebView)
│   ├── MemoBookmarkApplication.java
│   ├── config/                 # StorageConfig (저장 경로 Bean)
│   ├── controller/             # MemoController, BookmarkController, CalendarController
│   ├── model/                  # Memo, Bookmark, CalendarEvent
│   └── service/                # MemoService, BookmarkService, CalendarEventService
├── src/main/resources/
│   ├── templates/
│   │   ├── fragments/layout.html   # 공통 CSS/nav fragment
│   │   ├── memo/                   # list.html, form.html
│   │   ├── bookmark/               # list.html, form.html
│   │   └── calendar/               # index.html
│   └── application.properties
├── dist/                       # 배포 파일 (ZIP 생성 결과물)
├── prd/                        # 기획 문서
└── build.gradle
```
