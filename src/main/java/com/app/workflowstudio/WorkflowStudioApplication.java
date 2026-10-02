package com.app.workflowstudio;

import com.app.workflowstudio.controller.AppLiveController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.awt.Desktop;
import java.net.URI;

@SpringBootApplication
public class WorkflowStudioApplication {

    /** 이미 열려 있던 앱 탭이 재연결할 시간을 주고 판단 (탭은 1초 간격으로 재연결 시도) */
    private static final long REUSE_WAIT_MS = 4000;

    @Value("${server.port:8080}")
    private int port;

    @Value("${app.open-browser:true}")
    private boolean openBrowser;

    private final AppLiveController liveController;

    public WorkflowStudioApplication(AppLiveController liveController) {
        this.liveController = liveController;
    }

    public static void main(String[] args) {
        // 화면 캡처 / 브라우저 열기 (및 향후 화면 자동화 모듈)에 AWT가 필요하므로 headless 비활성화
        new SpringApplicationBuilder(WorkflowStudioApplication.class)
                .headless(false)
                .run(args);
    }

    /**
     * 기동 완료 시 기본 브라우저로 앱 주소를 연다 (app.open-browser=false 로 끌 수 있음).
     * 이미 열려 있던 앱 탭이 다시 연결되면 그 탭이 스스로 새로고침하므로 새 탭을 열지 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String url = "http://localhost:" + port;
        System.out.println("\n  Workflow Studio 실행 중 → " + url + "\n");
        if (!openBrowser) return;

        Thread t = new Thread(() -> {
            try {
                Thread.sleep(REUSE_WAIT_MS);
            } catch (InterruptedException e) {
                return;
            }
            int reconnected = liveController.clientCount();
            if (reconnected > 0) {
                System.out.println("  앱 화면이 열려 있던 브라우저 " + reconnected
                        + "개가 다시 연결됨 → 새 탭을 열지 않고 기존 탭을 새로고침합니다\n");
                return;
            }
            openInBrowser(url);
        }, "open-browser");
        t.setDaemon(true);
        t.start();
    }

    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
            }
        } catch (Exception ignored) {}
    }
}
