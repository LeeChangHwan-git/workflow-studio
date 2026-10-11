package com.app.workflowstudio.service;

import com.app.workflowstudio.model.*;
import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeTypeRegistry;
import com.app.workflowstudio.node.RunMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 파이프라인 실행 엔진 — 노드를 연결 순서대로 실행하고 실행 상태/로그를 관리한다. 노드별 동작은 {@link NodeModule}이 담당. */
@Service
public class PipelineRunnerService {

    private final PipelineService pipelineService;
    private final NodeTypeRegistry nodeTypeRegistry;
    private final ObjectMapper objectMapper;
    private final ExecutionHistoryStore history;

    // executionId -> execution state
    private final Map<String, ExecutionState> executions = new ConcurrentHashMap<>();

    public PipelineRunnerService(PipelineService pipelineService,
                                 NodeTypeRegistry nodeTypeRegistry,
                                 ObjectMapper objectMapper,
                                 ExecutionHistoryStore history) {
        this.pipelineService = pipelineService;
        this.nodeTypeRegistry = nodeTypeRegistry;
        this.objectMapper = objectMapper;
        this.history = history;
    }

    /** 메모리에 보관할 최대 실행 기록 수 (초과 시 끝난 실행부터 오래된 순으로 제거) */
    private static final int MAX_EXECUTIONS = 200;

    public static class ExecutionState {
        public final String executionId;
        public final String pipelineId;
        public final RunMode mode;
        public final String triggerNodeId; // 트리거로 시작된 경우 해당 노드 id, 수동 실행은 null
        public final LocalDateTime startedAt = LocalDateTime.now();
        public volatile String status = "running"; // running | done | error
        public final List<RunLog> logs = Collections.synchronizedList(new ArrayList<>());
        public volatile Thread thread;

        public ExecutionState(String executionId, String pipelineId, RunMode mode, String triggerNodeId) {
            this.executionId = executionId;
            this.pipelineId = pipelineId;
            this.mode = mode;
            this.triggerNodeId = triggerNodeId;
        }
    }

    /** 수동 실행 (▶ 실행 버튼) — 모든 노드 실행 → executionId 반환 */
    public String run(String pipelineId) {
        Pipeline pipeline = pipelineService.findById(pipelineId);
        if (pipeline == null) throw new IllegalArgumentException("파이프라인을 찾을 수 없습니다");
        return start(pipeline, RunMode.MANUAL, null, null);
    }

    /**
     * 트리거 실행 (웹훅 등) — triggerNodeId 에서 화살표로 도달 가능한 노드만 실행한다.
     * triggerData 는 트리거 모듈이 {@link NodeContext#triggerData()} 로 받는다.
     */
    public String runTriggered(Pipeline pipeline, String triggerNodeId, RunMode mode, Object triggerData) {
        return start(pipeline, mode, triggerNodeId, triggerData);
    }

    private String start(Pipeline pipeline, RunMode mode, String triggerNodeId, Object triggerData) {
        pruneExecutions();
        String execId = UUID.randomUUID().toString();
        ExecutionState state = new ExecutionState(execId, pipeline.getId(), mode, triggerNodeId);
        executions.put(execId, state);

        Thread t = new Thread(() -> {
            try {
                executePipeline(pipeline, state, triggerData, null, null, null);
            } finally {
                if ("running".equals(state.status)) state.status = "error";
                record(pipeline, state);
            }
        }, "pipeline-runner-" + execId);
        t.setDaemon(true);
        state.thread = t;
        t.start();

        return execId;
    }

    /** 지금 실행 중인 실행들 (홈 대시보드) */
    public List<ExecutionState> running() {
        return executions.values().stream().filter(st -> "running".equals(st.status)).toList();
    }

    public ExecutionState getState(String executionId) {
        return executions.get(executionId);
    }

    /** 해당 파이프라인의 가장 최근 실행 (mode 지정 시 그 모드만) */
    public ExecutionState findLatest(String pipelineId, RunMode mode) {
        return executions.values().stream()
                .filter(s -> s.pipelineId.equals(pipelineId))
                .filter(s -> mode == null || s.mode == mode)
                .max(Comparator.comparing((ExecutionState s) -> s.startedAt))
                .orElse(null);
    }

    private void pruneExecutions() {
        if (executions.size() < MAX_EXECUTIONS) return;
        executions.values().stream()
                .filter(s -> !"running".equals(s.status))
                .sorted(Comparator.comparing((ExecutionState s) -> s.startedAt))
                .limit(executions.size() - MAX_EXECUTIONS + 1)
                .map(s -> s.executionId)
                .toList()
                .forEach(executions::remove);
    }

