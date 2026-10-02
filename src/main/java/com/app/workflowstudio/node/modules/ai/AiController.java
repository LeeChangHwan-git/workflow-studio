package com.app.workflowstudio.node.modules.ai;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AI 부품 노드의 설정 화면 보조 API */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final GeminiClient gemini;
    private final ChatMemoryStore memoryStore;

    public AiController(GeminiClient gemini, ChatMemoryStore memoryStore) {
        this.gemini = gemini;
        this.memoryStore = memoryStore;
    }

    /** Gemini 모델 목록 {apiKey?} → {models:[...]} | {error} (키가 비면 GEMINI_API_KEY 환경 변수) */
    @PostMapping("/gemini/models")
    public ResponseEntity<Map<String, Object>> geminiModels(@RequestBody(required = false) Map<String, String> req) {
        try {
            String key = GeminiClient.resolveKey(req != null ? req.get("apiKey") : null);
            return ResponseEntity.ok(Map.of("models", gemini.listModels(key)));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage() == null ? "목록을 불러올 수 없습니다" : e.getMessage()));
        }
    }

    /**
     * 모델 부품 설정 검증 (실행 없이, 토큰 사용 없이) {apiKey?, model?}
     * → {ok, keyOk, modelOk, keySource: node|env|none, message, models}
     * 키는 모델 목록 조회로, 모델은 목록에 있는지(없으면 개별 조회로 별칭까지) 확인한다.
     */
    @PostMapping("/gemini/verify")
    public Map<String, Object> verifyGemini(@RequestBody(required = false) Map<String, String> req) {
        String nodeKey = req != null ? req.get("apiKey") : null;
        String model = req != null && req.get("model") != null ? req.get("model").trim() : "";
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("keySource", nodeKey != null && !nodeKey.isBlank() ? "node"
                : (System.getenv("GEMINI_API_KEY") != null && !System.getenv("GEMINI_API_KEY").isBlank() ? "env" : "none"));

        String key;
        try {
            key = GeminiClient.resolveKey(nodeKey);
        } catch (Exception e) {
            return fail(res, false, null, e.getMessage());
        }

        List<String> models;
        try {
            models = gemini.listModels(key);
        } catch (Exception e) {
            return fail(res, false, null, "API 키 확인 실패 — " + e.getMessage());
        }
        res.put("keyOk", true);
        res.put("models", models);

        if (model.isEmpty()) {
            return fail(res, true, false, "API 키는 정상입니다. 모델을 입력하세요");
        }
        boolean found = models.contains(GeminiClient.modelId(model));
        if (!found) {
            try {
                found = gemini.modelExists(key, model);
            } catch (Exception e) {
                return fail(res, true, false, "모델 확인 실패 — " + e.getMessage());
            }
        }
        if (!found) {
            return fail(res, true, false, "API 키는 정상이지만 모델 '" + model + "'을(를) 찾을 수 없습니다 — 목록에서 고르세요");
        }
        res.put("modelOk", true);
        res.put("ok", true);
        res.put("message", "연결 정상 — 키 확인 완료, 모델 '" + model + "' 사용 가능");
        return res;
    }

    private static Map<String, Object> fail(Map<String, Object> res, boolean keyOk, Boolean modelOk, String message) {
        res.put("ok", false);
        res.put("keyOk", keyOk);
        res.put("modelOk", modelOk);
        res.put("message", message);
        return res;
    }

    /** 메모리 노드에 저장된 대화 목록 → [{sessionKey, messages}] */
    @GetMapping("/memory/{nodeId}")
    public List<Map<String, Object>> memorySessions(@PathVariable String nodeId) throws Exception {
        return memoryStore.sessions(nodeId);
    }

    /** 메모리 노드의 모든 대화 삭제 */
    @DeleteMapping("/memory/{nodeId}")
    public ResponseEntity<Void> clearMemory(@PathVariable String nodeId) throws Exception {
        memoryStore.clear(nodeId);
        return ResponseEntity.noContent().build();
    }
}
