package com.app.workflowstudio;

import com.app.workflowstudio.controller.AppInfoController;
import com.app.workflowstudio.controller.AppLiveController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.awt.Desktop;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

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
        // 중복 실행 방지: 같은 포트에 이미 Workflow Studio 가 떠 있으면 새로 띄우지 않고 브라우저만 연다
        int port = configuredPort(args);
        if (alreadyRunning(port)) {
            System.out.println("Workflow Studio 가 이미 실행 중입니다 → http://localhost:" + port);
            boolean open = true;
            for (String a : args) if (a.equals("--app.open-browser=false")) open = false;
            if (open) openInBrowser("http://localhost:" + port);
            return;
        }

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

    /** --server.port=… 인자 > 실행 폴더의 application.properties > 8080 */
    static int configuredPort(String[] args) {
        for (String a : args) {
            if (a.startsWith("--server.port=")) {
                try { return Integer.parseInt(a.substring("--server.port=".length()).trim()); } catch (NumberFormatException ignore) {}
            }
        }
        Path external = Path.of("application.properties");
        if (Files.isRegularFile(external)) {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(external.toFile())) {
                p.load(in);
                String v = p.getProperty("server.port");
                if (v != null && !v.isBlank() && !v.trim().equals("0")) return Integer.parseInt(v.trim());
            } catch (Exception ignore) {}
        }
        return 8080;
    }

    /** 그 포트에서 Workflow Studio 가 응답하는지 (1초 안에) */
    static boolean alreadyRunning(int port) {
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/app/info"))
                            .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains(AppInfoController.APP_NAME);
        } catch (Exception e) {
            return false;
        }
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