    public boolean cancel(String executionId) {
        ExecutionState state = executions.get(executionId);
        if (state == null || !"running".equals(state.status)) return false;
        if (state.thread != null) state.thread.interrupt();
        state.status = "error";
        return true;
    }

    /** 끝난 실행 요약을 파일 기록에 남김 (홈 대시보드 통계용) */
    private void record(Pipeline pipeline, ExecutionState state) {
        try {
            ExecutionHistoryStore.Entry e = new ExecutionHistoryStore.Entry();
            LocalDateTime end = LocalDateTime.now();
            e.executionId = state.executionId;
            e.pipelineId = pipeline.getId();
            e.pipelineName = pipeline.getName();
            e.mode = state.mode.name();
            e.status = state.status;
            e.startedAt = state.startedAt.withNano(0).toString();
            e.endedAt = end.withNano(0).toString();
            e.durationMs = java.time.Duration.between(state.startedAt, end).toMillis();
            List<RunLog> logs;
            synchronized (state.logs) { logs = new ArrayList<>(state.logs); }
            e.nodes = (int) logs.stream().filter(l -> "success".equals(l.getStatus())).count();
            logs.stream().filter(l -> "error".equals(l.getStatus())).findFirst().ifPresent(l -> {
                e.errorNode = nodeName(pipeline, l.getNodeId());
                e.message = l.getMessage();
            });
            history.add(e);
        } catch (Exception ignore) {
            // 기록 실패는 무시
        }
    }

    private static String nodeName(Pipeline pipeline, String nodeId) {
        return pipeline.getNodes().stream().filter(n -> n.getId().equals(nodeId)).findFirst().map(n -> {
            Object nm = n.getConfig() == null ? null : n.getConfig().get("name");
            return nm instanceof String str && !str.isBlank() ? str : n.getType();
        }).orElse(nodeId);
    }

    // ── 실행 엔진 ──────────────────────────────────────────────

    /** 노드 안에서 다른 파이프라인을 바로 실행한 결과 (파이프라인 모듈용) */
    public record InlineResult(String status, String output, List<RunLog> logs, Map<String, String> outputs) {}

    /**
     * 파이프라인을 <b>지금 스레드에서</b> 바로 실행 (파이프라인 모듈 — 노드 하나처럼 쓰기).
     * 실행 기록·대시보드에는 남기지 않고, 부모 실행의 id 를 그대로 써서 취소·확인 요청이 부모와 같이 움직인다.
     * @param initialVars  모듈 입력값 등 미리 넣을 변수 (예: {@code ${in.폴더}})
     * @param rootInput    들어오는 화살표가 없는 첫 노드들의 {@code ${input}}
     * @param outputNodeId 결과로 쓸 노드 (null 이면 마지막으로 출력을 낸 노드)
     */
    public InlineResult runInline(Pipeline pipeline, Map<String, Object> initialVars, String rootInput, RunMode mode,
                                  String parentExecutionId, String outputNodeId) {
        ExecutionState state = new ExecutionState(
                parentExecutionId == null ? UUID.randomUUID().toString() : parentExecutionId, pipeline.getId(), mode, null);
        Map<String, String> outputs = new LinkedHashMap<>();
        executePipeline(pipeline, state, null, initialVars, rootInput, outputs);
        if ("running".equals(state.status)) state.status = "error";
        String out = null;
        if (outputNodeId != null && !outputNodeId.isBlank()) out = outputs.get(outputNodeId);
        else for (String v : outputs.values()) out = v;      // 실행 순서상 마지막
        List<RunLog> logs;
        synchronized (state.logs) { logs = new ArrayList<>(state.logs); }
        return new InlineResult(state.status, out, logs, outputs);
    }

