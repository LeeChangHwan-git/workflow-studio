package com.app.workflowstudio.controller;

import com.app.workflowstudio.node.modules.schedule.ScheduleService;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 서버 상태 / 종료 — 백그라운드(창 없이) 실행할 때 사용.
 * <ul>
 *   <li>GET /api/app/info : 실행 중인지·가동 시간·메모리·등록된 타이머 수 (중복 실행 방지 확인에도 사용)</li>
 *   <li>POST /api/app/shutdown : 서버 종료 — 헤더 {@value #SHUTDOWN_HEADER}: yes 필요.
 *       브라우저는 다른 사이트에서 이런 사용자 정의 헤더를 붙여 보낼 수 없으므로(CORS 사전 요청 차단),
 *       악성 웹페이지가 몰래 서버를 끄는 것을 막는다. 종료 스크립트(서버종료.bat)는 curl로 헤더를 붙여 호출</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/app")
public class AppInfoController {

    public static final String APP_NAME = "workflow-studio";
    public static final String SHUTDOWN_HEADER = "X-Workflow-Studio-Shutdown";

    private final ConfigurableApplicationContext context;
    private final ScheduleService scheduleService;
    private final LocalDateTime startedAt = LocalDateTime.now().withNano(0);

    public AppInfoController(ConfigurableApplicationContext context, ScheduleService scheduleService) {
        this.context = context;
        this.scheduleService = scheduleService;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", APP_NAME);
        m.put("instance", AppLiveController.INSTANCE_ID);
        m.put("pid", ProcessHandle.current().pid());
        m.put("startedAt", startedAt.toString());
        m.put("uptimeMinutes", Duration.between(startedAt, LocalDateTime.now()).toMinutes());
        m.put("heapUsedMB", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        m.put("heapMaxMB", rt.maxMemory() / (1024 * 1024));
        m.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        m.put("activeTimers", scheduleService.activeCount());
        return m;
    }

    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, Object>> shutdown(@RequestHeader(value = SHUTDOWN_HEADER, required = false) String confirm) {
        if (!"yes".equalsIgnoreCase(confirm)) {
            return ResponseEntity.status(403).body(Map.of("error", "종료하려면 " + SHUTDOWN_HEADER + ": yes 헤더가 필요합니다"));
        }
        Thread t = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignore) {}
            int code = SpringApplication.exit(context, () -> 0);
            System.exit(code);
        }, "shutdown");
        t.setDaemon(false);
        t.start();
        return ResponseEntity.ok(Map.of("message", "Workflow Studio 서버를 종료합니다"));
    }
}
