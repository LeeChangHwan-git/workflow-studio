package com.app.workflowstudio.node.modules.outline;

import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeParam;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 아웃라인(Outline) 게시판 연동 — 조회 · 검색 · 문서 하나 가져오기 · 등록 · 수정.
 * 출력은 JSON. 조회/검색은 {@code titles}(번호 붙은 제목 목록 텍스트)를 함께 넣어 AI에게 바로 넘기기 좋게 한다.
 */
@Component
@Order(310)
public class OutlineModule implements NodeModule {

    public static final String TYPE = "OUTLINE";

    static final String ACT_LIST = "조회 — 문서 목록";
    static final String ACT_SEARCH = "검색 — 키워드로 찾기";
    static final String ACT_GET = "문서 하나 가져오기";
    static final String ACT_CREATE = "등록 — 새 문서";
    static final String ACT_UPDATE = "수정 — 기존 문서";

    static final String INCLUDE_TITLE = "제목만";
    static final String INCLUDE_TEXT = "제목 + 내용";

    static final String UPDATE_REPLACE = "내용 바꾸기";
    static final String UPDATE_APPEND = "끝에 덧붙이기";

    static final String PUBLISH_YES = "바로 게시";
    static final String PUBLISH_DRAFT = "초안으로 저장";

    private final OutlineClient client;

    public OutlineModule(OutlineClient client) {
        this.client = client;
    }

    @Override
    public NodeTypeInfo info() {
        String read = "action=" + ACT_LIST + "|" + ACT_SEARCH;
        return new NodeTypeInfo(TYPE, "아웃라인", "📘", "#4E5BF2", "통합", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("baseUrl", "아웃라인 주소", "text", "http://localhost:3000", null),
                new NodeParam("apiKey", "API 키 (비우면 OUTLINE_API_KEY 환경 변수 · 발급: 아웃라인 설정 → API 액세스)",
                        "password", "", null),
                new NodeParam("connection", "연결", "outlineConnection", null, null),
                new NodeParam("action", "작업", "select", ACT_LIST,
                        List.of(ACT_LIST, ACT_SEARCH, ACT_GET, ACT_CREATE, ACT_UPDATE)),
                new NodeParam("collection", "컬렉션 (조회·검색은 비우면 전체)", "outlineCollection", "", null,
                        "action=" + ACT_LIST + "|" + ACT_SEARCH + "|" + ACT_CREATE),
                new NodeParam("query", "검색어 (${...} 사용 가능)", "text", "", null, "action=" + ACT_SEARCH),
                new NodeParam("documentId", "문서 ID 또는 문서 주소 (${...} 사용 가능)", "text", "", null,
                        "action=" + ACT_GET + "|" + ACT_UPDATE),
                new NodeParam("include", "가져올 내용", "select", INCLUDE_TITLE, List.of(INCLUDE_TITLE, INCLUDE_TEXT), read),
                new NodeParam("limit", "최대 개수", "number", 50, null, read),
                new NodeParam("recentDays", "최근 N일 안에 수정된 문서만 (0 = 기간 제한 없음)", "number", 0, null,
                        "action=" + ACT_LIST),
                new NodeParam("title", "제목 (${...} 사용 가능) — 비우면 등록: 내용의 첫 '# 제목' 줄 / 수정: 그대로", "text", "",
                        null, "action=" + ACT_CREATE + "|" + ACT_UPDATE),
                new NodeParam("text", "내용 (마크다운, ${input} = 앞 노드 출력)", "textarea", "${input}", null,
                        "action=" + ACT_CREATE + "|" + ACT_UPDATE),
                new NodeParam("updateMode", "수정 방식", "select", UPDATE_REPLACE, List.of(UPDATE_REPLACE, UPDATE_APPEND),
                        "action=" + ACT_UPDATE),
                new NodeParam("publish", "게시", "select", PUBLISH_YES, List.of(PUBLISH_YES, PUBLISH_DRAFT),
                        "action=" + ACT_CREATE),
                new NodeParam("outputVar", "출력 변수명", "text", "outline", null)
        ));
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        OutlineClient.Conn c = OutlineClient.conn(ctx.raw("baseUrl", ""), ctx.raw("apiKey"));
        String action = ctx.raw("action", ACT_LIST);
        Object out = switch (action) {
            case ACT_LIST -> list(ctx, c);
            case ACT_SEARCH -> search(ctx, c);
            case ACT_GET -> get(ctx, c);
            case ACT_CREATE -> create(ctx, c);
            case ACT_UPDATE -> update(ctx, c);
            default -> throw new Exception("알 수 없는 작업: " + action);
        };
        return ctx.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    // ── 조회 ─────────────────────────────────────────────

