package com.app.workflowstudio.assistant;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.service.PipelineBackupService;
import com.app.workflowstudio.service.PipelineService;
import com.app.workflowstudio.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가짜 Gemini / 가짜 OpenAI 호환 서버가 정해진 순서로 도구 호출을 돌려주고,
 * 도우미가 실제로 파이프라인을 검증·저장·수정·삭제하는지 확인.
 */
@SpringBootTest
class PipelineAssistantTest {

    static final ObjectMapper OM = new ObjectMapper();
    static HttpServer fake;
    /** 다음에 돌려줄 응답들 (Gemini 형식 또는 OpenAI 형식) */
    static final Deque<Object> script = new ConcurrentLinkedDeque<>();
    /** 받은 요청 바디 */
    static final List<JsonNode> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", ex -> {
            received.add(OM.readTree(ex.getRequestBody().readAllBytes()));
            Object next = script.pollFirst();
            byte[] out = OM.writeValueAsBytes(next == null ? Map.of("error", Map.of("message", "스크립트 끝")) : next);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(next == null ? 500 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
    }

    @AfterAll
    static void stop() {
        fake.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.storage.path", () -> System.getProperty("java.io.tmpdir") + "/workflow-studio-assistant-test-" + UUID.randomUUID());
        r.add("app.open-browser", () -> "false");
        r.add("app.gemini.base-url", () -> "http://127.0.0.1:" + fake.getAddress().getPort());
    }

    @Autowired PipelineAssistantService assistant;
    @Autowired PipelineToolkit kit;
    @Autowired PipelineService pipelines;
    @Autowired PipelineBackupService backups;
    @Autowired SettingsService settings;

    @BeforeEach
    void reset() throws Exception {
        script.clear();
        received.clear();
        settings.update(OM.readTree("""
                {"assistant":{"provider":"gemini","geminiModel":"fake-model","geminiApiKey":"test-key","maxSteps":12}}"""));
    }

    // ── Gemini 형식 응답 만들기 ──
    static Map<String, Object> geminiCall(String name, Map<String, Object> args) {
        return Map.of("candidates", List.of(Map.of("content", Map.of("role", "model", "parts", List.of(
                Map.of("functionCall", Map.of("name", name, "args", args), "thoughtSignature", "sig-" + name))))));
    }

    static Map<String, Object> geminiText(String text) {
        return Map.of("candidates", List.of(Map.of("content", Map.of("role", "model", "parts", List.of(Map.of("text", text))))));
    }

    static String json(Object o) throws Exception {
        return OM.writeValueAsString(o);
    }

    @Test
    void 검증_오류를_돌려주면_AI가_고쳐서_실제로_저장한다() throws Exception {
        Map<String, Object> bad = Map.of("name", "질문 저장", "nodes", List.of(
                Map.of("id", "hook", "type", "WEBHOOK", "config", Map.of("path", "ask-" + UUID.randomUUID(), "testEnabled", "ON")),
                Map.of("id", "save", "type", "FILE_WRITE", "config", Map.of("extension", "md"))),
                "connections", List.of(Map.of("from", "hook", "to", "save")));
        Map<String, Object> good = new LinkedHashMap<>(bad);
        good.put("nodes", List.of(
                Map.of("id", "hook", "type", "WEBHOOK", "config", Map.of("path", "ask-" + UUID.randomUUID(), "testEnabled", "켜짐")),
                Map.of("id", "save", "type", "FILE_WRITE", "config", Map.of("extension", "md"))));
        script.add(geminiCall("create_pipeline", Map.of("pipeline_json", json(bad))));
        script.add(geminiCall("create_pipeline", Map.of("pipeline_json", json(good))));
        script.add(geminiText("만들었습니다."));

        var res = assistant.chat(null, "웹훅으로 받아서 md로 저장", null);

        assertThat(res.reply()).isEqualTo("만들었습니다.");
        assertThat(res.actions()).hasSize(1);
        assertThat(res.actions().get(0).type()).isEqualTo("created");
        assertThat(res.steps().get(0).ok()).isFalse();                    // 첫 시도는 검증 오류
        Pipeline saved = pipelines.findById(res.actions().get(0).pipelineId());
        assertThat(saved.getName()).isEqualTo("질문 저장");
        assertThat(saved.getNodes()).extracting(PipelineNode::getId).containsExactly("hook", "save");
        assertThat(saved.getNodes().get(1).getConfig()).containsEntry("extension", "md")
                .containsKey("folder");                                      // 생략한 설정은 기본값
        assertThat(saved.getNodes().get(1).getPosX()).isGreaterThan(saved.getNodes().get(0).getPosX()); // 자동 배치

        // 두 번째 요청에 오류 내용이 그대로 전달됐는지 (선택지 안내)
        String second = received.get(1).toString();
        assertThat(second).contains("선택지에 없습니다").contains("켜짐");
        // Gemini 생각 서명이 기록에 그대로 남아 다시 전달됨
        assertThat(second).contains("sig-create_pipeline");
        // 시스템 프롬프트에 노드 설명서 포함
        assertThat(received.get(0).path("systemInstruction").toString()).contains("FILE_WRITE").contains("OUTLINE");
    }

