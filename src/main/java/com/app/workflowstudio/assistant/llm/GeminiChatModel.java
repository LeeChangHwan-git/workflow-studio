package com.app.workflowstudio.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Google Gemini generateContent + functionDeclarations.
 * 모델 응답(content)은 받은 그대로 기록에 다시 넣는다 — 생각 서명(thoughtSignature)이 빠지면 다음 호출이 거절되기 때문.
 */
public class GeminiChatModel implements ChatModel {

    private final ObjectMapper om;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;

    public GeminiChatModel(ObjectMapper om, String baseUrl, String apiKey, String model, double temperature) {
        this.om = om;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model.startsWith("models/") ? model.substring(7) : model;
        this.temperature = temperature;
    }

    @Override
    public String describe() {
        return "Gemini · " + model;
    }

    @Override
    public Session start(String systemPrompt, List<ToolSpec> tools) {
        return new GeminiSession(systemPrompt, tools);
    }

    private class GeminiSession implements Session {
        private final String system;
        private final List<Map<String, Object>> declarations = new ArrayList<>();
        private final List<Object> contents = new ArrayList<>();

        GeminiSession(String system, List<ToolSpec> tools) {
            this.system = system;
            for (ToolSpec t : tools) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", t.name());
                d.put("description", t.description());
                // 인자가 없는 도구는 parameters 를 생략 (빈 OBJECT 스키마는 Gemini가 거절)
                Object props = t.parameters() == null ? null : t.parameters().get("properties");
                if (props instanceof Map<?, ?> pm && !pm.isEmpty()) d.put("parameters", upperTypes(t.parameters()));
                declarations.add(d);
            }
        }

        @Override
        public Reply send(String userText) throws Exception {
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userText))));
            return call();
        }

        @Override
        public Reply sendToolResults(List<ToolResult> results) throws Exception {
            List<Object> parts = new ArrayList<>();
            for (ToolResult r : results) {
                // functionResponse.response 는 객체여야 함
                Object resp = r.result() instanceof Map<?, ?> ? r.result() : Map.of("result", r.result());
                parts.add(Map.of("functionResponse", Map.of("name", r.name(), "response", resp)));
            }
            contents.add(Map.of("role", "user", "parts", parts));
            return call();
        }

        private Reply call() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            if (system != null && !system.isBlank())
                body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
            body.put("contents", contents);
            if (!declarations.isEmpty()) body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
            body.put("generationConfig", Map.of("temperature", temperature));

            JsonNode json = LlmHttp.post(om, baseUrl + "/v1beta/models/" + model + ":generateContent",
                    Map.of("x-goog-api-key", apiKey), body, "Gemini");
            JsonNode cand = json.path("candidates").path(0);
            JsonNode content = cand.path("content");
            if (content.isMissingNode() || !content.path("parts").isArray()) {
                String reason = json.path("promptFeedback").path("blockReason").asText(cand.path("finishReason").asText("?"));
                throw new Exception("Gemini가 응답을 만들지 않았습니다 (" + reason + ")");
            }
            contents.add(content);   // 받은 그대로

            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            int i = 0;
            for (JsonNode part : content.path("parts")) {
                if (part.has("functionCall")) {
                    JsonNode fc = part.get("functionCall");
                    calls.add(new ToolCall(fc.path("id").asText("call-" + i++), fc.path("name").asText(),
                            fc.path("args").isObject() ? fc.get("args") : om.createObjectNode()));
                } else if (part.has("text") && !part.path("thought").asBoolean(false)) {
                    text.append(part.get("text").asText());
                }
            }
            return new Reply(text.toString().trim(), calls);
        }
    }

    /** JSON 스키마 type 값을 Gemini 형식(대문자)으로 */
    static Object upperTypes(Object schema) {
        if (schema instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (k.equals("type") && v instanceof String s) out.put(k, s.toUpperCase(Locale.ROOT));
                else out.put(k, upperTypes(v));
            }
            return out;
        }
        if (schema instanceof List<?> l) return l.stream().map(GeminiChatModel::upperTypes).toList();
        return schema;
    }
}
