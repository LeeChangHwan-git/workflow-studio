package com.app.memobookmark.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class NodeTypeRegistry {

    public record NodeParam(
            String name,
            String label,
            String type,    // text | textarea | select | number | image
            Object defaultValue,
            List<String> options  // select 전용
    ) {}

    public record NodeTypeInfo(
            String id,
            String label,
            String icon,
            String color,
            String category,  // 팔레트 그룹 (null = 팔레트에 미노출 / 레거시)
            List<NodeParam> params
    ) {}

    private static final List<NodeTypeInfo> TYPES = List.of(

        // ── 아웃라인 ─────────────────────────────────────────────────
        new NodeTypeInfo("OUTLINE_COLLECTIONS", "컬렉션 목록", "📋", "#0EA5E9", "아웃라인", List.of(
            new NodeParam("limit",     "최대 개수",   "number", "50",          null),
            new NodeParam("outputVar", "출력 변수명", "text",   "collections", null)
        )),

        new NodeTypeInfo("OUTLINE_DOCS", "문서 목록", "📄", "#0EA5E9", "아웃라인", List.of(
            new NodeParam("collectionId", "컬렉션 ID (${변수} 참조)", "text",   "",         null),
            new NodeParam("limit",        "최대 개수",                "number", "50",       null),
            new NodeParam("outputVar",    "출력 변수명",              "text",   "documents", null)
        )),

        new NodeTypeInfo("OUTLINE_SEARCH", "문서 검색", "🔍", "#0EA5E9", "아웃라인", List.of(
            new NodeParam("query",     "검색어 (${변수} 참조)", "text",   "",             null),
            new NodeParam("limit",     "최대 개수",             "number", "50",           null),
            new NodeParam("outputVar", "출력 변수명",           "text",   "searchResults", null)
        )),

        new NodeTypeInfo("OUTLINE_EXPORT", "문서 내보내기", "📝", "#0EA5E9", "아웃라인", List.of(
            new NodeParam("documentId", "문서 ID (${변수} 참조)", "text", "",           null),
            new NodeParam("outputVar",  "출력 변수명",            "text", "documentMd", null)
        )),

        // ── AI ───────────────────────────────────────────────────────
        new NodeTypeInfo("CALL_AI", "AI 질의", "🤖", "#9B59B6", "AI", List.of(
            new NodeParam("prompt", "프롬프트 (${nodeId.output} 참조 가능)", "textarea",
                "다음 내용을 분석해줘: ${이전노드.output}", null),
            new NodeParam("model", "모델", "select", "claude-sonnet-4-6",
                List.of("claude-sonnet-4-6", "claude-haiku-4-5-20251001", "claude-opus-4-8")),
            new NodeParam("outputVar", "출력 변수명", "text", "aiResult", null)
        )),

        new NodeTypeInfo("AI_AGENT", "AI 에이전트", "🧠", "#7C3AED", "AI", List.of(
            new NodeParam("model", "AI 모델", "select", "claude-sonnet-4-6",
                List.of("claude-sonnet-4-6", "claude-haiku-4-5-20251001", "claude-opus-4-8")),
            new NodeParam("systemPrompt", "에이전트 역할 (System Prompt)", "textarea",
                "당신은 유능한 AI 에이전트입니다. 제공된 도구를 활용하여 사용자의 요청을 정확하게 완수하세요.", null),
            new NodeParam("userInput", "사용자 입력 (${변수} 참조 가능)", "textarea", "", null),
            new NodeParam("tools", "사용 도구", "select", "전체 (HTTP + 앱 검색)",
                List.of("없음", "HTTP 요청", "앱 검색 (메모/북마크)", "전체 (HTTP + 앱 검색)")),
            new NodeParam("maxIter", "최대 도구 호출 횟수", "number", "5", null),
            new NodeParam("outputVar", "출력 변수명", "text", "agentResult", null)
        )),

        // ── 통합 ─────────────────────────────────────────────────────
        new NodeTypeInfo("HTTP_REQUEST", "HTTP 요청", "🔗", "#0EA5E9", "통합", List.of(
            new NodeParam("method", "메소드", "select", "GET",
                List.of("GET", "POST", "PUT", "DELETE", "PATCH")),
            new NodeParam("url", "URL", "text", "https://", null),
            new NodeParam("headers", "헤더 (Key: Value, 줄바꿈 구분)", "textarea", "", null),
            new NodeParam("bodyType", "바디 타입", "select", "없음",
                List.of("없음", "JSON", "Form", "Raw")),
            new NodeParam("body", "요청 바디", "textarea", "", null),
            new NodeParam("outputVar", "출력 변수명", "text", "httpResult", null)
        )),

        // ── 화면 자동화 ──────────────────────────────────────────────
        new NodeTypeInfo("OPEN_URL", "사이트 열기", "🌐", "#4A90D9", "화면 자동화", List.of(
            new NodeParam("url", "URL", "text", "https://", null),
            new NodeParam("browser", "브라우저", "select", "기본",
                List.of("기본", "Edge", "Chrome"))
        )),

        new NodeTypeInfo("WAIT_IMAGE", "이미지 대기", "⏳", "#E67E22", "화면 자동화", List.of(
            new NodeParam("imagePath", "대기할 이미지", "image", null, null),
            new NodeParam("timeoutSec", "제한 시간(초)", "number", 10, null)
        )),

        new NodeTypeInfo("CLICK_IMAGE", "이미지 클릭", "🖱️", "#E74C3C", "화면 자동화", List.of(
            new NodeParam("imagePath", "클릭할 이미지", "image", null, null),
            new NodeParam("timeoutSec", "대기 시간(초)", "number", 8, null),
            new NodeParam("offsetX", "X 오프셋(px)", "number", 0, null),
            new NodeParam("offsetY", "Y 오프셋(px)", "number", 0, null)
        )),

        new NodeTypeInfo("TYPE_TEXT", "텍스트 입력", "⌨️", "#27AE60", "화면 자동화", List.of(
            new NodeParam("text", "입력할 텍스트", "textarea", "", null)
        )),

        new NodeTypeInfo("KEY_PRESS", "키 입력", "🔑", "#8E44AD", "화면 자동화", List.of(
            new NodeParam("key", "키", "select", "ENTER",
                List.of("ENTER", "TAB", "ESC", "SPACE", "BACKSPACE",
                        "F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12"))
        )),

        new NodeTypeInfo("DELAY", "딜레이", "⏱️", "#95A5A6", "화면 자동화", List.of(
            new NodeParam("seconds", "대기 시간(초)", "number", 1, null)
        )),

        new NodeTypeInfo("SCREENSHOT", "스크린샷", "📸", "#16A085", "화면 자동화", List.of(
            new NodeParam("outputVar", "출력 변수명", "text", "screenshot", null)
        )),

        // ── 파일 출력 ────────────────────────────────────────────────
        new NodeTypeInfo("SAVE_MD", "MD 파일 저장", "💾", "#F59E0B", "파일 출력", List.of(
            new NodeParam("content",   "내용 (${변수} 참조)", "textarea", "", null),
            new NodeParam("outputDir", "저장 폴더 경로",      "text",     "~/Desktop", null),
            new NodeParam("saveMode",  "저장 방식",           "select",   "단일 파일",
                List.of("단일 파일", "섹션별 분할 (## 기준)", "구분자로 분할 (---)")),
            new NodeParam("filename",  "파일명 패턴 (단일: output.md │ 분할: ${heading}.md 또는 doc_${index}.md)",
                "text", "output.md", null),
            new NodeParam("outputVar", "출력 변수명 (저장 경로 목록)", "text", "savedFiles", null)
        )),

        // ── 레거시 (팔레트 미노출, 기존 파이프라인 호환용) ─────────
        new NodeTypeInfo("OUTLINE", "아웃라인 (구버전)", "📋", "#0EA5E9", null, List.of(
            new NodeParam("operation", "작업", "select", "문서 목록",
                List.of("컬렉션 목록", "문서 목록", "문서 검색", "문서 내보내기(MD)")),
            new NodeParam("collectionId", "컬렉션 ID (${변수} 참조)", "text", "", null),
            new NodeParam("documentId",   "문서 ID (${변수} 참조)",   "text", "", null),
            new NodeParam("query",        "검색어 (${변수} 참조)",     "text", "", null),
            new NodeParam("limit",        "최대 개수",                  "number", "50", null),
            new NodeParam("outputVar",    "출력 변수명",                "text", "outlineResult", null)
        ))
    );

    public List<NodeTypeInfo> getAll() {
        return TYPES;
    }

    public NodeTypeInfo findById(String id) {
        return TYPES.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }

    public Map<String, NodeTypeInfo> asMap() {
        Map<String, NodeTypeInfo> map = new java.util.LinkedHashMap<>();
        TYPES.forEach(t -> map.put(t.id(), t));
        return map;
    }
}
