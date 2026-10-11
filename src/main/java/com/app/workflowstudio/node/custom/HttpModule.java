package com.app.workflowstudio.node.custom;

import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.app.workflowstudio.node.modules.http.HttpRequestModule;
import com.app.workflowstudio.service.HttpSender;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 모듈 실행 — 정의의 URL·헤더·바디 틀에 입력칸 값({{이름}})을 넣어 요청 하나를 보내고, 응답에서 값을 꺼낸다.
 * <ul>
 *   <li>URL: 값은 URL 인코딩 · 헤더: 줄바꿈 제거 · JSON 바디: 문자열 안에 들어가도록 이스케이프 · form: URL 인코딩</li>
 *   <li>입력칸 값 자체의 {@code ${...}} 는 노드 실행 때 먼저 치환됨 (앞 노드 출력 등)</li>
 * </ul>
 * 출력: {@code {ok, status, <꺼낸 값들>, response}} — {@code ${출력변수.꺼낸이름}} 으로 다음 노드에서 사용
 */
public class HttpModule implements NodeModule {

    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    private final CustomModuleDef def;
    private final NodeTypeInfo info;
    private final HttpSender sender;
    private final ObjectMapper om;

    HttpModule(CustomModuleDef def, NodeTypeInfo info, HttpSender sender, ObjectMapper om) {
        this.def = def;
        this.info = info;
        this.sender = sender;
        this.om = om;
    }

    @Override
    public NodeTypeInfo info() {
        return info;
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        CustomModuleDef.Http h = def.http;
        if (h == null || h.url == null || h.url.isBlank()) throw new Exception("모듈에 요청 주소가 없습니다 (🧩 모듈 관리에서 고치세요)");
        Map<String, String> values = values(def, ctx);

        String url = ctx.resolve(fill(h.url, values, Mode.URL)).trim();
        if (!url.matches("(?i)https?://.+")) throw new Exception("요청 주소는 http:// 또는 https:// 로 시작해야 합니다: " + url);
        List<HttpSender.Header> headers = HttpRequestModule.parseHeaders(ctx.resolve(fill(h.headers, values, Mode.HEADER)));
        String method = h.method == null || h.method.isBlank() ? "POST" : h.method.trim().toUpperCase();
        String bodyType = h.bodyType == null ? "none" : h.bodyType;
        String body = null;
        if (!"none".equals(bodyType) && !method.equals("GET") && !method.equals("HEAD")) {
            Mode m = switch (bodyType) {
                case "json" -> Mode.JSON;
                case "form" -> Mode.URL;
                default -> Mode.RAW;
            };
            body = ctx.resolve(fill(h.body, values, m));
            String ct = switch (bodyType) {
                case "json" -> "application/json; charset=UTF-8";
                case "form" -> "application/x-www-form-urlencoded; charset=UTF-8";
                default -> "text/plain; charset=UTF-8";
            };
            if (headers.stream().noneMatch(x -> x.key().equalsIgnoreCase("content-type")))
                headers.add(new HttpSender.Header("Content-Type", ct));
            if ("json".equals(bodyType)) {
                try {
                    om.readTree(body);
                } catch (Exception e) {
                    throw new Exception("모듈 바디가 올바른 JSON 이 아닙니다 — 문자열 값은 \"{{이름}}\" 처럼 따옴표 안에 쓰세요");
                }
            }
        }

        HttpSender.Result r = sender.send(method, url, headers, body, Duration.ofSeconds(Math.max(1, Math.min(300, h.timeoutSec))));
        boolean ok = r.status() >= 200 && r.status() < 300;
        JsonNode json = null;
        try {
            if (r.body() != null && !r.body().isBlank()) json = om.readTree(r.body());
        } catch (Exception ignore) {
            // JSON 이 아닌 응답 — 글자 그대로
        }
        if (!ok && h.failOnError) {
            String preview = r.body() == null ? "" : r.body().length() > 300 ? r.body().substring(0, 300) + "…" : r.body();
            throw new Exception("HTTP " + r.status() + " 응답 — " + method + " " + url + (preview.isBlank() ? "" : "\n" + preview));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("status", r.status());
        for (CustomModuleDef.Extract e : h.extract == null ? List.<CustomModuleDef.Extract>of() : h.extract) {
            if (e.name == null || e.name.isBlank()) continue;
            JsonNode v = json == null ? null : at(json, e.path);
            out.put(e.name.trim(), v == null || v.isMissingNode() || v.isNull() ? "" : v.isValueNode() ? v.asText() : v);
        }
        out.put("response", json != null ? json : r.body());
        return om.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    /** 입력칸 값 (노드 설정 → 없으면 기본값, ${...} 치환) */
    static Map<String, String> values(CustomModuleDef def, NodeContext ctx) {
        Map<String, String> v = new LinkedHashMap<>();
        for (CustomModuleDef.Param p : def.params) {
            if (p.name == null || p.name.isBlank()) continue;
            v.put(p.name.trim(), ctx.str(p.name.trim(), p.defaultValue == null ? "" : p.defaultValue));
        }
        return v;
    }

    enum Mode { URL, HEADER, JSON, RAW }

    /** 틀의 {{이름}} 을 값으로 — 없는 이름이면 오류 (오타 방지) */
    static String fill(String template, Map<String, String> values, Mode mode) throws Exception {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).trim();
            if (!values.containsKey(name))
                throw new Exception("모듈 틀에 없는 입력칸 {{" + name + "}} 이 있습니다 — 입력칸 이름: " + String.join(", ", values.keySet()));
            String v = values.get(name);
            String r = switch (mode) {
                case URL -> URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
                case HEADER -> v.replaceAll("[\\r\\n]+", " ");
                case JSON -> jsonInner(v);
                case RAW -> v;
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** JSON 문자열 안에 넣을 수 있게 (양쪽 따옴표 없이) */
    static String jsonInner(String v) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** "data.items[0].title" → JSON 값 */
    static JsonNode at(JsonNode root, String path) {
        if (path == null || path.isBlank()) return root;
        StringBuilder ptr = new StringBuilder();
        for (String part : path.trim().replaceAll("\\[(\\d+)]", ".$1").split("\\.")) {
            if (part.isEmpty()) continue;
            ptr.append('/').append(part.replace("~", "~0").replace("/", "~1"));
        }
        return root.at(JsonPointer.compile(ptr.toString()));
    }
}
