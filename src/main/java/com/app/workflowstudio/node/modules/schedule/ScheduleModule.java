package com.app.workflowstudio.node.modules.schedule;

import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.*;
import com.app.workflowstudio.node.modules.filewrite.FileWriteModule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 타이머 트리거 — 정해진 시각·간격마다 파이프라인을 시작한다 (n8n Schedule Trigger 와 같은 개념).
 * 실제 등록·실행은 {@link ScheduleService}. 출력: '전달 내용'이 있으면 그 텍스트, 없으면 실행 정보 JSON.
 */
@Component
@Order(10)
public class ScheduleModule implements NodeModule {

    public static final String TYPE = "SCHEDULE";
    public static final String ON = "켜짐";
    public static final String OFF = "꺼짐";

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "타이머", "⏰", "#F97316", "트리거", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("active", "타이머", "select", OFF, List.of(ON, OFF)),
                new NodeParam("mode", "실행 방식", "select", ScheduleSpec.MODE_INTERVAL, ScheduleSpec.MODES),
                new NodeParam("intervalValue", "간격", "number", 5, null, "mode=" + ScheduleSpec.MODE_INTERVAL),
                new NodeParam("intervalUnit", "간격 단위", "select", ScheduleSpec.UNIT_MIN,
                        List.of(ScheduleSpec.UNIT_MIN, ScheduleSpec.UNIT_HOUR), "mode=" + ScheduleSpec.MODE_INTERVAL),
                new NodeParam("days", "요일", "select", "매일", new ArrayList<>(ScheduleSpec.DAYS.keySet()),
                        "mode=" + ScheduleSpec.MODE_DAILY),
                new NodeParam("time", "시각 (24시간, 예: 09:00)", "text", "09:00", null, "mode=" + ScheduleSpec.MODE_DAILY),
                new NodeParam("cron", "cron 식 — 분 시 일 월 요일 (예: 0 9 * * MON-FRI = 평일 9시)", "text", "0 9 * * MON-FRI",
                        null, "mode=" + ScheduleSpec.MODE_CRON),
                new NodeParam("onceAt", "날짜·시각 (예: 2026-10-05 09:00)", "text", "", null, "mode=" + ScheduleSpec.MODE_ONCE),
                new NodeParam("message", "전달 내용 (선택, ${date} ${time} ${datetime} 사용 가능 — 비우면 실행 정보 JSON)",
                        "textarea", "", null),
                new NodeParam("scheduleInfo", "상태", "scheduleInfo", null, null),
                new NodeParam("outputVar", "출력 변수명", "text", "timer", null)
        ), true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(NodeContext ctx) throws Exception {
        Map<String, Object> payload;
        if (ctx.triggerData() instanceof Map<?, ?> m) {
            payload = new LinkedHashMap<>((Map<String, Object>) m);
        } else {
            // ▶ 실행(수동) — 지금 실행된 것으로 간주
            payload = new LinkedHashMap<>();
            payload.put("firedAt", LocalDateTime.now().withNano(0).toString());
            payload.put("trigger", "manual");
        }

        Map<String, Object> scope = new LinkedHashMap<>(FileWriteModule.timeVars(LocalDateTime.now()));
        scope.put("firedAt", payload.get("firedAt"));
        String message = ctx.raw("message", "");
        if (!message.isBlank()) return ctx.resolve(message, scope);
        return ctx.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload);
    }

    public static boolean isActive(PipelineNode n) {
        return n.getConfig() != null && ON.equals(n.getConfig().get("active"));
    }
}
