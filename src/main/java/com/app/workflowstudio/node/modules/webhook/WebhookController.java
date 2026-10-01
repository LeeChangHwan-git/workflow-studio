package com.app.workflowstudio.node.modules.webhook;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.RunMode;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.app.workflowstudio.service.PipelineService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 웹훅 수신 엔드포인트 (모든 HTTP 메소드).
 * 저장된 파이프라인에서 경로가 일치하는 WEBHOOK 노드를 찾아 그 노드부터 파이프라인을 실행한다.
 */
@RestController
public class WebhookController {

    static final String TEST_PREFIX = "/webhook-test/";

    private final PipelineService pipelineService;
    private final PipelineRunnerService runnerService;
    private final WebhookHistoryStore historyStore;
    private final ObjectMapper objectMapper;

    public WebhookController(PipelineService pipelineService,
                             PipelineRunnerService runnerService,
                             WebhookHistoryStore historyStore,
                             ObjectMapper objectMapper) {
        this.pipelineService = pipelineService;
        this.runnerService = runnerService;
        this.historyStore = historyStore;
        this.objectMapper = objectMapper;
    }

    @RequestMapping({"/webhook/{path}", "/webhook-test/{path}"})
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String path,
                                                       @RequestBody(required = false) String body,
                                                       HttpServletRequest req) {
        RunMode mode = req.getRequestURI().startsWith(TEST_PREFIX) ? RunMode.TEST : RunMode.PROD;
        String modeLabel = mode == RunMode.TEST ? "테스트" : "운영";

        // 경로가 일치하는 웹훅 노드 찾기
        List<Map.Entry<Pipeline, PipelineNode>> matches = new ArrayList<>();
        for (Pipeline p : pipelineService.findAll()) {
            if (p.getNodes() == null) continue;
            for (PipelineNode n : p.getNodes()) {
                if (WebhookModule.TYPE.equals(n.getType()) && path.equals(WebhookModule.effectivePath(n))) {
                    matches.add(Map.entry(p, n));
                }
            }
        }

        if (matches.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "등록된 웹훅이 없습니다: " + path
                    + " (파이프라인을 저장했는지 확인하세요)");
        }
        if (matches.size() > 1) {
            return error(HttpStatus.CONFLICT, "같은 경로를 쓰는 웹훅 노드가 " + matches.size()
                    + "개 있습니다: " + path + " — 노드 설정에서 경로를 다르게 지정하세요");
        }

        Pipeline pipeline = matches.get(0).getKey();
        PipelineNode node = matches.get(0).getValue();
        Map<String, Object> payload = buildPayload(req, path, mode, body);

        // 노드가 특정되면 거부된 요청도 수신 기록에 남긴다
        if (!WebhookModule.isEnabled(node, mode)) {
            String msg = mode == RunMode.TEST
                    ? "테스트 URL이 꺼져 있습니다 (에디터에서 ▶ 수신 대기를 켜세요)"
                    : "운영 URL이 꺼져 있습니다 (노드 설정에서 켜세요)";
            record(node, payload, mode, req.getMethod(), HttpStatus.NOT_FOUND, null, msg);
            return error(HttpStatus.NOT_FOUND, msg);
        }
        if (!WebhookModule.acceptsMethod(node, req.getMethod())) {
            String msg = req.getMethod() + " 요청은 허용되지 않습니다 (설정: " + node.getConfig().get("httpMethod") + ")";
            record(node, payload, mode, req.getMethod(), HttpStatus.METHOD_NOT_ALLOWED, null, msg);
            return error(HttpStatus.METHOD_NOT_ALLOWED, msg);
        }

        String execId = runnerService.runTriggered(pipeline, node.getId(), mode, payload);
        record(node, payload, mode, req.getMethod(), HttpStatus.OK, execId, modeLabel + " 수신 → 실행");

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("message", "워크플로우 실행을 시작했습니다");
        res.put("mode", mode.name().toLowerCase());
        res.put("pipeline", pipeline.getName());
        res.put("executionId", execId);
        return ResponseEntity.ok(res);
    }

    // ── 수신 기록 API (에디터 '수신 기록' 탭) ─────────────────────

    /** 최신순 수신 기록. 실행된 건은 현재 실행 상태(running/done/error, 서버 재시작 후엔 null)를 함께 반환 */
    @GetMapping("/api/webhooks/{nodeId}/history")
    public List<Map<String, Object>> history(@PathVariable String nodeId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WebhookHistoryStore.Entry e : historyStore.list(nodeId)) {
            Map<String, Object> m = objectMapper.convertValue(e, new TypeReference<LinkedHashMap<String, Object>>() {});
            PipelineRunnerService.ExecutionState s = e.executionId != null ? runnerService.getState(e.executionId) : null;
            m.put("executionStatus", s != null ? s.status : null);
            result.add(m);
        }
        return result;
    }

    /**
     * 출력 구성 미리보기 — 이 노드가 마지막으로 받은 요청(거부된 것 포함)에 템플릿을 적용한 결과와
     * 템플릿에 쓸 수 있는 키 목록을 돌려준다. 받은 요청이 없으면 hasSample=false.
     */
    @PostMapping("/api/webhooks/{nodeId}/preview")
    public Map<String, Object> preview(@PathVariable String nodeId, @RequestBody Map<String, String> req) {
        List<WebhookHistoryStore.Entry> list = historyStore.list(nodeId);
        Map<String, Object> res = new LinkedHashMap<>();
        if (list.isEmpty() || list.get(0).payload == null) {
            res.put("hasSample", false);
            return res;
        }
        Map<String, Object> payload = list.get(0).payload;
        String template = req.getOrDefault("template", "");
        res.put("hasSample", true);
        res.put("sampleAt", list.get(0).receivedAt);
        res.put("keys", WebhookModule.availableKeys(payload));
        res.put("result", template == null || template.isBlank() ? null
                : WebhookModule.compose(template, payload, objectMapper));
        return res;
    }

    @DeleteMapping("/api/webhooks/{nodeId}/history")
    public ResponseEntity<Void> clearHistory(@PathVariable String nodeId) {
        historyStore.clear(nodeId);
        return ResponseEntity.noContent().build();
    }

    private void record(PipelineNode node, Map<String, Object> payload, RunMode mode, String method,
                        HttpStatus status, String execId, String message) {
        WebhookHistoryStore.Entry e = new WebhookHistoryStore.Entry();
        e.id = UUID.randomUUID().toString();
        e.receivedAt = String.valueOf(payload.get("receivedAt"));
        e.mode = mode.name().toLowerCase();
        e.method = method;
        e.httpStatus = status.value();
        e.accepted = execId != null;
        e.executionId = execId;
        e.message = message;
        e.payload = payload;
        historyStore.add(node.getId(), e);
    }

    private Map<String, Object> buildPayload(HttpServletRequest req, String path, RunMode mode, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(req.getHeaderNames())) {
            headers.put(name.toLowerCase(), req.getHeader(name));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("receivedAt", LocalDateTime.now().toString());
        payload.put("mode", mode.name().toLowerCase());
        payload.put("method", req.getMethod());
        payload.put("path", path);
        payload.put("query", parseQuery(req.getQueryString()));
        payload.put("headers", headers);
        payload.put("body", parseBody(body, req.getContentType()));
        return payload;
    }

    /** JSON → 객체, form-urlencoded → 맵, 그 외 → 원문 문자열, 비어 있으면 null */
    private Object parseBody(String body, String contentType) {
        if (body == null || body.isBlank()) return null;
        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            return parseQuery(body);
        }
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception e) {
            return body;
        }
    }

    /** a=1&b=2&b=3 → {a:"1", b:["2","3"]} */
    private Map<String, Object> parseQuery(String qs) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (qs == null || qs.isBlank()) return map;
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.merge(k, v, (oldV, newV) -> {
                @SuppressWarnings("unchecked")
                List<Object> list = oldV instanceof List<?> l ? (List<Object>) l : new ArrayList<>(List.of(oldV));
                list.add(newV);
                return list;
            });
        }
        return map;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
