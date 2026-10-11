package com.app.workflowstudio.node.custom;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.*;
import com.app.workflowstudio.service.PipelineService;
import com.app.workflowstudio.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 🧩 모듈 관리 — 화면(/modules) + API.
 * <pre>
 * GET    /api/modules                      기본 모듈(켜짐 여부·사용처) + 내 모듈 목록
 * PUT    /api/modules/state {disabled:[]}  켜기/끄기 (⚙ settings.json modules.disabled)
 * GET    /api/modules/{id}                 내 모듈 정의
 * POST   /api/modules                      새로 만들기 (pipeline 종류는 sourcePipelineId 의 노드를 복사)
 * PUT    /api/modules/{id}                 고치기
 * POST   /api/modules/{id}/refresh         파이프라인 모듈: 원본에서 다시 가져오기
 * DELETE /api/modules/{id}?force=          지우기 (쓰는 파이프라인이 있으면 force 필요)
 * GET    /api/modules/{id}/export          .wbmod 내려받기
 * POST   /api/modules/import               .wbmod 올리기 (overwrite / copy)
 * POST   /api/modules/test {module, values, input}  저장 전 시험 실행
 * GET    /api/modules/pipeline-source/{pipelineId}  모듈로 만들 파이프라인의 노드·설정 (입력칸 연결용)
 * </pre>
 */
@Controller
public class CustomModuleController {

    private final CustomModuleService service;
    private final NodeTypeRegistry registry;
    private final PipelineService pipelines;
    private final SettingsService settings;
    private final ObjectMapper om;

    public CustomModuleController(CustomModuleService service, NodeTypeRegistry registry, PipelineService pipelines,
                                  SettingsService settings, ObjectMapper om) {
        this.service = service;
        this.registry = registry;
        this.pipelines = pipelines;
        this.settings = settings;
        this.om = om;
    }

    @GetMapping("/modules")
    public String page() {
        return "modules/index";
    }

