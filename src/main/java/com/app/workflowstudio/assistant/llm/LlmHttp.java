package com.app.workflowstudio.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** AI 제공자 공용 HTTP 호출 — 일시적 오류(429·500·503)는 잠깐 쉬었다 두 번까지 재시도 */
final class LlmHttp {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private LlmHttp() {}

    static JsonNode post(ObjectMapper om, String url, Map<String, String> headers, Object body, String who) throws Exception {
        String json = om.writeValueAsString(body);
        Exception last = new Exception(who + " 응답 없음");
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(attempt * 2500L);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            headers.forEach(b::header);
            HttpResponse<String> resp;
            try {
                resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (ConnectException e) {
                throw new Exception(who + " 서버에 연결할 수 없습니다: " + url.replaceAll("\\?.*", ""));
            } catch (HttpTimeoutException e) {
                throw new Exception(who + " 응답이 180초 안에 오지 않았습니다");
            }
            int st = resp.statusCode();
            JsonNode tree;
            try {
                tree = om.readTree(resp.body() == null || resp.body().isBlank() ? "{}" : resp.body());
            } catch (Exception e) {
                tree = null;
            }
            if (st < 400 && tree != null) return tree;

            String msg = tree == null ? "" : tree.path("error").isTextual() ? tree.path("error").asText()
                    : tree.path("error").path("message").asText("");
            last = new Exception(who + " 오류 HTTP " + st + (msg.isBlank() ? "" : ": " + msg)
                    + (st == 429 ? " — 무료 사용량 한도일 수 있습니다. 잠시 후 다시 시도하세요" : ""));
            if (st != 429 && st != 500 && st != 502 && st != 503) throw last;
        }
        throw last;
    }
}
