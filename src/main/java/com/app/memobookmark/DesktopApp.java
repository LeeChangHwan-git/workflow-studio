package com.app.memobookmark;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class DesktopApp extends Application {

    private ConfigurableApplicationContext springContext;
    private final CompletableFuture<Integer> portFuture = new CompletableFuture<>();

    @Override
    public void init() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String[] args = getParameters().getRaw().toArray(new String[0]);

        Thread springThread = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(cl);
            try {
                springContext = new SpringApplicationBuilder(MemoBookmarkApplication.class)
                        .headless(false)
                        .run(args);
                int port = springContext.getEnvironment()
                        .getProperty("local.server.port", Integer.class, 8080);
                portFuture.complete(port);
            } catch (Exception e) {
                portFuture.completeExceptionally(e);
            }
        }, "spring-boot-starter");
        springThread.setDaemon(true);
        springThread.start();
    }

    @Override
    public void start(Stage stage) {
        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setStyle("-fx-progress-color: #6366F1;");
        spinner.setMaxSize(52, 52);

        Label titleLabel = new Label("메모 & 북마크");
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");
        Label subLabel = new Label("시작하는 중...");
        subLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");

        VBox loadBox = new VBox(12, titleLabel, spinner, subLabel);
        loadBox.setAlignment(Pos.CENTER);

        StackPane root = new StackPane(loadBox);
        root.setStyle("-fx-background-color: #F0F4FF;");

        Scene scene = new Scene(root, 1280, 840);
        scene.setFill(Color.web("#F0F4FF"));

        stage.setTitle("메모 & 북마크");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        setupSystemTray(stage);
        Platform.setImplicitExit(false);
        stage.setOnCloseRequest(e -> { e.consume(); stage.hide(); });
        stage.show();

        portFuture.thenAccept(port -> Platform.runLater(() -> {
            WebView webView = new WebView();
            webView.setPrefSize(1280, 840);

            // confirm() 다이얼로그 — JavaFX Alert으로 처리
            webView.getEngine().setConfirmHandler(message -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("확인");
                alert.setHeaderText(null);
                alert.setContentText(message);
                Optional<ButtonType> result = alert.showAndWait();
                return result.isPresent() && result.get() == ButtonType.OK;
            });

            // alert() 다이얼로그
            webView.getEngine().setOnAlert(e -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("알림");
                alert.setHeaderText(null);
                alert.setContentText(e.getData());
                alert.showAndWait();
            });

            // target="_blank" 링크 → OS 기본 브라우저로 오픈
            webView.getEngine().setCreatePopupHandler(popupFeatures -> {
                javafx.scene.web.WebView popup = new javafx.scene.web.WebView();
                popup.getEngine().locationProperty().addListener((obs, oldUrl, newUrl) -> {
                    if (newUrl != null && !newUrl.isEmpty() && !newUrl.equals("about:blank")) {
                        try {
                            Desktop.getDesktop().browse(new java.net.URI(newUrl));
                        } catch (Exception ignored) {}
                    }
                });
                return popup.getEngine();
            });

            root.getChildren().setAll(webView);
            webView.getEngine().load("http://localhost:" + port);
        })).exceptionally(ex -> {
            Platform.runLater(() -> {
                subLabel.setText("시작 실패: " + ex.getCause().getMessage());
                subLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #EF4444;");
            });
            return null;
        });
    }

    private void setupSystemTray(Stage stage) {
        if (!SystemTray.isSupported()) return;
        try {
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(99, 102, 241));
            g.fillRoundRect(0, 0, 32, 32, 8, 8);
            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 18));
            FontMetrics fm = g.getFontMetrics();
            int x = (32 - fm.stringWidth("M")) / 2;
            int y = (32 - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString("M", x, y);
            g.dispose();

            TrayIcon trayIcon = new TrayIcon(img, "메모 & 북마크");
            trayIcon.setImageAutoSize(true);

            PopupMenu popup = new PopupMenu();
            MenuItem showItem = new MenuItem("열기");
            showItem.addActionListener(e -> Platform.runLater(() -> { stage.show(); stage.toFront(); }));
            MenuItem exitItem = new MenuItem("종료");
            exitItem.addActionListener(e -> {
                try { SystemTray.getSystemTray().remove(trayIcon); } catch (Exception ignored) {}
                if (springContext != null) springContext.close();
                Platform.exit();
                System.exit(0);
            });
            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);
            trayIcon.setPopupMenu(popup);
            trayIcon.addActionListener(e -> Platform.runLater(() -> { stage.show(); stage.toFront(); }));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stop() {
        if (springContext != null) springContext.close();
        Platform.exit();
        System.exit(0);
    }
}
