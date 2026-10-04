package com.app.workflowstudio.node.modules.schedule;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.model.RunLog;
import com.app.workflowstudio.node.*;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.app.workflowstudio.service.PipelineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-schedule-test",
        "app.open-browser=false"
})
class ScheduleModuleTest {

    /** 테스트 전용: 1.5초 걸리는 노드 (겹침 방지 확인용) */
    @TestConfiguration
    static class SlowConfig {
        @Bean
        NodeModule slowModule() {
            return new NodeModule() {
                public NodeTypeInfo info() {
                    return new NodeTypeInfo("TEST_SLOW", "slow", "", "", null, List.of());
                }
                public String execute(NodeContext ctx) throws Exception {
                    Thread.sleep(1500);
                    return "done:" + ctx.input();
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired PipelineService pipelineService;
    @Autowired PipelineRunnerService runner;
    @Autowired ScheduleService service;

    // ── 실행 규칙 해석 ──

    @Test
    void 네가지_실행_방식을_해석한다() throws Exception {
        ScheduleSpec interval = ScheduleSpec.parse(Map.of("mode", "일정 간격", "intervalValue", 2, "intervalUnit", "시간"));
        assertThat(interval.interval()).isEqualTo(Duration.ofHours(2));
        assertThat(interval.description()).isEqualTo("2시간마다");

        ScheduleSpec daily = ScheduleSpec.parse(Map.of("mode", "매일·요일 지정 시각", "days", "평일 (월~금)", "time", "9:05"));
        assertThat(daily.description()).isEqualTo("평일 09:05");
        // 2026-10-03(토) 10:00 → 다음은 10-05(월) 09:05
        LocalDateTime sat = LocalDateTime.of(2026, 10, 3, 10, 0);
        assertThat(daily.nextRuns(sat, null, 2))
                .containsExactly(LocalDateTime.of(2026, 10, 5, 9, 5), LocalDateTime.of(2026, 10, 6, 9, 5));

        ScheduleSpec cron5 = ScheduleSpec.parse(Map.of("mode", "고급 (cron)", "cron", "30 18 * * SUN"));
        assertThat(cron5.nextRuns(sat, null, 1)).containsExactly(LocalDateTime.of(2026, 10, 4, 18, 30));

        ScheduleSpec once = ScheduleSpec.parse(Map.of("mode", "한 번만", "onceAt", "2026-10-03 12:00"));
        assertThat(once.nextRuns(sat, null, 3)).containsExactly(LocalDateTime.of(2026, 10, 3, 12, 0));
        assertThat(once.nextRuns(sat.plusHours(3), null, 3)).isEmpty();       // 지난 시각은 실행 안 함
    }

    @Test
    void 간격은_등록시각_기준으로_이어진다() throws Exception {
        ScheduleSpec s = ScheduleSpec.parse(Map.of("mode", "일정 간격", "intervalValue", 15, "intervalUnit", "분"));
        LocalDateTime anchor = LocalDateTime.of(2026, 10, 3, 9, 7);
        assertThat(s.nextRuns(LocalDateTime.of(2026, 10, 3, 9, 40), anchor, 2))
                .containsExactly(LocalDateTime.of(2026, 10, 3, 9, 52), LocalDateTime.of(2026, 10, 3, 10, 7));
    }

    @Test
    void 잘못된_설정은_알기쉽게_알려준다() {
        assertThatThrownBy(() -> ScheduleSpec.parse(Map.of("mode", "일정 간격", "intervalValue", 0)))
                .hasMessageContaining("1 이상");
        assertThatThrownBy(() -> ScheduleSpec.parse(Map.of("mode", "매일·요일 지정 시각", "time", "25:00")))
                .hasMessageContaining("시각 형식");
        assertThatThrownBy(() -> ScheduleSpec.parse(Map.of("mode", "고급 (cron)", "cron", "every day")))
                .hasMessageContaining("cron 식이 올바르지 않습니다");
        assertThatThrownBy(() -> ScheduleSpec.parse(Map.of("mode", "한 번만", "onceAt", "내일 9시")))
                .hasMessageContaining("예: 2026-10-05 09:00");
    }

    // ── 등록·실행 ──

    @Test
    void 저장하면_등록되고_실행되며_겹치면_건너뛰고_끄면_해제된다() throws Exception {
        String nodeId = "timer-" + UUID.randomUUID();
        Pipeline p = pipelineService.create(pipeline(nodeId, "켜짐"));   // 저장 → 이벤트로 자동 등록

        Map<String, Object> st = service.status(p.getId(), nodeId);
        assertThat(st.get("registered")).isEqualTo(true);
        assertThat(st.get("description")).isEqualTo("1분마다");
        assertThat((List<?>) st.get("next")).hasSize(3);

        // 1회차 실행 → 전달 내용이 다음 노드 ${input} 으로
        String exec1 = service.fire(p.getId(), nodeId, "now");
        assertThat(exec1).isNotNull();
        // 1회차가 끝나기 전 2회차 → 건너뜀
        assertThat(service.fire(p.getId(), nodeId, "timer")).isNull();

        PipelineRunnerService.ExecutionState s1 = await(exec1);
        assertThat(s1.mode).isEqualTo(RunMode.SCHEDULE);
        RunLog slow = s1.logs.stream().filter(l -> l.getNodeId().equals("slow")).findFirst().orElseThrow();
        assertThat(slow.getOutput()).startsWith("done:점검 " + LocalDate.now());

        List<?> history = (List<?>) service.status(p.getId(), nodeId).get("history");
        assertThat(history).hasSize(2);
        assertThat(((Map<?, ?>) history.get(0)).get("result")).isEqualTo("skipped");
        assertThat(((Map<?, ?>) history.get(1)).get("result")).isEqualTo("fired");

        // 끄고 저장 → 해제, 타이머로는 실행 안 됨 (지금 실행 버튼은 가능)
        Pipeline cur = pipelineService.findById(p.getId());
        cur.getNodes().get(0).getConfig().put("active", "꺼짐");
        pipelineService.update(p.getId(), cur);
        assertThat(service.status(p.getId(), nodeId).get("registered")).isEqualTo(false);
        assertThat(service.fire(p.getId(), nodeId, "timer")).isNull();

        // 파이프라인 삭제 → 등록 정보 없음
        pipelineService.delete(p.getId());
        assertThat(service.status(p.getId(), nodeId).get("registered")).isEqualTo(false);
    }

    @Test
    void 상태_API는_저장전_설정으로_미리보기를_준다() throws Exception {
        mvc.perform(post("/api/schedule/new/x/status").contentType("application/json")
                        .content("{\"mode\":\"매일·요일 지정 시각\",\"days\":\"매일\",\"time\":\"07:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(false))
                .andExpect(jsonPath("$.preview.description").value("매일 07:30"))
                .andExpect(jsonPath("$.preview.next.length()").value(3));
        mvc.perform(post("/api/schedule/new/x/status").contentType("application/json")
                        .content("{\"mode\":\"일정 간격\",\"intervalValue\":0}"))
                .andExpect(jsonPath("$.preview.error").value(org.hamcrest.Matchers.containsString("1 이상")));
    }

    @Test
    void 서버_상태_API와_종료_보호() throws Exception {
        mvc.perform(get("/api/app/info"))
                .andExpect(jsonPath("$.app").value("workflow-studio"))
                .andExpect(jsonPath("$.activeTimers").isNumber());
        // 헤더 없이는 종료 거부 (다른 웹사이트가 몰래 끄지 못하도록)
        mvc.perform(post("/api/app/shutdown")).andExpect(status().isForbidden());
    }

    @Test
    void 달력에_예정과_지난_실행이_날짜별로_나온다() throws Exception {
        String daily = "cal-daily-" + UUID.randomUUID();
        Pipeline p = new Pipeline();
        p.setName("달력 테스트");
        p.setNodes(new ArrayList<>(List.of(
                node(daily, ScheduleModule.TYPE, Map.of("active", "켜짐", "mode", "매일·요일 지정 시각",
                        "days", "매일", "time", "07:30", "name", "아침")),
                node("often", ScheduleModule.TYPE, Map.of("active", "켜짐", "mode", "일정 간격",
                        "intervalValue", 5, "intervalUnit", "분")))));
        p.setConnections(new ArrayList<>());
        Pipeline saved = pipelineService.create(p);
        service.refresh();

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        var cal = service.calendar(LocalDate.now(), tomorrow.plusDays(6));
        List<Map<String, Object>> day = cal.get(tomorrow.toString()).stream()
                .filter(m -> saved.getId().equals(m.get("pipelineId"))).toList();
        assertThat(day).hasSize(2);
        // 07:30 은 한 줄, 5분 간격은 "하루 288회" 한 줄로 묶임
        assertThat(day).anySatisfy(m -> {
            assertThat(m).containsEntry("time", "07:30").containsEntry("kind", "planned").containsEntry("nodeName", "아침");
            assertThat(m).doesNotContainKey("count");
        });
        assertThat(day).anySatisfy(m -> {
            assertThat(m).containsEntry("count", 288);
            assertThat((String) m.get("time")).matches("00:0[0-9]");   // 간격은 등록 시각 기준이라 몇 분 어긋날 수 있음
            assertThat((String) m.get("until")).matches("23:5[0-9]");
        });
        long dailyDays = cal.values().stream().flatMap(List::stream)
                .filter(m -> daily.equals(m.get("nodeId"))).count();
        assertThat(dailyDays).isBetween(7L, 8L);   // 오늘 07:30이 지났으면 7일

        // 지난 실행 기록도 그날에 표시 (지금 실행 → trigger=now)
        service.fire(saved.getId(), daily, "now");
        var today = service.calendar(LocalDate.now(), LocalDate.now()).get(LocalDate.now().toString());
        assertThat(today).anySatisfy(m -> {
            assertThat(m).containsEntry("nodeId", daily).containsEntry("kind", "fired").containsEntry("trigger", "now");
            assertThat(m).containsKey("executionId");
        });
        pipelineService.delete(saved.getId());
    }

    // ── helpers ──

    private Pipeline pipeline(String timerId, String active) {
        PipelineNode timer = node(timerId, ScheduleModule.TYPE, Map.of(
                "active", active, "mode", "일정 간격", "intervalValue", 1, "intervalUnit", "분",
                "message", "점검 ${date}"));
        PipelineNode slow = node("slow", "TEST_SLOW", Map.of());
        Pipeline p = new Pipeline();
        p.setName("schedule test");
        p.setNodes(new ArrayList<>(List.of(timer, slow)));
        p.setConnections(new ArrayList<>(List.of(new PipelineConnection(timerId, "slow"))));
        return p;
    }

    private PipelineNode node(String id, String type, Map<String, Object> config) {
        PipelineNode n = new PipelineNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new HashMap<>(config));
        return n;
    }

    private PipelineRunnerService.ExecutionState await(String execId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            PipelineRunnerService.ExecutionState s = runner.getState(execId);
            if (s != null && !"running".equals(s.status)) return s;
            Thread.sleep(50);
        }
        throw new AssertionError("실행이 끝나지 않음");
    }
}
