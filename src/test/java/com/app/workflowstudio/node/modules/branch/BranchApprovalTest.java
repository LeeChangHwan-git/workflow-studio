package com.app.workflowstudio.node.modules.branch;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.model.RunLog;
import com.app.workflowstudio.node.modules.approval.ApprovalModule;
import com.app.workflowstudio.node.modules.approval.ApprovalService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 분기 실행(고른 출력만 흐름, 나머지 건너뜀) + 확인 요청(사람 결정 기다리기, AI 수정 반복) */
@SpringBootTest
@AutoConfigureMockMvc
class BranchApprovalTest {

    static HttpServer fakeGemini;
    static final List<String> prompts = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        fakeGemini = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeGemini.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            prompts.add(body);
            String text = "# 수정본 " + prompts.size() + "\n- 의견 반영됨";
            byte[] out = new ObjectMapper().writeValueAsBytes(Map.of("candidates", List.of(Map.of("content",
                    Map.of("role", "model", "parts", List.of(Map.of("text", text)))))));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fakeGemini.start();
    }

    @AfterAll
    static void stop() {
        fakeGemini.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.storage.path", () -> System.getProperty("java.io.tmpdir") + "/workflow-studio-branch-test-" + UUID.randomUUID());
        r.add("app.open-browser", () -> "false");
        r.add("app.gemini.base-url", () -> "http://127.0.0.1:" + fakeGemini.getAddress().getPort());
    }

    @Autowired MockMvc mvc;
    @Autowired PipelineService pipelines;
    @Autowired PipelineRunnerService runner;
    @Autowired ApprovalService approvals;
    @Autowired ObjectMapper om;

    static final String RULES = "[{\"id\":\"one\",\"label\":\"1 포함\",\"op\":\"contains\",\"value\":\"1\"},"
            + "{\"id\":\"two\",\"label\":\"2 포함\",\"op\":\"contains\",\"value\":\"2\"}]";

    // ── 분기 ──

    @Test
    void 분기는_맞는_쪽으로만_흐르고_나머지는_건너뛴다() throws Exception {
        Map<String, RunLog> a = runBranch("주문번호 1번", Map.of());
        assertThat(a.get("b").getMessage()).contains("→ 1 포함");
        assertThat(a.get("d1").getStatus()).isEqualTo("success");
        assertThat(a.get("d1").getOutput()).isEqualTo("주문번호 1번");          // 값은 그대로 다음 노드로
        assertThat(a.get("d2").getStatus()).isEqualTo("skipped");
        assertThat(a.get("dElse").getStatus()).isEqualTo("skipped");
        assertThat(a.get("after").getStatus()).isEqualTo("success");           // 합쳐지는 노드는 한쪽만 와도 실행

        Map<String, RunLog> none = runBranch("아무것도 없음", Map.of());
        assertThat(none.get("dElse").getStatus()).isEqualTo("success");
        assertThat(none.get("d1").getStatus()).isEqualTo("skipped");

        Map<String, RunLog> both = runBranch("1과 2 둘 다", Map.of("mode", BranchModule.MODE_ALL));
        assertThat(both.get("d1").getStatus()).isEqualTo("success");
        assertThat(both.get("d2").getStatus()).isEqualTo("success");
        assertThat(both.get("dElse").getStatus()).isEqualTo("skipped");
        Map<String, RunLog> first = runBranch("1과 2 둘 다", Map.of());
        assertThat(first.get("d2").getStatus()).isEqualTo("skipped");          // 기본: 처음 맞는 하나만
    }

    @Test
    void 연산자와_식() throws Exception {
        assertThat(m("contains", "fw", "[FW] 배포", true)).isTrue();
        assertThat(m("contains", "fw", "[FW] 배포", false)).isFalse();         // 대소문자 구분
        assertThat(m("anyOf", "긴급, 장애", "서버 장애 발생", true)).isTrue();
        assertThat(m("regex", "^\\d{8}$", "20231234", true)).isTrue();
        assertThat(m("gte", "80", " 85점 ", true)).isTrue();
        assertThat(m("lt", "80", "1,234", true)).isFalse();
        assertThat(m("gt", "3", "숫자 아님", true)).isFalse();                    // 숫자가 아니면 맞지 않음
        assertThat(m("empty", "", "   ", true)).isTrue();
        assertThat(m("lenGt", "5", "가나다라마바", true)).isTrue();
        assertThat(m("expr", "#value.contains('FW') and #len > 3", "[FW] 배포", true)).isTrue();
        assertThat(m("expr", "#json['score'] >= 80 and #json['type'] == 'FW'", "{\"score\": 91, \"type\": \"FW\"}", true)).isTrue();
        assertThat(m("expr", "#num != null and #num > 10", "12", true)).isTrue();
        // 안전 모드: 클래스 접근·객체 생성 불가
        assertThatThrownBy(() -> m("expr", "T(java.lang.Runtime).getRuntime().exec('calc')", "x", true)).hasMessageContaining("식 오류");
        assertThatThrownBy(() -> m("expr", "new java.io.File('C:/').delete()", "x", true)).hasMessageContaining("식 오류");
        assertThatThrownBy(() -> m("expr", "#len", "x", true)).hasMessageContaining("참/거짓");
        assertThatThrownBy(() -> m("gt", "abc", "1", true)).hasMessageContaining("비교할 숫자");

        // 설정 화면 시험 API
        JsonNode t = om.readTree(mvc.perform(post("/api/branch/test").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("sample", "2번 문의", "rules", RULES))))
                .andReturn().getResponse().getContentAsString());
        assertThat(t.get("picked")).extracting(JsonNode::asText).containsExactly("two");
    }

    private static boolean m(String op, String value, String subject, boolean ignoreCase) throws Exception {
        return BranchRules.matches(new BranchRules.Rule("r", op, op, value, ""), subject, ignoreCase);
    }

    // ── 확인 요청 ──

    @Test
    void 확인_요청_승인_고쳐서_승인_거절() throws Exception {
        // 그대로 승인
        String e1 = startApproval("회의록 요약", false);
        var p1 = waitPending(e1);
        assertThat(p1.get("content")).isEqualTo("회의록 요약");
        assertThat(log(e1, "ok").getStatus()).isNull();                        // 아직 실행 전
        assertThat(log(e1, "ap").getStatus()).isEqualTo("waiting");
        approvals.decide((String) p1.get("id"), ApprovalService.APPROVE, "", null);
        Map<String, RunLog> r1 = finish(e1);
        assertThat(r1.get("ok").getStatus()).isEqualTo("success");
        assertThat(r1.get("again").getStatus()).isEqualTo("skipped");
        assertThat(r1.get("no").getStatus()).isEqualTo("skipped");

        // 고쳐서 승인 → 고친 내용이 다음 노드로
        String e2 = startApproval("초안", false);
        approvals.decide((String) waitPending(e2).get("id"), ApprovalService.APPROVE, "", "# 최종본\n- 사람이 고침");
        assertThat(finish(e2).get("ok").getOutput()).isEqualTo("# 최종본\n- 사람이 고침");

        // 거절
        String e3 = startApproval("초안", false);
        approvals.decide((String) waitPending(e3).get("id"), ApprovalService.REJECT, "필요 없음", null);
        Map<String, RunLog> r3 = finish(e3);
        assertThat(r3.get("no").getStatus()).isEqualTo("success");
        assertThat(r3.get("ok").getStatus()).isEqualTo("skipped");
        assertThat(approvals.recent().get(0).get("decision")).isEqualTo("reject");
    }

    @Test
    void 다시는_AI가_없으면_다시_출력으로_있으면_노드_안에서_고쳐_다시_묻는다() throws Exception {
        // AI 없음 → 다시 출력, 의견은 ${approval.feedback}
        String e1 = startApproval("초안", false);
        approvals.decide((String) waitPending(e1).get("id"), ApprovalService.RETRY, "결정 사항을 표로", null);
        Map<String, RunLog> r1 = finish(e1);
        assertThat(r1.get("again").getStatus()).isEqualTo("success");
        assertThat(r1.get("again").getMessage()).contains("→ 의견 받음");          // ${approval.feedback} 에 의견이 들어 있음
        assertThat(r1.get("ok").getStatus()).isEqualTo("skipped");

        // AI 있음 → 의견 반영본으로 다시 묻기 → 승인
        prompts.clear();
        String e2 = startApproval("초안 본문", true);
        var first = waitPending(e2);
        assertThat(first.get("aiRevise")).isEqualTo(true);
        approvals.decide((String) first.get("id"), ApprovalService.RETRY, "할 일을 담당자별로", null);
        var second = waitPendingRound(e2, 2);
        assertThat(second.get("content")).isEqualTo("# 수정본 1\n- 의견 반영됨");
        assertThat(prompts.get(0)).contains("할 일을 담당자별로").contains("초안 본문").contains("회의록 편집 규칙");
        approvals.decide((String) second.get("id"), ApprovalService.APPROVE, "", null);
        Map<String, RunLog> r2 = finish(e2);
        assertThat(r2.get("ok").getOutput()).isEqualTo("# 수정본 1\n- 의견 반영됨");
        assertThat(r2.get("again").getStatus()).isEqualTo("skipped");
    }

    @Test
    void 기다리는_중에_실행을_취소하면_대기도_사라진다() throws Exception {
        String e = startApproval("초안", false);
        waitPending(e);
        runner.cancel(e);
        for (int i = 0; i < 50 && approvals.pending().stream().anyMatch(p -> e.equals(p.get("executionId"))); i++) Thread.sleep(50);
        assertThat(approvals.pending()).noneMatch(p -> e.equals(p.get("executionId")));
    }

    // ── helpers ──

    /** 웹훅(${text}) → 분기 b → d1(1)/d2(2)/dElse(그 외) → after (d1·d2·dElse 모두에서) */
    private Map<String, RunLog> runBranch(String text, Map<String, Object> extra) throws Exception {
        String path = "br-" + UUID.randomUUID();
        Map<String, Object> cfg = new HashMap<>(Map.of("rules", RULES));
        cfg.putAll(extra);
        List<PipelineNode> nodes = new ArrayList<>(List.of(hook(path), node("b", BranchModule.TYPE, cfg),
                pass("d1"), pass("d2"), pass("dElse"), pass("after")));
        List<PipelineConnection> conns = new ArrayList<>(List.of(new PipelineConnection(path, "b"),
                out("b", "d1", "one"), out("b", "d2", "two"), out("b", "dElse", BranchRules.ELSE),
                out("d1", "after", "r"), out("d2", "after", "r"), out("dElse", "after", "r")));
        return finish(fire(path, nodes, conns, text));
    }

    /** 웹훅(${text}) → 확인 요청 ap → ok(승인)/again(다시: "의견: ${approval.feedback}")/no(거절) */
    private String startApproval(String text, boolean withModel) throws Exception {
        String path = "ap-" + UUID.randomUUID();
        List<PipelineNode> nodes = new ArrayList<>(List.of(hook(path), node("ap", ApprovalModule.TYPE, Map.of("title", "회의록 확인")),
                pass("ok"), feedbackCheck("again"), pass("no")));
        List<PipelineConnection> conns = new ArrayList<>(List.of(new PipelineConnection(path, "ap"),
                out("ap", "ok", ApprovalModule.OUT_APPROVE), out("ap", "again", ApprovalModule.OUT_RETRY), out("ap", "no", ApprovalModule.OUT_REJECT)));
        if (withModel) {
            nodes.add(node("model", "GEMINI_MODEL", Map.of("apiKey", "test-key", "model", "fake-model")));
            nodes.add(node("ins", "AI_INSTRUCTION", Map.of("source", "직접 입력", "text", "회의록 편집 규칙: 표 유지")));
            conns.add(new PipelineConnection("model", "ap", "model"));
            conns.add(new PipelineConnection("ins", "ap", "instruction"));
        }
        return fire(path, nodes, conns, text);
    }

    private Map<String, Object> waitPending(String execId) throws Exception {
        return waitPendingRound(execId, 1);
    }

    private Map<String, Object> waitPendingRound(String execId, int round) throws Exception {
        for (int i = 0; i < 200; i++) {
            Optional<Map<String, Object>> p = approvals.pending().stream()
                    .filter(x -> execId.equals(x.get("executionId")) && Integer.valueOf(round).equals(x.get("round"))).findFirst();
            if (p.isPresent()) return p.get();
            Thread.sleep(50);
        }
        throw new AssertionError("확인 요청이 오지 않음");
    }

    private RunLog log(String execId, String nodeId) {
        return runner.getState(execId).logs.stream().filter(l -> l.getNodeId().equals(nodeId)).findFirst().orElse(new RunLog());
    }

    private String fire(String path, List<PipelineNode> nodes, List<PipelineConnection> conns, String text) throws Exception {
        Pipeline p = new Pipeline();
        p.setName("branch/approval test");
        p.setNodes(nodes);
        p.setConnections(conns);
        pipelines.create(p);
        String res = mvc.perform(post("/webhook-test/" + path).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("text", text))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(res).get("executionId").asText();
    }

    private Map<String, RunLog> finish(String execId) throws Exception {
        for (int i = 0; i < 200; i++) {
            var s = runner.getState(execId);
            if (s != null && !"running".equals(s.status)) {
                Map<String, RunLog> m = new HashMap<>();
                for (RunLog l : s.logs) m.put(l.getNodeId(), l);
                return m;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("실행이 끝나지 않음");
    }

    private static PipelineConnection out(String from, String to, String handle) {
        PipelineConnection c = new PipelineConnection(from, to);
        c.setFromHandle(handle);
        return c;
    }

    private PipelineNode hook(String path) {
        return node(path, "WEBHOOK", Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST", "outputTemplate", "${text}"));
    }

    /** 값을 그대로 넘기는 노드 (규칙 하나가 항상 맞는 분기) */
    private PipelineNode pass(String id) {
        return node(id, BranchModule.TYPE, Map.of("rules", "[{\"id\":\"r\",\"op\":\"notEmpty\"},{\"id\":\"e\",\"op\":\"empty\"}]"));
    }

    /** "다시" 쪽: ${approval.feedback} 에 "표로"가 들어 있으면 "의견 받음" 규칙으로 (로그 메시지로 확인) */
    private PipelineNode feedbackCheck(String id) {
        return node(id, BranchModule.TYPE, Map.of("subject", "${approval.feedback}",
                "rules", "[{\"id\":\"r\",\"label\":\"의견 받음\",\"op\":\"contains\",\"value\":\"표로\"}]"));
    }

    private PipelineNode node(String id, String type, Map<String, Object> config) {
        PipelineNode n = new PipelineNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new HashMap<>(config));
        return n;
    }
}
