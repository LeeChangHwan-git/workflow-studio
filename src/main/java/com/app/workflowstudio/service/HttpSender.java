package com.app.workflowstudio.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * HTTP 요청 전송 (상단 HTTP 메뉴와 HTTP 요청 노드가 함께 사용).
 * Host·Content-Length 등 Java HttpClient가 직접 설정을 금지하는 헤더는 건너뛰고 결과에 알려준다.
 */
@Component
public class HttpSender {

    public record Header(String key, String value) {}

    public record Result(int status, Map<String, String> headers, String body, boolean truncated,
                         long durationMs, int size, List<String> skippedHeaders) {}

    /** Java HttpClient 가 직접 설정을 금지하는 헤더 (자동 설정됨) */
    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("host", "content-length", "connection", "expect", "upgrade");
    public static final int MAX_BODY_CHARS = 2_000_000;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Result send(String method, String url, List<Header> headers, String body, Duration timeout) throws Exception {
        String m = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 형식이 올바르지 않습니다: " + url);
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException("URL은 http:// 또는 https:// 로 시작해야 합니다: " + url);
        }

        boolean hasBody = body != null && !body.isEmpty() && !m.equals("GET") && !m.equals("HEAD");
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                // 평문 http 에서 HTTP/2 업그레이드(h2c) 헤더를 보내면 400으로 거절하는 서버가 많음 (Node.js 등) → 1.1 고정.
                // https 는 TLS(ALPN)로 협상하므로 그대로 둔다
                .version(uri.getScheme().equals("http") ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2)
                .method(m, hasBody
                        ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                        : HttpRequest.BodyPublishers.noBody());

        List<String> skipped = new ArrayList<>();
        if (headers != null) {
            for (Header h : headers) {
                if (h == null || h.key() == null || h.key().isBlank()) continue;
                if (RESTRICTED_HEADERS.contains(h.key().trim().toLowerCase(Locale.ROOT))) {
                    skipped.add(h.key().trim());
                    continue;
                }
                b.header(h.key().trim(), h.value() == null ? "" : h.value());
            }
        }

        long start = System.nanoTime();
        HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        String respBody = resp.body() == null ? "" : resp.body();
        Map<String, String> respHeaders = new TreeMap<>();
        resp.headers().map().forEach((k, v) -> respHeaders.put(k, String.join(", ", v)));
        boolean truncated = respBody.length() > MAX_BODY_CHARS;
        return new Result(resp.statusCode(), respHeaders,
                truncated ? respBody.substring(0, MAX_BODY_CHARS) : respBody, truncated,
                durationMs, respBody.getBytes(StandardCharsets.UTF_8).length, skipped);
    }
}