    private void executePipeline(Pipeline pipeline, ExecutionState state, Object triggerData,
                                 Map<String, Object> initialVars, String rootInput, Map<String, String> outputsOut) {
        Map<String, Object> vars = new HashMap<>();     // outputVar / nodeId.output → value
        if (initialVars != null) vars.putAll(initialVars);
        // nodeId → 이번 실행 출력 (${input} 계산용, 실행 순서 유지)
        Map<String, String> outputs = outputsOut != null ? outputsOut : new LinkedHashMap<>();
        Set<String> reachable = state.triggerNodeId != null
                ? reachableFrom(pipeline, state.triggerNodeId) : null;
        // 분기: 노드가 고른 출력(없으면 전부) — 고르지 않은 출력 쪽 화살표는 흐르지 않음
        Map<String, List<PipelineConnection>> incoming = new HashMap<>();
        for (PipelineConnection c : flowConnections(pipeline)) {
            if (reachable != null && !reachable.contains(c.getFromNodeId())) continue;
            incoming.computeIfAbsent(c.getToNodeId(), k -> new ArrayList<>()).add(c);
        }
        Set<String> executed = new HashSet<>();
        Map<String, Set<String>> routes = new HashMap<>();

        Map<String, Map<String, List<PipelineNode>>> partsByNode = partsMap(pipeline);

        for (PipelineNode node : topoSort(pipeline)) {
            if (reachable != null && !reachable.contains(node.getId())) continue;
            if (isPart(node)) continue; // 부품 노드는 소켓에 끼워진 노드가 읽어서 사용
            if (Thread.currentThread().isInterrupted()) {
                state.status = "error";
                return;
            }

            // 들어오는 화살표가 있는데 하나도 흐르지 않았으면(분기에서 다른 쪽이 선택됨) 건너뜀
            List<PipelineConnection> ins = incoming.getOrDefault(node.getId(), List.of());
            List<String> activeSources = ins.stream().filter(c -> flows(c, executed, routes))
                    .map(PipelineConnection::getFromNodeId).distinct().toList();
            if (!ins.isEmpty() && activeSources.isEmpty()) {
                state.logs.add(new RunLog(node.getId(), node.getType(), "skipped",
                        "[" + node.getType() + "] 건너뜀 — 앞 분기에서 이 쪽이 선택되지 않음"));
                continue;
            }

            RunLog log = new RunLog(node.getId(), node.getType(), "running",
                    "[" + node.getType() + "] 실행 중...");
            state.logs.add(log);

            try {
                NodeModule module = nodeTypeRegistry.find(node.getType());
                if (module == null) throw new Exception("등록되지 않은 노드 타입: " + node.getType());

                String input = activeSources.stream()
                        .map(outputs::get)
                        .filter(Objects::nonNull)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse(ins.isEmpty() ? rootInput : null);
                Object nodeTrigger = node.getId().equals(state.triggerNodeId) ? triggerData : null;

                NodeContext ctx = new NodeContext(node, vars, input, state.mode, nodeTrigger,
                        partsByNode.getOrDefault(node.getId(), Map.of()), objectMapper)
                        .withRun(state.executionId, pipeline.getId(), pipeline.getName(), (st, msg) -> {
                            log.setStatus(st);
                            log.setMessage(msg);
                        });
                String output = module.execute(ctx);
                executed.add(node.getId());
                if (ctx.routes() != null) {
                    routes.put(node.getId(), ctx.routes());
                    log.setRoute(new ArrayList<>(ctx.routes()));
                }
                log.setStatus("success");
                log.setMessage("[" + tag(module, node) + "] 완료"
                        + (ctx.routes() != null ? " → " + String.join(", ", outputLabels(module, node, ctx.routes())) : ""));
                log.setOutput(output);
                if (output != null) {
                    outputs.put(node.getId(), output);
                    // outputVar 설정 있으면 해당 키로, 없으면 nodeId.output 으로 저장
                    Object varName = node.getConfig() != null ? node.getConfig().get("outputVar") : null;
                    String key = (varName instanceof String s && !s.isBlank())
                            ? s : node.getId() + ".output";
                    vars.put(key, output);
                    vars.put(node.getId() + ".output", output);
                    log.setOutputVar(key);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.setStatus("error");
                log.setMessage("실행 취소됨");
                state.status = "error";
                return;
            } catch (Exception e) {
                log.setStatus("error");
                log.setMessage("[" + tag(nodeTypeRegistry.find(node.getType()), node) + "] 오류: " + e.getMessage());
                state.status = "error";
                return;
            }
        }

        state.status = "done";
    }

    /** 로그 앞 표시 — 기본 모듈은 타입 id, 내 모듈(MOD_…)은 모듈 이름 */
    private static String tag(NodeModule module, PipelineNode node) {
        return module != null && node.getType().startsWith("MOD_") ? module.info().label() : node.getType();
    }

    /** 이 화살표로 흐르는지: 앞 노드가 실행됐고, 출력을 골랐다면 그 출력에서 나온 화살표 */
    static boolean flows(PipelineConnection c, Set<String> executed, Map<String, Set<String>> routes) {
        if (!executed.contains(c.getFromNodeId())) return false;
        Set<String> r = routes.get(c.getFromNodeId());
        return r == null || (c.getFromHandle() != null && r.contains(c.getFromHandle()));
    }

    /** 고른 출력 id → 화면 이름 (정적 출력은 모듈 정보, 규칙 기반 출력은 노드 설정) */
    private List<String> outputLabels(NodeModule module, PipelineNode node, Set<String> ids) {
        Map<String, String> names = new HashMap<>();
        for (var o : module.info().outputs() == null ? List.<com.app.workflowstudio.node.NodeOutput>of() : module.info().outputs())
            names.put(o.id(), o.label());
        if (module instanceof com.app.workflowstudio.node.DynamicOutputs d) names.putAll(d.outputLabels(node));
        return ids.stream().map(id -> names.getOrDefault(id, id)).toList();
    }

    // ── 그래프 헬퍼 ───────────────────────────────────────────
    // 실행 흐름(화살표)만 순서·${input}에 쓰고, 소켓 연결(부품)은 partsMap 으로 따로 전달한다.

    private boolean isPart(PipelineNode node) {
        NodeModule m = nodeTypeRegistry.find(node.getType());
        return m != null && m.info().isPart();
    }

    /** 실행 흐름 연결만 (소켓 연결 제외) */
    private List<PipelineConnection> flowConnections(Pipeline pipeline) {
        if (pipeline.getConnections() == null) return List.of();
        return pipeline.getConnections().stream().filter(c -> !c.socket()).toList();
    }

    /** nodeId → (소켓 id → 끼워진 부품 노드 목록, 연결 순서) */
    private Map<String, Map<String, List<PipelineNode>>> partsMap(Pipeline pipeline) {
        Map<String, Map<String, List<PipelineNode>>> map = new HashMap<>();
        if (pipeline.getConnections() == null || pipeline.getNodes() == null) return map;
        Map<String, PipelineNode> byId = new HashMap<>();
        for (PipelineNode n : pipeline.getNodes()) byId.put(n.getId(), n);
        for (PipelineConnection c : pipeline.getConnections()) {
            if (!c.socket()) continue;
            PipelineNode part = byId.get(c.getFromNodeId());
            if (part == null) continue;
            map.computeIfAbsent(c.getToNodeId(), k -> new LinkedHashMap<>())
               .computeIfAbsent(c.getToHandle(), k -> new ArrayList<>())
               .add(part);
        }
        return map;
    }

    /** startId 자신 + 화살표를 따라 도달 가능한 모든 노드 id */
    private Set<String> reachableFrom(Pipeline pipeline, String startId) {
        Map<String, List<String>> down = new HashMap<>();
        for (PipelineConnection c : flowConnections(pipeline)) {
            down.computeIfAbsent(c.getFromNodeId(), k -> new ArrayList<>()).add(c.getToNodeId());
        }
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>(List.of(startId));
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (seen.add(id)) stack.addAll(down.getOrDefault(id, List.of()));
        }
        return seen;
    }

