package com.app.workflowstudio.node.modules.messenger;

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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 HTTP 로 시연용 메신저(이 앱)에 보내 보고, 회사 메신저 형식(직접 구성)은 가짜 서버로 확인 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-messenger-test",
        "app.open-browser=false"
})
class MessengerModuleTest {

    static HttpServer company;
    static String companyUrl;
    static final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        company = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        company.createContext("/msg/send", ex -> {
            received.add(ex.getRequestHeaders().getFirst("Authorization") + " | "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean bad = received.get(received.size() - 1).contains("99999999");
            byte[] out = (bad ? "{\"result\":\"NO_USER\"}" : "{\"result\":\"OK\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(bad ? 404 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        company.start();
        companyUrl = "http://127.0.0.1:" + company.getAddress().getPort() + "/msg/send";
    }

    @AfterAll
    static void stop() {
        company.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired PipelineService pipelines;
    @Autowired PipelineRunnerService runner;
    @Autowired ObjectMapper om;

    @Test
    void 주소를_비우면_시연용_메신저로_여러_명에게_보낸다() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/demo-messenger/api/notes"));
        RunLog log = run(Map.of("empIds", "20231234, 99990000 ,20231234", "message", "${input}", "sender", "빌드봇"),
                "빌드 실패: \"main\" 브랜치\n확인 부탁드립니다");
        assertThat(log.getStatus()).as(log.getMessage()).isEqualTo("success");
        JsonNode out = om.readTree(log.getOutput());
        assertThat(out.get("sent").asInt()).isEqualTo(2);                    // 중복 사번은 한 번만
        assertThat(out.get("demo").asBoolean()).isTrue();
        assertThat(out.get("results").get(0).get("response").get("to").asText()).isEqualTo("홍길동 (FW팀) · 20231234");

        JsonNode inbox = om.readTree(mvc.perform(get("/demo-messenger/api/notes")).andReturn().getResponse().getContentAsString());
        assertThat(inbox.get("notes")).hasSize(2);
        JsonNode latest = inbox.get("notes").get(0);
        assertThat(latest.get("to").asText()).isEqualTo("사번 99990000");      // 목록에 없는 사번도 받음
        assertThat(latest.get("known").asBoolean()).isFalse();
        assertThat(latest.get("message").asText()).isEqualTo("빌드 실패: \"main\" 브랜치\n확인 부탁드립니다");
        assertThat(latest.get("sender").asText()).isEqualTo("빌드봇");
    }

    @Test
    void 회사_메신저_형식으로_JSON_안전하게_보내고_실패를_구분한다() throws Exception {
        received.clear();
        Map<String, Object> cfg = new HashMap<>(Map.of("empIds", "20231234,99999999", "message", "따옴표 \" 와 줄바꿈\n포함",
                "endpoint", companyUrl, "bodyType", MessengerModule.BODY_CUSTOM,
                "bodyTemplate", "{\"to\": \"${empId}\", \"text\": \"[알림] ${message}\", \"bot\": \"${sender}\"}",
                "headers", "Authorization: Bearer test-token"));
        RunLog stop = run(cfg, "x");
        assertThat(stop.getStatus()).isEqualTo("error");
        assertThat(stop.getMessage()).contains("사번 99999999 에게 보내기 실패 — HTTP 404");

        JsonNode first = om.readTree(received.get(0).substring(received.get(0).indexOf("| ") + 2));
        assertThat(received.get(0)).startsWith("Bearer test-token");
        assertThat(first.get("to").asText()).isEqualTo("20231234");
        assertThat(first.get("text").asText()).isEqualTo("[알림] 따옴표 \" 와 줄바꿈\n포함");
        assertThat(first.get("bot").asText()).isEqualTo("Workflow Studio");

        cfg.put("onFail", MessengerModule.FAIL_CONTINUE);
        JsonNode out = om.readTree(run(cfg, "x").getOutput());
        assertThat(out.get("sent").asInt()).isEqualTo(1);
        assertThat(out.get("failed").asInt()).isEqualTo(1);
        assertThat(out.get("results").get(1).get("response").get("result").asText()).isEqualTo("NO_USER");
    }

    @Test
    void 사번이나_메시지가_없으면_알려준다() throws Exception {
        assertThat(run(Map.of("empIds", " , ", "message", "x"), "x").getMessage()).contains("받는 사람 사번을 입력하세요");
        assertThat(run(Map.of("empIds", "20231234", "message", "  "), "x").getMessage()).contains("메시지가 비어 있습니다");
    }

    @Test
    void 나에게_온_쪽지만_쪽지_창_대상이고_나의_사번을_바꿀_수_있다() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/demo-messenger/api/notes"));
        JsonNode out = om.readTree(run(Map.of("empIds", "20240001, 20231234", "message", "${input}"), "회의록 올렸습니다").getOutput());
        JsonNode toMe = out.get("results").get(0).get("response");
        JsonNode toOther = out.get("results").get(1).get("response");
        assertThat(toMe.get("toMe").asBoolean()).isTrue();
        assertThat(toMe.get("to").asText()).isEqualTo("나 · 20240001");
        assertThat(toOther.get("toMe").asBoolean()).isFalse();
        assertThat(toOther.get("shown").asBoolean()).isFalse();             // 다른 사람 쪽지는 이 PC 에 창을 띄우지 않음

        // 나의 사번을 홍길동으로 바꾸면 홍길동에게 온 쪽지가 "나"
        mvc.perform(post("/demo-messenger/api/me").contentType(MediaType.APPLICATION_JSON).content("{\"empId\":\"20231234\"}"))
                .andExpect(status().isOk());
        try {
            JsonNode again = om.readTree(run(Map.of("empIds", "20231234", "message", "x"), "x").getOutput());
            assertThat(again.get("results").get(0).get("response").get("toMe").asBoolean()).isTrue();
            assertThat(again.get("results").get(0).get("response").get("to").asText()).isEqualTo("나 · 홍길동 (FW팀) · 20231234");
            JsonNode inbox = om.readTree(mvc.perform(get("/demo-messenger/api/notes")).andReturn().getResponse().getContentAsString());
            assertThat(inbox.get("myId").asText()).isEqualTo("20231234");
        } finally {
            mvc.perform(post("/demo-messenger/api/me").contentType(MediaType.APPLICATION_JSON).content("{\"empId\":\"20240001\"}"));
        }
        mvc.perform(post("/demo-messenger/api/me").contentType(MediaType.APPLICATION_JSON).content("{\"empId\":\"a b\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 메신저_그룹을_골뱅이로_쓰면_그룹_사번으로_보낸다() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messenger\":{\"groups\":[{\"name\":\"@ FW 팀\",\"members\":\"20240001,20231234 20231234\",\"description\":\"d\"},"
                                + "{\"name\":\"리더\",\"members\":\"20190012\"},{\"name\":\"fw_팀\",\"members\":\"1\"},{\"name\":\"빈\",\"members\":\"\"}]}}"))
                .andExpect(status().isOk());
        JsonNode groups = om.readTree(mvc.perform(get("/api/messenger/groups")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(groups.get("groups")).hasSize(3);                                   // 이름 정리 후 중복(fw_팀) 제거
        assertThat(groups.get("groups").get(0).get("name").asText()).isEqualTo("FW_팀");   // @·공백 정리
        assertThat(groups.get("groups").get(0).get("members")).hasSize(2);
        assertThat(groups.get("groups").get(0).get("members").get(0).get("name").asText()).isEqualTo("나");

        JsonNode out = om.readTree(run(Map.of("empIds", "@fw_팀, @리더, 20231234", "message", "x"), "x").getOutput());
        assertThat(out.get("sent").asInt()).isEqualTo(3);                              // 20240001, 20231234, 20190012
        assertThat(out.get("groups").toString()).contains("@FW_팀", "@리더");

        assertThat(run(Map.of("empIds", "@없는그룹", "message", "x"), "x").getMessage())
                .contains("메신저 그룹이 없습니다: @없는그룹", "@FW_팀");
        assertThat(run(Map.of("empIds", "@빈", "message", "x"), "x").getMessage()).contains("사번이 없습니다");
    }

    @Test
    void 웹훅으로_온_한글이_어떤_인코딩이어도_깨지지_않는다() throws Exception {
        String text = "주문 1번 문의 — 반품·교환";
        byte[] json = ("{\"text\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] cp949 = ("{\"text\":\"" + text.replace("—", "-").replace("·", ",") + "\"}").getBytes(java.nio.charset.Charset.forName("MS949"));
        // UTF-8 (charset 없음) · UTF-8 (charset 명시) · CP949 (charset 없음 — PowerShell 5·cmd curl) · EUC-KR 명시
        assertThat(sendRaw(json, "application/json")).isEqualTo(text);
        assertThat(sendRaw(json, "application/json; charset=UTF-8")).isEqualTo(text);
        assertThat(sendRaw(cp949, "application/json")).isEqualTo("주문 1번 문의 - 반품,교환");
        assertThat(sendRaw(cp949, "application/json; charset=EUC-KR")).isEqualTo("주문 1번 문의 - 반품,교환");
        assertThat(com.app.workflowstudio.node.modules.webhook.WebhookControllerAccess.decode(null, "application/json")).isNull();
    }

    /** 바이트 그대로 웹훅에 보내고 쪽지로 받은 메시지 */
    private String sendRaw(byte[] body, String contentType) throws Exception {
        String path = "raw-" + UUID.randomUUID();
        PipelineNode hook = node(path, "WEBHOOK", Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST",
                "outputTemplate", "${text}", "outputVar", "webhook"));
        PipelineNode msg = node("msg", MessengerModule.TYPE, Map.of("empIds", "20240001", "message", "${input}"));
        Pipeline p = new Pipeline();
        p.setName("raw test");
        p.setNodes(new ArrayList<>(List.of(hook, msg)));
        p.setConnections(new ArrayList<>(List.of(new PipelineConnection(path, "msg"))));
        pipelines.create(p);
        String res = mvc.perform(post("/webhook-test/" + path).header("Content-Type", contentType).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String execId = om.readTree(res).get("executionId").asText();
        for (int i = 0; i < 200; i++) {
            var s = runner.getState(execId);
            if (s != null && !"running".equals(s.status)) {
                RunLog log = s.logs.stream().filter(l -> l.getNodeId().equals("msg")).findFirst().orElseThrow();
                assertThat(log.getStatus()).as(log.getMessage()).isEqualTo("success");
                JsonNode inbox = om.readTree(mvc.perform(get("/demo-messenger/api/notes")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
                return inbox.get("notes").get(0).get("message").asText();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("실행이 끝나지 않음");
    }

    // ── helpers ──

    /** 웹훅(구성: ${text}) → 메신저 */
    private RunLog run(Map<String, Object> config, String input) throws Exception {
        String path = "msg-" + UUID.randomUUID();
        PipelineNode hook = node(path, "WEBHOOK", Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST",
                "outputTemplate", "${text}", "outputVar", "webhook"));
        PipelineNode msg = node("msg", MessengerModule.TYPE, config);
        Pipeline p = new Pipeline();
        p.setName("messenger test");
        p.setNodes(new ArrayList<>(List.of(hook, msg)));
        p.setConnections(new ArrayList<>(List.of(new PipelineConnection(path, "msg"))));
        pipelines.create(p);
        String res = mvc.perform(post("/webhook-test/" + path).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("text", input))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String execId = om.readTree(res).get("executionId").asText();
        for (int i = 0; i < 200; i++) {
            var s = runner.getState(execId);
            if (s != null && !"running".equals(s.status))
                return s.logs.stream().filter(l -> l.getNodeId().equals("msg")).findFirst().orElseThrow();
            Thread.sleep(50);
        }
        throw new AssertionError("실행이 끝나지 않음");
    }

    private PipelineNode node(String id, String type, Map<String, Object> config) {
        PipelineNode n = new PipelineNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new HashMap<>(config));
        return n;
    }
}
