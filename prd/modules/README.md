# 노드 모듈 설계

모듈 하나당 문서 하나 (`prd/modules/<모듈id 소문자>.md`). 구현 방법은 `prd/engine-and-editor.md`의 "모듈 추가 방법" 참고.

## 모듈 목록

| ID | 이름 | 카테고리 | 상태 | 문서 |
|----|------|----------|------|------|
| `WEBHOOK` | 웹훅 | 트리거 | ✅ 완료 (2026-10-01) | [webhook.md](webhook.md) |
| `FILE_WRITE` | 파일 쓰기 (Windows 전용) | 파일 | ✅ 완료 (2026-10-02) | [file-write.md](file-write.md) |
| `HTTP_REQUEST` | HTTP 요청 (REST API 호출) | 통합 | ✅ 완료 (2026-10-03) | [http-request.md](http-request.md) |
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
