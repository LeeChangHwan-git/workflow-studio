package com.app.workflowstudio.node;

import com.app.workflowstudio.model.PipelineNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 노드 실행 시 모듈에 전달되는 컨텍스트.
 * 노드 설정값 조회와 이전 노드 출력 변수({@code ${...}}) 치환을 제공한다 (치환 규칙은 {@link Templates}).
 */
public class NodeContext {

    /** 화살표로 들어온 이전 노드 출력을 가리키는 예약 변수명: ${input}, ${input.field} */
    public static final String INPUT_VAR = "input";

    private final PipelineNode node;
    private final Map<String, Object> config;
    private final Map<String, Object> vars;
    private final String input;
    private final RunMode mode;
    private final Object triggerData;
    private final ObjectMapper objectMapper;

    private final Map<String, List<PipelineNode>> parts;

    public NodeContext(PipelineNode node, Map<String, Object> vars, String input,
                       RunMode mode, Object triggerData, ObjectMapper objectMapper) {
        this(node, vars, input, mode, triggerData, Map.of(), objectMapper);
    }

    public NodeContext(PipelineNode node, Map<String, Object> vars, String input,
                       RunMode mode, Object triggerData, Map<String, List<PipelineNode>> parts,
                       ObjectMapper objectMapper) {
        this.node = node;
        this.config = node.getConfig() != null ? node.getConfig() : Map.of();
        this.vars = vars;
        this.input = input;
        this.mode = mode;
        this.triggerData = triggerData;
        this.parts = parts;
        this.objectMapper = objectMapper;
    }

    /** 소켓(socketId)에 끼워진 부품 노드들 (연결된 순서, 없으면 빈 목록) */
    public List<PipelineNode> parts(String socketId) {
        return parts.getOrDefault(socketId, List.of());
    }

    public PipelineNode node() {
        return node;
    }

    /** 이전 노드들의 출력 변수 (outputVar / nodeId.output → 값). 모듈이 직접 값을 추가할 수도 있다. */
    public Map<String, Object> vars() {
        return vars;
    }

    /** 화살표로 연결된 직전 노드(들)의 출력. 여러 개면 줄바꿈으로 합침, 없으면 null */
    public String input() {
        return input;
    }

    /** 이번 실행이 어떻게 시작됐는지 (수동 / 웹훅 테스트 / 웹훅 운영) */
    public RunMode mode() {
        return mode;
    }

    /** 이 노드가 이번 실행을 시작시킨 트리거라면 외부 이벤트 데이터, 아니면 null */
    public Object triggerData() {
        return triggerData;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    /** 실행 취소 여부 — 오래 걸리는 모듈은 루프 안에서 확인할 것 */
    public boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }

    // ── 설정값 조회 (원본 그대로) ─────────────────────────────

    /** 다른 노드(예: 소켓에 끼워진 부품)의 설정값 — ${...} 치환은 이 노드의 변수 기준 */
    public static String configOf(PipelineNode n, String key, String def) {
        Object v = n.getConfig() != null ? n.getConfig().get(key) : null;
        if (v instanceof String s && !s.isBlank()) return s;
        if (v instanceof Number num) return String.valueOf(num);
        return def;
    }

    public String raw(String key) {
        Object v = config.get(key);
        return v instanceof String s ? s : null;
    }

    public String raw(String key, String def) {
        Object v = config.get(key);
        return v instanceof String s && !s.isBlank() ? s : def;
    }

    public int intVal(String key, int def) {
        Object v = config.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
        }
        return def;
    }

    // ── 설정값 조회 (${변수} 치환 적용) ───────────────────────

    public String str(String key) {
        return resolve(raw(key));
    }

    public String str(String key, String def) {
        return resolve(raw(key, def));
    }

    /** 텍스트 안의 ${var} / ${var.key[0].nested} 를 변수 값으로 치환. 해석 못 한 참조는 그대로 둔다. */
    public String resolve(String text) {
        return resolve(text, Map.of());
    }

    /** 모듈 전용 변수(extra)를 가장 먼저 찾고, 그다음 ${input}, 전역 vars 순으로 치환 */
    public String resolve(String text, Map<String, ?> extra) {
        // ${input} 은 노드마다 다른 값 → 전역 vars 보다 우선
        Map<String, Object> inputScope = input != null ? Map.of(INPUT_VAR, input) : Map.of();
        return Templates.resolve(text, List.of(extra, inputScope, vars), objectMapper);
    }
}
