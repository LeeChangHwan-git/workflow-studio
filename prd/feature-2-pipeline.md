# 기능2 — 파이프라인 자동화

## 구현 상태: ✅ 완료 (2026-06-24)

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
5. 노드의 출력 포트(오른쪽 점)를 클릭 → 다음 노드 입력 포트(왼쪽 점) 클릭으로 연결
6. **저장** → **실행** 버튼으로 파이프라인 즉시 실행
7. 실행 모달에서 노드별 성공/실패 로그 실시간 확인
8. **내보내기** 버튼으로 `.zip` 파일 다운로드 → 다른 PC에서 **가져오기**로 재사용

---

## 지원 노드 타입

| 타입 | 아이콘 | 파라미터 | 설명 |
|------|--------|----------|------|
| `OPEN_URL` | 🌐 | url, browser | 지정 브라우저로 URL 열기 |
| `WAIT_IMAGE` | ⏳ | imagePath, timeoutSec | 이미지가 화면에 나타날 때까지 대기 |
| `CLICK_IMAGE` | 🖱️ | imagePath, offsetX, offsetY | 이미지 중심 클릭 |
| `TYPE_TEXT` | ⌨️ | text | 클립보드 경유 텍스트 입력 (한글 지원) |
| `KEY_PRESS` | 🔑 | key | 특수키 입력 (ENTER/TAB/ESC 등) |
| `DELAY` | ⏱️ | seconds | N초 대기 |
| `SCREENSHOT` | 📸 | outputVar | 전체 화면 캡처 후 변수 저장 |
| `CALL_AI` | 🤖 | prompt, model, outputVar | Claude API 질의 |

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
  "imageMatchRetryMs": 500
}
```

- `imageMatchThreshold`: 픽셀 일치율 임계값 (0.1~1.0). 높을수록 엄격.
- `imageMatchRetryMs`: WAIT_IMAGE 재시도 간격 (ms).

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
| `GET` | `/api/images/{filename}` | 이미지 서빙 |
| `GET` | `/api/settings` | 설정 조회 |
| `PUT` | `/api/settings` | 설정 저장 |

---

## 주요 구현 파일

| 파일 | 역할 |
|------|------|
| `service/NodeTypeRegistry.java` | 8종 노드 타입 정의. 파라미터 스키마 포함. |
| `service/PipelineService.java` | JSON CRUD + ZIP import/export |
| `service/PipelineRunnerService.java` | 실행 엔진. 위상정렬 → 순서 보장. |
| `service/ImageMatcherUtil.java` | 화면 픽셀 비교 이미지 탐색 |
| `controller/PipelineApiController.java` | REST API 전체 |
| `templates/pipeline/editor.html` | 노드 에디터 UI (순수 JS, 외부 라이브러리 없음) |

---

## 알려진 한계 / 향후 개선

| 항목 | 현재 | 향후 |
|------|------|------|
| 이미지 캡처 | 파일 업로드만 지원 | 앱 내 화면 캡처 오버레이 (JavaFX 투명 Stage) |
| DPI 스케일링 | 오프셋 수동 조정 필요 | `GraphicsDevice` 스케일 자동 보정 |
| 분기(if/else) | 미지원, 직선 파이프라인만 | 조건 노드 추가 예정 |
| 실행 취소 | 스레드 interrupt로 처리 | 더 안정적인 취소 메커니즘 |
| 웹 DOM 자동화 | Robot API (이미지 기반) | Playwright for Java 도입 검토 |
