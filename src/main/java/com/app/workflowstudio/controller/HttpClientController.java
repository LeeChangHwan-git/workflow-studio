package com.app.workflowstudio.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 간이 HTTP 클라이언트 (Postman 류) — 웹훅 테스트 등에 사용.
 * 브라우저에서 직접 보내면 외부 URL은 CORS에 막히므로 서버가 대신 요청을 보내고 결과를 돌려준다.
 */
@Controller
public class HttpClientController {

    /** java.net.http 가 직접 설정을 금지하는 헤더 (자동 설정됨) */
    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("host", "content-length", "connection", "expect", "upgrade");
    private static final int MAX_BODY_CHARS = 2_000_000;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @GetMapping("/http")
    public String page() {
        return "http/index";
    }

    public record SendRequest(String method, String url, List<Header> headers, String body) {}
    public record Header(String key, String value) {}

    @PostMapping("/api/http-client/send")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendRequest req) {
        Map<String, Object> res = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            String method = req.method() == null || req.method().isBlank() ? "GET" : req.method().toUpperCase();
            URI uri = URI.create(req.url() == null ? "" : req.url().trim());
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                throw new IllegalArgumentException("URL은 http:// 또는 https:// 로 시작해야 합니다");
            }

            boolean hasBody = req.body() != null && !req.body().isEmpty()
                    && !method.equals("GET") && !method.equals("HEAD");
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .method(method, hasBody
                            ? HttpRequest.BodyPublishers.ofString(req.body(), StandardCharsets.UTF_8)
                            : HttpRequest.BodyPublishers.noBody());

            List<String> skipped = new ArrayList<>();
            if (req.headers() != null) {
                for (Header h : req.headers()) {
                    if (h == null || h.key() == null || h.key().isBlank()) continue;
                    if (RESTRICTED_HEADERS.contains(h.key().trim().toLowerCase())) {
                        skipped.add(h.key().trim());
                        continue;
                    }
                    b.header(h.key().trim(), h.value() == null ? "" : h.value());
                }
            }

            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() == null ? "" : resp.body();

            Map<String, String> headers = new TreeMap<>();
            resp.headers().map().forEach((k, v) -> headers.put(k, String.join(", ", v)));

            res.put("status", resp.statusCode());
            res.put("durationMs", (System.nanoTime() - start) / 1_000_000);
            res.put("size", body.getBytes(StandardCharsets.UTF_8).length);
            res.put("headers", headers);
            res.put("body", body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body);
            res.put("truncated", body.length() > MAX_BODY_CHARS);
            if (!skipped.isEmpty()) res.put("skippedHeaders", skipped);
            return ResponseEntity.ok(res);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            res.put("error", "요청이 중단되었습니다");
        } catch (Exception e) {
            res.put("error", e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
        }
        res.put("durationMs", (System.nanoTime() - start) / 1_000_000);
        return ResponseEntity.ok(res);
    }
}
