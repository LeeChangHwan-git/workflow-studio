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

    // executionId -> execution state
    private final Map<String, ExecutionState> executions = new ConcurrentHashMap<>();

    public PipelineRunnerService(PipelineService pipelineService,
                                 NodeTypeRegistry nodeTypeRegistry,
                                 ObjectMapper objectMapper) {
        this.pipelineService = pipelineService;
        this.nodeTypeRegistry = nodeTypeRegistry;
        this.objectMapper = objectMapper;
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

        Thread t = new Thread(() -> executePipeline(pipeline, state, triggerData), "pipeline-runner-" + execId);
        t.setDaemon(true);
        state.thread = t;
        t.start();

        return execId;
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

    // ── 실행 엔진 ──────────────────────────────────────────────

    private void executePipeline(Pipeline pipeline, ExecutionState state, Object triggerData) {
        Map<String, Object> vars = new HashMap<>();     // outputVar / nodeId.output → value
        Map<String, String> outputs = new HashMap<>();  // nodeId → 이번 실행 출력 (${input} 계산용)
        Map<String, List<String>> upstream = upstreamMap(pipeline);
        Set<String> reachable = state.triggerNodeId != null
                ? reachableFrom(pipeline, state.triggerNodeId) : null;

        for (PipelineNode node : topoSort(pipeline)) {
            if (reachable != null && !reachable.contains(node.getId())) continue;
            if (Thread.currentThread().isInterrupted()) {
                state.status = "error";
                return;
            }

            RunLog log = new RunLog(node.getId(), node.getType(), "running",
                    "[" + node.getType() + "] 실행 중...");
            state.logs.add(log);

            try {
                NodeModule module = nodeTypeRegistry.find(node.getType());
                if (module == null) throw new Exception("등록되지 않은 노드 타입: " + node.getType());

                String input = upstream.getOrDefault(node.getId(), List.of()).stream()
                        .map(outputs::get)
                        .filter(Objects::nonNull)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse(null);
                Object nodeTrigger = node.getId().equals(state.triggerNodeId) ? triggerData : null;

                String output = module.execute(
                        new NodeContext(node, vars, input, state.mode, nodeTrigger, objectMapper));
                log.setStatus("success");
                log.setMessage("[" + node.getType() + "] 완료");
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
                log.setMessage("[" + node.getType() + "] 오류: " + e.getMessage());
                state.status = "error";
                return;
            }
        }

        state.status = "done";
    }

    // ── 그래프 헬퍼 ───────────────────────────────────────────

    /** nodeId → 화살표로 이 노드에 들어오는 노드 id 목록 */
    private Map<String, List<String>> upstreamMap(Pipeline pipeline) {
        Map<String, List<String>> map = new HashMap<>();
        if (pipeline.getConnections() == null) return map;
        for (PipelineConnection c : pipeline.getConnections()) {
            map.computeIfAbsent(c.getToNodeId(), k -> new ArrayList<>()).add(c.getFromNodeId());
        }
        return map;
    }

    /** startId 자신 + 화살표를 따라 도달 가능한 모든 노드 id */
    private Set<String> reachableFrom(Pipeline pipeline, String startId) {
        Map<String, List<String>> down = new HashMap<>();
        if (pipeline.getConnections() != null) {
            for (PipelineConnection c : pipeline.getConnections()) {
                down.computeIfAbsent(c.getFromNodeId(), k -> new ArrayList<>()).add(c.getToNodeId());
            }
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
        if (pipeline.getConnections() == null || pipeline.getConnections().isEmpty()) {
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
        for (PipelineConnection c : pipeline.getConnections()) {
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