    private Map<String, Object> list(NodeContext ctx, OutlineClient.Conn c) throws Exception {
        String collection = ctx.str("collection", "");
        String collectionId = client.collectionId(c, collection);
        int limit = limit(ctx);
        int recentDays = Math.max(0, ctx.intVal("recentDays", 0));
        OffsetDateTime since = recentDays > 0 ? OffsetDateTime.now().minusDays(recentDays) : null;
        boolean withText = INCLUDE_TEXT.equals(ctx.raw("include", INCLUDE_TITLE));

        List<Map<String, Object>> docs = new ArrayList<>();
        Map<String, String> colNames = collectionNames(c);
        boolean done = false;
        for (int offset = 0; !done && docs.size() < limit; offset += OutlineClient.PAGE_MAX) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("limit", OutlineClient.PAGE_MAX);
            body.put("offset", offset);
            body.put("sort", "updatedAt");
            body.put("direction", "DESC");
            if (collectionId != null) body.put("collectionId", collectionId);
            JsonNode data = client.call(c, "documents.list", body).path("data");
            for (JsonNode d : data) {
                if (since != null && updatedAt(d) != null && updatedAt(d).isBefore(since)) { done = true; break; }
                docs.add(doc(c, d, withText, colNames));
                if (docs.size() >= limit) break;
            }
            if (data.size() < OutlineClient.PAGE_MAX) done = true;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", docs.size());
        if (!collection.isBlank()) out.put("collection", collection);
        if (recentDays > 0) out.put("recentDays", recentDays);
        out.put("titles", titles(docs));
        out.put("documents", docs);
        return out;
    }

    // ── 검색 ─────────────────────────────────────────────

    private Map<String, Object> search(NodeContext ctx, OutlineClient.Conn c) throws Exception {
        String query = ctx.str("query", "").trim();
        if (query.isEmpty()) throw new Exception("검색어를 입력하세요");
        String collectionId = client.collectionId(c, ctx.str("collection", ""));
        int limit = limit(ctx);
        boolean withText = INCLUDE_TEXT.equals(ctx.raw("include", INCLUDE_TITLE));
        Map<String, String> colNames = collectionNames(c);

        List<Map<String, Object>> docs = new ArrayList<>();
        for (int offset = 0; docs.size() < limit; offset += OutlineClient.PAGE_MAX) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("limit", OutlineClient.PAGE_MAX);
            body.put("offset", offset);
            if (collectionId != null) body.put("collectionId", collectionId);
            JsonNode data = client.call(c, "documents.search", body).path("data");
            for (JsonNode hit : data) {
                Map<String, Object> d = doc(c, hit.path("document"), withText, colNames);
                String context = hit.path("context").asText("").replaceAll("</?b>", "").trim();
                if (!context.isEmpty()) d.put("context", context);
                docs.add(d);
                if (docs.size() >= limit) break;
            }
            if (data.size() < OutlineClient.PAGE_MAX) break;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("count", docs.size());
        out.put("titles", titles(docs));
        out.put("documents", docs);
        return out;
    }

    // ── 문서 하나 ─────────────────────────────────────────

    private Map<String, Object> get(NodeContext ctx, OutlineClient.Conn c) throws Exception {
        String id = OutlineClient.documentRef(ctx.str("documentId", ""));
        JsonNode d = client.call(c, "documents.info", Map.of("id", id)).path("data");
        return doc(c, d, true, collectionNames(c));
    }

    // ── 등록 ─────────────────────────────────────────────

    private Map<String, Object> create(NodeContext ctx, OutlineClient.Conn c) throws Exception {
        String collection = ctx.str("collection", "");
        if (collection.isBlank()) throw new Exception("등록할 컬렉션을 고르세요");
        String collectionId = client.collectionId(c, collection);

        String text = stripCodeFence(ctx.str("text", ""));
        String title = ctx.str("title", "").trim();
        if (title.isEmpty()) {
            TitleSplit split = splitTitle(text);
            title = split.title();
            text = split.body();
        }
        if (title.isEmpty()) throw new Exception("제목이 비어 있습니다 — 제목을 입력하거나 내용 첫 줄을 '# 제목'으로 쓰세요");
        if (title.length() > 100) title = title.substring(0, 100);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collectionId", collectionId);
        body.put("title", title);
        body.put("text", text);
        body.put("publish", !PUBLISH_DRAFT.equals(ctx.raw("publish", PUBLISH_YES)));
        JsonNode d = client.call(c, "documents.create", body).path("data");
        return result("created", c, d);
    }

