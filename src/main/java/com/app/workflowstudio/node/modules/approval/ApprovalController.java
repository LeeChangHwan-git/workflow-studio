package com.app.workflowstudio.node.modules.approval;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 확인 요청 API — 화면(approvals.js)이 대기 목록을 보고 결정을 보냄 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    /** {pending:[{id, pipelineName, nodeName, title, content, round, aiRevise, history, …}], recent:[…]} */
    @GetMapping
    public Map<String, Object> list() {
        return Map.of("pending", approvals.pending(), "recent", approvals.recent());
    }

    /** 결정 {decision: approve|retry|reject, feedback?, content?(고쳐서 승인)} */
    @PostMapping("/{id}")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable String id, @RequestBody Map<String, String> req) {
        try {
            boolean ok = approvals.decide(id, req.get("decision"), req.get("feedback"), req.get("content"));
            return ok ? ResponseEntity.ok(Map.of("ok", true))
                    : ResponseEntity.status(410).body(Map.of("ok", false, "error", "이미 처리됐거나 끝난 요청입니다"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }
}
