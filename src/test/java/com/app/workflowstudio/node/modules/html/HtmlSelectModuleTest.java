package com.app.workflowstudio.node.modules.html;

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
import java.nio.charset.Charset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-html-test",
        "app.open-browser=false"
})
class HtmlSelectModuleTest {

    static HttpServer site;
    static String base;

    /** 옛날식 사내 게시판 (EUC-KR, meta 태그로만 문자셋 표시, 머리글 줄 + 공지 줄) */
    static final String BOARD = """
            <html><head><meta http-equiv="Content-Type" content="text/html; charset=euc-kr">
            <title>사내 공지 게시판</title><script>alert('x')</script></head>
            <body onload="init()"><div id="logo"><svg class="logo"><path d="M0 0h10"/></svg></div><div id="content">
            <table class="board" width="100%">
              <tr><th>번호</th><th>제목</th><th>작성자</th><th>날짜</th></tr>
              <tr class="notice"><td>공지</td><td class="subject"><a href="view.jsp?no=1">[공지] 보안 점검 안내</a></td><td>관리자</td><td>2026-10-01</td></tr>
              <tr><td>12</td><td class="subject"><a href="view.jsp?no=12"><span>[FW] 부트로더 v2.3 배포</span></a></td><td>홍길동</td><td>2026-10-02</td></tr>
              <tr><td>11</td><td class="subject"><a href="view.jsp?no=11" onclick="go()">[인사] 교육 신청</a></td><td>김영희</td><td>2026-10-03</td></tr>
            </table></div></body></html>""";

    @BeforeAll
    static void start() throws Exception {
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/board/list.jsp", ex -> send(ex, 200, "text/html", BOARD.getBytes(Charset.forName("EUC-KR"))));
        site.createContext("/secure/list.jsp", ex -> {
            String cookie = String.valueOf(ex.getRequestHeaders().getFirst("Cookie"));
            if (!cookie.contains("SESSION=ok")) send(ex, 403, "text/html", "forbidden".getBytes());
            else send(ex, 200, "text/html; charset=UTF-8", BOARD.replace("euc-kr", "utf-8").getBytes(Charset.forName("UTF-8")));
        });
        // 로그인 리다이렉트: 쿠키를 주고 다른 주소로 → 쿠키를 들고 따라가야 목록이 보임
        site.createContext("/sso", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "SESSION=ok; Path=/");
            ex.getResponseHeaders().add("Location", "/secure/list.jsp");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        site.createContext("/frames.html", ex -> send(ex, 200, "text/html; charset=UTF-8",
                "<html><frameset cols='200,*'><frame src='menu.html'><frame src='board/list.jsp'></frameset></html>".getBytes()));
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        site.stop(0);
    }