    @GetMapping("/api/modules")
    @ResponseBody
    public Map<String, Object> list() {
        Set<String> disabled = new HashSet<>(settings.get().modules.disabled);
        Map<String, Integer> usage = usageCounts();
        List<Map<String, Object>> builtin = new ArrayList<>();
        for (NodeTypeInfo t : registry.builtins()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("label", t.label());
            m.put("icon", t.icon());
            m.put("color", t.color());
            m.put("category", t.category());
            m.put("enabled", !disabled.contains(t.id()));
            m.put("used", usage.getOrDefault(t.id(), 0));
            builtin.add(m);
        }
        List<Map<String, Object>> custom = new ArrayList<>();
        for (CustomModuleDef d : service.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id);
            m.put("kind", d.kind);
            m.put("label", d.label);
            m.put("icon", d.icon);
            m.put("color", d.color);
            m.put("category", d.category);
            m.put("description", d.description);
            m.put("version", d.version);
            m.put("updatedAt", d.updatedAt);
            m.put("params", d.params.size());
            m.put("enabled", !disabled.contains(d.id));
            m.put("usedIn", service.usedIn(d.id));
            if (d.pipeline != null) {
                m.put("sourceName", d.pipeline.sourceName);
                m.put("sourceExists", d.pipeline.sourcePipelineId != null && pipelines.findById(d.pipeline.sourcePipelineId) != null);
                m.put("nodes", d.pipeline.nodes.size());
            }
            if (d.http != null) m.put("request", d.http.method + " " + d.http.url);
            custom.add(m);
        }
        return Map.of("builtin", builtin, "custom", custom, "disabled", disabled);
    }

    @PutMapping("/api/modules/state")
    @ResponseBody
    public Map<String, Object> state(@RequestBody Map<String, List<String>> req) throws Exception {
        List<String> disabled = req.getOrDefault("disabled", List.of());
        var node = om.createObjectNode();
        node.putObject("modules").set("disabled", om.valueToTree(disabled));
        settings.update(node);
        return Map.of("disabled", settings.get().modules.disabled);
    }

    @GetMapping("/api/modules/{id}")
    @ResponseBody
    public ResponseEntity<?> get(@PathVariable String id) {
        CustomModuleDef d = service.get(id);
        return d == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(d);
    }

    @PostMapping("/api/modules")
    @ResponseBody
    public ResponseEntity<?> create(@RequestBody CustomModuleDef d) {
        try {
            d.id = null;
            fillFromSource(d);
            return ResponseEntity.ok(service.save(d));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PutMapping("/api/modules/{id}")
    @ResponseBody
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody CustomModuleDef d) {
        CustomModuleDef old = service.get(id);
        if (old == null) return ResponseEntity.notFound().build();
        try {
            d.id = id;
            d.kind = old.kind;     // 종류는 바꿀 수 없음
            if (CustomModuleDef.KIND_PIPELINE.equals(d.kind)) {
                // 안쪽 노드는 화면에서 보내지 않음 — 기존 것 유지 (원본에서 다시 가져오기는 /refresh)
                String outputNode = d.pipeline == null ? old.pipeline.outputNodeId : d.pipeline.outputNodeId;
                d.pipeline = om.convertValue(old.pipeline, CustomModuleDef.Sub.class);
                d.pipeline.outputNodeId = outputNode;
            }
            return ResponseEntity.ok(service.save(d));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/api/modules/{id}/refresh")
    @ResponseBody
    public ResponseEntity<?> refresh(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.refreshFromSource(id));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping("/api/modules/{id}")
    @ResponseBody
    public ResponseEntity<?> delete(@PathVariable String id, @RequestParam(defaultValue = "false") boolean force) throws Exception {
        if (service.get(id) == null) return ResponseEntity.notFound().build();
        List<Map<String, String>> used = service.usedIn(id);
        if (!used.isEmpty() && !force)
            return ResponseEntity.status(409).body(Map.of("error", "이 모듈을 쓰는 곳이 있습니다", "usedIn", used));
        service.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/api/modules/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) throws Exception {
        CustomModuleDef d = service.get(id);
        if (d == null) return ResponseEntity.notFound().build();
        byte[] body = om.writerWithDefaultPrettyPrinter().writeValueAsBytes(service.export(id));
        String name = d.label.replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + ".wbmod";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @PostMapping("/api/modules/import")
    @ResponseBody
    public ResponseEntity<?> importModule(@RequestParam("file") MultipartFile file,
                                          @RequestParam(defaultValue = "false") boolean overwrite,
                                          @RequestParam(defaultValue = "false") boolean copy) {
        try {
            if (file.getSize() > 5_000_000) return bad("모듈 파일이 너무 큽니다 (5MB까지)");
            var r = service.importModule(file.getBytes(), overwrite, copy);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", r.module());
            m.put("replaced", r.replaced());
            m.put("missingTypes", r.missingTypes());
            return ResponseEntity.ok(m);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", "같은 모듈이 이미 있습니다: " + e.getMessage(), "conflict", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** 저장 전 시험 실행 — 정의 + 입력칸 값으로 노드 하나를 바로 실행 */
    @PostMapping("/api/modules/test")
    @ResponseBody
    public ResponseEntity<?> test(@RequestBody Map<String, Object> req) {
        try {
            CustomModuleDef d = om.convertValue(req.get("module"), CustomModuleDef.class);
            if (d.id == null || d.id.isBlank()) d.id = "MOD_test";
            CustomModuleDef saved = service.get(d.id);
            if (CustomModuleDef.KIND_PIPELINE.equals(d.kind)) {
                if (saved != null && saved.pipeline != null && (d.pipeline == null || d.pipeline.nodes == null || d.pipeline.nodes.isEmpty())) {
                    String out = d.pipeline == null ? null : d.pipeline.outputNodeId;
                    d.pipeline = om.convertValue(saved.pipeline, CustomModuleDef.Sub.class);
                    if (out != null) d.pipeline.outputNodeId = out;
                }
                fillFromSource(d);
            }
            service.validate(d);
            @SuppressWarnings("unchecked")
            Map<String, Object> values = req.get("values") instanceof Map<?, ?> m ? new HashMap<>((Map<String, Object>) m) : new HashMap<>();
            NodeModule module = buildForTest(d);
            PipelineNode node = new PipelineNode();
            node.setId("test");
            node.setType(d.id);
            node.setConfig(values);
            String input = req.get("input") == null ? null : String.valueOf(req.get("input"));
            NodeContext ctx = new NodeContext(node, new HashMap<>(), input, RunMode.MANUAL, null, om)
                    .withRun(UUID.randomUUID().toString(), "module-test", d.label, (s, msg) -> { });
            long t0 = System.currentTimeMillis();
            String out = module.execute(ctx);
            return ResponseEntity.ok(Map.of("ok", true, "output", out == null ? "" : out, "ms", System.currentTimeMillis() - t0));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        }
    }

    /** 모듈로 만들 파이프라인의 노드 목록 (트리거·부품 표시) + 설정값 — 입력칸을 어느 노드 설정에 연결할지 고르는 데 씀 */
    @GetMapping("/api/modules/pipeline-source/{pipelineId}")
    @ResponseBody
    public ResponseEntity<?> source(@PathVariable String pipelineId) {
        Pipeline p = pipelines.findById(pipelineId);
        if (p == null) return ResponseEntity.notFound().build();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PipelineNode n : p.getNodes()) {
            NodeModule m = registry.find(n.getType());
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", n.getId());
            e.put("type", n.getType());
            Object nm = n.getConfig() == null ? null : n.getConfig().get("name");
            e.put("name", nm instanceof String s && !s.isBlank() ? s : m == null ? n.getType() : m.info().label());
            e.put("icon", m == null ? "❓" : m.info().icon());
            e.put("trigger", m != null && m.info().trigger());
            e.put("part", m != null && m.info().isPart());
            List<Map<String, Object>> params = new ArrayList<>();
            if (m != null) for (NodeParam pr : m.info().params()) {
                if (!Set.of("text", "textarea", "number", "select", "folder", "file", "outlineCollection").contains(pr.type())) continue;
                if (pr.name().equals("name") || pr.name().equals("outputVar")) continue;
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("key", pr.name());
                q.put("label", pr.label());
                q.put("type", pr.type());
                q.put("options", pr.options());
                Object v = n.getConfig() == null ? null : n.getConfig().get(pr.name());
                q.put("value", v == null ? pr.defaultValue() : v);
                params.add(q);
            }
            e.put("params", params);
            nodes.add(e);
        }
        return ResponseEntity.ok(Map.of("id", p.getId(), "name", p.getName(), "nodes", nodes));
    }

    // ── 도우미 ──

    /** 파이프라인 모듈을 새로 만들 때: sourcePipelineId 의 노드를 복사 */
    private void fillFromSource(CustomModuleDef d) {
        if (!CustomModuleDef.KIND_PIPELINE.equals(d.kind)) return;
        if (d.pipeline == null) throw new IllegalArgumentException("모듈로 만들 파이프라인을 고르세요");
        if (d.pipeline.nodes != null && !d.pipeline.nodes.isEmpty()) return;
        Pipeline src = d.pipeline.sourcePipelineId == null ? null : pipelines.findById(d.pipeline.sourcePipelineId);
        if (src == null) throw new IllegalArgumentException("모듈로 만들 파이프라인을 고르세요");
        service.snapshot(d, src);
    }

    private NodeModule buildForTest(CustomModuleDef d) {
        // 저장하지 않고 실행 — 서비스의 생성 로직을 그대로 쓰기 위해 임시 등록 없이 직접 만듦
        return service.buildDetached(d);
    }

    private Map<String, Integer> usageCounts() {
        Map<String, Integer> c = new HashMap<>();
        for (Pipeline p : pipelines.findAll())
            if (p.getNodes() != null) for (PipelineNode n : p.getNodes()) c.merge(n.getType(), 1, Integer::sum);
        return c;
    }

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(msg)));
    }
}
