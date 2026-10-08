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
    /** 여러 단계(문서 아래 문서) 테스트용 컬렉션 */
    static final String TEAM = "33333333-3333-3333-3333-333333333333";
    static final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger rateLimited = new java.util.concurrent.atomic.AtomicInteger();
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

    /** 아웃라인 문서 트리 모양 [{id, title, url, children}] — 만든 순서대로 */
    static List<Map<String, Object>> tree(String collectionId, String parentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> ordered = new ArrayList<>(docs);
        Collections.reverse(ordered);
        for (Map<String, Object> d : ordered) {
            if (!collectionId.equals(d.get("collectionId")) || !Objects.equals(parentId, d.get("parentDocumentId"))) continue;
            out.add(Map.of("id", d.get("id"), "title", d.get("title"), "url", d.get("url"),
                    "children", tree(collectionId, (String) d.get("id"))));
        }
        return out;
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
                        Map.of("id", BOARD, "name", "사내 게시판"), Map.of("id", OTHER, "name", "기타"), Map.of("id", TEAM, "name", "FW팀")));
                case "collections.documents" -> result = Map.of("data", tree((String) body.get("id"), null));
                case "documents.move" -> {
                    Map<String, Object> d = docs.stream().filter(x -> x.get("id").equals(body.get("id"))).findFirst().orElseThrow();
                    d.put("collectionId", body.get("collectionId"));
                    d.put("parentDocumentId", body.get("parentDocumentId"));
                    result = Map.of("data", Map.of("documents", List.of(d)));
                }
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
                    if ("rate-limit".equals(body.get("title")) && rateLimited.getAndIncrement() == 0) {
                        // 처음 한 번은 "요청이 너무 많음" (Retry-After 1초)
                        byte[] busy = OM.writeValueAsBytes(Map.of("ok", false, "error", "rate_limit_exceeded", "message", "Rate limit exceeded"));
                        ex.getResponseHeaders().add("Retry-After", "1");
                        ex.sendResponseHeaders(429, busy.length);
                        ex.getResponseBody().write(busy);
                        ex.close();
                        return;
                    }
                    Map<String, Object> d = doc("new" + seq.incrementAndGet(), (String) body.get("title"), (String) body.get("text"),
                            (String) body.get("collectionId"), OffsetDateTime.now(ZoneOffset.UTC).toString());
                    d.put("publish", body.get("publish"));
                    d.put("parentDocumentId", body.get("parentDocumentId"));
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
        assertThat(v.get("collections")).hasSize(3);
    }

    @Test
    void 여러_단계_위치에_등록_조회_검색_가져오기_이동() throws Exception {
        String u = UUID.randomUUID().toString().substring(0, 6);   // 테스트끼리 겹치지 않게
        String place = "FW팀/회의록-" + u + "/2026년/10월";

        // 상위 문서가 없으면 오류 + 있는 문서 안내
        RunLog missing = runLog(Map.of("action", OutlineModule.ACT_CREATE, "collection", place, "title", "회의 A", "text", "본문"), "x");
        assertThat(missing.getStatus()).isEqualTo("error");
        assertThat(missing.getMessage()).contains("'FW팀' 아래에 '회의록-" + u + "' 문서가 없습니다").contains("없는 상위 문서는 만들기");

        // 만들기: 회의록-u / 2026년 / 10월 을 차례로 만들고 그 아래 등록
        JsonNode a = run(Map.of("action", OutlineModule.ACT_CREATE, "collection", place, "title", "회의 A", "text", "본문 A",
                "createParents", OutlineModule.PARENTS_CREATE), "x");
        assertThat(a.get("action").asText()).isEqualTo("created");
        assertThat(a.get("path").asText()).isEqualTo(place + "/회의 A");
        assertThat(a.get("createdParents")).extracting(JsonNode::asText).containsExactly("회의록-" + u, "2026년", "10월");
        Map<String, Object> created = docs.stream().filter(d -> d.get("id").equals(a.get("id").asText())).findFirst().orElseThrow();
        Map<String, Object> month = docs.stream().filter(d -> d.get("id").equals(created.get("parentDocumentId"))).findFirst().orElseThrow();
        assertThat(month.get("title")).isEqualTo("10월");

        // 같은 위치에 한 번 더 — 상위 문서는 다시 안 만들고, 같은 제목 처리 옵션
        JsonNode b = run(Map.of("action", OutlineModule.ACT_CREATE, "collection", place, "title", "회의 B", "text", "본문 B 부트로더"), "x");
        assertThat(b.get("createdParents")).isEmpty();
        JsonNode skip = run(Map.of("action", OutlineModule.ACT_CREATE, "collection", place, "title", "회의 A", "text", "다른 본문",
                "ifExists", OutlineModule.EXISTS_SKIP), "x");
        assertThat(skip.get("action").asText()).isEqualTo("skipped");
        JsonNode replaced = run(Map.of("action", OutlineModule.ACT_CREATE, "collection", place, "title", "회의 A", "text", "새 본문",
                "ifExists", OutlineModule.EXISTS_REPLACE), "x");
        assertThat(replaced.get("action").asText()).isEqualTo("replaced");
        assertThat(replaced.get("id").asText()).isEqualTo(a.get("id").asText());
        assertThat(created.get("text")).isEqualTo("새 본문");

        // 조회: 하위 전부 vs 바로 아래만 (경로 포함)
        String root = "FW팀/회의록-" + u;
        JsonNode all = run(Map.of("action", OutlineModule.ACT_LIST, "collection", root), "x");
        assertThat(all.get("documents")).extracting(d -> d.get("path").asText())
                .containsExactlyInAnyOrder(root + "/2026년", root + "/2026년/10월", place + "/회의 A", place + "/회의 B");
        assertThat(all.get("titles").asText()).contains("2026년/10월/회의 A (").doesNotContain("FW팀/");   // 조회 위치 기준 경로
        JsonNode direct = run(Map.of("action", OutlineModule.ACT_LIST, "collection", root, "depth", OutlineModule.DEPTH_DIRECT), "x");
        assertThat(direct.get("documents")).extracting(d -> d.get("title").asText()).containsExactly("2026년");

        // 검색: 이 위치 아래에서만
        JsonNode found = run(Map.of("action", OutlineModule.ACT_SEARCH, "collection", root, "query", "회의"), "x");
        assertThat(found.get("documents")).extracting(d -> d.get("path").asText()).containsExactlyInAnyOrder(place + "/회의 A", place + "/회의 B");

        // 가져오기: 경로로 문서 지정
        JsonNode got = run(Map.of("action", OutlineModule.ACT_GET, "documentId", place + "/회의 B"), "x");
        assertThat(got.get("text").asText()).isEqualTo("본문 B 부트로더");

        // 이동: 다른 컬렉션의 새 위치로 (없는 상위 만들기)
        JsonNode moved = run(Map.of("action", OutlineModule.ACT_MOVE, "documentId", place + "/회의 B",
                "collection", "기타/보관-" + u, "createParents", OutlineModule.PARENTS_CREATE), "x");
        assertThat(moved.get("action").asText()).isEqualTo("moved");
        assertThat(moved.get("path").asText()).isEqualTo("기타/보관-" + u + "/회의 B");
        assertThat(moved.get("from").asText()).isEqualTo("FW팀");
        Map<String, Object> bDoc = docs.stream().filter(d -> d.get("id").equals(b.get("id").asText())).findFirst().orElseThrow();
        assertThat(bDoc.get("collectionId")).isEqualTo(OTHER);
        JsonNode after = run(Map.of("action", OutlineModule.ACT_LIST, "collection", place), "x");
        assertThat(after.get("documents")).extracting(d -> d.get("title").asText()).containsExactly("회의 A");
    }

    @Test
    void 요청이_너무_많으면_잠시_쉬었다_다시_시도한다() throws Exception {
        long t = System.currentTimeMillis();
        JsonNode out = run(Map.of("action", OutlineModule.ACT_CREATE, "collection", "기타", "title", "rate-limit", "text", "x"), "x");
        assertThat(out.get("action").asText()).isEqualTo("created");
        assertThat(rateLimited.get()).isEqualTo(2);                       // 429 한 번 → 재시도 성공
        assertThat(System.currentTimeMillis() - t).isGreaterThanOrEqualTo(900);   // Retry-After 1초 지킴
        assertThat(OutlineClient.retryAfterMillis(Map.of(), 1)).isEqualTo(6000);
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
