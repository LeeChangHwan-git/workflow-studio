package com.app.workflowstudio.controller;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.app.workflowstudio.node.NodeTypeRegistry;
import com.app.workflowstudio.node.modules.approval.ApprovalService;
import com.app.workflowstudio.node.modules.schedule.ScheduleService;
import com.app.workflowstudio.service.ExecutionHistoryStore;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.app.workflowstudio.service.PipelineService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.*;

@Controller
public class HomeController {

    private final PipelineService pipelineService;
    private final PipelineRunnerService runner;
    private final ExecutionHistoryStore history;
    private final ScheduleService scheduleService;
    private final ApprovalService approvals;
    private final NodeTypeRegistry registry;
    private final org.thymeleaf.ITemplateEngine templates;
    private final com.app.workflowstudio.settings.SettingsService settings;

    public HomeController(PipelineService pipelineService, PipelineRunnerService runner, ExecutionHistoryStore history,
                          ScheduleService scheduleService, ApprovalService approvals, NodeTypeRegistry registry,
                          org.thymeleaf.ITemplateEngine templates, com.app.workflowstudio.settings.SettingsService settings) {
        this.templates = templates;
        this.settings = settings;
        this.pipelineService = pipelineService;
        this.runner = runner;
        this.history = history;
        this.scheduleService = scheduleService;
        this.approvals = approvals;
        this.registry = registry;
    }

    /** 🏠 홈 대시보드 */
    @GetMapping("/")
    public String home() {
        return "home/index";
    }

    /** 브라우저 탭 아이콘 — 고른 로고 그대로 */
    @GetMapping("/favicon.svg")
    public org.springframework.http.ResponseEntity<String> favicon() {
        var ctx = new org.thymeleaf.context.Context();
        var s = settings.get();
        ctx.setVariable("brandLogo", s == null || s.appearance == null ? "bench" : s.appearance.logo);
        String svg = templates.process("home/favicon", ctx).trim();
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.valueOf("image/svg+xml"))
                .cacheControl(org.springframework.http.CacheControl.noCache())
                .body(svg);
    }

    /** 🎨 로고 후보 모음 (설정에서 고르기) */
    @GetMapping("/brand")
    public String brand() {
        return "home/brand";
    }

    /**
     * 홈 대시보드 데이터 — 파이프라인 요약, 최근 60일 실행 기록(파일, 30일 비교용), 지금 실행 중, 타이머 예정, 확인 대기.
     * 통계(기간별 실행 수·실패율·평균 시간·차트)는 화면에서 계산
     */
    @GetMapping("/api/dashboard")
    @ResponseBody
    public Map<String, Object> dashboard() {
        Map<String, NodeTypeInfo> types = new HashMap<>();
        for (NodeTypeInfo t : registry.getAll()) types.put(t.id(), t);

        String since = LocalDateTime.now().minusDays(60).withNano(0).toString();
        List<ExecutionHistoryStore.Entry> runs = history.list().stream()
                .filter(e -> e.startedAt != null && e.startedAt.compareTo(since) >= 0).toList();
        Map<String, ExecutionHistoryStore.Entry> lastRun = new HashMap<>();
        for (var e : runs) lastRun.putIfAbsent(e.pipelineId, e);   // 최신순이므로 처음 것이 마지막 실행

        Set<String> timerPipelines = new HashSet<>();
        List<Map<String, Object>> timers = scheduleService.listActive();
        for (var t : timers) timerPipelines.add(String.valueOf(t.get("pipelineId")));
        Set<String> runningPipelines = new HashSet<>();
        List<Map<String, Object>> running = new ArrayList<>();
        Map<String, String> names = new HashMap<>();

        List<Map<String, Object>> pipelines = new ArrayList<>();
        for (Pipeline p : pipelineService.findAll()) names.put(p.getId(), p.getName());
        for (var s : runner.running()) {
            runningPipelines.add(s.pipelineId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("executionId", s.executionId);
            m.put("pipelineId", s.pipelineId);
            m.put("pipelineName", names.getOrDefault(s.pipelineId, "?"));
            m.put("mode", s.mode.name());
            m.put("startedAt", s.startedAt.withNano(0).toString());
            boolean waiting;
            synchronized (s.logs) { waiting = s.logs.stream().anyMatch(l -> "waiting".equals(l.getStatus())); }
            m.put("waiting", waiting);
            running.add(m);
        }

        for (Pipeline p : pipelineService.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("description", p.getDescription());
            m.put("updatedAt", p.getUpdatedAt() == null ? null : p.getUpdatedAt().withNano(0).toString());
            List<Map<String, String>> icons = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            List<String> triggers = new ArrayList<>();
            int count = 0;
            for (PipelineNode n : p.getNodes()) {
                NodeTypeInfo t = types.get(n.getType());
                if (t == null) continue;
                if ("AI 부품".equals(t.category())) continue;   // 부품은 아이콘 줄에서 생략
                count++;
                if ("트리거".equals(t.category()) && !triggers.contains(t.label())) triggers.add(t.label());
                if (seen.add(n.getType())) icons.add(Map.of("icon", t.icon(), "color", t.color(), "label", t.label()));
            }
            m.put("nodeCount", count);
            m.put("icons", icons);
            m.put("triggers", triggers);
            m.put("timerOn", timerPipelines.contains(p.getId()));
            m.put("running", runningPipelines.contains(p.getId()));
            var last = lastRun.get(p.getId());
            m.put("lastRun", last == null ? null : Map.of("status", last.status, "at", last.startedAt, "mode", last.mode));
            pipelines.add(m);
        }
        pipelines.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("updatedAt"))).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", LocalDateTime.now().withNano(0).toString());
        out.put("pipelines", pipelines);
        out.put("runs", runs);
        out.put("running", running);
        out.put("timers", timers);
        out.put("approvals", approvals.pending().size());
        return out;
    }

    /** 🍪 로그인 쿠키 넣는 법 (요청 헤더 칸의 ❓ 버튼이 별도 창으로 띄움) */
    @GetMapping("/guide/cookie")
    public String cookieGuide() {
        return "guide/cookie";
    }
}