    // ── 위상 정렬 (연결 순서대로 실행) ─────────────────────────

    private List<PipelineNode> topoSort(Pipeline pipeline) {
        if (pipeline.getNodes() == null) return List.of();
        List<PipelineConnection> flow = flowConnections(pipeline);
        if (flow.isEmpty()) {
            return new ArrayList<>(pipeline.getNodes());
        }

        Map<String, PipelineNode> nodeMap = new LinkedHashMap<>();
        for (PipelineNode n : pipeline.getNodes()) nodeMap.put(n.getId(), n);

        Map<String, List<String>> adj = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeMap.keySet()) {
            adj.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }
        for (PipelineConnection c : flow) {
            if (!adj.containsKey(c.getFromNodeId()) || !inDegree.containsKey(c.getToNodeId())) continue; // 지워진 노드
            adj.get(c.getFromNodeId()).add(c.getToNodeId());
            inDegree.merge(c.getToNodeId(), 1, Integer::sum);
        }

        Queue<String> queue = new ArrayDeque<>();
        inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .forEach(queue::add);

        List<PipelineNode> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            PipelineNode node = nodeMap.get(id);
            if (node != null) result.add(node);
            for (String next : adj.getOrDefault(id, List.of())) {
                inDegree.merge(next, -1, Integer::sum);
                if (inDegree.get(next) == 0) queue.add(next);
            }
        }
        return result;
    }
}
