package com.app.workflowstudio.node.custom;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.*;
import com.app.workflowstudio.service.HttpSender;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.app.workflowstudio.service.PipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 내 모듈 — 저장({storage}/modules/*.json), 노드로 등록, 내보내기/가져오기(.wbmod), 파이프라인에서 만들기.
 * 노드 타입 등록부({@link NodeTypeRegistry})가 기본 모듈 다음에 여기 모듈을 붙여 팔레트·실행에 씀.
 */
@Service
public class CustomModuleService {

    public static final String FORMAT = "worKBench-module";
    public static final int FORMAT_VERSION = 1;
    static final Pattern NAME = Pattern.compile("[^\\s{}$,]{1,40}");
    static final Set<String> PARAM_TYPES = Set.of("text", "textarea", "number", "select", "password");
    /** 내보낼 때 값을 비우는 헤더 이름 (토큰·쿠키) */
    static final Pattern SECRET_HEADER = Pattern.compile("(?i)authorization|cookie|token|api[-_]?key|secret|password");

    private final Path dir;
    private final ObjectMapper om;
    private final HttpSender http;
    private final PipelineService pipelines;
    private final ObjectProvider<PipelineRunnerService> runner;
    private final ObjectProvider<NodeTypeRegistry> registry;
    private final Map<String, CustomModuleDef> defs = new ConcurrentHashMap<>();
    private final Map<String, NodeModule> modules = new ConcurrentHashMap<>();

    public CustomModuleService(Path storageDirectory, ObjectMapper om, HttpSender http, PipelineService pipelines,
                               ObjectProvider<PipelineRunnerService> runner, ObjectProvider<NodeTypeRegistry> registry) throws IOException {
        this.dir = storageDirectory.resolve("modules");
        this.om = om;
        this.http = http;
        this.pipelines = pipelines;
        this.runner = runner;
        this.registry = registry;
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    CustomModuleDef d = om.readValue(f.toFile(), CustomModuleDef.class);
                    if (d.id != null) put(d);
                } catch (Exception ignore) {
                    // 깨진 파일은 건너뜀
                }
            });
        }
    }

    // ── 조회 ──

    public List<CustomModuleDef> list() {
        return defs.values().stream().sorted(Comparator.comparing((CustomModuleDef d) -> d.label == null ? "" : d.label)).toList();
    }

    public CustomModuleDef get(String id) {
        return defs.get(id);
    }

    /** 등록부용 — 내 모듈 노드들 */
    public Collection<NodeModule> modules() {
        return list().stream().map(d -> modules.get(d.id)).filter(Objects::nonNull).toList();
    }

    public NodeModule find(String id) {
        return modules.get(id);
    }

    /** 이 모듈을 쓰는 파이프라인 이름 */
    public List<Map<String, String>> usedIn(String typeId) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Pipeline p : pipelines.findAll()) {
            if (p.getNodes() != null && p.getNodes().stream().anyMatch(n -> typeId.equals(n.getType())))
                out.add(Map.of("id", p.getId(), "name", p.getName() == null ? "" : p.getName()));
        }
        // 다른 내 모듈 안에서 쓰는 경우
        for (CustomModuleDef d : defs.values()) {
            if (d.pipeline != null && d.pipeline.nodes.stream().anyMatch(n -> typeId.equals(n.getType())))
                out.add(Map.of("id", "module:" + d.id, "name", "🧩 " + d.label + " (모듈 안)"));
        }
        return out;
    }

    // ── 저장 · 삭제 ──

    /** 새로 만들기 / 고치기 (id 가 있으면 고치기) */
    public synchronized CustomModuleDef save(CustomModuleDef d) throws Exception {
        boolean isNew = d.id == null || d.id.isBlank() || !defs.containsKey(d.id);
        if (d.id == null || d.id.isBlank()) d.id = newId();
        if (!d.id.matches("MOD_[A-Za-z0-9_]{1,40}")) throw new IllegalArgumentException("모듈 id 형식이 잘못됐습니다: " + d.id);
        if (isNew && registry.getObject().builtin(d.id) != null) throw new IllegalArgumentException("기본 모듈과 id 가 겹칩니다: " + d.id);
        validate(d);
        String now = LocalDateTime.now().withNano(0).toString();
        CustomModuleDef old = defs.get(d.id);
        d.createdAt = old != null && old.createdAt != null ? old.createdAt : d.createdAt != null ? d.createdAt : now;
        d.updatedAt = now;
        om.writerWithDefaultPrettyPrinter().writeValue(file(d.id).toFile(), d);
        put(d);
        return d;
    }

    public synchronized boolean delete(String id) throws IOException {
        if (defs.remove(id) == null) return false;
        modules.remove(id);
        Files.deleteIfExists(file(id));
        return true;
    }

    /** 파이프라인 모듈: 원본 파이프라인에서 안쪽 노드를 다시 가져오기 */
    public CustomModuleDef refreshFromSource(String id) throws Exception {
        CustomModuleDef d = defs.get(id);
        if (d == null) throw new IllegalArgumentException("모듈이 없습니다");
        if (!CustomModuleDef.KIND_PIPELINE.equals(d.kind) || d.pipeline == null)
            throw new IllegalArgumentException("파이프라인 모듈만 원본에서 다시 가져올 수 있습니다");
        Pipeline src = pipelines.findById(d.pipeline.sourcePipelineId);
        if (src == null) throw new IllegalArgumentException("원본 파이프라인이 없습니다 (다른 PC 에서 가져온 모듈이거나 삭제됨)");
        CustomModuleDef copy = om.convertValue(d, CustomModuleDef.class);
        snapshot(copy, src);
        return save(copy);
    }

    /** 원본 파이프라인의 노드를 모듈 안으로 복사 (트리거 노드와 그 화살표는 뺌) */
    public void snapshot(CustomModuleDef d, Pipeline src) {
        Set<String> triggers = new HashSet<>();
        List<PipelineNode> nodes = new ArrayList<>();
        for (PipelineNode n : src.getNodes() == null ? List.<PipelineNode>of() : src.getNodes()) {
            NodeModule m = registry.getObject().find(n.getType());
            if (m != null && m.info().trigger()) { triggers.add(n.getId()); continue; }
            nodes.add(om.convertValue(n, PipelineNode.class));
        }
        List<PipelineConnection> conns = new ArrayList<>();
        for (PipelineConnection c : src.getConnections() == null ? List.<PipelineConnection>of() : src.getConnections()) {
            if (triggers.contains(c.getFromNodeId()) || triggers.contains(c.getToNodeId())) continue;
            conns.add(om.convertValue(c, PipelineConnection.class));
        }
        if (d.pipeline == null) d.pipeline = new CustomModuleDef.Sub();
        d.pipeline.sourcePipelineId = src.getId();
        d.pipeline.sourceName = src.getName();
        d.pipeline.nodes = nodes;
        d.pipeline.connections = conns;
        if (d.pipeline.outputNodeId != null && nodes.stream().noneMatch(n -> n.getId().equals(d.pipeline.outputNodeId)))
            d.pipeline.outputNodeId = null;
    }

    // ── 내보내기 · 가져오기 ──

    /** .wbmod 파일 내용 — 비밀 입력칸 기본값·토큰 헤더·안쪽 노드의 비밀 설정은 비움 */
    public ObjectNode export(String id) {
        CustomModuleDef d = defs.get(id);
        if (d == null) throw new IllegalArgumentException("모듈이 없습니다");
        CustomModuleDef copy = om.convertValue(d, CustomModuleDef.class);
        List<String> removed = new ArrayList<>();
        for (CustomModuleDef.Param p : copy.params) {
            if ("password".equals(p.type) && p.defaultValue != null && !p.defaultValue.isBlank()) {
                p.defaultValue = "";
                removed.add("입력칸 '" + p.label + "' 기본값");
            }
        }
        if (copy.http != null && copy.http.headers != null) {
            StringBuilder sb = new StringBuilder();
            for (String line : copy.http.headers.split("\\r?\\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && SECRET_HEADER.matcher(line.substring(0, colon)).find() && !line.contains("{{")) {
                    sb.append(line, 0, colon + 1).append(" \n");
                    removed.add("헤더 '" + line.substring(0, colon).trim() + "' 값");
                } else sb.append(line).append('\n');
            }
            copy.http.headers = sb.toString().stripTrailing();
        }
        if (copy.pipeline != null) {
            for (PipelineNode n : copy.pipeline.nodes) {
                NodeModule m = registry.getObject().find(n.getType());
                if (m == null || n.getConfig() == null) continue;
                for (NodeParam p : m.info().params()) {
                    if (("password".equals(p.type()) || "secretTextarea".equals(p.type()))
                            && n.getConfig().get(p.name()) instanceof String s && !s.isBlank()) {
                        n.getConfig().put(p.name(), "");
                        removed.add("안쪽 노드 '" + n.getConfig().getOrDefault("name", n.getType()) + "' 의 " + p.label());
                    }
                }
            }
            copy.pipeline.sourcePipelineId = null;    // 다른 PC 에는 없는 id
        }
        ObjectNode root = om.createObjectNode();
        root.put("format", FORMAT);
        root.put("formatVersion", FORMAT_VERSION);
        root.put("exportedAt", LocalDateTime.now().withNano(0).toString());
        if (!removed.isEmpty()) root.set("secretsRemoved", om.valueToTree(removed));
        root.set("module", om.valueToTree(copy));
        return root;
    }

    public record ImportResult(CustomModuleDef module, boolean replaced, List<String> missingTypes) {}

    /**
     * .wbmod 가져오기. 같은 id 의 모듈이 있으면 overwrite=false 면 IllegalStateException(충돌), true 면 바꿔 씀.
     * asCopy=true 면 새 id 로 하나 더 만듦.
     */
    public synchronized ImportResult importModule(byte[] data, boolean overwrite, boolean asCopy) throws Exception {
        JsonNode root;
        try {
            root = om.readTree(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("모듈 파일(.wbmod)을 읽을 수 없습니다 — JSON 이 아닙니다");
        }
        if (!FORMAT.equals(root.path("format").asText()) || !root.has("module"))
            throw new IllegalArgumentException("worKBench 모듈 파일이 아닙니다 (format: " + root.path("format").asText("없음") + ")");
        if (root.path("formatVersion").asInt(1) > FORMAT_VERSION)
            throw new IllegalArgumentException("더 새 버전의 앱에서 만든 모듈입니다 (파일 형식 " + root.path("formatVersion").asInt() + ")");
        CustomModuleDef d = om.treeToValue(root.get("module"), CustomModuleDef.class);
        boolean exists = d.id != null && defs.containsKey(d.id);
        if (asCopy || d.id == null || !d.id.matches("MOD_[A-Za-z0-9_]{1,40}")) {
            d.id = newId();
            if (asCopy) d.label = d.label + " (사본)";
            exists = false;
        } else if (exists && !overwrite) {
            throw new IllegalStateException(defs.get(d.id).label);
        }
        d.createdAt = null;
        List<String> missing = new ArrayList<>();
        if (d.pipeline != null) {
            for (PipelineNode n : d.pipeline.nodes) {
                if (registry.getObject().find(n.getType()) == null && !missing.contains(n.getType())) missing.add(n.getType());
            }
        }
        return new ImportResult(save(d), exists, missing);
    }

    // ── 검증 · 등록 ──

    void validate(CustomModuleDef d) {
        if (d.label == null || d.label.isBlank()) throw new IllegalArgumentException("모듈 이름을 입력하세요");
        d.label = d.label.trim();
        if (d.label.length() > 40) throw new IllegalArgumentException("모듈 이름은 40자까지");
        if (d.icon == null || d.icon.isBlank()) d.icon = "🧩";
        if (d.color == null || !d.color.matches("#[0-9A-Fa-f]{6}")) d.color = "#7C3AED";
        if (d.category == null || d.category.isBlank()) d.category = "내 모듈";
        if (d.params == null) d.params = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (CustomModuleDef.Param p : d.params) {
            p.name = p.name == null ? "" : p.name.trim();
            if (!NAME.matcher(p.name).matches())
                throw new IllegalArgumentException("입력칸 이름 '" + p.name + "' — 공백·중괄호·$·쉼표 없이 40자까지");
            if (p.name.equals("name") || p.name.equals("outputVar"))
                throw new IllegalArgumentException("입력칸 이름으로 '" + p.name + "' 은 쓸 수 없습니다 (노드 이름·출력 변수명과 겹침)");
            if (!names.add(p.name)) throw new IllegalArgumentException("입력칸 이름이 겹칩니다: " + p.name);
            if (p.label == null || p.label.isBlank()) p.label = p.name;
            if (!PARAM_TYPES.contains(p.type)) p.type = "text";
            if (p.options == null) p.options = new ArrayList<>();
            if ("select".equals(p.type) && p.options.isEmpty())
                throw new IllegalArgumentException("선택형 입력칸 '" + p.label + "' 에 고를 값이 없습니다");
            if (p.defaultValue == null) p.defaultValue = "";
        }
        if (d.outputVar == null || d.outputVar.isBlank()) d.outputVar = defaultVar(d);
        if (CustomModuleDef.KIND_HTTP.equals(d.kind)) {
            if (d.http == null) throw new IllegalArgumentException("요청 설정이 없습니다");
            if (d.http.url == null || !d.http.url.trim().matches("(?i)https?://.+"))
                throw new IllegalArgumentException("요청 주소는 http:// 또는 https:// 로 시작해야 합니다");
            d.http.method = d.http.method == null ? "POST" : d.http.method.trim().toUpperCase();
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(d.http.method))
                throw new IllegalArgumentException("메소드는 GET·POST·PUT·PATCH·DELETE 중 하나");
            if (!Set.of("none", "json", "text", "form").contains(d.http.bodyType)) d.http.bodyType = "json";
            if (d.http.extract == null) d.http.extract = new ArrayList<>();
            d.http.extract.removeIf(e -> e == null || e.name == null || e.name.isBlank());
            // 틀에 없는 입력칸 이름을 쓰면 저장 때 바로 알려 줌
            String all = d.http.url + "\n" + (d.http.headers == null ? "" : d.http.headers) + "\n" + (d.http.body == null ? "" : d.http.body);
            var m = HttpModule.PLACEHOLDER.matcher(all);
            while (m.find()) {
                if (!names.contains(m.group(1).trim()))
                    throw new IllegalArgumentException("틀에 쓴 {{" + m.group(1).trim() + "}} 에 해당하는 입력칸이 없습니다");
            }
            d.pipeline = null;
        } else if (CustomModuleDef.KIND_PIPELINE.equals(d.kind)) {
            if (d.pipeline == null || d.pipeline.nodes == null || d.pipeline.nodes.isEmpty())
                throw new IllegalArgumentException("모듈로 만들 파이프라인에 (트리거 말고) 노드가 없습니다");
            Set<String> ids = new HashSet<>();
            for (PipelineNode n : d.pipeline.nodes) {
                ids.add(n.getId());
                if (d.id != null && d.id.equals(n.getType())) throw new IllegalArgumentException("모듈 안에 자기 자신을 넣을 수 없습니다");
            }
            for (CustomModuleDef.Param p : d.params) {
                if (p.target != null && (p.target.nodeId == null || p.target.nodeId.isBlank())) p.target = null;
                if (p.target != null && !ids.contains(p.target.nodeId))
                    throw new IllegalArgumentException("입력칸 '" + p.label + "' 이 연결된 노드가 모듈 안에 없습니다");
            }
            if (d.pipeline.connections == null) d.pipeline.connections = new ArrayList<>();
            d.http = null;
        } else {
            throw new IllegalArgumentException("모듈 종류는 http 또는 pipeline");
        }
    }

    private static String defaultVar(CustomModuleDef d) {
        String s = d.label.replaceAll("[^0-9A-Za-z가-힣_]", "");
        return s.isEmpty() ? "module" : s;
    }

    private void put(CustomModuleDef d) {
        defs.put(d.id, d);
        modules.put(d.id, build(d));
    }

    /** 저장하지 않고 노드로 만들기 (시험 실행용) */
    public NodeModule buildDetached(CustomModuleDef d) {
        return build(d);
    }

    private NodeModule build(CustomModuleDef d) {
        List<NodeParam> params = new ArrayList<>();
        params.add(new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null));
        for (CustomModuleDef.Param p : d.params) {
            String type = p.type == null ? "text" : p.type;
            Object def = "number".equals(type) && p.defaultValue != null && p.defaultValue.matches("-?\\d+") ? Integer.parseInt(p.defaultValue) : p.defaultValue;
            params.add(new NodeParam(p.name, p.label, type, def, "select".equals(type) ? p.options : null));
        }
        params.add(new NodeParam("outputVar", "출력 변수명", "text", d.outputVar, null));
        NodeTypeInfo info = new NodeTypeInfo(d.id, d.label, d.icon, d.color, d.category, params);
        return CustomModuleDef.KIND_PIPELINE.equals(d.kind)
                ? new PipelineModule(d, info, runner, om, type -> {
                    NodeModule m = registry.getObject().find(type);
                    return m == null ? null : m.info().label();
                })
                : new HttpModule(d, info, http, om);
    }

    private Path file(String id) {
        return dir.resolve(id + ".json");
    }

    private String newId() {
        String id;
        do id = "MOD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8); while (defs.containsKey(id));
        return id;
    }
}
