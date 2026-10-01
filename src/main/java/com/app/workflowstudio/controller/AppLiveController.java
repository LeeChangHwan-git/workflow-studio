package com.app.workflowstudio.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 열려 있는 앱 화면(브라우저 탭)과의 상시 연결 (Server-Sent Events).
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

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

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

    /** 현재 연결된 앱 화면 수 */
    public int clientCount() {
        return emitters.size();
    }
}
