package com.app.workflowstudio.node.modules.outline;

import com.app.workflowstudio.service.HttpSender;
import com.app.workflowstudio.settings.AppSettings;
import com.app.workflowstudio.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outline(아웃라인) 위키 API 호출.
 * 모든 API가 {@code POST {서버}/api/{메소드}} + JSON 바디 + {@code Authorization: Bearer <API 키>} 형식이고,
 * 응답은 {@code {ok, data, pagination}} / 실패 시 {@code {ok:false, error, message}}.
 */
@Component
public class OutlineClient {

    /** 한 번에 받을 수 있는 최대 개수 (Outline 제한) */
    static final int PAGE_MAX = 100;
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final HttpSender sender;
    private final ObjectMapper om;

    public OutlineClient(HttpSender sender, ObjectMapper om) {
        this.sender = sender;
        this.om = om;
    }

    /** 접속 정보 (서버 주소 + API 키) */
    public record Conn(String baseUrl, String apiKey) {}

    /** 주소·키 찾는 순서: 노드 → ⚙ 설정의 공용 값 → 환경 변수 (OUTLINE_URL / OUTLINE_API_KEY) */
    public static Conn conn(String baseUrl, String nodeKey) throws Exception {
        AppSettings.Credentials cred = SettingsService.current().credentials;
        String url = SettingsService.firstNonBlank(baseUrl, cred.outlineBaseUrl, System.getenv("OUTLINE_URL"));
        if (url == null) throw new Exception("아웃라인 주소를 입력하세요 (예: https://wiki.회사.com) — 노드 또는 ⚙ 설정 → 공용 키");
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw new Exception("아웃라인 주소는 http:// 또는 https:// 로 시작해야 합니다: " + url);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/api")) url = url.substring(0, url.length() - 4);

        String key = SettingsService.firstNonBlank(nodeKey, cred.outlineApiKey, System.getenv("OUTLINE_API_KEY"));
        if (key == null)
            throw new Exception("API 키가 없습니다 — 아웃라인 설정 → API 액세스에서 발급해 노드나 ⚙ 설정 → 공용 키에 넣으세요");
        return new Conn(url, key.trim());
    }

    /** API 호출 → 응답 전체 JSON. 실패하면 이유를 알기 쉬운 메시지로 예외 */
    public JsonNode call(Conn c, String method, Map<String, ?> body) throws Exception {
        String url = c.baseUrl() + "/api/" + method;
        HttpSender.Result r = null;
        // 요청이 너무 많으면(429) 서버가 알려준 시간(Retry-After, 없으면 3·6·12초)만큼 쉬었다 다시 — 최대 3번
        for (int attempt = 0; ; attempt++) {
            try {
                r = sender.send("POST", url, List.of(
                        new HttpSender.Header("Authorization", "Bearer " + c.apiKey()),
                        new HttpSender.Header("Content-Type", "application/json"),
                        new HttpSender.Header("Accept", "application/json")
                ), om.writeValueAsString(body == null ? Map.of() : body), Duration.ofSeconds(30));
            } catch (java.net.ConnectException e) {
                throw new Exception("아웃라인 서버에 연결할 수 없습니다: " + c.baseUrl());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new Exception("아웃라인 서버 응답이 없습니다 (30초): " + c.baseUrl());
            }
            if (r.status() != 429 || attempt >= RATE_LIMIT_RETRIES) break;
            Thread.sleep(retryAfterMillis(r.headers(), attempt));
        }

        JsonNode json;
        try {
            json = om.readTree(r.body());
        } catch (Exception e) {
            json = null;
        }
        if (r.status() >= 200 && r.status() < 300 && json != null && json.isObject()) return json;

        String msg = json != null ? json.path("message").asText(json.path("error").asText("")) : "";
        String reason = switch (r.status()) {
            case 401 -> "API 키가 올바르지 않거나 만료되었습니다";
            case 403 -> "권한이 없습니다 (API 키 범위 또는 컬렉션 권한 확인)";
            case 404 -> json == null ? "아웃라인 API를 찾을 수 없습니다 — 주소를 확인하세요" : "대상을 찾을 수 없습니다";
            case 429 -> "요청이 너무 많습니다 — 잠시 후 다시 시도하세요";
            default -> json == null ? "아웃라인 API 응답이 아닙니다 — 주소를 확인하세요" : "요청 실패";
        };
        throw new Exception("아웃라인 " + method + " 실패 (HTTP " + r.status() + "): " + reason
                + (msg.isBlank() ? "" : " — " + msg));
    }

