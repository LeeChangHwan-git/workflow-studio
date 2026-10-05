package com.app.workflowstudio.node.modules.html;

import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeParam;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.app.workflowstudio.node.modules.http.HttpRequestModule;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 웹 페이지 추출 — 브라우저에서만 보이는(API 없는) 사내 게시판 등에서 선택자로 목록을 뽑는다.
 * 선택자는 설정 화면의 🔍 분석기에서 페이지를 보며 클릭해서 만든다.
 * 출력: {url, title, count, items:[{항목: 값}], text: "1. 제목 · 날짜 …"}
 */
@Component
@Order(305)
public class HtmlSelectModule implements NodeModule {

    public static final String TYPE = "HTML_SELECT";

    static final String SRC_URL = "URL에서 가져오기";
    static final String SRC_INPUT = "앞 노드 출력(HTML)";
    static final String EMPTY_FAIL = "오류로 중단";
    static final String EMPTY_OK = "빈 결과로 계속";

    private final HtmlFetcher fetcher;

    public HtmlSelectModule(HtmlFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "웹 페이지 추출", "🔎", "#D97706", "통합", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("source", "HTML 가져오기", "select", SRC_URL, List.of(SRC_URL, SRC_INPUT)),
                new NodeParam("url", "페이지 주소 (${...} 사용 가능)", "text", "https://", null, "source=" + SRC_URL),
                new NodeParam("headers", "요청 헤더 — 로그인이 필요하면 브라우저의 쿠키를 \"Cookie: 이름=값\" 으로 (한 줄에 하나)",
                        "secretTextarea", "", null, "source=" + SRC_URL),
                new NodeParam("picker", "분석기", "htmlPicker", null, null),
                new NodeParam("rowSelector", "반복 항목(행) 선택자 — 게시글 한 줄 (비우면 문서 전체)", "text", "", null),
                new NodeParam("fields", "가져올 항목 — 한 줄에 하나 \"이름: 선택자 @속성\" (@속성 생략=글자, @href=링크 주소)",
                        "textarea", "", null),
                new NodeParam("limit", "최대 개수", "number", 100, null),
                new NodeParam("ifEmpty", "찾은 항목이 없으면", "select", EMPTY_FAIL, List.of(EMPTY_FAIL, EMPTY_OK)),
                new NodeParam("outputVar", "출력 변수명", "text", "page", null)
        ));
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        List<HtmlExtractor.Field> fields = HtmlExtractor.parseFields(ctx.raw("fields", ""));
        String rowSelector = ctx.raw("rowSelector", "").trim();
        int limit = Math.max(1, Math.min(5000, ctx.intVal("limit", 100)));

        Document doc;
        String url;
        if (SRC_INPUT.equals(ctx.raw("source", SRC_URL))) {
            String html = ctx.input() == null ? "" : ctx.input();
            if (html.isBlank()) throw new Exception("앞 노드 출력(HTML)이 비어 있습니다");
            url = "";
            doc = Jsoup.parse(html);
        } else {
            HtmlFetcher.Page page = fetcher.fetch(ctx.str("url", ""),
                    HttpRequestModule.parseHeaders(ctx.str("headers", "")), 30);
            url = page.finalUrl();
            doc = page.doc();
        }

        List<Map<String, String>> items = HtmlExtractor.extract(doc, rowSelector, fields, limit);
        if (items.isEmpty() && EMPTY_FAIL.equals(ctx.raw("ifEmpty", EMPTY_FAIL))) {
            int matched = HtmlExtractor.matchedElements(doc, rowSelector, fields);
            if (matched > 0) {
                throw new Exception("선택자에 맞는 요소 " + matched + "개를 찾았지만 가져온 값이 모두 비어 있습니다 — "
                        + "글자가 없는 요소(로고 그림·아이콘 등)라면 가져올 내용을 지정하세요. "
                        + "예: \"로고: 선택자 @src\"(이미지 주소) · \"@html\"(HTML) · \"@alt\"(그림 설명). 🔍 분석기에서 바로 고칠 수 있습니다");
            }
            throw new Exception("선택자에 맞는 항목이 없습니다 — 로그인이 필요한 페이지(쿠키 확인)이거나, "
                    + "자바스크립트로 그리는 페이지이거나, 페이지 구조가 바뀌었을 수 있습니다. 🔍 분석기로 다시 확인하세요"
                    + (doc.title().isBlank() ? "" : " (받은 페이지 제목: " + doc.title() + ")"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("title", doc.title());
        out.put("count", items.size());
        out.put("items", items);
        out.put("text", text(items));
        return ctx.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    /** "1. 제목 · 날짜" — 링크(http…)는 빼고 한 줄씩 (AI에게 넘기기 좋은 형태) */
    static String text(List<Map<String, String>> items) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Map<String, String> r : items) {
            List<String> vals = new ArrayList<>();
            for (String v : r.values()) if (!v.isEmpty() && !v.startsWith("http")) vals.add(v);
            sb.append(i++).append(". ").append(String.join(" · ", vals)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
