package com.app.workflowstudio.node.modules.schedule;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.RunMode;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.app.workflowstudio.service.PipelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 타이머(SCHEDULE) 노드 실행 관리.
 * <ul>
 *   <li>저장된 파이프라인에서 '켜짐'인 타이머 노드를 찾아 등록 — 기동 시, 파이프라인 저장·삭제 시, 그리고 1분마다 재확인</li>
 *   <li>설정이 바뀐 타이머만 다시 등록 (그대로면 유지 → 간격 방식의 기준 시각이 흔들리지 않음)</li>
 *   <li>이전 실행이 아직 진행 중이면 이번 회차는 건너뜀 (실행이 쌓이지 않도록)</li>
 *   <li>서버가 꺼져 있던 동안 놓친 회차는 실행하지 않음</li>
 * </ul>
 * 스레드 2개짜리 전용 스케줄러를 쓰며, 실제 파이프라인 실행은 실행 엔진의 별도 스레드에서 돈다.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final Duration RECHECK = Duration.ofMinutes(1);

    /** 등록된 타이머 하나 */
    private record Entry(String pipelineId, String nodeId, String fingerprint, ScheduleSpec spec,
                         LocalDateTime anchor, ScheduledFuture<?> future) {}

    private final PipelineService pipelineService;
    private final PipelineRunnerService runner;
    private final ScheduleHistoryStore history;
    private final ObjectMapper om;
    private final ThreadPoolTaskScheduler scheduler;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();      // key → 설정 오류
    private final Map<String, String> lastExecution = new ConcurrentHashMap<>(); // key → 마지막 executionId

    public ScheduleService(PipelineService pipelineService, PipelineRunnerService runner,
                           ScheduleHistoryStore history, ObjectMapper om) {
        this.pipelineService = pipelineService;
        this.runner = runner;
        this.history = history;
        this.om = om;
        this.scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("timer-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(t -> log.warn("타이머 작업 오류", t));
        scheduler.initialize();
    }

    public static String key(String pipelineId, String nodeId) {
        return pipelineId + "_" + nodeId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        refresh();
        scheduler.scheduleWithFixedDelay(this::refreshQuietly, Instant.now().plus(RECHECK), RECHECK);
    }

    @EventListener
    public void onPipelinesChanged(PipelineService.PipelinesChangedEvent e) {
        refreshQuietly();
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdown();
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("타이머 재확인 실패", e);
        }
    }

    /** 저장된 파이프라인과 등록된 타이머를 맞춤 */
    public synchronized void refresh() {
        Map<String, Map.Entry<Pipeline, PipelineNode>> wanted = new LinkedHashMap<>();
        for (Pipeline p : pipelineService.findAll()) {
            if (p.getNodes() == null) continue;
            for (PipelineNode n : p.getNodes()) {
                if (ScheduleModule.TYPE.equals(n.getType()) && ScheduleModule.isActive(n)) {
                    wanted.put(key(p.getId(), n.getId()), Map.entry(p, n));
                }
            }
        }

        // 꺼졌거나 지워진 타이머 해제
        for (String k : new ArrayList<>(entries.keySet())) {
            if (!wanted.containsKey(k)) unregister(k);
        }
        errors.keySet().removeIf(k -> !wanted.containsKey(k));

        // 새로 켜졌거나 설정이 바뀐 타이머 등록
        for (Map.Entry<String, Map.Entry<Pipeline, PipelineNode>> w : wanted.entrySet()) {
            String k = w.getKey();
            Pipeline p = w.getValue().getKey();
            PipelineNode n = w.getValue().getValue();
            String fp = fingerprint(n);
            Entry cur = entries.get(k);
            if (cur != null && cur.fingerprint().equals(fp)) continue;
            if (cur != null) unregister(k);
            register(k, p.getId(), n, fp);
        }
    }

    private void register(String k, String pipelineId, PipelineNode node, String fp) {
        ScheduleSpec spec;
        try {
            spec = ScheduleSpec.parse(node.getConfig() == null ? Map.of() : node.getConfig());
        } catch (Exception e) {
            errors.put(k, e.getMessage());
            return;
        }
        errors.remove(k);

        String nodeId = node.getId();
        Runnable task = () -> fire(pipelineId, nodeId, "timer");
        LocalDateTime now = LocalDateTime.now();
        ScheduledFuture<?> future = null;
        if (spec.cron() != null) {
            future = scheduler.schedule(task, new CronTrigger(spec.cron().toString(), ZoneId.systemDefault()));
        } else if (spec.interval() != null) {
            future = scheduler.scheduleAtFixedRate(task, toInstant(now.plus(spec.interval())), spec.interval());
        } else if (spec.once() != null && spec.once().isAfter(now)) {
            future = scheduler.schedule(task, toInstant(spec.once()));
        }
        entries.put(k, new Entry(pipelineId, nodeId, fp, spec, now, future));
        log.info("타이머 등록: {} ({})", k, spec.description());
    }

    private void unregister(String k) {
        Entry e = entries.remove(k);
        if (e != null && e.future() != null) e.future().cancel(false);
    }

    /**
     * 타이머 1회 실행. trigger = timer(정해진 시각) | now(지금 실행 버튼, 꺼져 있어도 실행).
     * @return executionId, 건너뛰었으면 null
     */
    public String fire(String pipelineId, String nodeId, String trigger) {
        String k = key(pipelineId, nodeId);
        ScheduleHistoryStore.Entry h = new ScheduleHistoryStore.Entry();
        h.at = LocalDateTime.now().withNano(0).toString();
        h.trigger = trigger;
        try {
            Pipeline p = pipelineService.findById(pipelineId);
            PipelineNode node = p == null || p.getNodes() == null ? null
                    : p.getNodes().stream().filter(n -> n.getId().equals(nodeId)).findFirst().orElse(null);
            if (node == null || !ScheduleModule.TYPE.equals(node.getType())) {
                refreshQuietly();
                return null;
            }
            if ("timer".equals(trigger) && !ScheduleModule.isActive(node)) {
                refreshQuietly();
                return null;
            }

            // 이전 실행이 아직 진행 중이면 건너뜀
            String prev = lastExecution.get(k);
            PipelineRunnerService.ExecutionState prevState = prev == null ? null : runner.getState(prev);
            if (prevState != null && "running".equals(prevState.status)) {
                h.result = "skipped";
                h.message = "이전 실행이 아직 진행 중이라 건너뜀";
                history.add(k, h);
                return null;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("firedAt", h.at);
            payload.put("trigger", trigger);
            Entry e = entries.get(k);
            payload.put("schedule", e != null ? e.spec().description() : null);

            String execId = runner.runTriggered(p, nodeId, RunMode.SCHEDULE, payload);
            lastExecution.put(k, execId);
            h.result = "fired";
            h.executionId = execId;
            history.add(k, h);
            return execId;
        } catch (Exception ex) {
            h.result = "error";
            h.message = ex.getMessage();
            history.add(k, h);
            log.warn("타이머 실행 실패: {}", k, ex);
            return null;
        }
    }

    /** 설정 화면용 상태 */
    public Map<String, Object> status(String pipelineId, String nodeId) {
        String k = key(pipelineId, nodeId);
        Entry e = entries.get(k);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registered", e != null);
        m.put("error", errors.get(k));
        if (e != null) {
            m.put("description", e.spec().description());
            m.put("next", e.spec().nextRuns(LocalDateTime.now(), e.anchor(), 3).stream().map(this::fmt).toList());
        }
        List<Map<String, Object>> hist = new ArrayList<>();
        for (ScheduleHistoryStore.Entry h : history.list(k)) {
            Map<String, Object> row = om.convertValue(h,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            PipelineRunnerService.ExecutionState s = h.executionId == null ? null : runner.getState(h.executionId);
            row.put("executionStatus", s == null ? null : s.status);
            hist.add(row);
        }
        m.put("history", hist);
        return m;
    }

    /** 동작 중인 타이머 전체 (⚙ 설정 → 타이머) — 다음 실행 순 */
    public List<Map<String, Object>> listActive() {
        Map<String, String> names = new HashMap<>();
        for (var p : pipelineService.findAll()) names.put(p.getId(), p.getName());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pipelineId", e.pipelineId());
            m.put("pipelineName", names.getOrDefault(e.pipelineId(), "?"));
            m.put("nodeId", e.nodeId());
            m.put("description", e.spec().description());
            List<String> next = e.spec().nextRuns(LocalDateTime.now(), e.anchor(), 1).stream().map(this::fmt).toList();
            m.put("next", next.isEmpty() ? null : next.get(0));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("next"))));
        return out;
    }

    /** 하루에 이보다 많이 실행되면 달력에는 "하루 N회" 한 줄로 묶음 */
    static final int CALENDAR_DAY_LIMIT = 3;

    /**
     * 달력용 타이머 일정 (from~to, 날짜별).
     * <ul>
     *   <li>예정(planned): 켜져 있는 타이머의 앞으로 실행 시각 (지금 이후만)</li>
     *   <li>지난 실행(fired / skipped / error): 타이머 실행 기록 (파일, 타이머마다 최근 100건)</li>
     * </ul>
     * 하루에 같은 타이머가 {@value #CALENDAR_DAY_LIMIT}번보다 많으면 한 줄로 묶어 count 에 횟수를 넣는다.
     */
    public Map<String, List<Map<String, Object>>> calendar(LocalDate from, LocalDate to) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        Map<String, List<Map<String, Object>>> byDate = new TreeMap<>();

        for (Pipeline p : pipelineService.findAll()) {
            for (PipelineNode n : p.getNodes()) {
                if (!ScheduleModule.TYPE.equals(n.getType())) continue;
                String k = key(p.getId(), n.getId());
                Object nm = n.getConfig() == null ? null : n.getConfig().get("name");
                String nodeName = nm instanceof String s && !s.isBlank() ? s : "타이머";

                // 예정
                Entry e = entries.get(k);
                if (e != null) {
                    LocalDateTime start = from.atStartOfDay().isAfter(now) ? from.atStartOfDay().minusSeconds(1) : now;
                    Map<LocalDate, List<LocalDateTime>> runs = new TreeMap<>();
                    LocalDateTime cursor = start;
                    int guard = 0;
                    outer:
                    while (guard < 20_000) {
                        List<LocalDateTime> batch = e.spec().nextRuns(cursor, e.anchor(), 500);
                        if (batch.isEmpty()) break;
                        for (LocalDateTime t : batch) {
                            guard++;
                            if (!t.isBefore(end)) break outer;
                            runs.computeIfAbsent(t.toLocalDate(), d -> new ArrayList<>()).add(t);
                        }
                        cursor = batch.get(batch.size() - 1);
                    }
                    runs.forEach((day, times) -> addCalendarItems(byDate, day, times, "planned", p, n.getId(),
                            nodeName, e.spec().description(), Map.of()));
                }

                // 지난 실행
                Map<LocalDate, Map<String, List<ScheduleHistoryStore.Entry>>> past = new TreeMap<>();
                for (ScheduleHistoryStore.Entry h : history.list(k)) {
                    LocalDateTime at;
                    try {
                        at = LocalDateTime.parse(h.at);
                    } catch (Exception ex) {
                        continue;
                    }
                    if (at.toLocalDate().isBefore(from) || !at.isBefore(end)) continue;
                    past.computeIfAbsent(at.toLocalDate(), d -> new TreeMap<>())
                            .computeIfAbsent(pastKind(h), r -> new ArrayList<>()).add(h);
                }
                String desc = e != null ? e.spec().description() : null;
                past.forEach((day, byKind) -> byKind.forEach((kind, list) -> {
                    list.sort(Comparator.comparing(h -> h.at));
                    if (list.size() > CALENDAR_DAY_LIMIT) {   // 많으면 한 줄로
                        addCalendarItems(byDate, day, list.stream().map(h -> LocalDateTime.parse(h.at)).toList(),
                                kind, p, n.getId(), nodeName, desc, Map.of());
                        return;
                    }
                    for (ScheduleHistoryStore.Entry h : list) {   // 적으면 실행마다 (트리거·실행 id·메시지 포함)
                        Map<String, Object> extra = new LinkedHashMap<>();
                        if (h.trigger != null) extra.put("trigger", h.trigger);
                        if (h.executionId != null) extra.put("executionId", h.executionId);
                        String msg = h.message;
                        if ("error".equals(kind) && msg == null) msg = executionError(h.executionId);
                        if (msg != null) extra.put("message", msg);
                        addCalendarItems(byDate, day, List.of(LocalDateTime.parse(h.at)), kind, p, n.getId(), nodeName, desc, extra);
                    }
                }));
            }
        }
        byDate.values().forEach(list -> list.sort(Comparator.comparing(m -> String.valueOf(m.get("time")))));
        return byDate;
    }

    /** 지난 실행의 표시 종류: 시작은 했지만 파이프라인이 오류로 끝났으면 error (실행 상세가 메모리에 남아 있을 때) */
    private String pastKind(ScheduleHistoryStore.Entry h) {
        String r = h.result == null ? "fired" : h.result;
        if ("fired".equals(r) && h.executionId != null) {
            PipelineRunnerService.ExecutionState s = runner.getState(h.executionId);
            if (s != null && "error".equals(s.status)) return "error";
        }
        return r;
    }

    private String executionError(String executionId) {
        PipelineRunnerService.ExecutionState s = executionId == null ? null : runner.getState(executionId);
        if (s == null) return null;
        synchronized (s.logs) {
            for (var log : s.logs) if ("error".equals(log.getStatus())) return log.getMessage();
        }
        return null;
    }

    private static void addCalendarItems(Map<String, List<Map<String, Object>>> byDate, LocalDate day,
                                         List<LocalDateTime> times, String kind, Pipeline p,
                                         String nodeId, String nodeName, String description, Map<String, Object> extra) {
        List<Map<String, Object>> list = byDate.computeIfAbsent(day.toString(), d -> new ArrayList<>());
        boolean group = times.size() > CALENDAR_DAY_LIMIT;
        for (LocalDateTime t : group ? times.subList(0, 1) : times) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", String.format("%02d:%02d", t.getHour(), t.getMinute()));
            m.put("kind", kind);
            m.put("pipelineId", p.getId());
            m.put("pipelineName", p.getName());
            m.put("nodeId", nodeId);
            m.put("nodeName", nodeName);
            if (description != null) m.put("description", description);
            if (group) {
                m.put("count", times.size());
                LocalDateTime lastT = times.get(times.size() - 1);
                m.put("until", String.format("%02d:%02d", lastT.getHour(), lastT.getMinute()));
            }
            m.putAll(extra);
            list.add(m);
        }
    }

    /** 등록된 타이머 수 (상태 확인용) */
    public int activeCount() {
        return entries.size();
    }

    String fmt(LocalDateTime t) {
        return t.withNano(0).toString();
    }

    private String fingerprint(PipelineNode n) {
        try {
            return om.writeValueAsString(new TreeMap<>(n.getConfig() == null ? Map.of() : n.getConfig()));
        } catch (Exception e) {
            return String.valueOf(n.getConfig());
        }
    }

    private static Instant toInstant(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant();
    }
}
