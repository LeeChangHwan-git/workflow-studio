package com.app.workflowstudio.node.custom;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 내 모듈: HTTP 모듈(틀 채우기·응답 꺼내기·실패) · 파이프라인 모듈(입력 연결·중첩·오류) · 내보내기/가져오기 · 켜기/끄기 · 삭제 보호 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-custom-module-test",
        "app.open-browser=false"
})
class CustomModuleTest {

    static HttpServer api;
    static String base;
    static final List<Map<String, String>> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/doc", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> r = new HashMap<>();
            r.put("method", ex.getRequestMethod());
            r.put("query", ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            r.put("auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            r.put("type", String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")));
            r.put("body", body);
            received.add(r);
            boolean fail = body.contains("FAIL");
            byte[] out = (fail ? "{\"error\":\"boom\"}" : "{\"data\":{\"docNo\":\"KB-" + received.size() + "\"},\"items\":[{\"title\":\"첫 글\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(fail ? 500 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        api.start();
        base = "http://127.0.0.1:" + api.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        api.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired PipelineService pipelines;
    @Autowired PipelineRunnerService runner;
    @Autowired CustomModuleService modules;

    @Test
    void HTTP_모듈은_입력칸을_틀에_안전하게_넣고_응답에서_값을_꺼낸다() throws Exception {
        JsonNode m = createHttpModule("문서 등록");
        String id = m.get("id").asText();
        // 팔레트(노드 타입 목록)에 바로 나타남
        String types = mvc.perform(get("/api/node-types")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(types).contains(id, "문서 등록", "내 모듈");

        received.clear();
        RunLog log = run(node("mod", id, Map.of("제목", "따옴표 \" 와 & 공백", "내용", "${input}")), "첫 줄\n둘째 줄");
        assertThat(log.getStatus()).as(log.getMessage()).isEqualTo("success");
        Map<String, String> req = received.get(0);
        assertThat(req.get("method")).isEqualTo("POST");
        assertThat(req.get("query")).isEqualTo("q=따옴표 \" 와 & 공백");                // URL 인코딩되어 갔다가 그대로 복원
        assertThat(req.get("auth")).isEqualTo("Bearer secret-123");                  // 비밀값 기본값이 헤더로
        assertThat(req.get("type")).startsWith("application/json");
        JsonNode sent = om.readTree(req.get("body"));                                  // JSON 이 깨지지 않음
        assertThat(sent.get("title").asText()).isEqualTo("따옴표 \" 와 & 공백");
        assertThat(sent.get("content").asText()).isEqualTo("첫 줄\n둘째 줄");
        JsonNode out = om.readTree(log.getOutput());
        assertThat(out.get("ok").asBoolean()).isTrue();
        assertThat(out.get("문서번호").asText()).isEqualTo("KB-1");
        assertThat(out.get("첫제목").asText()).isEqualTo("첫 글");

        // 2xx 가 아니면 오류로 멈춤
        RunLog fail = run(node("mod", id, Map.of("제목", "x", "내용", "FAIL")), "x");
        assertThat(fail.getStatus()).isEqualTo("error");
        assertThat(fail.getMessage()).contains("HTTP 500");

        // 틀에 없는 입력칸을 쓰면 저장 때 막음
        Map<String, Object> bad = httpDef("잘못된 모듈");
        ((Map<String, Object>) bad.get("http")).put("body", "{\"x\":\"{{없는칸}}\"}");
        mvc.perform(post("/api/modules").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());

        // 시험 실행 (저장 전)
        String test = mvc.perform(post("/api/modules/test").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("module", httpDef("시험"), "values", Map.of("제목", "t", "내용", "c")))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(om.readTree(test).get("ok").asBoolean()).as(test).isTrue();
        assertThat(om.readTree(om.readTree(test).get("output").asText()).get("문서번호").asText()).startsWith("KB-");
    }

    @Test
    void 내보내면_비밀값이_빠지고_가져오기는_충돌_덮어쓰기_사본을_구분한다() throws Exception {
        Map<String, Object> def = httpDef("결재 올리기");
        ((Map<String, Object>) def.get("http")).put("headers", "Authorization: Bearer {{토큰}}\nCookie: SESSION=raw-secret\nX-App: worKBench");
        String id = om.readTree(mvc.perform(post("/api/modules").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(def)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("id").asText();

        byte[] file = mvc.perform(get("/api/modules/" + id + "/export")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode wb = om.readTree(file);
        assertThat(wb.get("format").asText()).isEqualTo("worKBench-module");
        String text = wb.toString();
        assertThat(text).doesNotContain("secret-123", "raw-secret");                  // 비밀 기본값·쿠키 값 제거
        assertThat(text).contains("Bearer {{토큰}}", "X-App: worKBench");             // 틀·일반 헤더는 유지
        assertThat(wb.get("secretsRemoved").toString()).contains("토큰", "Cookie");

        MockMultipartFile f = new MockMultipartFile("file", "결재.wbmod", "application/json", file);
        mvc.perform(multipart("/api/modules/import").file(f)).andExpect(status().isConflict());     // 같은 모듈 있음
        String over = mvc.perform(multipart("/api/modules/import").file(f).param("overwrite", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(om.readTree(over).get("replaced").asBoolean()).isTrue();
        assertThat(modules.get(id).params.stream().filter(p -> p.name.equals("토큰")).findFirst().orElseThrow().defaultValue).isEmpty();
        String copy = mvc.perform(multipart("/api/modules/import").file(f).param("copy", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode c = om.readTree(copy).get("module");
        assertThat(c.get("id").asText()).isNotEqualTo(id);
        assertThat(c.get("label").asText()).isEqualTo("결재 올리기 (사본)");

        mvc.perform(multipart("/api/modules/import").file(new MockMultipartFile("file", "x.wbmod", "text/plain", "{\"hello\":1}".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 파이프라인_모듈은_안쪽_노드에_입력을_넣고_결과_노드의_출력을_넘긴다() throws Exception {
        String httpId = createHttpModule("안쪽 문서 등록").get("id").asText();
        // 원본 파이프라인: 웹훅(트리거, 빠짐) → 안쪽 HTTP 모듈 → 분기(통과)
        Pipeline src = new Pipeline();
        src.setName("부품 — 문서 등록 후 확인");
        PipelineNode hook = node("hook", "WEBHOOK", Map.of("path", "src-" + UUID.randomUUID(), "outputVar", "webhook"));
        PipelineNode doc = node("doc", httpId, Map.of("제목", "원본 기본 제목", "내용", "${input}"));
        PipelineNode check = node("check", "BRANCH", Map.of("subject", "${input}", "rules", "[{\"id\":\"r1\",\"label\":\"번호\",\"op\":\"contains\",\"value\":\"KB-\"}]"));
        src.setNodes(new ArrayList<>(List.of(hook, doc, check)));
        src.setConnections(new ArrayList<>(List.of(new PipelineConnection("hook", "doc"), new PipelineConnection("doc", "check"))));
        String srcId = pipelines.create(src).getId();

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("kind", "pipeline");
        def.put("label", "문서 등록 묶음");
        def.put("params", List.of(Map.of("name", "제목", "label", "제목", "type", "text", "defaultValue", "기본",
                "target", Map.of("nodeId", "doc", "key", "제목"))));
        def.put("pipeline", Map.of("sourcePipelineId", srcId, "outputNodeId", "doc"));
        JsonNode m = om.readTree(mvc.perform(post("/api/modules").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(def)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(m.get("pipeline").get("nodes")).hasSize(2);                           // 트리거(웹훅)는 빠짐
        String modId = m.get("id").asText();

        received.clear();
        RunLog log = run(node("wrap", modId, Map.of("제목", "모듈 입력 ${date}")), "웹훅으로 온 글");
        assertThat(log.getStatus()).as(log.getMessage()).isEqualTo("success");
        JsonNode sent = om.readTree(received.get(0).get("body"));
        assertThat(sent.get("title").asText()).startsWith("모듈 입력 20");                // 입력칸 → 안쪽 노드 설정 (변수도 치환)
        assertThat(sent.get("content").asText()).isEqualTo("웹훅으로 온 글");             // 앞 노드 출력 → 안쪽 첫 노드 ${input}
        assertThat(om.readTree(log.getOutput()).get("문서번호").asText()).startsWith("KB-"); // 결과 노드 = doc

        // 안쪽 실패는 어느 노드에서 났는지와 함께
        RunLog fail = run(node("wrap", modId, Map.of("제목", "x")), "FAIL");
        assertThat(fail.getStatus()).isEqualTo("error");
        assertThat(fail.getMessage()).contains("모듈 안 [안쪽 문서 등록]", "HTTP 500");

        // 쓰는 곳이 있으면 지우기 막음 (force 로만), 자기 자신을 넣을 수 없음
        mvc.perform(delete("/api/modules/" + httpId)).andExpect(status().isConflict());
        String used = mvc.perform(get("/api/modules")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(used).contains("문서 등록 묶음 (모듈 안)");
    }

    @Test
    void 끈_모듈은_목록에_꺼짐으로_표시된다() throws Exception {
        mvc.perform(put("/api/modules/state").contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":[\"HTTP_REQUEST\",\"HTTP_REQUEST\"]}"))
                .andExpect(status().isOk());
        try {
            JsonNode r = om.readTree(mvc.perform(get("/api/modules")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(r.get("disabled")).hasSize(1);
            JsonNode http = null;
            for (JsonNode b : r.get("builtin")) if (b.get("id").asText().equals("HTTP_REQUEST")) http = b;
            assertThat(http).isNotNull();
            assertThat(http.get("enabled").asBoolean()).isFalse();
        } finally {
            mvc.perform(put("/api/modules/state").contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":[]}"));
        }
    }

    // ── helpers ──

    private Map<String, Object> httpDef(String label) {
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", "POST");
        http.put("url", base + "/api/doc?q={{제목}}");
        http.put("headers", "Authorization: Bearer {{토큰}}");
        http.put("bodyType", "json");
        http.put("body", "{\"title\": \"{{제목}}\", \"content\": \"{{내용}}\"}");
        http.put("failOnError", true);
        http.put("extract", List.of(Map.of("name", "문서번호", "path", "data.docNo"), Map.of("name", "첫제목", "path", "items[0].title")));
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("kind", "http");
        def.put("label", label);
        def.put("icon", "📝");
        def.put("params", List.of(
                Map.of("name", "제목", "label", "제목", "type", "text", "defaultValue", ""),
                Map.of("name", "내용", "label", "내용", "type", "textarea", "defaultValue", "${input}"),
                Map.of("name", "토큰", "label", "API 토큰", "type", "password", "defaultValue", "secret-123")));
        def.put("http", http);
        return def;
    }

    private JsonNode createHttpModule(String label) throws Exception {
        return om.readTree(mvc.perform(post("/api/modules").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(httpDef(label))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 웹훅(${text}) → 노드 하나 실행 */
    private RunLog run(PipelineNode target, String text) throws Exception {
        String path = "cm-" + UUID.randomUUID();
        PipelineNode hook = node(path, "WEBHOOK", Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST",
                "outputTemplate", "${text}", "outputVar", "webhook"));
        Pipeline p = new Pipeline();
        p.setName("custom module test");
        p.setNodes(new ArrayList<>(List.of(hook, target)));
        p.setConnections(new ArrayList<>(List.of(new PipelineConnection(path, target.getId()))));
        pipelines.create(p);
        String res = mvc.perform(post("/webhook-test/" + path).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("text", text))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String execId = om.readTree(res).get("executionId").asText();
        for (int i = 0; i < 300; i++) {
            var s = runner.getState(execId);
            if (s != null && !"running".equals(s.status))
                return s.logs.stream().filter(l -> l.getNodeId().equals(target.getId())).findFirst().orElseThrow();
            Thread.sleep(50);
        }
        throw new AssertionError("실행이 끝나지 않음");
    }

    private static PipelineNode node(String id, String type, Map<String, Object> config) {
        PipelineNode n = new PipelineNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new HashMap<>(config));
        return n;
    }
}
