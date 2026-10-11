# 노드 모듈 설계

모듈 하나당 문서 하나 (`prd/modules/<모듈id 소문자>.md`). 구현 방법은 `prd/engine-and-editor.md`의 "모듈 추가 방법" 참고.

## 모듈 목록

| ID | 이름 | 카테고리 | 상태 | 문서 |
|----|------|----------|------|------|
| `WEBHOOK` | 웹훅 | 트리거 | ✅ 완료 (2026-10-01) | [webhook.md](webhook.md) |
| `SCHEDULE` | 타이머 (간격·요일 시각·cron·1회) | 트리거 | ✅ 완료 (2026-10-03) | [schedule.md](schedule.md) |
| `FILE_WRITE` | 파일 쓰기 (Windows 전용) | 파일 | ✅ 완료 (2026-10-02) | [file-write.md](file-write.md) |
| `FILE_IMPORT` | 파일 가져오기 (txt/html/pdf/docx/xlsx/pptx → AI로) | 파일 | ✅ 완료 (2026-10-07) | [file-import.md](file-import.md) |
| `HTTP_REQUEST` | HTTP 요청 (REST API 호출) | 통합 | ✅ 완료 (2026-10-03) | [http-request.md](http-request.md) |
| `HTML_SELECT` | 웹 페이지 추출 + 🔍 HTML 분석기 (클릭으로 선택자) | 통합 | ✅ 완료 (2026-10-05) | [html-select.md](html-select.md) |
| `MESSENGER` | 사내 메신저 쪽지 (사번·메시지, 시연용 메신저 포함) | 통합 | ✅ 완료 (2026-10-09) | [messenger.md](messenger.md) |
| (내 모듈) | 🧩 모듈 관리 — 켜기/끄기 · HTTP 모듈 · 파이프라인 모듈 · .wbmod 내보내기/가져오기 | 내 모듈 | ✅ 완료 (2026-10-11) | [custom-modules.md](custom-modules.md) |
| `BRANCH` | 분기 (규칙마다 출력 + 그 외, 표현식 설명서) | 흐름 | ✅ 완료 (2026-10-09) | [branch.md](branch.md) |
| `APPROVAL` | 확인 요청 (승인 / 고쳐서 승인 / 다시 → AI 수정 / 거절) | 흐름 | ✅ 완료 (2026-10-09) | [approval.md](approval.md) |
| `OUTLINE` | 아웃라인 게시판 (조회·검색·가져오기·등록·수정) | 통합 | ✅ 완료 (2026-10-04) | [outline.md](outline.md) |
| `AI_AGENT` | AI 에이전트 (소켓: 모델·메모리·지시문) | AI | ✅ 1차 (2026-10-02) | [ai-agent.md](ai-agent.md) |
| `GEMINI_MODEL` · `CHAT_MEMORY` · `AI_INSTRUCTION` | Gemini 모델 · 대화 메모리 · 지시문 (부품) | AI 부품 | ✅ 1차 (2026-10-02) | [ai-agent.md](ai-agent.md) |

상태: 📝 설계 중 · 🚧 구현 중 · ✅ 완료

---

## 설계 문서 템플릿

```markdown
# <모듈 이름> (`<ID>`)

## 상태: 📝 설계 중 (YYYY-MM-DD)

## 목적
이 노드가 해결하는 문제 / 쓰는 상황 한두 줄

## 파라미터
| name | label | type | 기본값 | 설명 |
|------|-------|------|--------|------|

## 동작
1. ...

## 출력
- 형식 (텍스트 / JSON / 파일 경로 …), 다음 노드에서 참조 예: `${outputVar.field}`

## 오류 처리
- 어떤 경우 예외로 중단하는지

## 의존성 / 설정
- 외부 API, 키, 라이브러리, OS 제약

## 테스트 방법
- 확인용 파이프라인 예시
```
