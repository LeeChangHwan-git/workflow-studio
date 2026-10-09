package com.app.workflowstudio.node.modules.approval;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 확인 요청 대기열 — 파이프라인이 "확인 요청" 노드에서 멈춰 사람의 결정을 기다린다.
 * 화면(approvals.js)이 대기 목록을 보여 주고, 사람이 고른 결정이 기다리던 실행으로 전달된다.
 */
@Service
public class ApprovalService {

    public static final String APPROVE = "approve";
    public static final String RETRY = "retry";
    public static final String REJECT = "reject";
    public static final String TIMEOUT = "timeout";

    /** 사람의 결정: approve | retry | reject | timeout, 의견, (승인 시) 고친 내용 */
    public record Decision(String decision, String feedback, String content) {}

    public static class Pending {
        public final String id = UUID.randomUUID().toString();
        public final String executionId, pipelineId, pipelineName, nodeId, nodeName, title;
        public final String content;
        public final int round;
        /** 다시를 누르면 이 노드 안의 AI가 고쳐서 다시 묻는지 (아니면 "다시" 출력으로 흘러감) */
        public final boolean aiRevise;
        public final List<Map<String, String>> history;
        public final LocalDateTime createdAt = LocalDateTime.now();
        public final LocalDateTime deadline;
        final CompletableFuture<Decision> future = new CompletableFuture<>();

        Pending(String executionId, String pipelineId, String pipelineName, String nodeId, String nodeName, String title,
                String content, int round, boolean aiRevise, List<Map<String, String>> history, LocalDateTime deadline) {
            this.executionId = executionId;
            this.pipelineId = pipelineId;
            this.pipelineName = pipelineName;
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.title = title;
            this.content = content;
            this.round = round;
            this.aiRevise = aiRevise;
            this.history = history;
            this.deadline = deadline;
        }

        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("executionId", executionId);
            m.put("pipelineId", pipelineId);
            m.put("pipelineName", pipelineName);
            m.put("nodeId", nodeId);
            m.put("nodeName", nodeName);
            m.put("title", title);
            m.put("content", content);
            m.put("round", round);
            m.put("aiRevise", aiRevise);
            m.put("history", history);
            m.put("createdAt", createdAt.withNano(0).toString());
            m.put("deadline", deadline == null ? null : deadline.withNano(0).toString());
            return m;
        }
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> recent = new ConcurrentLinkedDeque<>();

    public Pending open(String executionId, String pipelineId, String pipelineName, String nodeId, String nodeName,
                        String title, String content, int round, boolean aiRevise, List<Map<String, String>> history,
                        long timeoutMillis) {
        Pending p = new Pending(executionId, pipelineId, pipelineName, nodeId, nodeName, title, content, round, aiRevise,
                List.copyOf(history), timeoutMillis > 0 ? LocalDateTime.now().plusNanos(timeoutMillis * 1_000_000) : null);
        pending.put(p.id, p);
        return p;
    }

    /** 결정이 올 때까지 기다림 (timeoutMillis 0 = 계속). 실행이 취소되면 InterruptedException */
    public Decision await(Pending p, long timeoutMillis) throws InterruptedException {
        try {
            return timeoutMillis > 0 ? p.future.get(timeoutMillis, TimeUnit.MILLISECONDS) : p.future.get();
        } catch (TimeoutException e) {
            remember(p, new Decision(TIMEOUT, null, null));
            return new Decision(TIMEOUT, null, null);
        } catch (ExecutionException e) {
            return new Decision(REJECT, String.valueOf(e.getCause()), null);
        } finally {
            pending.remove(p.id);
        }
    }

    /** 화면에서 결정 — 없는(이미 끝난) 요청이면 false */
    public boolean decide(String id, String decision, String feedback, String content) {
        if (!List.of(APPROVE, RETRY, REJECT).contains(decision)) throw new IllegalArgumentException("결정은 approve / retry / reject 중 하나");
        Pending p = pending.remove(id);
        if (p == null) return false;
        Decision d = new Decision(decision, feedback == null ? "" : feedback.trim(), content);
        remember(p, d);
        p.future.complete(d);
        return true;
    }

    public List<Map<String, Object>> pending() {
        return pending.values().stream().sorted(Comparator.comparing(p -> p.createdAt)).map(Pending::view).toList();
    }

    public List<Map<String, Object>> recent() {
        return List.copyOf(recent);
    }

    private void remember(Pending p, Decision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pipelineName", p.pipelineName);
        m.put("nodeName", p.nodeName);
        m.put("title", p.title);
        m.put("round", p.round);
        m.put("decision", d.decision());
        m.put("feedback", d.feedback());
        m.put("at", LocalDateTime.now().withNano(0).toString());
        recent.addFirst(m);
        while (recent.size() > 30) recent.removeLast();
    }
}