    static void send(HttpExchange ex, int status, String type, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @Autowired MockMvc mvc;
    @Autowired PipelineService pipelines;
    @Autowired PipelineRunnerService runner;
    @Autowired ObjectMapper om;
    @Autowired HtmlFetcher fetcher;

    static final String FIELDS = "제목: td.subject a\n링크: td.subject a @href\n# 주석\n날짜: td:nth-of-type(4)";

    @Test
    void EUC_KR_게시판에서_행마다_제목_링크_날짜를_뽑는다() throws Exception {
        HtmlFetcher.Page page = fetcher.fetch(base + "/board/list.jsp", List.of(), 10);
        assertThat(page.charset().toUpperCase()).isEqualTo("EUC-KR");
        var rows = HtmlExtractor.extract(page.doc(), "table.board > tbody > tr", HtmlExtractor.parseFields(FIELDS), 100);

        assertThat(rows).hasSize(3);                                         // 머리글 줄(th)은 빠짐
        assertThat(rows.get(1)).containsEntry("제목", "[FW] 부트로더 v2.3 배포")
                .containsEntry("링크", base + "/board/view.jsp?no=12")         // 상대 주소 → 절대 주소
                .containsEntry("날짜", "2026-10-02");
        assertThat(HtmlExtractor.extract(page.doc(), "table.board > tbody > tr", HtmlExtractor.parseFields(FIELDS), 2)).hasSize(2);

        // 행 없이: 항목별로 모두 찾아 순서대로 짝짓기
        var flat = HtmlExtractor.extract(page.doc(), "", HtmlExtractor.parseFields("제목: td.subject a"), 100);
        assertThat(flat).extracting(r -> r.get("제목")).containsExactly("[공지] 보안 점검 안내", "[FW] 부트로더 v2.3 배포", "[인사] 교육 신청");
    }

    @Test
    void 파이프라인에서_실행하면_목록과_요약_텍스트를_넘긴다() throws Exception {
        JsonNode out = om.readTree(run(Map.of("url", base + "/board/list.jsp", "rowSelector", "table.board tr",
                "fields", FIELDS)).getOutput());
        assertThat(out.get("count").asInt()).isEqualTo(3);
        assertThat(out.get("title").asText()).isEqualTo("사내 공지 게시판");
        assertThat(out.get("items").get(2).get("제목").asText()).isEqualTo("[인사] 교육 신청");
        assertThat(out.get("text").asText()).startsWith("1. [공지] 보안 점검 안내 · 2026-10-01\n2. [FW]");   // 링크는 텍스트에서 제외

        RunLog none = run(Map.of("url", base + "/board/list.jsp", "rowSelector", "ul.none > li", "fields", FIELDS));
        assertThat(none.getStatus()).isEqualTo("error");
        assertThat(none.getMessage()).contains("선택자에 맞는 항목이 없습니다").contains("사내 공지 게시판");

        RunLog empty = run(Map.of("url", base + "/board/list.jsp", "rowSelector", "ul.none > li", "fields", FIELDS,
                "ifEmpty", HtmlSelectModule.EMPTY_OK));
        assertThat(empty.getStatus()).isEqualTo("success");
    }

    @Test
    void 글자가_없는_요소는_값이_비었다고_알려주고_속성으로_가져올_수_있다() throws Exception {
        RunLog empty = run(Map.of("url", base + "/board/list.jsp", "fields", "로고: div#logo > svg.logo"));
        assertThat(empty.getStatus()).isEqualTo("error");
        assertThat(empty.getMessage()).contains("요소 1개를 찾았지만 가져온 값이 모두 비어 있습니다").contains("@html");

        RunLog ok = run(Map.of("url", base + "/board/list.jsp", "fields", "로고: div#logo > svg.logo @html"));
        assertThat(ok.getStatus()).as(ok.getMessage()).isEqualTo("success");
        assertThat(om.readTree(ok.getOutput()).get("items").get(0).get("로고").asText()).contains("<path");
    }

    @Test
    void 로그인이_필요한_페이지는_쿠키_헤더로_가져오고_리다이렉트_쿠키도_유지한다() throws Exception {
        RunLog denied = run(Map.of("url", base + "/secure/list.jsp", "rowSelector", "table.board tr", "fields", FIELDS));
        assertThat(denied.getMessage()).contains("HTTP 403").contains("Cookie");

        RunLog ok = run(Map.of("url", base + "/secure/list.jsp", "headers", "Cookie: SESSION=ok",
                "rowSelector", "table.board tr", "fields", FIELDS));
        assertThat(ok.getStatus()).as(ok.getMessage()).isEqualTo("success");

        HtmlFetcher.Page viaSso = fetcher.fetch(base + "/sso", List.of(), 10);
        assertThat(viaSso.finalUrl()).endsWith("/secure/list.jsp");
        assertThat(viaSso.doc().title()).isEqualTo("사내 공지 게시판");
    }

    @Test
    void 분석기용_HTML은_스크립트를_빼고_프레임을_알려준다() throws Exception {
        JsonNode r = om.readTree(mvc.perform(post("/api/html/fetch").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("url", base + "/board/list.jsp"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String html = r.get("html").asText();
        assertThat(r.get("ok").asBoolean()).isTrue();
        assertThat(html).doesNotContain("<script").doesNotContain("onclick").doesNotContain("onload")
                .contains("<base href=\"" + base + "/board/list.jsp\"").contains("[FW] 부트로더");

        JsonNode f = om.readTree(mvc.perform(post("/api/html/fetch").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("url", base + "/frames.html"))))
                .andReturn().getResponse().getContentAsString());
        assertThat(f.get("frames")).extracting(JsonNode::asText)
                .containsExactly(base + "/menu.html", base + "/board/list.jsp");

        JsonNode t = om.readTree(mvc.perform(post("/api/html/test").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("url", base + "/board/list.jsp",
                                "rowSelector", "table.board tr", "fields", FIELDS))))
                .andReturn().getResponse().getContentAsString());
        assertThat(t.get("count").asInt()).isEqualTo(3);
    }

    @Test
    void 잘못된_항목_형식은_알려준다() {
        assertThatThrownBy(() -> HtmlExtractor.parseFields("제목 td a")).hasMessageContaining("1번째 줄");
        assertThatThrownBy(() -> HtmlExtractor.parseFields("제목: a\n제목: b")).hasMessageContaining("중복");
        assertThatThrownBy(() -> HtmlExtractor.parseFields("제목: td[[")).hasMessageContaining("선택자가 올바르지 않습니다");
        assertThatThrownBy(() -> HtmlExtractor.parseFields("")).hasMessageContaining("가져올 항목이 없습니다");
    }

    // ── helpers ──

    /** 웹훅 → 웹 페이지 추출 */
    private RunLog run(Map<String, Object> config) throws Exception {
        String path = "html-" + UUID.randomUUID();
        PipelineNode hook = node(path, "WEBHOOK", Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST", "outputVar", "webhook"));
        PipelineNode html = node("html", HtmlSelectModule.TYPE, config);
        Pipeline p = new Pipeline();
        p.setName("html test");
        p.setNodes(new ArrayList<>(List.of(hook, html)));
        p.setConnections(new ArrayList<>(List.of(new PipelineConnection(path, "html"))));
        pipelines.create(p);
        String res = mvc.perform(post("/webhook-test/" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String execId = om.readTree(res).get("executionId").asText();
        for (int i = 0; i < 100; i++) {
            var s = runner.getState(execId);
            if (s != null && !"running".equals(s.status))
                return s.logs.stream().filter(l -> l.getNodeId().equals("html")).findFirst().orElseThrow();
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