    @Test
    void 수정은_비밀값과_위치를_유지하고_백업을_남긴다() throws Exception {
        Pipeline p = new Pipeline();
        p.setName("AI 요약");
        PipelineNode agent = node("agent", "AI_AGENT", Map.of("prompt", "${input}"), 400, 100);
        PipelineNode model = node("model", "GEMINI_MODEL", Map.of("apiKey", "REAL-SECRET", "model", "gemini-flash-latest"), 300, 360);
        p.setNodes(new ArrayList<>(List.of(agent, model)));
        p.setConnections(new ArrayList<>(List.of(new PipelineConnection("model", "agent", "model"))));
        Pipeline created = pipelines.create(p);

        // AI는 비밀값을 *** 로 본다
        assertThat(OM.writeValueAsString(kit.toAi(created))).contains("***").doesNotContain("REAL-SECRET");

        Map<String, Object> updated = Map.of("name", "AI 요약 v2", "nodes", List.of(
                Map.of("id", "agent", "type", "AI_AGENT", "config", Map.of("prompt", "요약: ${input}")),
                Map.of("id", "model", "type", "GEMINI_MODEL", "config", Map.of("apiKey", "***", "model", "gemini-flash-latest")),
                Map.of("id", "out", "type", "FILE_WRITE", "config", Map.of())),
                "connections", List.of(Map.of("from", "model", "to", "agent", "socket", "model"), Map.of("from", "agent", "to", "out")));
        script.add(geminiCall("get_pipeline", Map.of("id", created.getId())));
        script.add(geminiCall("update_pipeline", Map.of("id", created.getId(), "pipeline_json", json(updated))));
        script.add(geminiText("수정했습니다."));

        var res = assistant.chat(null, "이거 결과를 파일로도 저장해줘", created.getId());

        assertThat(res.actions()).extracting(PipelineAssistantService.Action::type).containsExactly("updated");
        Pipeline now = pipelines.findById(created.getId());
        assertThat(now.getName()).isEqualTo("AI 요약 v2");
        PipelineNode m = now.getNodes().stream().filter(n -> n.getId().equals("model")).findFirst().orElseThrow();
        assertThat(m.getConfig()).containsEntry("apiKey", "REAL-SECRET");   // *** → 원래 값
        PipelineNode a = now.getNodes().stream().filter(n -> n.getId().equals("agent")).findFirst().orElseThrow();
        assertThat(a.getPosX()).isEqualTo(400);                              // 기존 노드 위치 유지
        PipelineNode out = now.getNodes().stream().filter(n -> n.getId().equals("out")).findFirst().orElseThrow();
        assertThat(out.getPosX()).isEqualTo(740);                            // 새 노드는 앞 노드 바로 오른쪽
        assertThat(out.getPosY()).isEqualTo(100);
        assertThat(backups.list(created.getId())).anyMatch(b -> b.reason().equals("AI 수정") && b.name().equals("AI 요약"));
        // 사용자가 보고 있는 파이프라인 정보가 첫 메시지에 붙음
        assertThat(received.get(0).toString()).contains("지금 보고 있는 파이프라인").contains(created.getId());
        // get_pipeline 결과에도 비밀값 노출 없음
        assertThat(received.get(1).toString()).doesNotContain("REAL-SECRET");
    }