    /** 키가 어디서 오는지: node | settings | env | none */
    public static String keySource(String nodeKey) {
        if (nodeKey != null && !nodeKey.isBlank()) return "node";
        if (!SettingsService.current().credentials.outlineApiKey.isBlank()) return "settings";
        String env = System.getenv("OUTLINE_API_KEY");
        return env != null && !env.isBlank() ? "env" : "none";
    }

    static int RATE_LIMIT_RETRIES = 3;

    /** Retry-After(초) 헤더가 있으면 그만큼(최대 60초), 없으면 3·6·12초 */
    static long retryAfterMillis(Map<String, String> headers, int attempt) {
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (!h.getKey().equalsIgnoreCase("retry-after")) continue;
            try {
                return Math.min(60, Math.max(1, Long.parseLong(h.getValue().trim()))) * 1000L;
            } catch (NumberFormatException ignore) {
            }
        }
        return 3000L << attempt;
    }

    /** 컬렉션 목록 [{id, name}] */
    public List<Map<String, String>> collections(Conn c) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        for (int offset = 0; offset < 1000; offset += PAGE_MAX) {
            JsonNode data = call(c, "collections.list", Map.of("limit", PAGE_MAX, "offset", offset)).path("data");
            for (JsonNode col : data) list.add(Map.of("id", col.path("id").asText(), "name", col.path("name").asText()));
            if (data.size() < PAGE_MAX) break;
        }
        return list;
    }

    /** 컬렉션 이름 또는 ID → ID (비어 있으면 null = 전체) */
    public String collectionId(Conn c, String nameOrId) throws Exception {
        if (nameOrId == null || nameOrId.isBlank()) return null;
        String v = nameOrId.trim();
        if (UUID_PATTERN.matcher(v).matches()) return v;
        List<Map<String, String>> cols = collections(c);
        for (Map<String, String> col : cols) if (col.get("name").equals(v)) return col.get("id");
        for (Map<String, String> col : cols) if (col.get("name").equalsIgnoreCase(v)) return col.get("id");
        throw new Exception("컬렉션 '" + v + "'을(를) 찾을 수 없습니다 — 있는 컬렉션: "
                + String.join(", ", cols.stream().map(m -> m.get("name")).toList()));
    }

    // ── 여러 단계 위치: "컬렉션/상위 문서/하위 문서" ─────────────────────

    /**
     * 위치 — 컬렉션 하나 + (선택) 그 안의 상위 문서.
     * @param docId    위치가 문서면 그 문서 id, 컬렉션 바로 아래면 null
     * @param path     사람이 읽는 경로 ("FW팀/회의록/2026년")
     * @param children 그 위치 바로 아래 문서들 (문서 트리 노드: {id, title, url, children})
     * @param created  없어서 새로 만든 상위 문서 제목들
     */
    public record Location(String collectionId, String collectionName, String docId, String path,
                           List<JsonNode> children, List<String> created) {}

    /** 컬렉션의 문서 트리 (게시된 문서) — [{id, title, url, children:[…]}] */
    public List<JsonNode> tree(Conn c, String collectionId) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        call(c, "collections.documents", Map.of("id", collectionId)).path("data").forEach(out::add);
        return out;
    }

    /**
     * "컬렉션/문서/문서…" 경로 → 위치. 컬렉션 이름에 '/'가 들어 있어도 되도록 가장 긴 이름부터 맞춰 봄.
     * 첫 조각이 컬렉션 ID(UUID)여도 됨. 비어 있으면 null (전체).
     * @param createMissing 없는 상위 문서를 빈 문서로 만들지 (false 면 오류 + 있는 문서 목록 안내)
     */
    public Location resolve(Conn c, String path, boolean createMissing) throws Exception {
        if (path == null || path.isBlank()) return null;
        if (path.contains("${")) throw new Exception("위치에 해석되지 않은 변수가 있습니다: " + path);
        List<String> segs = new ArrayList<>();
        for (String s : path.split("/")) if (!s.isBlank()) segs.add(s.trim());
        if (segs.isEmpty()) return null;

        List<Map<String, String>> cols = collections(c);
        String colId = null, colName = null;
        int used = 0;
        if (UUID_PATTERN.matcher(segs.get(0)).matches()) {
            colId = segs.get(0);
            used = 1;
            for (Map<String, String> col : cols) if (col.get("id").equals(colId)) colName = col.get("name");
            if (colName == null) colName = colId;
        } else {
            outer:
            for (boolean ignoreCase : new boolean[]{false, true}) {
                for (int k = segs.size(); k >= 1; k--) {
                    String name = String.join("/", segs.subList(0, k));
                    for (Map<String, String> col : cols) {
                        if (ignoreCase ? col.get("name").equalsIgnoreCase(name) : col.get("name").equals(name)) {
                            colId = col.get("id");
                            colName = col.get("name");
                            used = k;
                            break outer;
                        }
                    }
                }
            }
        }
        if (colId == null) {
            throw new Exception("컬렉션 '" + segs.get(0) + "'을(를) 찾을 수 없습니다 — 있는 컬렉션: "
                    + String.join(", ", cols.stream().map(m -> m.get("name")).toList()));
        }

        List<JsonNode> level = tree(c, colId);
        String docId = null;
        StringBuilder where = new StringBuilder(colName);
        List<String> created = new ArrayList<>();
        for (String seg : segs.subList(used, segs.size())) {
            JsonNode found = null;
            for (JsonNode n : level) if (n.path("title").asText().trim().equals(seg)) { found = n; break; }
            if (found == null) for (JsonNode n : level) if (n.path("title").asText().trim().equalsIgnoreCase(seg)) { found = n; break; }
            if (found == null) {
                if (!createMissing) {
                    List<String> names = level.stream().map(n -> n.path("title").asText()).toList();
                    throw new Exception("'" + where + "' 아래에 '" + seg + "' 문서가 없습니다 — 있는 문서: "
                            + (names.isEmpty() ? "(없음)" : String.join(", ", names))
                            + " (등록·이동은 \"없는 상위 문서는 만들기\"를 고르면 자동으로 만듭니다)");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("collectionId", colId);
                if (docId != null) body.put("parentDocumentId", docId);
                body.put("title", seg);
                body.put("text", "");
                body.put("publish", true);
                JsonNode d = call(c, "documents.create", body).path("data");
                found = om.createObjectNode().put("id", d.path("id").asText()).put("title", seg)
                        .put("url", d.path("url").asText()).set("children", om.createArrayNode());
                created.add(seg);
            }
            docId = found.path("id").asText();
            where.append('/').append(found.path("title").asText());
            List<JsonNode> next = new ArrayList<>();
            found.path("children").forEach(next::add);
            level = next;
        }
        return new Location(colId, colName, docId, where.toString(), level, created);
    }

    /** 문서 트리를 펼친 목록 — {id, path, depth} (depth 1 = 바로 아래) */
    public static List<Map<String, Object>> flatten(List<JsonNode> nodes, String basePath, boolean recursive) {
        List<Map<String, Object>> out = new ArrayList<>();
        flatten(nodes, basePath, 1, recursive, out);
        return out;
    }

    private static void flatten(List<JsonNode> nodes, String base, int depth, boolean recursive, List<Map<String, Object>> out) {
        for (JsonNode n : nodes) {
            String p = base + "/" + n.path("title").asText();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.path("id").asText());
            m.put("path", p);
            m.put("depth", depth);
            out.add(m);
            if (recursive) {
                List<JsonNode> kids = new ArrayList<>();
                n.path("children").forEach(kids::add);
                flatten(kids, p, depth + 1, true, out);
            }
        }
    }

    /** 문서 지정: 경로("컬렉션/…/문서 제목")면 트리에서 찾고, 아니면 ID·주소 */
    public String documentId(Conn c, String value) throws Exception {
        String v = value == null ? "" : value.trim();
        boolean isPath = v.contains("/") && !v.contains("/doc/") && !v.startsWith("http");
        if (!isPath) return documentRef(v);
        Location loc = resolve(c, v, false);
        if (loc == null || loc.docId() == null) throw new Exception("문서 경로는 컬렉션 다음에 문서 제목까지 적어야 합니다: " + v);
        return loc.docId();
    }

    /** 위치 경로 후보 (설정 화면 자동완성용) — 컬렉션과 그 아래 문서들, 최대 max 개 */
    public List<String> paths(Conn c, int max) throws Exception {
        List<String> out = new ArrayList<>();
        for (Map<String, String> col : collections(c)) {
            out.add(col.get("name"));
            if (out.size() >= max) break;
            for (Map<String, Object> d : flatten(tree(c, col.get("id")), col.get("name"), true)) {
                out.add((String) d.get("path"));
                if (out.size() >= max) return out;
            }
        }
        return out;
    }

    /**
     * 문서 ID / 문서 주소 → API에 넘길 id.
     * 주소 예: https://wiki/doc/주간-회의록-AbCdEf1234 → 마지막 '-' 뒤 urlId (AbCdEf1234)
     */
    public static String documentRef(String value) throws Exception {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) throw new Exception("문서 ID 또는 문서 주소를 입력하세요");
        if (v.contains("${")) throw new Exception("문서 ID에 해석되지 않은 변수가 있습니다: " + v);
        Matcher m = Pattern.compile("/doc/([^/?#]+)").matcher(v);
        if (m.find()) {
            String slug = m.group(1);
            int dash = slug.lastIndexOf('-');
            return dash >= 0 ? slug.substring(dash + 1) : slug;
        }
        return v;
    }
}
