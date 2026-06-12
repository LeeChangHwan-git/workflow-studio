# 기능1 — 매크로 자동화

## 한 줄 요약

이미지 기반 화면 클릭 매크로를 앱 안에서 등록/실행하는 기능.  
사내 시스템 반복 작업(로그인, 메뉴 탐색 등)을 자동화하는 것이 주 목적.

---

## 사용자 시나리오

1. 앱에서 "새 매크로" 생성, 이름 입력 (예: "사내 인트라넷 로그인")
2. 스텝을 순서대로 추가:
   - URL 열기 (어떤 브라우저로 열지 선택)
   - 이미지 대기 (지정 이미지가 화면에 나타날 때까지 최대 N초 대기)
   - 이미지 클릭 (지정 이미지를 화면에서 찾아 클릭)
   - 텍스트 입력 (키보드 입력 시뮬레이션)
   - 딜레이 (N초 대기)
3. 각 스텝의 이미지는 **앱 내 스크린샷 캡처**로 바로 저장 가능
4. 매크로 저장 후 목록에서 클릭 한 번으로 실행

---

## 스텝 타입 상세

| 타입 | 파라미터 | 설명 |
|------|----------|------|
| `OPEN_URL` | url, browser | 지정 브라우저로 URL 열기 |
| `WAIT_IMAGE` | imagePath, timeoutSec | 이미지가 화면에 나타날 때까지 대기 |
| `CLICK_IMAGE` | imagePath, offsetX, offsetY | 이미지 중심 (+ offset) 클릭 |
| `TYPE_TEXT` | text | 키보드 입력 (메모의 값을 참조 가능: `${메모.시스템명.필드}`) |
| `DELAY` | seconds | N초 대기 |
| `KEY_PRESS` | key | 특수키 입력 (ENTER, TAB, ESC 등) |

---

## 이미지 매칭 방식

- Java `Robot.createScreenCapture()` 로 전체 화면 캡처
- `BufferedImage` 픽셀 단위 비교로 저장된 이미지의 위치 탐색 (외부 라이브러리 없음)
- 일치 임계값(threshold) 설정 가능 — 기본 95% 이상 일치 시 매칭
- 못 찾으면 설정된 간격(기본 0.5초)으로 재시도, timeout 초과 시 스텝 실패 처리

> **한계**: 해상도/DPI 변경, 버튼 색상 미세 변화, HiDPI 환경에서 오차 가능.  
> 정밀도가 필요하면 추후 OpenCV(opencv-java) 도입 검토 — JAR 약 50MB 증가.

---

## 스크린샷 캡처 흐름 (앱 내)

1. 스텝 편집 화면에서 "화면 캡처" 버튼 클릭
2. 앱 창이 최소화되고 3초 카운트다운 오버레이 표시
3. 사용자가 캡처할 화면으로 이동
4. 전체 화면에 반투명 오버레이 + 드래그로 영역 선택
5. 선택 완료 → 이미지 파일로 저장, 스텝에 자동 연결
6. 앱 창 복원

구현: JavaFX 투명 Stage (`StageStyle.TRANSPARENT`) + 마우스 드래그 이벤트

---

## 데이터 구조 (예상)

```json
// data/macros.json
[
  {
    "id": "uuid",
    "name": "사내 인트라넷 로그인",
    "steps": [
      { "type": "OPEN_URL",    "url": "https://intranet.company.com", "browser": "edge" },
      { "type": "WAIT_IMAGE",  "imagePath": "macros/images/abc123.png", "timeoutSec": 10 },
      { "type": "CLICK_IMAGE", "imagePath": "macros/images/def456.png", "offsetX": 0, "offsetY": 0 },
      { "type": "TYPE_TEXT",   "text": "${메모.사내인트라넷.ID}" },
      { "type": "KEY_PRESS",   "key": "TAB" },
      { "type": "TYPE_TEXT",   "text": "${메모.사내인트라넷.PW}" },
      { "type": "KEY_PRESS",   "key": "ENTER" }
    ],
    "createdAt": "...",
    "updatedAt": "..."
  }
]
```

이미지 파일 저장 위치: `{app.storage.path}/macros/images/`

---

## 화면 구성 (예상)

```
[매크로 목록]
┌─────────────────────────────────┐
│ 매크로                  + 새 매크로│
├─────────────────────────────────┤
│ ▶ 사내 인트라넷 로그인    [실행] [수정] [삭제] │
│ ▶ 주간 보고 시스템        [실행] [수정] [삭제] │
└─────────────────────────────────┘

[매크로 편집]
┌─────────────────────────────────┐
│ 이름: [사내 인트라넷 로그인       ] │
├─────────────────────────────────┤
│ 1. URL 열기   https://...  [Edge] │
│ 2. 이미지 대기 [login_form.png] 10s│
│ 3. 이미지 클릭 [id_field.png]      │
│ 4. 텍스트 입력 ${메모.인트라넷.ID} │
│    ＋ 스텝 추가                    │
└─────────────────────────────────┘
```

---

## 구현 시 주요 고려사항

- **매크로 실행 중 앱 창 처리**: WebView 앱 창이 앞에 있으면 화면 인식 방해 → 실행 중 최소화 필요
- **스텝 실패 처리**: 이미지 못 찾으면 나머지 스텝 중단 + 실패 알림
- **메모 값 참조**: `${메모.시스템명.필드}` 문법으로 저장된 메모 내용을 텍스트 입력에 활용 (파싱 필요)
- **다중 모니터**: `Robot.createScreenCapture()`는 기본 모니터만 캡처 — 확장 필요 시 `GraphicsEnvironment` 활용
- **실행 로그**: 스텝별 성공/실패 결과를 화면에 실시간 표시

---

## 기술 구현 포인트

```java
// 화면에서 이미지 찾기 (핵심 로직)
Robot robot = new Robot();
BufferedImage screen = robot.createScreenCapture(screenBounds);
Point found = findImage(screen, templateImage, threshold); // 픽셀 비교
if (found != null) {
    robot.mouseMove(found.x + offsetX, found.y + offsetY);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
}

// 텍스트 입력
for (char c : text.toCharArray()) {
    int keyCode = KeyEvent.getExtendedKeyCodeForChar(c);
    robot.keyPress(keyCode);
    robot.keyRelease(keyCode);
}
```

---

## 우선순위 및 구현 순서 (제안)

1. 매크로 CRUD (모델 + 서비스 + 목록/편집 화면)
2. 스텝 편집 UI (타입별 파라미터 폼)
3. 이미지 파일 업로드/저장
4. 매크로 실행 엔진 (Robot 기반)
5. 앱 내 스크린샷 캡처 (투명 오버레이 Stage)
6. 메모 값 참조 파싱 (`${메모.xxx}`)
7. 실행 로그 UI