    @Test
    void 삭제는_사용자가_확인해야_지워지고_백업에서_되살릴_수_있다() throws Exception {
        Pipeline p = new Pipeline();
        p.setName("지울 것");
        p.setNodes(new ArrayList<>(List.of(node("x", "FILE_WRITE", Map.of(), 0, 0))));
        Pipeline created = pipelines.create(p);

        script.add(geminiCall("delete_pipeline", Map.of("id", created.getId())));
        script.add(geminiText("삭제 확인을 눌러 주세요."));
        var res = assistant.chat(null, "지울 것 삭제해줘", null);

        assertThat(res.actions()).extracting(PipelineAssistantService.Action::type).containsExactly("delete_pending");
        assertThat(pipelines.findById(created.getId())).isNotNull();          // 아직 안 지워짐

        assertThatThrownBy(() -> assistant.confirmDelete("다른대화", created.getId())).isInstanceOf(IllegalArgumentException.class);
        assistant.confirmDelete(res.conversationId(), created.getId());
        assertThat(pipelines.findById(created.getId())).isNull();

        var b = backups.list(created.getId()).get(0);
        assertThat(b.reason()).isEqualTo("AI 삭제");
        pipelines.restore(created.getId(), b.file());
        assertThat(pipelines.findById(created.getId()).getName()).isEqualTo("지울 것");
    }

    @Test
    void 연결_규칙_검증() throws Exception {
        JsonNode ai = OM.readTree("""
                {"name":"규칙","nodes":[
                  {"id":"t","type":"SCHEDULE","config":{}},
                  {"id":"agent","type":"AI_AGENT","config":{}},
                  {"id":"ins","type":"AI_INSTRUCTION","config":{"text":"hi"}},
                  {"id":"x","type":"NOPE","config":{}}],
                 "connections":[{"from":"agent","to":"t"},{"from":"ins","to":"agent"},{"from":"ins","to":"agent","socket":"model"}]}""");
        var built = kit.build(ai, null);
        assertThat(String.join("\n", built.errors()))
                .contains("없는 타입 'NOPE'")
                .contains("트리거(SCHEDULE)로 들어가는 화살표")
                .contains("socket 을 지정해")
                .contains("'model' 소켓에는 ai_model 부품만")
                .contains("필수 소켓 'model'");
    }

    @Test
    void OpenAI_호환_제공자도_도구를_호출한다() throws Exception {
        settings.update(OM.readTree("{\"assistant\":{\"provider\":\"openai\",\"openaiBaseUrl\":\"http://127.0.0.1:"
                + fake.getAddress().getPort() + "/v1\",\"openaiModel\":\"qwen3:8b\"}}"));
        script.add(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "",
                "tool_calls", List.of(Map.of("id", "c1", "type", "function",
                        "function", Map.of("name", "list_pipelines", "arguments", "{}"))))))));
        script.add(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant",
                "content", "<think>생각</think>파이프라인을 확인했습니다.")))));

        var res = assistant.chat(null, "목록 보여줘", null);

        assertThat(res.reply()).isEqualTo("파이프라인을 확인했습니다.");    // <think> 제거
        assertThat(res.model()).contains("qwen3:8b");
        JsonNode second = received.get(1);
        assertThat(second.path("messages").toString()).contains("\"role\":\"tool\"").contains("\"tool_call_id\":\"c1\"");
        assertThat(received.get(0).path("tools").get(0).path("function").path("name").asText()).isEqualTo("list_pipelines");
    }

    @Test
    void 설정_비밀값은_화면에_안_보이고_null이면_유지된다() throws Exception {
        settings.update(OM.readTree("{\"credentials\":{\"geminiApiKey\":\"AIza-secret-1234\"}}"));
        Map<String, Object> view = settings.forView();
        assertThat(OM.writeValueAsString(view)).doesNotContain("AIza-secret").contains("…1234");

        settings.update(OM.readTree("{\"credentials\":{\"geminiApiKey\":null,\"outlineBaseUrl\":\"http://w\"}}"));
        assertThat(SettingsService.current().credentials.geminiApiKey).isEqualTo("AIza-secret-1234");
        assertThat(SettingsService.current().credentials.outlineBaseUrl).isEqualTo("http://w");

        settings.update(OM.readTree("{\"credentials\":{\"geminiApiKey\":\"\"}}"));
        assertThat(SettingsService.current().credentials.geminiApiKey).isEmpty();
    }

    private static PipelineNode node(String id, String type, Map<String, Object> cfg, double x, double y) {
        PipelineNode n = new PipelineNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new HashMap<>(cfg));
        n.setPosX(x);
        n.setPosY(y);
        return n;
    }
}
