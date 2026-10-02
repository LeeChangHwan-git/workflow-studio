package com.app.workflowstudio.node.modules.ai;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 가짜 Gemini 서버로 에이전트가 보내는 요청(키·모델·지시문·메모리)과 결과를 검증 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-ai-test",
        "app.open-browser=false"
})
class AiAgentModuleTest {

    /** 가짜 서버가 받은 요청: [경로, API 키 헤더, 바디] */
    record Captured(String path, String apiKey, JsonNode body) {}

    static final List<Captured> captured = new CopyOnWriteArrayList<>();
    static final ObjectMapper OM = new ObjectMapper();
    static HttpServer fake;

    @DynamicPropertySource
    static void fakeGemini(DynamicPropertyRegistry registry) throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", ex -> {
            byte[] reqBody = ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().getPath();
            String key = ex.getRequestHeaders().getFirst("x-goog-api-key");
            String res;
            int code = 200;
            if ("bad-key".equals(key)) {
                code = 400;
                res = "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"API key not valid.\"}}";
            } else if (path.endsWith("/models/gemini-alias-latest")) {
                res = "{\"name\":\"models/gemini-alias-latest\"}";
            } else if (path.contains("/models/") && !path.contains(":")) {
                code = 404;
                res = "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"message\":\"not found\"}}";
            } else if (path.endsWith("/models")) {
                res = "{\"models\":[{\"name\":\"models/gemini-b\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                        + "{\"name\":\"models/embed-x\",\"supportedGenerationMethods\":[\"embedContent\"]},"
                        + "{\"name\":\"models/gemini-a\",\"supportedGenerationMethods\":[\"countTokens\",\"generateContent\"]}]}";
            } else if (path.contains("bad-model")) {
                code = 404;
                res = "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"message\":\"model not found\"}}";
            } else {
                JsonNode body = OM.readTree(reqBody);
                captured.add(new Captured(path, key, body));
                JsonNode contents = body.path("contents");
                String last = contents.get(contents.size() - 1).path("parts").get(0).path("text").asText();
                res = OM.writeValueAsString(Map.of("candidates", List.of(Map.of(
                        "content", Map.of("role", "model", "parts", List.of(Map.of("text", "답:" + last)))))));
            }
            byte[] out = res.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
        registry.add("app.gemini.base-url", () -> "http://127.0.0.1:" + fake.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        if (fake != null) fake.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired PipelineService pipelineService;
    @Autowired PipelineRunnerService runner;
    @Autowired ObjectMapper objectMapper;

    @TempDir Path dir;

    @BeforeEach
    void reset() {
        captured.clear();
    }

    @Test
    void 소켓의_모델_메모리_지시문을_조합해_답한다() throws Exception {
        // 파일 지시문은 Windows 한글(MS949)로 저장 → 자동 인식되어야 함
        Path insFile = dir.resolve("규칙.md");
        Files.write(insFile, "파일 지시: ${webhook}에 답하라".getBytes(Charset.forName("MS949")));

        String path = "ai-" + UUID.randomUUID();
        Pipeline p = pipeline(path, insFile, "gemini-test");
        pipelineService.create(p);

        // 1번째 질문
        RunLog agent1 = agentLog(fire(path, "{\"user\":\"u1\",\"q\":\"안녕\"}"));
        assertThat(agent1.getStatus()).as(agent1.getMessage()).isEqualTo("success");
        assertThat(agent1.getOutput()).isEqualTo("답:안녕");

        Captured c1 = captured.get(0);
        assertThat(c1.path()).isEqualTo("/v1beta/models/gemini-test:generateContent");
        assertThat(c1.apiKey()).isEqualTo("test-key");
        assertThat(c1.body().path("systemInstruction").path("parts").get(0).path("text").asText())
                .isEqualTo("규칙1: 짧게\n\n파일 지시: 안녕에 답하라");               // 연결 순서대로 이어 붙임
        assertThat(c1.body().path("contents")).hasSize(1);
        assertThat(c1.body().path("generationConfig").path("temperature").asDouble()).isEqualTo(0.3);

        // 같은 사용자 2번째 질문 → 직전 1턴(질문+답)이 함께 전달
        fire(path, "{\"user\":\"u1\",\"q\":\"두번째\"}");
        JsonNode contents2 = captured.get(1).body().path("contents");
        assertThat(contents2).hasSize(3);
        assertThat(contents2.get(0).path("parts").get(0).path("text").asText()).isEqualTo("안녕");
        assertThat(contents2.get(1).path("role").asText()).isEqualTo("model");

        // 3번째 → 창 크기 1턴이라 여전히 3개 (가장 최근 1턴만)
        fire(path, "{\"user\":\"u1\",\"q\":\"세번째\"}");
        JsonNode contents3 = captured.get(2).body().path("contents");
        assertThat(contents3).hasSize(3);
        assertThat(contents3.get(0).path("parts").get(0).path("text").asText()).isEqualTo("두번째");

        // 다른 사용자 → 기억 없음
        fire(path, "{\"user\":\"u2\",\"q\":\"처음\"}");
        assertThat(captured.get(3).body().path("contents")).hasSize(1);

        // 메모리 API: 대화 ID별 메시지 수, 비우기
        mvc.perform(get("/api/ai/memory/mem-" + path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(delete("/api/ai/memory/mem-" + path)).andExpect(status().isNoContent());
        mvc.perform(get("/api/ai/memory/mem-" + path)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 모델_소켓이_비어있거나_API오류면_실패한다() throws Exception {
        String path = "ai-" + UUID.randomUUID();
        Pipeline p = pipeline(path, null, "gemini-test");
        p.getConnections().removeIf(c -> "model".equals(c.getToHandle()));
        pipelineService.create(p);
        RunLog log = agentLog(fire(path, "{\"user\":\"u\",\"q\":\"x\"}"));
        assertThat(log.getStatus()).isEqualTo("error");
        assertThat(log.getMessage()).contains("'모델' 소켓");

        String path2 = "ai-" + UUID.randomUUID();
        pipelineService.create(pipeline(path2, null, "bad-model"));
        RunLog log2 = agentLog(fire(path2, "{\"user\":\"u\",\"q\":\"x\"}"));
        assertThat(log2.getStatus()).isEqualTo("error");
        assertThat(log2.getMessage()).contains("HTTP 404").contains("model not found");
    }

    @Test
    void 모델_목록은_generateContent_지원_모델만() throws Exception {
        mvc.perform(post("/api/ai/gemini/models").contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"k\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models.length()").value(2))
                .andExpect(jsonPath("$.models[0]").value("gemini-a"))
                .andExpect(jsonPath("$.models[1]").value("gemini-b"));
    }

    @Test
    void 실행없이_키와_모델을_검증한다() throws Exception {
        // 정상: 목록에 있는 모델
        mvc.perform(post("/api/ai/gemini/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"k\",\"model\":\"gemini-a\"}"))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.keyOk").value(true))
                .andExpect(jsonPath("$.keySource").value("node"))
                .andExpect(jsonPath("$.models.length()").value(2));
        // 목록에는 없지만 개별 조회로 존재하는 별칭
        mvc.perform(post("/api/ai/gemini/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"k\",\"model\":\"gemini-alias-latest\"}"))
                .andExpect(jsonPath("$.ok").value(true));
        // 키는 정상, 모델 없음
        mvc.perform(post("/api/ai/gemini/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"k\",\"model\":\"no-such-model\"}"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.keyOk").value(true))
                .andExpect(jsonPath("$.modelOk").value(false));
        // 잘못된 키
        mvc.perform(post("/api/ai/gemini/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"bad-key\",\"model\":\"gemini-a\"}"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.keyOk").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("API key not valid")));
        // 실행(generateContent)은 한 번도 호출되지 않음 = 토큰 사용 없음
        assertThat(captured).isEmpty();
    }

    // ── helpers ──

    /** 웹훅(구성: ${q}) → AI 에이전트, 소켓: 모델·메모리(대화 ID=user, 1턴)·지시문(직접 입력 + 파일) */
    private Pipeline pipeline(String path, Path instructionFile, String model) {
        List<PipelineNode> nodes = new ArrayList<>(List.of(
                node(path, "WEBHOOK", Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST",
                        "outputTemplate", "${q}", "outputVar", "webhook")),
                node("agent", AiAgentModule.TYPE, Map.of("prompt", "${input}", "outputVar", "agent")),
                node("model", AiParts.GeminiModel.TYPE, Map.of("apiKey", "test-key", "model", model, "temperature", 0.3)),
                node("mem-" + path, AiParts.ChatMemory.TYPE,
                        Map.of("sessionKey", "${webhook.request.body.user}", "windowTurns", 1)),
                node("ins1", AiParts.Instruction.TYPE, Map.of("source", "직접 입력", "text", "규칙1: 짧게"))));
        List<PipelineConnection> conns = new ArrayList<>(List.of(
                new PipelineConnection(path, "agent"),
                new PipelineConnection("model", "agent", "model"),
                new PipelineConnection("mem-" + path, "agent", "memory"),
                new PipelineConnection("ins1", "agent", "instruction")));
        if (instructionFile != null) {
            nodes.add(node("ins2", AiParts.Instruction.TYPE, Map.of("source", "파일", "filePath", instructionFile.toString())));
            conns.add(new PipelineConnection("ins2", "agent", "instruction"));
        }
        Pipeline p = new Pipeline();
        p.setName("ai agent test");
        p.setNodes(nodes);
        p.setConnections(conns);
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
            if (s != null && !"running".equals(s.status)) {
                // 부품 노드는 단독 실행되지 않음
                assertThat(s.logs).noneMatch(l -> l.getNodeType().startsWith("GEMINI") || l.getNodeType().contains("MEMORY")
                        || l.getNodeType().equals(AiParts.Instruction.TYPE));
                return s;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("실행이 끝나지 않음");
    }

    private RunLog agentLog(PipelineRunnerService.ExecutionState s) {
        return s.logs.stream().filter(l -> l.getNodeId().equals("agent")).findFirst().orElseThrow();
    }
}
