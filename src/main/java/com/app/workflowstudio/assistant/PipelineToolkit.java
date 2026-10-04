package com.app.workflowstudio.assistant;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.NodeParam;
import com.app.workflowstudio.node.NodeSocket;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.app.workflowstudio.node.NodeTypeRegistry;
import com.app.workflowstudio.service.PipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 파이프라인 도우미가 쓰는 도구 모음 — AI용 노드 설명서 만들기, AI 형식 ↔ 저장 형식 변환, 검증, 자동 배치.
 *
 * <pre>
 * AI 형식 (저장 형식보다 단순):
 * { "name": "...", "description": "...",
 *   "nodes": [ { "id": "hook", "type": "WEBHOOK", "config": { ... } } ],
 *   "connections": [ { "from": "hook", "to": "agent" }, { "from": "model", "to": "agent", "socket": "model" } ] }
 * </pre>
 * 비밀값(password 타입)은 AI에게 "***"로 보이고, AI가 "***"를 그대로 돌려주면 원래 값을 유지한다.
 */
@Component
public class PipelineToolkit {

    public static final String MASK = "***";
    /** 화면 표시 전용 파라미터 타입 — AI에게 보여 주지 않음 */
    static final Set<String> DISPLAY_TYPES =
            Set.of("webhookUrls", "memoryInfo", "httpOpen", "scheduleInfo", "outlineConnection");
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    /** 이만큼 작으면 설명서 전체를 시스템 프롬프트에 넣고, 크면 목록만 넣고 상세는 도구로 */
    static final int INLINE_SPEC_LIMIT = 14_000;

    private final NodeTypeRegistry registry;
    private final PipelineService pipelines;
    private final ObjectMapper om;

    public PipelineToolkit(NodeTypeRegistry registry, PipelineService pipelines, ObjectMapper om) {
        this.registry = registry;
        this.pipelines = pipelines;
        this.om = om;
    }

    // ── 설명서 ────────────────────────────────────────────

    /** 노드 타입 한 줄 목록 */
    public String catalogIndex() {
        StringBuilder sb = new StringBuilder();
        for (NodeTypeInfo t : registry.getAll()) {
            if (t.category() == null) continue;
            sb.append("- ").append(t.id()).append(" — ").append(t.label()).append(" [").append(t.category()).append("] ");
            if (t.trigger()) sb.append("트리거(시작 노드, 들어오는 화살표 없음)");
            else if (t.isPart()) sb.append("부품: '").append(t.provides()).append("' 소켓에 끼움 (화살표 흐름에는 안 들어감)");
            else sb.append("일반 노드");
            if (t.sockets() != null && !t.sockets().isEmpty()) {
                sb.append(", 소켓: ");
                List<String> s = new ArrayList<>();
                for (NodeSocket so : t.sockets())
                    s.add(so.id() + "(" + so.accepts() + (so.required() ? ", 필수" : "") + (so.multiple() ? ", 여러 개" : "") + ")");
                sb.append(String.join(", ", s));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 노드 타입 상세 설명 (설정 항목·기본값·선택지·표시 조건) */
    public List<Map<String, Object>> specs(Collection<String> typeIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : typeIds) {
            var module = registry.find(id);
            if (module == null) {
                out.add(Map.of("type", id, "error", "없는 노드 타입"));
                continue;
            }
            out.add(spec(module.info()));
        }
        return out;
    }

    public List<Map<String, Object>> allSpecs() {
        return registry.getAll().stream().filter(t -> t.category() != null).map(this::spec).toList();
    }

    private Map<String, Object> spec(NodeTypeInfo t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", t.id());
        m.put("label", t.label());
        m.put("kind", t.trigger() ? "trigger" : t.isPart() ? "part:" + t.provides() : "node");
        if (t.sockets() != null && !t.sockets().isEmpty()) {
            m.put("sockets", t.sockets().stream().map(s -> Map.of("id", s.id(), "accepts", s.accepts(),
                    "required", s.required(), "multiple", s.multiple(), "label", s.label())).toList());
        }
        List<Map<String, Object>> params = new ArrayList<>();
        for (NodeParam p : t.params()) {
            if (DISPLAY_TYPES.contains(p.type())) continue;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("label", p.label());
            pm.put("type", switch (p.type()) {
                case "select", "number", "textarea", "password" -> p.type();
                case "geminiModel", "outlineCollection", "webhookTemplate", "folder", "file", "image" -> "text";
                default -> "text";
            });
            if (p.defaultValue() != null && !"".equals(p.defaultValue())) pm.put("default", p.defaultValue());
            if (p.options() != null) pm.put("options", p.options());
            if (p.showWhen() != null) pm.put("onlyWhen", p.showWhen());
            params.add(pm);
        }
        m.put("config", params);
        return m;
    }

    /** 설명서 전체 JSON (작으면 시스템 프롬프트에 그대로) */
    public String allSpecsJson() {
        try {
            return om.writeValueAsString(allSpecs()).replace("\r", "");
        } catch (Exception e) {
            return "[]";
        }
    }

    // ── 저장 형식 → AI 형식 ─────────────────────────────────

    public Map<String, Object> toAi(Pipeline p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription() == null ? "" : p.getDescription());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PipelineNode n : p.getNodes()) {
            NodeTypeInfo t = info(n.getType());
            Map<String, Object> cfg = new LinkedHashMap<>();
            if (n.getConfig() != null) {
                for (Map.Entry<String, Object> e : n.getConfig().entrySet()) {
                    NodeParam param = t == null ? null : param(t, e.getKey());
                    if (param != null && DISPLAY_TYPES.contains(param.type())) continue;
                    boolean secret = param != null && "password".equals(param.type());
                    Object v = e.getValue();
                    cfg.put(e.getKey(), secret && v instanceof String s && !s.isBlank() ? MASK : v);
                }
            }
            Map<String, Object> nm = new LinkedHashMap<>();
            nm.put("id", n.getId());
            nm.put("type", n.getType());
            nm.put("config", cfg);
            nodes.add(nm);
        }
        m.put("nodes", nodes);
        List<Map<String, Object>> conns = new ArrayList<>();
        for (PipelineConnection c : p.getConnections()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("from", c.getFromNodeId());
            cm.put("to", c.getToNodeId());
            if (c.socket()) cm.put("socket", c.getToHandle());
            conns.add(cm);
        }
        m.put("connections", conns);
        return m;
    }

