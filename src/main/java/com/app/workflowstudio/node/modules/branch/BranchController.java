package com.app.workflowstudio.node.modules.branch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 분기 설정 화면 보조 — 예시 값으로 규칙 시험 · 표현식 설명서 */
@Controller
public class BranchController {

    private final ObjectMapper om;

    public BranchController(ObjectMapper om) {
        this.om = om;
    }

    /** {sample, rules(JSON), mode, ignoreCase} → {picked:[id], results:[{id,label,matched,error}]} — 실제 실행과 같은 판정 */
    @PostMapping("/api/branch/test")
    @ResponseBody
    public Map<String, Object> test(@RequestBody Map<String, String> req) {
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            List<BranchRules.Rule> rules = BranchRules.parse(req.get("rules"), om);
            boolean ignoreCase = !BranchModule.CASE_EXACT.equals(req.get("ignoreCase"));
            boolean all = BranchModule.MODE_ALL.equals(req.get("mode"));
            String sample = req.getOrDefault("sample", "");
            List<Map<String, Object>> results = new ArrayList<>();
            List<String> picked = new ArrayList<>();
            for (BranchRules.Rule r : rules) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id());
                m.put("label", r.label());
                try {
                    // 규칙별 대상(${...})은 실제 실행 때만 해석됨 → 시험에서는 예시 값으로
                    boolean ok = BranchRules.matches(r, sample, ignoreCase);
                    m.put("matched", ok);
                    if (ok && (all || picked.isEmpty())) picked.add(r.id());
                } catch (Exception e) {
                    m.put("matched", false);
                    m.put("error", e.getMessage());
                }
                results.add(m);
            }
            if (picked.isEmpty()) picked.add(BranchRules.ELSE);
            res.put("ok", true);
            res.put("picked", picked);
            res.put("results", results);
        } catch (Exception e) {
            res.put("ok", false);
            res.put("error", e.getMessage());
        }
        return res;
    }

    /** 연산자 목록 (설정 화면 선택지) */
    @GetMapping("/api/branch/ops")
    @ResponseBody
    public Map<String, String> ops() {
        return BranchRules.OPS;
    }

    /** ❓ 표현식 설명서 (별도 창) */
    @GetMapping("/guide/branch")
    public String guide() {
        return "guide/branch";
    }
}
