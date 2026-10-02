package com.app.workflowstudio.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 열려 있는 앱 화면(브라우저 탭)과의 상시 연결 (Server-Sent Events).
 * 탭마다 연결하지 않고 브라우저의 SharedWorker(static/js/app-live-worker.js) 하나가 연결한다
 * — 탭마다 열면 브라우저의 같은 주소 동시 연결 6개 제한을 채워 화면이 멈추기 때문.
 * <ul>
 *   <li>연결 시 이 서버 실행의 고유 id(instance)를 보낸다 → 서버가 재시작되면 탭이 자동 재연결하면서
 *       id가 바뀐 것을 보고 스스로 새로고침 (layout.html 스크립트)</li>
 *   <li>기동 직후 연결된 탭이 있으면 새 브라우저 탭을 열지 않는다 (WorkflowStudioApplication)</li>
 * </ul>
 */
@RestController
public class AppLiveController {

    /** 이번 서버 실행의 고유 id */
    public static final String INSTANCE_ID = UUID.randomUUID().toString();

    /** 끊긴 뒤 브라우저가 재연결을 시도하는 간격 (ms) */
    private static final long RETRY_MS = 1000;

    /** 끊긴 연결 정리용 하트비트 간격 (끊긴 연결은 전송을 시도해 봐야 감지됨) */
    private static final long HEARTBEAT_SEC = 30;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public AppLiveController() {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-live-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::pingAll, HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
    }

    private void pingAll() {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().comment("ping"));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }

    @GetMapping(path = "/api/app/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live() throws IOException {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 시간 제한 없음
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitter.send(SseEmitter.event().reconnectTime(RETRY_MS).name("instance").data(INSTANCE_ID));
        return emitter;
    }

    /** 현재 연결 수 (= 앱 화면이 열려 있는 브라우저 수) */
    public int clientCount() {
        return emitters.size();
    }
}
