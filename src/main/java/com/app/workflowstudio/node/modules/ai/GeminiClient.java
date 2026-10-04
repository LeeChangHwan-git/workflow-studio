package com.app.workflowstudio.node.modules.ai;

import com.app.workflowstudio.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Google Gemini API (generateContent / models.list) 호출.
 * API 키는 URL이 아니라 x-goog-api-key 헤더로 보낸다 (로그·히스토리에 키가 남지 않도록).
 */
@Component
public class GeminiClient {

    /** 대화 한 턴: role = user | model */
    public record Message(String role, String text) {}

    private final String baseUrl;
    private final ObjectMapper om;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GeminiClient(@Value("${app.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
                        ObjectMapper om) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.om = om;
    }

    /** 키 찾는 순서: 노드 → 설정의 공용 키 → 환경 변수 GEMINI_API_KEY */
    public static String resolveKey(String nodeKey) throws Exception {
        String key = SettingsService.firstNonBlank(nodeKey,
                SettingsService.current().credentials.geminiApiKey, System.getenv("GEMINI_API_KEY"));
        if (key != null) return key;
        throw new Exception("Gemini API 키가 없습니다. 모델 부품에 입력하거나 ⚙ 설정 → 공용 키에 넣으세요 "
                + "(무료 발급: https://aistudio.google.com/apikey)");
    }

    /** 키가 어디서 오는지: node | settings | env | none */
    public static String keySource(String nodeKey) {
        if (nodeKey != null && !nodeKey.isBlank()) return "node";
        if (!SettingsService.current().credentials.geminiApiKey.isBlank()) return "settings";
        String env = System.getenv("GEMINI_API_KEY");
        return env != null && !env.isBlank() ? "env" : "none";
    }

    /** 응답 텍스트 생성 */
    public String generate(String apiKey, String model, String system, List<Message> history,
                           String userText, Double temperature) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (system != null && !system.isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        }
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Message m : history) {
            contents.add(Map.of("role", m.role(), "parts", List.of(Map.of("text", m.text()))));
        }
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userText))));
        body.put("contents", contents);
        if (temperature != null) body.put("generationConfig", Map.of("temperature", temperature));

        String url = baseUrl + "/v1beta/models/" + modelId(model) + ":generateContent";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = parse(resp);

        JsonNode candidates = json.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String reason = json.path("promptFeedback").path("blockReason").asText("");
            throw new Exception("Gemini가 응답을 만들지 않았습니다" + (reason.isEmpty() ? "" : " (차단 사유: " + reason + ")"));
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidates.get(0).path("content").path("parts")) {
            if (part.has("text")) text.append(part.get("text").asText());
        }
        if (text.isEmpty()) {
            throw new Exception("Gemini 응답이 비어 있습니다 (finishReason: "
                    + candidates.get(0).path("finishReason").asText("?") + ")");
        }
        return text.toString();
    }

    /** generateContent 를 지원하는 모델 id 목록 (예: gemini-2.5-flash) */
    public List<String> listModels(String apiKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models?pageSize=1000"))
                .timeout(Duration.ofSeconds(30))
                .header("x-goog-api-key", apiKey)
                .GET().build();
        JsonNode json = parse(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        List<String> ids = new ArrayList<>();
        for (JsonNode m : json.path("models")) {
            boolean generates = false;
            for (JsonNode method : m.path("supportedGenerationMethods")) {
                if ("generateContent".equals(method.asText())) generates = true;
            }
            if (generates) ids.add(modelId(m.path("name").asText()));
        }
        Collections.sort(ids);
        return ids;
    }

    /** 모델이 존재하는지 (목록에 안 나오는 별칭 확인용). 키 오류 등은 예외 */
    public boolean modelExists(String apiKey, String model) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models/" + modelId(model)))
                .timeout(Duration.ofSeconds(30))
                .header("x-goog-api-key", apiKey)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) return false;
        parse(resp);
        return true;
    }

    /** "models/gemini-2.5-flash" → "gemini-2.5-flash" */
    static String modelId(String name) {
        String s = name == null ? "" : name.trim();
        return s.startsWith("models/") ? s.substring("models/".length()) : s;
    }

    private JsonNode parse(HttpResponse<String> resp) throws Exception {
        JsonNode json;
        try {
            json = om.readTree(resp.body() == null || resp.body().isBlank() ? "{}" : resp.body());
        } catch (Exception e) {
            throw new Exception("Gemini 응답을 읽을 수 없습니다 (HTTP " + resp.statusCode() + ")");
        }
        if (resp.statusCode() >= 400) {
            String msg = json.path("error").path("message").asText("");
            String status = json.path("error").path("status").asText("");
            throw new Exception("Gemini API 오류 HTTP " + resp.statusCode()
                    + (status.isEmpty() ? "" : " " + status) + (msg.isEmpty() ? "" : ": " + msg));
        }
        return json;
    }
}
