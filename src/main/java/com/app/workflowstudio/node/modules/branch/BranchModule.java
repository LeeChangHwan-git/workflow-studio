package com.app.workflowstudio.node.modules.branch;

import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 분기 — 값을 규칙으로 검사해 맞는 쪽 출력으로만 흘려보낸다 (규칙 하나 = 출력 하나 + "그 외").
 * 출력 내용은 들어온 값 그대로 (다음 노드의 ${input} 이 바뀌지 않음). 맞은 규칙 이름은 ${출력변수.matched}.
 */
@Component
@Order(150)
public class BranchModule implements NodeModule, DynamicOutputs {

    public static final String TYPE = "BRANCH";
    static final String MODE_FIRST = "처음 맞는 규칙 하나만";
    static final String MODE_ALL = "맞는 규칙 모두";
    static final String CASE_IGNORE = "대소문자 무시";
    static final String CASE_EXACT = "대소문자 구분";
    static final String DEFAULT_RULES = "[{\"id\":\"r1\",\"label\":\"1 포함\",\"op\":\"contains\",\"value\":\"1\"},"
            + "{\"id\":\"r2\",\"label\":\"2 포함\",\"op\":\"contains\",\"value\":\"2\"}]";

    private final com.fasterxml.jackson.databind.ObjectMapper om;

    public BranchModule(com.fasterxml.jackson.databind.ObjectMapper om) {
        this.om = om;
    }

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "분기", "🔀", "#0891B2", "흐름", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("subject", "검사할 값 (기본 ${input} = 앞 노드 출력 · 예: ${webhook.body.type})", "text", "${input}", null),
                new NodeParam("rules", "규칙 — 하나마다 오른쪽 출력이 생김 (+ 그 외)", "branchRules", DEFAULT_RULES, null),
                new NodeParam("mode", "여러 규칙이 맞으면", "select", MODE_FIRST, List.of(MODE_FIRST, MODE_ALL)),
                new NodeParam("ignoreCase", "글자 비교", "select", CASE_IGNORE, List.of(CASE_IGNORE, CASE_EXACT)),
                new NodeParam("outputVar", "출력 변수명", "text", "branch", null)
        ), false, List.of(), null, List.of(), "rules");
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        List<BranchRules.Rule> rules = BranchRules.parse(ctx.raw("rules", ""), om);
        if (rules.isEmpty()) throw new Exception("분기 규칙을 하나 이상 만드세요");
        String subject = ctx.str("subject", "${input}");
        if (subject == null || subject.equals("${input}")) subject = ctx.input() == null ? "" : ctx.input();
        boolean ignoreCase = !CASE_EXACT.equals(ctx.raw("ignoreCase", CASE_IGNORE));
        boolean all = MODE_ALL.equals(ctx.raw("mode", MODE_FIRST));

        List<String> picked = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (BranchRules.Rule r : rules) {
            String s = r.subject().isEmpty() ? subject : ctx.resolve(r.subject());
            if (BranchRules.matches(r, s, ignoreCase)) {
                picked.add(r.id());
                labels.add(r.label());
                if (!all) break;
            }
        }
        if (picked.isEmpty()) {
            picked.add(BranchRules.ELSE);
            labels.add("그 외");
        }
        ctx.route(picked);
        String out = ctx.raw("outputVar", "branch");
        ctx.vars().put(out + ".matched", String.join(", ", labels));
        // 들어온 값을 그대로 넘김 — 다음 노드는 분기가 없었던 것처럼 ${input} 사용
        return ctx.input() != null ? ctx.input() : subject;
    }

    @Override
    public Map<String, String> outputLabels(PipelineNode node) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            Object raw = node.getConfig() == null ? null : node.getConfig().get("rules");
            for (BranchRules.Rule r : BranchRules.parse(raw == null ? DEFAULT_RULES : String.valueOf(raw), om)) m.put(r.id(), r.label());
        } catch (Exception ignore) {
        }
        m.put(BranchRules.ELSE, "그 외");
        return m;
    }
}
