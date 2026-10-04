package com.app.workflowstudio.node.modules.outline;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.model.RunLog;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.app.workflowstudio.service.PipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-outline-test",
        "app.open-browser=false"
})
class OutlineModuleTest {

    static final String KEY = "ol_api_test";
    static final String BOARD = "11111111-1111-1111-1111-111111111111";
    static final String OTHER = "22222222-2222-2222-2222-222222222222";
    static HttpServer api;
    static String base;
    static final ObjectMapper OM = new ObjectMapper();
    /** 가짜 아웃라인 문서 (최근 수정 순) */
    static final List<Map<String, Object>> docs = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startFakeOutline() throws Exception {
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30).toString();
        docs.add(doc("d1", "[FW] 부트로더 v2.3 배포", "OTA 서명 검증 추가", BOARD, now));
        docs.add(doc("d2", "[인사] 교육 신청", "10/10까지", BOARD, now));
        docs.add(doc("d3", "[FW] 오래된 글", "예전 내용", BOARD, old));
        docs.add(doc("d4", "다른 컬렉션 글", "x", OTHER, now));

        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/", OutlineModuleTest::handle);
        api.start();
        base = "http://127.0.0.1:" + api.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        api.stop(0);
    }

    static Map<String, Object> doc(String id, String title, String text, String col, String updatedAt) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("title", title);
        d.put("text", text);
        d.put("collectionId", col);
        d.put("url", "/doc/slug-" + id);
        d.put("updatedAt", updatedAt);
        d.put("createdBy", Map.of("name", "홍길동"));
        return d;
    }

    @SuppressWarnings("unchecked")
    static void handle(HttpExchange ex) throws java.io.IOException {
        String method = ex.getRequestURI().getPath().substring("/api/".length());
        Map<String, Object> body = OM.readValue(ex.getRequestBody().readAllBytes(), Map.class);
        Object result;
        int status = 200;
        if (!("Bearer " + KEY).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            status = 401;
            result = Map.of("ok", false, "error", "authentication_required", "message", "Authentication required");
        } else {
            int offset = ((Number) body.getOrDefault("offset", 0)).intValue();
            int limit = ((Number) body.getOrDefault("limit", 25)).intValue();
            switch (method) {
                case "auth.info" -> result = Map.of("data", Map.of("user", Map.of("name", "테스터"), "team", Map.of("name", "회사")));
                case "collections.list" -> result = Map.of("data", offset > 0 ? List.of() : List.of(
                        Map.of("id", BOARD, "name", "사내 게시판"), Map.of("id", OTHER, "name", "기타")));
                case "documents.list" -> {
                    List<Map<String, Object>> list = docs.stream()
                            .filter(d -> body.get("collectionId") == null || body.get("collectionId").equals(d.get("collectionId")))
                            .toList();
                    result = Map.of("data", list.subList(Math.min(offset, list.size()), Math.min(offset + limit, list.size())));
                }
                case "documents.search" -> {
                    String q = (String) body.get("query");
                    List<Map<String, Object>> hits = docs.stream()
                            .filter(d -> ((String) d.get("title")).contains(q))
                            .map(d -> Map.<String, Object>of("context", "<b>" + q + "</b> 관련", "document", d))
                            .toList();
                    result = Map.of("data", offset > 0 ? List.of() : hits);
                }
                case "documents.info" -> {
                    Optional<Map<String, Object>> d = docs.stream().filter(x -> x.get("id").equals(body.get("id"))).findFirst();
                    if (d.isEmpty()) { status = 404; result = Map.of("ok", false, "error", "not_found", "message", "Resource not found"); }
                    else result = Map.of("data", d.get());
                }
                case "documents.create" -> {
                    Map<String, Object> d = doc("new" + docs.size(), (String) body.get("title"), (String) body.get("text"),
                            (String) body.get("collectionId"), OffsetDateTime.now(ZoneOffset.UTC).toString());
                    d.put("publish", body.get("publish"));
                    docs.add(0, d);
                    result = Map.of("data", d);
                }
                case "documents.update" -> {
                    Map<String, Object> d = docs.stream().filter(x -> x.get("id").equals(body.get("id"))).findFirst().orElseThrow();
                    if (body.containsKey("title")) d.put("title", body.get("title"));
                    d.put("text", body.get("text"));
                    result = Map.of("data", d);
                }
                default -> { status = 404; result = Map.of("ok", false, "message", "unknown " + method); }
            }
        }
        byte[] out = OM.writeValueAsBytes(result);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @Autowired MockMvc mvc;
    @Autowired PipelineService pipelineService;
    @Autowired PipelineRunnerService runner;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 조회는_컬렉션_이름으로_찾고_제목_목록을_만든다() throws Exception {
        JsonNode out = run(Map.of("action", OutlineModule.ACT_LIST, "collection", "사내 게시판",
                "include", OutlineModule.INCLUDE_TITLE, "limit", 10), "x");
        assertThat(out.get("count").asInt()).isEqualTo(3);
        assertThat(out.get("titles").asText()).startsWith("1. [FW] 부트로더 v2.3 배포 (").contains("3. [FW] 오래된 글");
        JsonNode first = out.get("documents").get(0);
        assertThat(first.get("url").asText()).isEqualTo(base + "/doc/slug-d1");
        assertThat(first.get("collection").asText()).isEqualTo("사내 게시판");
        assertThat(first.get("author").asText()).isEqualTo("홍길동");
        assertThat(first.has("text")).isFalse();                       // 제목만

        // 최근 7일 + 내용 포함
        JsonNode recent = run(Map.of("action", OutlineModule.ACT_LIST, "collection", "사내 게시판",
                "include", OutlineModule.INCLUDE_TEXT, "recentDays", 7), "x");
        assertThat(recent.get("count").asInt()).isEqualTo(2);
        assertThat(recent.get("documents").get(0).get("text").asText()).isEqualTo("OTA 서명 검증 추가");
    }

    @Test
    void 검색은_앞_노드_값으로_찾고_강조_태그를_뺀다() throws Exception {
        JsonNode out = run(Map.of("action", OutlineModule.ACT_SEARCH, "query", "${input}"), "[FW]");
        assertThat(out.get("count").asInt()).isEqualTo(2);
        assertThat(out.get("documents").get(0).get("context").asText()).isEqualTo("[FW] 관련");
    }

    @Test
    void 등록은_첫_제목줄을_제목으로_쓰고_코드블록을_벗긴다() throws Exception {
        String ai = "```markdown\n# 주간 정리\n\n- 항목 1\n- 항목 2\n```";
        JsonNode out = run(Map.of("action", OutlineModule.ACT_CREATE, "collection", "기타",
                "text", "${input}", "publish", OutlineModule.PUBLISH_DRAFT), ai);
        assertThat(out.get("action").asText()).isEqualTo("created");
        assertThat(out.get("title").asText()).isEqualTo("주간 정리");
        Map<String, Object> saved = docs.get(0);
        assertThat(saved.get("text")).isEqualTo("- 항목 1\n- 항목 2");
        assertThat(saved.get("collectionId")).isEqualTo(OTHER);
        assertThat(saved.get("publish")).isEqualTo(false);
    }

    @Test
    void 수정은_주소로_문서를_찾아_끝에_덧붙인다() throws Exception {
        docs.add(doc("u1", "회의록", "첫 줄", OTHER, OffsetDateTime.now(ZoneOffset.UTC).toString()));
        JsonNode out = run(Map.of("action", OutlineModule.ACT_UPDATE, "documentId", "https://wiki.x/doc/meeting-u1",
                "updateMode", OutlineModule.UPDATE_APPEND, "text", "${input}"), "추가 내용");
        assertThat(out.get("action").asText()).isEqualTo("updated");
        Map<String, Object> d = docs.stream().filter(x -> x.get("id").equals("u1")).findFirst().orElseThrow();
        assertThat(d.get("text")).isEqualTo("첫 줄\n\n추가 내용");
        assertThat(d.get("title")).isEqualTo("회의록");                  // 제목 비우면 그대로
    }

    @Test
    void 잘못된_키_없는_컬렉션은_이유를_알려준다() throws Exception {
        RunLog bad = runLog(Map.of("action", OutlineModule.ACT_LIST, "apiKey", "wrong"), "x");
        assertThat(bad.getStatus()).isEqualTo("error");
        assertThat(bad.getMessage()).contains("HTTP 401").contains("API 키가 올바르지 않거나");

        RunLog noCol = runLog(Map.of("action", OutlineModule.ACT_LIST, "collection", "없는곳"), "x");
        assertThat(noCol.getMessage()).contains("'없는곳'").contains("사내 게시판, 기타");

        // 연결 확인 API
        String res = mvc.perform(post("/api/outline/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("baseUrl", base + "/", "apiKey", KEY))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode v = objectMapper.readTree(res);
        assertThat(v.get("ok").asBoolean()).isTrue();
        assertThat(v.get("message").asText()).contains("회사").contains("테스터");
        assertThat(v.get("collections")).hasSize(2);
    }

    @Test
    void 제목_분리와_문서_주소_해석() throws Exception {
        assertThat(OutlineModule.splitTitle("# 제목\n본문").title()).isEqualTo("제목");
        assertThat(OutlineModule.splitTitle("# 제목\n본문").body()).isEqualTo("본문");
        assertThat(OutlineModule.splitTitle("**굵은 첫 줄**\n본문").title()).isEqualTo("굵은 첫 줄");
        assertThat(OutlineModule.splitTitle("소개\n# 나중 제목").title()).isEqualTo("소개");  // 첫 줄이 # 이 아니면 첫 줄
        assertThat(OutlineClient.documentRef("https://wiki/doc/주간-회의록-AbCdEf1234?x=1")).isEqualTo("AbCdEf1234");
        assertThat(OutlineClient.documentRef(" 8925abc7-b608-4caa-8bf1-089e59daf702 "))
                .isEqualTo("8925abc7-b608-4caa-8bf1-089e59daf702");
        assertThatThrownBy(() -> OutlineClient.documentRef("${outline.id}")).hasMessageContaining("해석되지 않은");
    }

    // ── helpers ──

    private JsonNode run(Map<String, Object> config, String input) throws Exception {
        RunLog log = runLog(config, input);
        assertThat(log.getStatus()).as(log.getMessage()).isEqualTo("success");
        return objectMapper.readTree(log.getOutput());
    }

    /** 웹훅(구성: ${text}) → 아웃라인 */
    private RunLog runLog(Map<String, Object> config, String input) throws Exception {
        String path = "outline-" + UUID.randomUUID();
        Map<String, Object> cfg = new HashMap<>(Map.of("baseUrl", base, "apiKey", KEY));
        cfg.putAll(config);
        PipelineNode hook = node(path, "WEBHOOK", Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST",
                "outputTemplate", "${text}", "outputVar", "webhook"));
        PipelineNode outline = node("outline", OutlineModule.TYPE, cfg);
        Pipeline p = new Pipeline();
        p.setName("outline test");
        p.setNodes(new ArrayList<>(List.of(hook, outline)));
        p.setConnections(new ArrayList<>(List.of(new PipelineConnection(path, "outline"))));
        pipelineService.create(p);

        String res = mvc.perform(post("/webhook-test/" + path).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", input))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String execId = objectMapper.readTree(res).get("executionId").asText();
        for (int i = 0; i < 100; i++) {
            PipelineRunnerService.ExecutionState s = runner.getState(execId);
            if (s != null && !"running".equals(s.status))
                return s.logs.stream().filter(l -> l.getNodeId().equals("outline")).findFirst().orElseThrow();
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
