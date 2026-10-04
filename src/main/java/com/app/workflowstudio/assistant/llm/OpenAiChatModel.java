package com.app.workflowstudio.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * OpenAI 호환 /chat/completions + tools — Ollama(http://localhost:11434/v1), LM Studio, vLLM, OpenRouter 등.
 * 도구 호출을 지원하는 모델이어야 한다 (Ollama 예: qwen3, llama3.1, mistral-nemo …).
 */
public class OpenAiChatModel implements ChatModel {

    private final ObjectMapper om;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;

    public OpenAiChatModel(ObjectMapper om, String baseUrl, String apiKey, String model, double temperature) {
        this.om = om;
        String b = baseUrl.trim().replaceAll("/+$", "");
        if (b.endsWith("/chat/completions")) b = b.substring(0, b.length() - "/chat/completions".length());
        this.baseUrl = b;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
    }

    @Override
    public String describe() {
        return "OpenAI 호환 · " + model + " @ " + baseUrl;
    }

    @Override
    public Session start(String systemPrompt, List<ToolSpec> tools) {
        return new OpenAiSession(systemPrompt, tools);
    }

    private class OpenAiSession implements Session {
        private final List<Object> messages = new ArrayList<>();
        private final List<Map<String, Object>> toolDefs = new ArrayList<>();

        OpenAiSession(String system, List<ToolSpec> tools) {
            if (system != null && !system.isBlank()) messages.add(Map.of("role", "system", "content", system));
            for (ToolSpec t : tools) {
                toolDefs.add(Map.of("type", "function", "function", Map.of(
                        "name", t.name(), "description", t.description(), "parameters", t.parameters())));
            }
        }

        @Override
        public Reply send(String userText) throws Exception {
            messages.add(Map.of("role", "user", "content", userText));
            return call();
        }

        @Override
        public Reply sendToolResults(List<ToolResult> results) throws Exception {
            for (ToolResult r : results) {
                messages.add(Map.of("role", "tool", "tool_call_id", r.callId(), "name", r.name(),
                        "content", om.writeValueAsString(r.result())));
            }
            return call();
        }

        private Reply call() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            if (!toolDefs.isEmpty()) body.put("tools", toolDefs);
            body.put("temperature", temperature);
            Map<String, String> headers = apiKey == null || apiKey.isBlank()
                    ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);

            JsonNode json = LlmHttp.post(om, baseUrl + "/chat/completions", headers, body, "AI(OpenAI 호환)");
            JsonNode msg = json.path("choices").path(0).path("message");
            if (msg.isMissingNode()) throw new Exception("AI 응답 형식을 읽을 수 없습니다: " + abbreviate(json.toString()));
            messages.add(msg);   // tool_calls 포함 그대로

            List<ToolCall> calls = new ArrayList<>();
            int i = 0;
            for (JsonNode tc : msg.path("tool_calls")) {
                JsonNode fn = tc.path("function");
                JsonNode args = fn.path("arguments");
                if (args.isTextual()) {
                    try {
                        args = om.readTree(args.asText().isBlank() ? "{}" : args.asText());
                    } catch (Exception e) {
                        args = om.createObjectNode().put("_invalid_json", args.asText());
                    }
                }
                calls.add(new ToolCall(tc.path("id").asText("call-" + i++), fn.path("name").asText(), args));
            }
            String text = msg.path("content").isTextual() ? msg.path("content").asText() : "";
            // 추론 모델(qwen3 등)의 <think>…</think> 는 사용자에게 보이지 않게
            text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
            return new Reply(text, calls);
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
