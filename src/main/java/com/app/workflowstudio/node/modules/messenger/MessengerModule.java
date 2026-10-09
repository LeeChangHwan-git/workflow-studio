package com.app.workflowstudio.node.modules.messenger;

import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeParam;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.app.workflowstudio.node.modules.http.HttpRequestModule;
import com.app.workflowstudio.settings.AppSettings;
import com.app.workflowstudio.settings.SettingsService;
import com.app.workflowstudio.service.HttpSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * 사내 메신저 쪽지 — 사번과 메시지를 받아 메신저 API(HTTP POST)로 보낸다.
 * 주소를 비우면 이 앱에 들어 있는 시연용 메신저(/demo-messenger)로 보내서 이 PC 화면에 쪽지 창이 뜬다.
 * 회사 메신저에 붙일 때는 주소·바디 형식·인증 헤더만 바꾸면 된다.
 * 출력: {sent, failed, results:[{empId, status, ok, response}], message}
 */
@Component
@Order(320)
public class MessengerModule implements NodeModule {

    public static final String TYPE = "MESSENGER";

    static final String BODY_DEFAULT = "기본 JSON {empId, message, sender}";
    static final String BODY_CUSTOM = "직접 구성 (회사 메신저 형식)";
    static final String FAIL_STOP = "오류로 중단";
    static final String FAIL_CONTINUE = "나머지는 계속 보내고 결과에 기록";
    static final String DEMO_PATH = "/demo-messenger/api/notes";

    private final HttpSender sender;
    private final Environment env;
    private final ObjectMapper om;

