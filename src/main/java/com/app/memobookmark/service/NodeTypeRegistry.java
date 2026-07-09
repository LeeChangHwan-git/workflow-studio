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
            List<NodeParam> params
    ) {}

    private static final List<NodeTypeInfo> TYPES = List.of(

        new NodeTypeInfo("HTTP_REQUEST", "HTTP 요청", "🔗", "#0EA5E9", List.of(
            new NodeParam("method", "메소드", "select", "GET",
                List.of("GET", "POST", "PUT", "DELETE", "PATCH")),
            new NodeParam("url", "URL", "text", "https://", null),
            new NodeParam("headers", "헤더 (Key: Value, 줄바꿈 구분)", "textarea", "", null),
            new NodeParam("bodyType", "바디 타입", "select", "없음",
                List.of("없음", "JSON", "Form", "Raw")),
            new NodeParam("body", "요청 바디", "textarea", "", null),
            new NodeParam("outputVar", "출력 변수명", "text", "httpResult", null)
        )),

        new NodeTypeInfo("OPEN_URL", "사이트 열기", "🌐", "#4A90D9", List.of(
            new NodeParam("url", "URL", "text", "https://", null),
            new NodeParam("browser", "브라우저", "select", "기본",
                List.of("기본", "Edge", "Chrome"))
        )),

        new NodeTypeInfo("WAIT_IMAGE", "이미지 대기", "⏳", "#E67E22", List.of(
            new NodeParam("imagePath", "대기할 이미지", "image", null, null),
            new NodeParam("timeoutSec", "제한 시간(초)", "number", 10, null)
        )),

        new NodeTypeInfo("CLICK_IMAGE", "이미지 클릭", "🖱️", "#E74C3C", List.of(
            new NodeParam("imagePath", "클릭할 이미지", "image", null, null),
            new NodeParam("timeoutSec", "대기 시간(초)", "number", 8, null),
            new NodeParam("offsetX", "X 오프셋(px)", "number", 0, null),
            new NodeParam("offsetY", "Y 오프셋(px)", "number", 0, null)
        )),

        new NodeTypeInfo("TYPE_TEXT", "텍스트 입력", "⌨️", "#27AE60", List.of(
            new NodeParam("text", "입력할 텍스트", "textarea", "",
                null)
        )),

        new NodeTypeInfo("KEY_PRESS", "키 입력", "🔑", "#8E44AD", List.of(
            new NodeParam("key", "키", "select", "ENTER",
                List.of("ENTER", "TAB", "ESC", "SPACE", "BACKSPACE",
                        "F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12"))
        )),

        new NodeTypeInfo("DELAY", "딜레이", "⏱️", "#95A5A6", List.of(
            new NodeParam("seconds", "대기 시간(초)", "number", 1, null)
        )),

        new NodeTypeInfo("SCREENSHOT", "스크린샷", "📸", "#16A085", List.of(
            new NodeParam("outputVar", "출력 변수명", "text", "screenshot", null)
        )),

        new NodeTypeInfo("CALL_AI", "AI 질의", "🤖", "#9B59B6", List.of(
            new NodeParam("prompt", "프롬프트 (${nodeId.output} 참조 가능)", "textarea",
                "다음 내용을 분석해줘: ${이전노드.output}", null),
            new NodeParam("model", "모델", "select", "claude-sonnet-4-6",
                List.of("claude-sonnet-4-6", "claude-haiku-4-5-20251001", "claude-opus-4-8")),
            new NodeParam("outputVar", "출력 변수명", "text", "aiResult", null)
        )),

        new NodeTypeInfo("AI_AGENT", "AI 에이전트", "🧠", "#7C3AED", List.of(
            new NodeParam("model", "AI 모델", "select", "claude-sonnet-4-6",
                List.of("claude-sonnet-4-6", "claude-haiku-4-5-20251001", "claude-opus-4-8")),
            new NodeParam("systemPrompt", "에이전트 역할 (System Prompt)", "textarea",
                "당신은 유능한 AI 에이전트입니다. 제공된 도구를 활용하여 사용자의 요청을 정확하게 완수하세요.", null),
            new NodeParam("userInput", "사용자 입력 (${변수} 참조 가능)", "textarea", "", null),
            new NodeParam("tools", "사용 도구", "select", "전체 (HTTP + 앱 검색)",
                List.of("없음", "HTTP 요청", "앱 검색 (메모/북마크)", "전체 (HTTP + 앱 검색)")),
            new NodeParam("maxIter", "최대 도구 호출 횟수", "number", "5", null),
            new NodeParam("outputVar", "출력 변수명", "text", "agentResult", null)
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
