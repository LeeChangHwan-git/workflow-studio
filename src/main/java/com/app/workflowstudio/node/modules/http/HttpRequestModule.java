package com.app.workflowstudio.node.modules.http;

import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeParam;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.app.workflowstudio.service.HttpSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * HTTP 요청 — REST API 호출 (상단 HTTP 메뉴와 같은 기능을 파이프라인 노드로).
 * URL·헤더·바디에 ${...} 사용 가능. 출력: {status, ok, durationMs, headers, body} — body가 JSON이면 객체로.
 */
@Component
@Order(300)
public class HttpRequestModule implements NodeModule {

    public static final String TYPE = "HTTP_REQUEST";

    static final String BODY_NONE = "없음";
    static final String BODY_JSON = "JSON";
    static final String BODY_TEXT = "Text";
    static final String BODY_FORM = "Form (a=1&b=2)";

    static final String FAIL_YES = "예 — 4xx/5xx 응답이면 오류로 중단";
    static final String FAIL_NO = "아니오 — 응답 코드와 상관없이 다음으로";

    private final HttpSender sender;

    public HttpRequestModule(HttpSender sender) {
        this.sender = sender;
    }

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "HTTP 요청", "🌐", "#0EA5E9", "통합", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("method", "메소드", "select", "GET",
                        List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")),
                new NodeParam("url", "URL (${...} 사용 가능)", "text", "https://", null),
                new NodeParam("headers", "헤더 — 한 줄에 하나씩 \"이름: 값\" (예: Authorization: Bearer ${token})",
                        "textarea", "", null),
                new NodeParam("bodyType", "바디", "select", BODY_NONE, List.of(BODY_NONE, BODY_JSON, BODY_TEXT, BODY_FORM)),
                new NodeParam("body", "바디 내용 (${...} 사용 가능)", "textarea", "", null, "bodyType!=" + BODY_NONE),
                new NodeParam("timeoutSec", "제한 시간 (초)", "number", 30, null),
                new NodeParam("failOnError", "실패 응답 처리", "select", FAIL_YES, List.of(FAIL_YES, FAIL_NO)),
                new NodeParam("httpOpen", "테스트", "httpOpen", null, null),
                new NodeParam("outputVar", "출력 변수명", "text", "http", null)
        ));
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        String method = ctx.raw("method", "GET");
        String url = ctx.str("url", "").trim();
        if (url.isEmpty() || url.equals("https://") || url.equals("http://")) throw new Exception("URL을 입력하세요");
        if (url.contains("${")) throw new Exception("URL에 해석되지 않은 변수가 있습니다: " + url);

        List<HttpSender.Header> headers = parseHeaders(ctx.str("headers", ""));

        String bodyType = ctx.raw("bodyType", BODY_NONE);
        String body = null;
        if (!BODY_NONE.equals(bodyType)) {
            body = ctx.str("body", "");
            String contentType = switch (bodyType) {
                case BODY_JSON -> "application/json";
                case BODY_FORM -> "application/x-www-form-urlencoded";
                default -> "text/plain; charset=UTF-8";
            };
            if (BODY_JSON.equals(bodyType) && !body.isBlank()) checkJson(body, ctx.objectMapper());
            boolean hasContentType = headers.stream().anyMatch(h -> h.key().equalsIgnoreCase("content-type"));
            if (!hasContentType && !body.isEmpty()) headers.add(new HttpSender.Header("Content-Type", contentType));
        }

        int timeoutSec = Math.max(1, Math.min(600, ctx.intVal("timeoutSec", 30)));
        HttpSender.Result r;
        try {
            r = sender.send(method, url, headers, body, Duration.ofSeconds(timeoutSec));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Exception(timeoutSec + "초 안에 응답이 없습니다: " + url);
        } catch (java.net.ConnectException e) {
            throw new Exception("서버에 연결할 수 없습니다: " + url);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status());
        out.put("ok", r.status() >= 200 && r.status() < 300);
        out.put("durationMs", r.durationMs());
        out.put("headers", r.headers());
        out.put("body", parseBody(r.body(), ctx.objectMapper()));
        if (r.truncated()) out.put("truncated", true);
        String json = ctx.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out);

        if (r.status() >= 400 && FAIL_YES.equals(ctx.raw("failOnError", FAIL_YES))) {
            String preview = r.body().length() > 300 ? r.body().substring(0, 300) + "…" : r.body();
            throw new Exception("HTTP " + r.status() + " 응답 — " + method + " " + url
                    + (preview.isBlank() ? "" : "\n" + preview));
        }
        return json;
    }

    /** "이름: 값" 줄 목록 → 헤더 (빈 줄·#주석 무시) */
    static List<HttpSender.Header> parseHeaders(String text) throws Exception {
        List<HttpSender.Header> list = new ArrayList<>();
        if (text == null) return list;
        int lineNo = 0;
        for (String line : text.split("\\r?\\n")) {
            lineNo++;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int colon = t.indexOf(':');
            if (colon <= 0) throw new Exception("헤더 " + lineNo + "번째 줄 형식 오류 (\"이름: 값\"): " + t);
            list.add(new HttpSender.Header(t.substring(0, colon).trim(), t.substring(colon + 1).trim()));
        }
        return list;
    }

    private static void checkJson(String body, ObjectMapper om) throws Exception {
        try {
            om.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new Exception("바디가 올바른 JSON이 아닙니다 (${...}로 넣은 값에 따옴표·줄바꿈이 있는지 확인): "
                    + e.getOriginalMessage());
        }
    }

    private static Object parseBody(String body, ObjectMapper om) {
        if (body == null || body.isBlank()) return body;
        try {
            return om.readValue(body, Object.class);
        } catch (Exception e) {
            return body;
        }
    }
}