    /** 목록용 요약 */
    public Map<String, Object> summary(Pipeline p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription() == null ? "" : p.getDescription());
        m.put("nodes", p.getNodes().stream().map(n -> {
            Object name = n.getConfig() == null ? null : n.getConfig().get("name");
            return n.getType() + (name instanceof String s && !s.isBlank() ? "(" + s + ")" : "");
        }).toList());
        m.put("updatedAt", p.getUpdatedAt() == null ? null : p.getUpdatedAt().withNano(0).toString());
        return m;
    }

    // ── AI 형식 → 저장 형식 (검증 포함) ─────────────────────────

    /** 검증 결과: errors 가 있으면 저장하지 않고 AI에게 돌려줘 고치게 함 */
    public record Built(Pipeline pipeline, List<String> errors, List<String> warnings) {}

    /**
     * @param existing 수정이면 기존 파이프라인 (비밀값 "***" 복원, 노드 위치 유지), 새로 만들면 null
     */
    public Built build(JsonNode ai, Pipeline existing) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Pipeline p = new Pipeline();

        String name = ai.path("name").asText("").trim();
        if (name.isEmpty()) {
            if (existing != null) name = existing.getName();
            else errors.add("name(파이프라인 이름)이 비어 있습니다");
        }
        p.setName(name);
        p.setDescription(ai.has("description") ? ai.path("description").asText("")
                : existing != null ? existing.getDescription() : "");

        Map<String, PipelineNode> old = new HashMap<>();
        if (existing != null) for (PipelineNode n : existing.getNodes()) old.put(n.getId(), n);

        // 노드
        List<PipelineNode> nodes = new ArrayList<>();
        Map<String, NodeTypeInfo> types = new LinkedHashMap<>();
        JsonNode aiNodes = ai.path("nodes");
        if (!aiNodes.isArray() || aiNodes.isEmpty()) errors.add("nodes 가 비어 있습니다");
        for (JsonNode an : aiNodes) {
            String id = an.path("id").asText("").trim();
            String type = an.path("type").asText("").trim();
            if (!NODE_ID.matcher(id).matches()) {
                errors.add("노드 id '" + id + "'가 올바르지 않습니다 (영문·숫자·-·_ 1~40자)");
                continue;
            }
            if (types.containsKey(id)) {
                errors.add("노드 id '" + id + "'가 중복됩니다");
                continue;
            }
            NodeTypeInfo t = info(type);
            if (t == null || t.category() == null) {
                errors.add("노드 '" + id + "': 없는 타입 '" + type + "'");
                continue;
            }
            types.put(id, t);
            PipelineNode prev = old.get(id);
            if (prev != null && !prev.getType().equals(type)) prev = null;   // 타입이 바뀌면 새 노드 취급
            nodes.add(node(id, t, an.path("config"), prev, errors, warnings));
        }
        p.setNodes(nodes);

        // 연결
        List<PipelineConnection> conns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode ac : ai.path("connections")) {
            String from = ac.path("from").asText("").trim();
            String to = ac.path("to").asText("").trim();
            String socket = ac.path("socket").asText("").trim();
            String where = from + " → " + to + (socket.isEmpty() ? "" : " (소켓 " + socket + ")");
            NodeTypeInfo ft = types.get(from), tt = types.get(to);
            if (ft == null || tt == null) {
                errors.add("연결 " + where + ": 없는 노드 id");
                continue;
            }
            if (from.equals(to)) {
                errors.add("연결 " + where + ": 자기 자신으로 연결할 수 없습니다");
                continue;
            }
            if (socket.isEmpty()) {
                if (ft.isPart()) { errors.add("연결 " + where + ": 부품 노드(" + ft.id() + ")는 socket 을 지정해 소켓에 연결해야 합니다"); continue; }
                if (tt.trigger()) { errors.add("연결 " + where + ": 트리거(" + tt.id() + ")로 들어가는 화살표는 만들 수 없습니다"); continue; }
                if (tt.isPart()) { errors.add("연결 " + where + ": 부품 노드로는 화살표를 연결할 수 없습니다"); continue; }
            } else {
                NodeSocket s = tt.sockets() == null ? null
                        : tt.sockets().stream().filter(x -> x.id().equals(socket)).findFirst().orElse(null);
                if (s == null) { errors.add("연결 " + where + ": " + tt.id() + "에 '" + socket + "' 소켓이 없습니다"); continue; }
                if (!ft.isPart() || !s.accepts().equals(ft.provides())) {
                    errors.add("연결 " + where + ": '" + socket + "' 소켓에는 " + s.accepts() + " 부품만 끼울 수 있습니다 ("
                            + ft.id() + " 는 " + (ft.isPart() ? ft.provides() : "부품 아님") + ")");
                    continue;
                }
            }
            if (!seen.add(from + "|" + to + "|" + socket)) continue;
            conns.add(new PipelineConnection(from, to, socket.isEmpty() ? null : socket));
        }
        p.setConnections(conns);

        // 소켓 규칙 (필수 / 하나만)
        for (Map.Entry<String, NodeTypeInfo> e : types.entrySet()) {
            NodeTypeInfo t = e.getValue();
            if (t.sockets() == null) continue;
            for (NodeSocket s : t.sockets()) {
                long n = conns.stream().filter(c -> c.getToNodeId().equals(e.getKey()) && s.id().equals(c.getToHandle())).count();
                if (s.required() && n == 0) errors.add("노드 '" + e.getKey() + "': 필수 소켓 '" + s.id() + "'에 " + s.accepts() + " 부품을 연결하세요");
                if (!s.multiple() && n > 1) errors.add("노드 '" + e.getKey() + "': 소켓 '" + s.id() + "'에는 부품을 하나만 연결할 수 있습니다");
            }
            if (t.isPart() && conns.stream().noneMatch(c -> c.getFromNodeId().equals(e.getKey())))
                warnings.add("부품 노드 '" + e.getKey() + "'가 어느 소켓에도 연결되지 않았습니다");
        }
        if (types.values().stream().noneMatch(NodeTypeInfo::trigger))
            warnings.add("트리거 노드가 없습니다 — 툴바의 ▶ 실행으로만 실행됩니다");

        checkWebhookPaths(p, existing, errors);
        layout(p, old);
        return new Built(p, errors, warnings);
    }

    private PipelineNode node(String id, NodeTypeInfo t, JsonNode cfgIn, PipelineNode prev,
                              List<String> errors, List<String> warnings) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        Map<String, Object> prevCfg = prev != null && prev.getConfig() != null ? prev.getConfig() : Map.of();
        Set<String> known = new HashSet<>();
        for (NodeParam p : t.params()) known.add(p.name());
        if (cfgIn.isObject()) {
            cfgIn.fieldNames().forEachRemaining(k -> {
                if (!known.contains(k)) errors.add("노드 '" + id + "' (" + t.id() + "): 없는 설정 '" + k + "' — 가능한 설정: "
                        + String.join(", ", t.params().stream().filter(p -> !DISPLAY_TYPES.contains(p.type())).map(NodeParam::name).toList()));
            });
        }
        for (NodeParam p : t.params()) {
            JsonNode v = cfgIn.path(p.name());
            Object value;
            if (DISPLAY_TYPES.contains(p.type())) {
                value = prevCfg.getOrDefault(p.name(), p.defaultValue() == null ? "" : p.defaultValue());
            } else if (v.isMissingNode() || v.isNull()) {
                value = prevCfg.containsKey(p.name()) ? prevCfg.get(p.name())
                        : p.defaultValue() != null ? p.defaultValue() : "number".equals(p.type()) ? 0 : "";
            } else if ("password".equals(p.type()) && MASK.equals(v.asText())) {
                value = prevCfg.getOrDefault(p.name(), "");   // 가려진 비밀값 → 원래 값 유지
            } else if ("number".equals(p.type())) {
                if (v.isNumber()) value = v.numberValue();
                else {
                    try {
                        value = Double.parseDouble(v.asText().trim());
                        if (((Double) value) == Math.rint((Double) value)) value = ((Double) value).intValue();
                    } catch (NumberFormatException e) {
                        errors.add("노드 '" + id + "': '" + p.name() + "'는 숫자여야 합니다 (받은 값: " + v.asText() + ")");
                        value = p.defaultValue();
                    }
                }
            } else {
                value = v.isTextual() ? v.asText() : v.toString();
            }
            if ("select".equals(p.type()) && p.options() != null && !p.options().contains(String.valueOf(value))) {
                errors.add("노드 '" + id + "': '" + p.name() + "' 값 '" + value + "'은(는) 선택지에 없습니다 — 다음 중 하나를 그대로 쓰세요: "
                        + String.join(" | ", p.options()));
            }
            if ("password".equals(p.type()) && (value == null || String.valueOf(value).isBlank()))
                warnings.add("노드 '" + id + "': " + p.label() + " 이(가) 비어 있음 — 노드에 직접 넣거나 ⚙ 설정의 공용 키를 사용");
            cfg.put(p.name(), value);
        }
        PipelineNode n = new PipelineNode();
        n.setId(id);
        n.setType(t.id());
        n.setConfig(cfg);
        if (prev != null) {
            n.setPosX(prev.getPosX());
            n.setPosY(prev.getPosY());
        } else {
            n.setPosX(Double.NaN);   // 자동 배치 대상 표시
        }
        return n;
    }

    /** 웹훅 경로는 모든 파이프라인에서 겹치면 안 됨 */
    private void checkWebhookPaths(Pipeline p, Pipeline existing, List<String> errors) {
        Map<String, String> used = new HashMap<>();
        for (Pipeline other : pipelines.findAll()) {
            if (existing != null && other.getId().equals(existing.getId())) continue;
            for (PipelineNode n : other.getNodes()) {
                if (!"WEBHOOK".equals(n.getType())) continue;
                used.put(webhookPath(n), other.getName());
            }
        }
        for (PipelineNode n : p.getNodes()) {
            if (!"WEBHOOK".equals(n.getType())) continue;
            String path = webhookPath(n);
            if (used.containsKey(path))
                errors.add("웹훅 경로 '" + path + "'는 파이프라인 '" + used.get(path) + "'에서 이미 사용 중입니다 — 다른 path 를 쓰세요");
        }
    }

    private static String webhookPath(PipelineNode n) {
        Object v = n.getConfig() == null ? null : n.getConfig().get("path");
        return v instanceof String s && !s.isBlank() ? s.trim() : n.getId();
    }

    /**
     * 위치가 없는 노드만 자동 배치: 흐름 노드는 화살표 깊이별 열(가로 340px), 부품은 연결된 노드 아래.
     */
    void layout(Pipeline p, Map<String, PipelineNode> old) {
        Map<String, PipelineNode> byId = new LinkedHashMap<>();
        for (PipelineNode n : p.getNodes()) byId.put(n.getId(), n);
        Map<String, Integer> depth = new HashMap<>();
        List<PipelineConnection> flow = p.getConnections().stream().filter(c -> !c.socket()).toList();
        for (PipelineNode n : p.getNodes()) depth.put(n.getId(), 0);
        for (int i = 0; i < byId.size(); i++) {   // 가장 긴 경로 깊이 (순환이 있어도 노드 수만큼만 반복)
            for (PipelineConnection c : flow) {
                int d = depth.getOrDefault(c.getFromNodeId(), 0) + 1;
                if (d > depth.getOrDefault(c.getToNodeId(), 0) && d < byId.size() + 1) depth.put(c.getToNodeId(), d);
            }
        }
        Map<Integer, Integer> rowsUsed = new HashMap<>();
        double maxOldY = old.values().stream().mapToDouble(PipelineNode::getPosY).max().orElse(0);
        boolean hasOld = !old.isEmpty();
        // 깊이 순으로 처리해야 앞 노드 위치가 먼저 정해짐
        List<PipelineNode> order = new ArrayList<>(p.getNodes());
        order.sort(Comparator.comparingInt(n -> depth.getOrDefault(n.getId(), 0)));
        for (PipelineNode n : order) {
            NodeTypeInfo t = info(n.getType());
            if (!Double.isNaN(n.getPosX()) || (t != null && t.isPart())) continue;
            // 위치가 정해진 앞 노드가 있으면 그 오른쪽 (자리가 차 있으면 아래로)
            PipelineNode prev = flow.stream().filter(c -> c.getToNodeId().equals(n.getId()))
                    .map(c -> byId.get(c.getFromNodeId()))
                    .filter(x -> x != null && !Double.isNaN(x.getPosX())).findFirst().orElse(null);
            if (prev != null) {
                double x = prev.getPosX() + 340, y = prev.getPosY();
                while (occupied(p, n, x, y)) y += 180;
                n.setPosX(x);
                n.setPosY(y);
                continue;
            }
            int d = depth.get(n.getId());
            int row = rowsUsed.merge(d, 1, Integer::sum) - 1;
            n.setPosX(40 + d * 340);
            n.setPosY((hasOld ? maxOldY + 360 : 140) + row * 420);
        }
        // 부품: 연결된 노드 아래에 나란히
        Map<String, Integer> partIndex = new HashMap<>();
        for (PipelineNode n : p.getNodes()) {
            if (!Double.isNaN(n.getPosX())) continue;
            PipelineConnection c = p.getConnections().stream()
                    .filter(x -> x.socket() && x.getFromNodeId().equals(n.getId())).findFirst().orElse(null);
            PipelineNode host = c == null ? null : byId.get(c.getToNodeId());
            if (host == null || Double.isNaN(host.getPosX())) {
                n.setPosX(40);
                n.setPosY(maxOldY + 600);
                continue;
            }
            int i = partIndex.merge(host.getId(), 1, Integer::sum) - 1;
            n.setPosX(host.getPosX() - 80 + i * 210);
            n.setPosY(host.getPosY() + 240);
        }
    }

    // ── helpers ──

    /** (x, y) 근처에 이미 다른 노드가 있는지 (노드 카드 크기 기준) */
    private static boolean occupied(Pipeline p, PipelineNode self, double x, double y) {
        for (PipelineNode o : p.getNodes()) {
            if (o == self || Double.isNaN(o.getPosX())) continue;
            if (Math.abs(o.getPosX() - x) < 260 && Math.abs(o.getPosY() - y) < 150) return true;
        }
        return false;
    }

    NodeTypeInfo info(String type) {
        var m = type == null ? null : registry.find(type);
        return m == null ? null : m.info();
    }

    private static NodeParam param(NodeTypeInfo t, String name) {
        for (NodeParam p : t.params()) if (p.name().equals(name)) return p;
        return null;
    }
}
