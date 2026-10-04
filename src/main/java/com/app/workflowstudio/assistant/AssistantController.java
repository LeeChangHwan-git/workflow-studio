package com.app.workflowstudio.assistant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 💬 파이프라인 도우미 API */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final PipelineAssistantService assistant;

    public AssistantController(PipelineAssistantService assistant) {
        this.assistant = assistant;
    }

    /** {conversationId?, message, currentPipelineId?} → {conversationId, reply, actions, steps, model} */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, String> req) {
        try {
            return ResponseEntity.ok(assistant.chat(req.get("conversationId"), req.get("message"), req.get("currentPipelineId")));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage() == null ? "처리 중 오류" : e.getMessage(),
                    "conversationId", req.getOrDefault("conversationId", "")));
        }
    }

    /** 삭제 확인 {conversationId, pipelineId, confirm: true|false} */
    @PostMapping("/delete")
    public ResponseEntity<?> delete(@RequestBody Map<String, Object> req) {
        String conv = String.valueOf(req.get("conversationId"));
        String id = String.valueOf(req.get("pipelineId"));
        if (!Boolean.TRUE.equals(req.get("confirm"))) {
            assistant.cancelDelete(conv, id);
            return ResponseEntity.ok(Map.of("cancelled", true));
        }
        try {
            return ResponseEntity.ok(assistant.confirmDelete(conv, id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody(required = false) Map<String, String> req) {
        assistant.reset(req == null ? null : req.get("conversationId"));
        return Map.of("ok", true);
    }

    /** 설정한 AI에 짧게 물어 연결 확인 */
    @PostMapping("/test")
    public Map<String, Object> test() {
        return assistant.test();
    }
}
