package com.app.workflowstudio.node.modules.webhook;

import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 웹훅 트리거 — 고정 URL로 HTTP 요청을 받으면 파이프라인을 시작하고, 요청 내용을 다음 노드로 넘긴다.
 * <ul>
 *   <li>테스트 URL: {@code /webhook-test/{path}} → 열려 있는 에디터에 실행 과정이 실시간 표시</li>
 *   <li>운영 URL:   {@code /webhook/{path}}      → 백그라운드 실행</li>
 * </ul>
 * 두 URL은 노드 설정에서 각각 켜고 끌 수 있다. 실제 수신은 {@link WebhookController}.
 */
@Component
@Order(0)
public class WebhookModule implements NodeModule {

    public static final String TYPE = "WEBHOOK";
    public static final String ON = "켜짐";
    public static final String OFF = "꺼짐";
    public static final String ANY_METHOD = "모두";

    private final WebhookHistoryStore historyStore;

    public WebhookModule(WebhookHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "웹훅", "🪝", "#FF6D5A", "트리거", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("urls", "웹훅 URL", "webhookUrls", null, null),
                new NodeParam("path", "경로 (비우면 노드 ID로 고정)", "text", "", null),
                new NodeParam("httpMethod", "HTTP 메소드", "select", "POST",
                        List.of("POST", "GET", "PUT", "PATCH", "DELETE", ANY_METHOD)),
                // 테스트 URL = 에디터 '▶ 수신 대기' / '■ 중지' 버튼과 연동되는 상태
                new NodeParam("testEnabled", "테스트 URL (수신 대기)", "select", OFF, List.of(ON, OFF)),
                new NodeParam("prodEnabled", "운영 URL", "select", OFF, List.of(ON, OFF)),
                new NodeParam("outputTemplate",
                        "전달 내용 구성 (비우면 요청 전체 JSON)", "webhookTemplate", "", null),
                new NodeParam("outputVar", "출력 변수명", "text", "webhook", null)
        ), true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(NodeContext ctx) throws Exception {
        Object data = ctx.triggerData();
        if (data == null) {
            // 수동 실행: 마지막으로 받은 요청을 재사용 (없으면 빈 요청)
            data = historyStore.lastAcceptedPayload(ctx.node().getId());
            if (data == null) data = emptyPayload();
        }
        String full = ctx.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data);

        String template = ctx.raw("outputTemplate");
        if (template == null || template.isBlank() || !(data instanceof Map<?, ?>)) return full;

        // 직접 구성: 구성 결과를 다음 노드로 넘기고, 요청 전체는 ${outputVar.request...} 로 계속 참조 가능
        String outputVar = ctx.raw("outputVar", "webhook");
        ctx.vars().put(outputVar + ".request", full);
        return compose(template, (Map<String, Object>) data, ctx.objectMapper());
    }

    /**
     * 출력 구성 템플릿 렌더링. 참조 순서:
     * 요청 필드(${body.a}, ${query.x}, ${headers.user-agent}, ${method} …) → 바디 바로 아래 키 축약(${a}).
     */
    @SuppressWarnings("unchecked")
    public static String compose(String template, Map<String, Object> payload, ObjectMapper om) {
        Map<String, Object> bodyScope = payload.get("body") instanceof Map<?, ?> b
                ? (Map<String, Object>) b : Map.of();
        return Templates.resolve(template, List.of(payload, bodyScope), om);
    }

    /** 템플릿에 쓸 수 있는 참조 목록 (에디터의 키 칩용) — 바디 키는 축약형, 그 외는 전체 경로 */
    public static List<String> availableKeys(Map<String, Object> payload) {
        List<String> keys = new ArrayList<>();
        if (payload.get("body") instanceof Map<?, ?> body) collectPaths("", body, keys, 0);
        else if (payload.containsKey("body")) keys.add("body");
        if (payload.get("query") instanceof Map<?, ?> q) q.keySet().forEach(k -> keys.add("query." + k));
        keys.addAll(List.of("method", "path", "mode", "receivedAt"));
        if (payload.get("headers") instanceof Map<?, ?> h) {
            for (String k : List.of("content-type", "user-agent", "authorization")) {
                if (h.containsKey(k)) keys.add("headers." + k);
            }
        }
        return keys;
    }

    private static void collectPaths(String prefix, Object node, List<String> out, int depth) {
        if (out.size() >= 60) return;
        if (node instanceof Map<?, ?> map && depth < 3) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String p = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                if (e.getValue() instanceof Map<?, ?> || e.getValue() instanceof List<?>) {
                    collectPaths(p, e.getValue(), out, depth + 1);
                } else {
                    out.add(p);
                }
            }
        } else if (node instanceof List<?> list && !list.isEmpty() && depth < 3) {
            collectPaths(prefix + "[0]", list.get(0), out, depth + 1);
        } else if (!prefix.isEmpty()) {
            out.add(prefix);
        }
    }

    /** 이 노드의 웹훅 경로 — 설정값이 없으면 노드 ID (노드를 지우지 않는 한 고정) */
    public static String effectivePath(PipelineNode node) {
        Object p = node.getConfig() != null ? node.getConfig().get("path") : null;
        String path = p instanceof String s ? s.trim().replaceAll("^/+|/+$", "") : "";
        return path.isEmpty() ? node.getId() : path;
    }

    public static boolean isEnabled(PipelineNode node, RunMode mode) {
        String key = mode == RunMode.TEST ? "testEnabled" : "prodEnabled";
        Object v = node.getConfig() != null ? node.getConfig().get(key) : null;
        return ON.equals(v);
    }

    public static boolean acceptsMethod(PipelineNode node, String method) {
        Object v = node.getConfig() != null ? node.getConfig().get("httpMethod") : null;
        String allowed = v instanceof String s && !s.isBlank() ? s : "POST";
        return ANY_METHOD.equals(allowed) || allowed.equalsIgnoreCase(method);
    }

    private static Map<String, Object> emptyPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", "manual");
        m.put("note", "아직 받은 웹훅 요청이 없어 빈 데이터로 실행했습니다. 수신 대기를 켜고 테스트 URL로 요청을 보내보세요.");
        m.put("method", null);
        m.put("query", Map.of());
        m.put("headers", Map.of());
        m.put("body", null);
        return m;
    }
}
