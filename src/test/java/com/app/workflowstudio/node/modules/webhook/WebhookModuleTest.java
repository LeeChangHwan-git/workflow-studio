package com.app.workflowstudio.node.modules.webhook;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.model.RunLog;
import com.app.workflowstudio.node.*;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.app.workflowstudio.service.PipelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-webhook-test",
        "app.open-browser=false"
})
class WebhookModuleTest {

    /** 테스트 전용 모듈: text 설정을 ${} 치환해 그대로 출력 */
    @TestConfiguration
    static class EchoConfig {
        @Bean
        NodeModule echoModule() {
            return new NodeModule() {
                public NodeTypeInfo info() {
                    return new NodeTypeInfo("TEST_ECHO", "echo", "", "", null, List.of());
                }
                public String execute(NodeContext ctx) {
                    return ctx.str("text");
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired PipelineService pipelineService;
    @Autowired PipelineRunnerService runner;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 테스트URL_수신시_연결된_노드로_값이_전달된다() throws Exception {
        String path = "hook-" + UUID.randomUUID();
        Pipeline p = pipelineService.create(pipeline(path, "켜짐", "꺼짐", "POST"));

        String res = mvc.perform(post("/webhook-test/" + path + "?src=test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"홍길동\",\"items\":[{\"id\":7}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String execId = objectMapper.readTree(res).get("executionId").asText();

        PipelineRunnerService.ExecutionState state = await(execId);
        assertThat(state.status).isEqualTo("done");
        assertThat(state.mode).isEqualTo(RunMode.TEST);

        Map<String, RunLog> logs = new HashMap<>();
        state.logs.forEach(l -> logs.put(l.getNodeId(), l));
        assertThat(logs.get("echo").getOutput()).isEqualTo("홍길동 / 7 / test / POST");
        assertThat(logs).doesNotContainKey("orphan"); // 웹훅에서 도달 불가능한 노드는 실행 안 됨

        // 에디터가 테스트 실행을 감지하는 API
        mvc.perform(get("/api/pipelines/" + p.getId() + "/executions/latest?mode=test"))
                .andExpect(status().isOk());

        // 수동 실행 시 마지막 수신 데이터 재사용
        PipelineRunnerService.ExecutionState manual = await(runner.run(p.getId()));
        assertThat(manual.logs.stream().filter(l -> l.getNodeId().equals("echo")).findFirst().orElseThrow().getOutput())
                .isEqualTo("홍길동 / 7 / test / POST");
    }

    @Test
    void 꺼진_URL과_허용되지_않은_메소드는_거부된다() throws Exception {
        String path = "hook-" + UUID.randomUUID();
        pipelineService.create(pipeline(path, "켜짐", "꺼짐", "POST"));

        mvc.perform(post("/webhook/" + path)).andExpect(status().isNotFound());        // 운영 꺼짐
        mvc.perform(get("/webhook-test/" + path)).andExpect(status().isMethodNotAllowed()); // GET 불허
        mvc.perform(post("/webhook-test/no-such-" + path)).andExpect(status().isNotFound());
    }

    @Test
    void 운영URL은_켜면_동작한다() throws Exception {
        String path = "hook-" + UUID.randomUUID();
        pipelineService.create(pipeline(path, "꺼짐", "켜짐", "모두"));

        mvc.perform(get("/webhook/" + path + "?name=a")).andExpect(status().isOk());
        mvc.perform(post("/webhook-test/" + path)).andExpect(status().isNotFound()); // 테스트 꺼짐
    }

    @Test
    void 받은_요청은_거부된_것까지_모두_수신기록에_쌓인다() throws Exception {
        String path = "hook-" + UUID.randomUUID();
        pipelineService.create(pipeline(path, "켜짐", "꺼짐", "POST"));

        mvc.perform(post("/webhook-test/" + path).contentType(MediaType.APPLICATION_JSON).content("{\"n\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/webhook/" + path)).andExpect(status().isNotFound());        // 운영 꺼짐 → 거부 기록
        mvc.perform(post("/webhook-test/" + path).contentType(MediaType.APPLICATION_JSON).content("{\"n\":2}"))
                .andExpect(status().isOk());

        String json = mvc.perform(get("/api/webhooks/" + path + "/history"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var mine = objectMapper.readTree(json); // 최신순
        assertThat(mine).hasSize(3);
        assertThat(mine.get(0).path("payload").path("body").path("n").asInt()).isEqualTo(2);
        assertThat(mine.get(1).path("accepted").asBoolean()).isFalse();
        assertThat(mine.get(1).path("httpStatus").asInt()).isEqualTo(404);
        assertThat(mine.get(2).path("executionId").isTextual()).isTrue();
    }

    @Test
    void 전달_내용을_템플릿으로_조합해서_넘긴다() throws Exception {
        String path = "hook-" + UUID.randomUUID();
        Pipeline p = pipeline(path, "켜짐", "꺼짐", "POST");
        p.getNodes().get(0).getConfig().put("outputTemplate", "${a}==${b}==${c} / ${query.src} / ${items[0].id} / ${nope}");
        p.getNodes().get(1).getConfig().put("text", "[${input}] raw=${webhook.request.body.a}");
        pipelineService.create(p);

        String res = mvc.perform(post("/webhook-test/" + path + "?src=q")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\":1,\"b\":\"엑스\",\"c\":true,\"items\":[{\"id\":7}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        PipelineRunnerService.ExecutionState state = await(objectMapper.readTree(res).get("executionId").asText());

        Map<String, RunLog> logs = new HashMap<>();
        state.logs.forEach(l -> logs.put(l.getNodeId(), l));
        String composed = "1==엑스==true / q / 7 / ${nope}";        // 없는 키는 원문 유지
        assertThat(logs.get(path).getOutput()).isEqualTo(composed);
        assertThat(logs.get("echo").getOutput()).isEqualTo("[" + composed + "] raw=1");

        // 미리보기 API: 마지막 수신 요청 기준
        String pv = mvc.perform(post("/api/webhooks/" + path + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\":\"${b}-${a}\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var preview = objectMapper.readTree(pv);
        assertThat(preview.get("hasSample").asBoolean()).isTrue();
        assertThat(preview.get("result").asText()).isEqualTo("엑스-1");
        assertThat(preview.get("keys").toString()).contains("\"a\"", "\"items[0].id\"", "\"query.src\"");
    }

    @Test
    void 받은_요청이_없으면_미리보기는_샘플없음() throws Exception {
        mvc.perform(post("/api/webhooks/never-" + UUID.randomUUID() + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"template\":\"${a}\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.hasSample").value(false));
    }

    // ── helpers ──

    private Pipeline pipeline(String path, String test, String prod, String method) {
        // 수신 기록이 노드 id 별로 파일에 쌓이므로 테스트마다 다른 id 사용 (path 와 동일하게)
        PipelineNode hook = node(path, WebhookModule.TYPE, Map.of(
                "path", path, "testEnabled", test, "prodEnabled", prod,
                "httpMethod", method, "outputVar", "webhook"));
        PipelineNode echo = node("echo", "TEST_ECHO", Map.of(
                "text", "${input.body.name} / ${webhook.body.items[0].id} / ${webhook.query.src} / ${webhook.method}"));
        PipelineNode orphan = node("orphan", "TEST_ECHO", Map.of("text", "x"));

        PipelineConnection c = new PipelineConnection();
        c.setFromNodeId(path);
        c.setToNodeId("echo");

        Pipeline p = new Pipeline();
        p.setName("webhook test");
        p.setNodes(new ArrayList<>(List.of(hook, echo, orphan)));
        p.setConnections(new ArrayList<>(List.of(c)));
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
        for (int i = 0; i < 50; i++) {
            PipelineRunnerService.ExecutionState s = runner.getState(execId);
            if (s != null && !"running".equals(s.status)) return s;
            Thread.sleep(100);
        }
        throw new AssertionError("실행이 끝나지 않음: " + execId);
    }
}
