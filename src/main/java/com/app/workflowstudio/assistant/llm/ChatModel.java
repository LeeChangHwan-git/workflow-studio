package com.app.workflowstudio.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 도구 호출(function calling)을 지원하는 대화 모델 — 제공자(Gemini, OpenAI 호환 …)마다 구현.
 * 대화 기록은 세션이 제공자 고유 형식 그대로 들고 있다 (Gemini thoughtSignature 같은 값을 잃지 않도록).
 */
public interface ChatModel {

    /** 도구 하나: 이름, 설명, 인자 JSON 스키마(type: object) */
    record ToolSpec(String name, String description, Map<String, Object> parameters) {}

    /** 모델이 요청한 도구 호출 */
    record ToolCall(String id, String name, JsonNode args) {}

    /** 도구 실행 결과 (result 는 JSON 으로 직렬화 가능한 값) */
    record ToolResult(String callId, String name, Object result) {}

    /** 모델 응답: 글 + 도구 호출들 (호출이 없으면 대화 턴 끝) */
    record Reply(String text, List<ToolCall> calls) {
        public boolean hasCalls() {
            return calls != null && !calls.isEmpty();
        }
    }

    /** 새 대화 세션 */
    Session start(String systemPrompt, List<ToolSpec> tools);

    /** 연결 확인용 이름 (예: "Gemini · gemini-flash-latest") */
    String describe();

    interface Session {
        Reply send(String userText) throws Exception;

        Reply sendToolResults(List<ToolResult> results) throws Exception;
    }
}
