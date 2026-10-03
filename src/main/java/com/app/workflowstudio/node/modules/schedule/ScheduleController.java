package com.app.workflowstudio.node.modules.schedule;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 타이머 노드 설정 화면용 API */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    /**
     * 상태 — body 의 config(아직 저장 안 된 화면 값)로 미리보기(설명·다음 실행·오류)를 계산하고,
     * 저장된 설정 기준 등록 여부·실행 기록을 함께 돌려준다. pipelineId 는 새 파이프라인이면 "new".
     */
    @PostMapping("/{pipelineId}/{nodeId}/status")
    public Map<String, Object> status(@PathVariable String pipelineId, @PathVariable String nodeId,
                                      @RequestBody(required = false) Map<String, Object> config) {
        Map<String, Object> res = new LinkedHashMap<>(service.status(pipelineId, nodeId));
        Map<String, Object> preview = new LinkedHashMap<>();
        try {
            ScheduleSpec spec = ScheduleSpec.parse(config == null ? Map.of() : config);
            preview.put("description", spec.description());
            preview.put("next", spec.nextRuns(LocalDateTime.now(), null, 3).stream().map(service::fmt).toList());
        } catch (Exception e) {
            preview.put("error", e.getMessage());
        }
        res.put("preview", preview);
        return res;
    }

    /** 지금 한 번 실행 (타이머가 꺼져 있어도 실행) → {executionId} | {skipped:true} */
    @PostMapping("/{pipelineId}/{nodeId}/run-now")
    public ResponseEntity<Map<String, Object>> runNow(@PathVariable String pipelineId, @PathVariable String nodeId) {
        String execId = service.fire(pipelineId, nodeId, "now");
        return execId == null
                ? ResponseEntity.ok(Map.of("skipped", true, "message", "실행하지 않았습니다 (저장 안 됨 또는 이전 실행 진행 중)"))
                : ResponseEntity.ok(Map.of("executionId", execId));
    }
}
