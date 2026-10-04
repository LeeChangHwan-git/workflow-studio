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
        HttpSender.Result r;
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
