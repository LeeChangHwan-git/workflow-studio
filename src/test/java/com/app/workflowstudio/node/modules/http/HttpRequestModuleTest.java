package com.app.workflowstudio.node.modules.http;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.model.RunLog;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.app.workflowstudio.service.PipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-http-node-test",
        "app.open-browser=false"
})
class HttpRequestModuleTest {

    static HttpServer api;
    static String base;
    /** 가짜 API가 마지막으로 받은 요청: method, X-Token 헤더, Content-Type, 바디 */
    static final AtomicReference<List<String>> last = new AtomicReference<>();

    @BeforeAll
    static void startApi() throws Exception {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/items", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            last.set(List.of(ex.getRequestMethod(),
                    String.valueOf(ex.getRequestHeaders().getFirst("X-Token")),
                    String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")), body));
            byte[] out = "{\"id\":42,\"saved\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(201, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        api.createContext("/broken", ex -> {
            byte[] out = "server exploded".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        api.start();
        base = "http://127.0.0.1:" + api.getAddress().getPort();
    }

    @AfterAll
    static void stopApi() {
        api.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired PipelineService pipelineService;
    @Autowired PipelineRunnerService runner;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 앞_노드_값으로_REST_API를_호출하고_응답을_넘긴다() throws Exception {
        String path = "http-" + UUID.randomUUID();
        pipelineService.create(pipeline(path, Map.of(
                "method", "POST",
                "url", base + "/items",
                "headers", "X-Token: ${webhook.request.body.token}\n# 주석은 무시",
                "bodyType", "JSON",
                "body", "{\"name\":\"${input}\"}",
                "failOnError", HttpRequestModule.FAIL_YES)));

        RunLog log = httpLog(fire(path, "{\"name\":\"사과\",\"token\":\"abc\"}"));
        assertThat(log.getStatus()).as(log.getMessage()).isEqualTo("success");
        JsonNode out = objectMapper.readTree(log.getOutput());
        assertThat(out.get("status").asInt()).isEqualTo(201);
        assertThat(out.get("ok").asBoolean()).isTrue();
        assertThat(out.path("body").path("id").asInt()).isEqualTo(42);   // JSON 응답은 객체로

        assertThat(last.get()).containsExactly("POST", "abc", "application/json", "{\"name\":\"사과\"}");
    }

    @Test
    void 실패_응답은_설정에_따라_중단하거나_넘긴다() throws Exception {
        String path = "http-" + UUID.randomUUID();
        Pipeline p = pipelineService.create(pipeline(path, new HashMap<>(Map.of(
                "method", "GET", "url", base + "/broken", "bodyType", "없음",
                "failOnError", HttpRequestModule.FAIL_YES))));
        RunLog failed = httpLog(fire(path, "{\"name\":\"x\"}"));
        assertThat(failed.getStatus()).isEqualTo("error");
        assertThat(failed.getMessage()).contains("HTTP 500").contains("server exploded");

        Pipeline cur = pipelineService.findById(p.getId());
        cur.getNodes().get(1).getConfig().put("failOnError", HttpRequestModule.FAIL_NO);
        pipelineService.update(p.getId(), cur);
        RunLog passed = httpLog(fire(path, "{\"name\":\"x\"}"));
        assertThat(passed.getStatus()).isEqualTo("success");
        assertThat(objectMapper.readTree(passed.getOutput()).get("status").asInt()).isEqualTo(500);
    }

    @Test
    void 잘못된_설정은_보내기_전에_알려준다() throws Exception {
        assertThatThrownBy(() -> HttpRequestModule.parseHeaders("Authorization Bearer x"))
                .hasMessageContaining("1번째 줄");

        String path = "http-" + UUID.randomUUID();
        pipelineService.create(pipeline(path, Map.of(
                "method", "POST", "url", base + "/items", "bodyType", "JSON",
                "body", "{\"name\":\"${input}\"}")));
        // 값에 따옴표가 들어가면 JSON이 깨짐 → 보내기 전에 오류
        RunLog bad = httpLog(fire(path, "{\"name\":\"a\\\"b\"}"));
        assertThat(bad.getStatus()).isEqualTo("error");
        assertThat(bad.getMessage()).contains("올바른 JSON이 아닙니다");
    }

    // ── helpers ──

    /** 웹훅(구성: ${name}) → HTTP 요청 */
    private Pipeline pipeline(String path, Map<String, Object> httpConfig) {
        PipelineNode hook = node(path, "WEBHOOK", Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST",
                "outputTemplate", "${name}", "outputVar", "webhook"));
        PipelineNode http = node("http", HttpRequestModule.TYPE, httpConfig);
        Pipeline p = new Pipeline();
        p.setName("http node test");
        p.setNodes(new ArrayList<>(List.of(hook, http)));
        p.setConnections(new ArrayList<>(List.of(new PipelineConnection(path, "http"))));
        return p;
    }

    private PipelineNode node(String id, String type, Map<String, Object> config) {
        PipelineNode n = new PipelineNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new HashMap<>(config));
        return n;
    }

    private PipelineRunnerService.ExecutionState fire(String path, String body) throws Exception {
        String res = mvc.perform(post("/webhook-test/" + path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String execId = objectMapper.readTree(res).get("executionId").asText();
        for (int i = 0; i < 100; i++) {
            PipelineRunnerService.ExecutionState s = runner.getState(execId);
            if (s != null && !"running".equals(s.status)) return s;
            Thread.sleep(50);
        }
        throw new AssertionError("실행이 끝나지 않음");
    }

    private RunLog httpLog(PipelineRunnerService.ExecutionState s) {
        return s.logs.stream().filter(l -> l.getNodeId().equals("http")).findFirst().orElseThrow();
    }
}