    public MessengerModule(HttpSender sender, Environment env, ObjectMapper om) {
        this.sender = sender;
        this.env = env;
        this.om = om;
    }

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "사내 메신저 쪽지", "✉️", "#3B4CCA", "통합", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("empIds", "받는 사람 — 사번 또는 @그룹 (예: 20231234, @FW팀 · 쉼표로 여러 개 · ${...} 사용 가능)",
                        "text", "", null),
                new NodeParam("groupsInfo", "메신저 그룹 (⚙ 설정 → 👥 메신저 그룹)", "messengerGroups", null, null),
                new NodeParam("message", "메시지 (${input} = 앞 노드 출력)", "textarea", "${input}", null),
                new NodeParam("sender", "보내는 사람 이름", "text", "Workflow Studio", null),
                new NodeParam("endpoint", "메신저 API 주소 — 비우면 이 PC의 시연용 메신저 (쪽지 창이 뜸 · 받은 쪽지함 /demo-messenger)",
                        "text", "", null),
                new NodeParam("bodyType", "요청 바디", "select", BODY_DEFAULT, List.of(BODY_DEFAULT, BODY_CUSTOM)),
                new NodeParam("bodyTemplate",
                        "바디 형식 — ${empId} ${message} ${sender} 는 JSON 안전하게 들어감 (그 외 ${...}도 가능)",
                        "textarea", "{\"receiver\": \"${empId}\", \"content\": \"${message}\", \"from\": \"${sender}\"}",
                        null, "bodyType=" + BODY_CUSTOM),
                new NodeParam("headers", "요청 헤더 — 인증이 필요하면 \"Authorization: Bearer …\" (한 줄에 하나)",
                        "secretTextarea", "", null),
                new NodeParam("onFail", "보내기 실패하면", "select", FAIL_STOP, List.of(FAIL_STOP, FAIL_CONTINUE)),
                new NodeParam("outputVar", "출력 변수명", "text", "messenger", null)
        ));
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        List<String> ids = new ArrayList<>();
        List<String> usedGroups = new ArrayList<>();
        for (String s : ctx.str("empIds", "").split("[,;\\s]+")) {
            String id = s.trim();
            if (id.isEmpty()) continue;
            if (id.contains("${")) throw new Exception("사번에 해석되지 않은 변수가 있습니다: " + id);
            if (id.startsWith("@")) {   // @그룹 → ⚙ 설정의 메신저 그룹 사번들
                AppSettings.Group g = findGroup(id.substring(1));
                if (g == null) throw new Exception("메신저 그룹이 없습니다: " + id + " (⚙ 설정 → 👥 메신저 그룹에서 만들기)"
                        + (groupNames().isEmpty() ? "" : " — 있는 그룹: " + String.join(", ", groupNames())));
                if (g.memberIds().isEmpty()) throw new Exception("메신저 그룹 @" + g.name + " 에 사번이 없습니다");
                usedGroups.add("@" + g.name);
                for (String m : g.memberIds()) if (!ids.contains(m)) ids.add(m);
                continue;
            }
            if (!ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) throw new Exception("받는 사람 사번을 입력하세요");
        if (ids.size() > 100) throw new Exception("한 번에 100명까지 보낼 수 있습니다 (" + ids.size() + "명)");

        String message = ctx.str("message", "").strip();
        if (message.isEmpty()) throw new Exception("보낼 메시지가 비어 있습니다");
        String from = ctx.str("sender", "Workflow Studio").strip();

        String endpoint = ctx.str("endpoint", "").trim();
        boolean demo = endpoint.isEmpty();
        if (demo) endpoint = "http://127.0.0.1:" + port() + DEMO_PATH;
        List<HttpSender.Header> headers = HttpRequestModule.parseHeaders(ctx.str("headers", ""));
        if (headers.stream().noneMatch(h -> h.key().equalsIgnoreCase("content-type")))
            headers.add(new HttpSender.Header("Content-Type", "application/json; charset=UTF-8"));
        boolean custom = BODY_CUSTOM.equals(ctx.raw("bodyType", BODY_DEFAULT));
        boolean stopOnFail = !FAIL_CONTINUE.equals(ctx.raw("onFail", FAIL_STOP));

        List<Map<String, Object>> results = new ArrayList<>();
        int sent = 0;
        for (String id : ids) {
            String body = custom ? customBody(ctx, id, message, from)
                    : om.writeValueAsString(Map.of("empId", id, "message", message, "sender", from));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("empId", id);
            try {
                HttpSender.Result res = sender.send("POST", endpoint, headers, body, Duration.ofSeconds(15));
                boolean ok = res.status() >= 200 && res.status() < 300;
                r.put("status", res.status());
                r.put("ok", ok);
                r.put("response", parse(res.body()));
                if (ok) sent++;
                else if (stopOnFail) throw new Exception("사번 " + id + " 에게 보내기 실패 — HTTP " + res.status() + " " + abbreviate(res.body()));
            } catch (java.net.ConnectException e) {
                throw new Exception("메신저 서버에 연결할 수 없습니다: " + endpoint);
            } catch (java.net.http.HttpTimeoutException e) {
                if (stopOnFail) throw new Exception("메신저 서버 응답이 없습니다 (15초): " + endpoint);
                r.put("ok", false);
                r.put("error", "응답 없음 (15초)");
            }
            results.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", sent);
        out.put("failed", ids.size() - sent);
        out.put("demo", demo);
        if (!usedGroups.isEmpty()) out.put("groups", usedGroups);
        out.put("results", results);
        out.put("message", message);
        return om.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    static AppSettings.Group findGroup(String name) {
        AppSettings s = SettingsService.current();
        if (s == null || s.messenger == null) return null;
        String key = name.trim();
        return s.messenger.groups.stream().filter(g -> g.name.equalsIgnoreCase(key)).findFirst().orElse(null);
    }

    static List<String> groupNames() {
        AppSettings s = SettingsService.current();
        if (s == null || s.messenger == null) return List.of();
        return s.messenger.groups.stream().map(g -> "@" + g.name).toList();
    }

    /** 직접 구성 바디: ${empId} ${message} ${sender} 는 JSON 문자열 안에 들어가도록 이스케이프해서 먼저 넣고, 나머지 ${...}는 평소대로 */
    private String customBody(NodeContext ctx, String empId, String message, String from) throws Exception {
        String tpl = ctx.raw("bodyTemplate", "");
        if (tpl.isBlank()) throw new Exception("바디 형식을 입력하세요");
        String t = tpl.replace("${empId}", jsonInner(empId)).replace("${message}", jsonInner(message)).replace("${sender}", jsonInner(from));
        String body = ctx.resolve(t);
        try {
            om.readTree(body);
        } catch (Exception e) {
            throw new Exception("바디 형식이 올바른 JSON 이 아닙니다: " + abbreviate(body));
        }
        return body;
    }

    /** JSON 문자열의 따옴표 안에 넣을 수 있게 (\" \n 등) — 바깥 따옴표는 템플릿에 있음 */
    private String jsonInner(String s) throws Exception {
        String q = om.writeValueAsString(s);
        return q.substring(1, q.length() - 1);
    }

    private String port() {
        String p = env.getProperty("local.server.port");
        return p != null ? p : env.getProperty("server.port", "8080");
    }

    private Object parse(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            return om.readValue(body, Object.class);
        } catch (Exception e) {
            return abbreviate(body);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
