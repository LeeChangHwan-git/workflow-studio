package com.app.workflowstudio.node.modules.outline;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 아웃라인 노드 설정 화면 보조 API */
@RestController
@RequestMapping("/api/outline")
public class OutlineController {

    private final OutlineClient client;

    public OutlineController(OutlineClient client) {
        this.client = client;
    }

    /**
     * 연결 확인 (실행 없이) {baseUrl, apiKey?}
     * → {ok, message, keySource: node|env|none, user, team, collections:[{id,name}]}
     */
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody(required = false) Map<String, String> req) {
        String baseUrl = req != null ? req.get("baseUrl") : null;
        String nodeKey = req != null ? req.get("apiKey") : null;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("keySource", OutlineClient.keySource(nodeKey));
        try {
            OutlineClient.Conn c = OutlineClient.conn(baseUrl, nodeKey);
            JsonNode info = client.call(c, "auth.info", Map.of()).path("data");
            String user = info.path("user").path("name").asText("");
            String team = info.path("team").path("name").asText("");
            res.put("user", user);
            res.put("team", team);
            res.put("collections", client.collections(c));
            res.put("ok", true);
            res.put("message", "연결 정상 — " + team + " 워크스페이스 · " + user);
        } catch (Exception e) {
            res.put("ok", false);
            res.put("message", e.getMessage() == null ? "연결할 수 없습니다" : e.getMessage());
        }
        return res;
    }
}