    // ── 수정 ─────────────────────────────────────────────

    private Map<String, Object> update(NodeContext ctx, OutlineClient.Conn c) throws Exception {
        String id = OutlineClient.documentRef(ctx.str("documentId", ""));
        String text = stripCodeFence(ctx.str("text", ""));
        String title = ctx.str("title", "").trim();

        Map<String, Object> body = new LinkedHashMap<>();
        if (UPDATE_APPEND.equals(ctx.raw("updateMode", UPDATE_REPLACE))) {
            // 현재 내용을 받아 뒤에 붙여서 저장 (API의 append 옵션은 버전마다 달라 직접 합침)
            JsonNode cur = client.call(c, "documents.info", Map.of("id", id)).path("data");
            id = cur.path("id").asText(id);
            String existing = cur.path("text").asText("");
            text = existing.isBlank() ? text : existing.stripTrailing() + "\n\n" + text;
        }
        body.put("id", id);
        if (!title.isEmpty()) body.put("title", title.length() > 100 ? title.substring(0, 100) : title);
        body.put("text", text);
        JsonNode d = client.call(c, "documents.update", body).path("data");
        return result("updated", c, d);
    }

    // ── helpers ──────────────────────────────────────────

    private static int limit(NodeContext ctx) {
        return Math.max(1, Math.min(1000, ctx.intVal("limit", 50)));
    }

    private Map<String, String> collectionNames(OutlineClient.Conn c) {
        Map<String, String> m = new HashMap<>();
        try {
            for (Map<String, String> col : client.collections(c)) m.put(col.get("id"), col.get("name"));
        } catch (Exception ignore) {
            // 컬렉션 이름은 부가 정보 — 못 받아도 계속
        }
        return m;
    }

    private static Map<String, Object> doc(OutlineClient.Conn c, JsonNode d, boolean withText, Map<String, String> colNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.path("id").asText());
        m.put("title", d.path("title").asText());
        m.put("url", c.baseUrl() + d.path("url").asText());
        String col = colNames.get(d.path("collectionId").asText());
        if (col != null) m.put("collection", col);
        String author = d.path("createdBy").path("name").asText("");
        if (!author.isEmpty()) m.put("author", author);
        OffsetDateTime updated = updatedAt(d);
        if (updated != null) m.put("updatedAt", updated.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().toString());
        if (withText) m.put("text", d.path("text").asText(""));
        return m;
    }

    private static OffsetDateTime updatedAt(JsonNode d) {
        String s = d.path("updatedAt").asText("");
        try {
            return s.isEmpty() ? null : OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** "1. 제목 (2026-10-04)" 줄 목록 — AI 프롬프트에 바로 넣기 좋은 형태 */
    private static String titles(List<Map<String, Object>> docs) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Map<String, Object> d : docs) {
            sb.append(i++).append(". ").append(d.get("title"));
            Object at = d.get("updatedAt");
            if (at != null) sb.append(" (").append(at.toString(), 0, Math.min(10, at.toString().length())).append(")");
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static Map<String, Object> result(String action, OutlineClient.Conn c, JsonNode d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("id", d.path("id").asText());
        m.put("title", d.path("title").asText());
        m.put("url", c.baseUrl() + d.path("url").asText());
        return m;
    }

    record TitleSplit(String title, String body) {}

    /** 내용 첫 '# 제목' 줄을 제목으로 떼어냄. 없으면 첫 줄(마크다운 기호 제거)을 제목으로 쓰고 내용은 그대로 */
    static TitleSplit splitTitle(String text) {
        String t = text == null ? "" : text.strip();
        Matcher h1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*#*\\s*$").matcher(t);
        if (h1.find() && t.substring(0, h1.start()).isBlank()) {
            return new TitleSplit(h1.group(1).trim(), t.substring(h1.end()).strip());
        }
        for (String line : t.split("\\r?\\n")) {
            String clean = line.replaceAll("^[#>*\\-\\s]+", "").replaceAll("[*_`]", "").trim();
            if (!clean.isEmpty()) return new TitleSplit(clean, t);
        }
        return new TitleSplit("", t);
    }

    /** AI가 내용 전체를 ```markdown … ``` 으로 감싸서 주는 경우 벗겨냄 */
    static String stripCodeFence(String text) {
        if (text == null) return "";
        String t = text.strip();
        Matcher m = Pattern.compile("^```[a-zA-Z]*\\s*\\n([\\s\\S]*?)\\n?```$").matcher(t);
        return m.matches() ? m.group(1).strip() : t;
    }

}
