package com.app.workflowstudio.node.custom;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.model.RunLog;
import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 파이프라인 모듈 실행 — 정의에 저장된 안쪽 파이프라인을 지금 실행 안에서 바로 돌린다.
 * <ul>
 *   <li>입력칸 값: 연결된 안쪽 노드 설정에 넣고(target), {@code ${in.이름}} 변수로도 넣음</li>
 *   <li>앞 노드 출력 = 안쪽 첫 노드(들)의 {@code ${input}}</li>
 *   <li>출력 = 결과 노드(지정 안 하면 마지막으로 출력을 낸 노드)의 출력</li>
 *   <li>모듈 안에서 모듈을 부르는 건 {@value #MAX_DEPTH} 단계까지 (자기 자신을 부르는 무한 반복 방지)</li>
 * </ul>
 */
public class PipelineModule implements NodeModule {

    static final int MAX_DEPTH = 5;
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private final CustomModuleDef def;
    private final NodeTypeInfo info;
    private final org.springframework.beans.factory.ObjectProvider<PipelineRunnerService> runner;
    private final ObjectMapper om;
    /** 노드 타입 id → 화면 이름 (오류 메시지용) */
    private final java.util.function.Function<String, String> typeLabel;

    PipelineModule(CustomModuleDef def, NodeTypeInfo info, org.springframework.beans.factory.ObjectProvider<PipelineRunnerService> runner, ObjectMapper om,
                   java.util.function.Function<String, String> typeLabel) {
        this.typeLabel = typeLabel;
        this.def = def;
        this.info = info;
        this.runner = runner;
        this.om = om;
    }

    @Override
    public NodeTypeInfo info() {
        return info;
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        if (def.pipeline == null || def.pipeline.nodes == null || def.pipeline.nodes.isEmpty())
            throw new Exception("모듈 안에 노드가 없습니다 (🧩 모듈 관리에서 원본 파이프라인을 다시 가져오세요)");
        int depth = DEPTH.get();
        if (depth >= MAX_DEPTH) throw new Exception("모듈 안에서 모듈을 너무 깊이 부릅니다 (" + MAX_DEPTH + "단계) — 자기 자신을 부르고 있지 않은지 확인");

        Map<String, String> values = HttpModule.values(def, ctx);
        // 안쪽 노드는 매번 복사해서 입력값을 넣음 (정의는 그대로)
        List<PipelineNode> nodes = om.convertValue(def.pipeline.nodes, new TypeReference<List<PipelineNode>>() {});
        Map<String, PipelineNode> byId = new HashMap<>();
        for (PipelineNode n : nodes) {
            if (n.getConfig() == null) n.setConfig(new HashMap<>());
            else n.setConfig(new HashMap<>(n.getConfig()));
            byId.put(n.getId(), n);
        }
        for (CustomModuleDef.Param p : def.params) {
            if (p.target == null || p.target.nodeId == null || p.target.key == null) continue;
            PipelineNode n = byId.get(p.target.nodeId);
            if (n != null && values.containsKey(p.name)) n.getConfig().put(p.target.key, values.get(p.name));
        }
        Pipeline inner = new Pipeline();
        inner.setId("module:" + def.id);
        inner.setName(def.label);
        inner.setNodes(nodes);
        inner.setConnections(om.convertValue(def.pipeline.connections, new TypeReference<>() {}));

        Map<String, Object> vars = new HashMap<>();
        vars.put("in", new LinkedHashMap<>(values));
        for (var e : values.entrySet()) vars.put("in." + e.getKey(), e.getValue());

        PipelineRunnerService.InlineResult r;
        DEPTH.set(depth + 1);
        try {
            r = runner.getObject().runInline(inner, vars, ctx.input(), ctx.mode(), ctx.executionId(), def.pipeline.outputNodeId);
        } finally {
            DEPTH.set(depth);
        }
        if (!"done".equals(r.status())) {
            RunLog err = r.logs().stream().filter(l -> "error".equals(l.getStatus())).reduce((a, b) -> b).orElse(null);
            String where = err == null ? "" : nodeName(byId.get(err.getNodeId()), err.getNodeId(), typeLabel);
            String why = err == null ? "취소됨" : err.getMessage().replaceFirst("^\\[[^\\]]*] (오류: )?", "");
            throw new Exception("모듈 안 [" + where + "] 실패 — " + why);
        }
        long ran = r.logs().stream().filter(l -> "success".equals(l.getStatus())).count();
        ctx.status("running", "🧩 모듈 안 노드 " + ran + "개 완료");
        return r.output() == null ? "" : r.output();
    }

    private static String nodeName(PipelineNode n, String fallback, java.util.function.Function<String, String> typeLabel) {
        if (n == null) return fallback;
        Object nm = n.getConfig() == null ? null : n.getConfig().get("name");
        if (nm instanceof String s && !s.isBlank()) return s;
        String label = typeLabel.apply(n.getType());
        return label != null ? label : n.getType();
    }
}
