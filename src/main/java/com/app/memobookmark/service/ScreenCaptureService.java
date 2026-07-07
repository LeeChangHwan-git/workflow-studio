package com.app.memobookmark.service;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 화면 영역을 드래그로 선택해 캡처하는 서비스.
 * 캡처 시작 시 앱 창을 잠깐 숨기고, 화면을 통째로 찍은 뒤
 * 그 스냅샷 위에서 사용자가 영역을 드래그로 고르게 한다 (스니핑 툴 방식).
 */
@Service
public class ScreenCaptureService {

    private volatile Stage stage;

    public void bindStage(Stage stage) {
        this.stage = stage;
    }

    /** 취소 시 null 반환 */
    public BufferedImage captureRegionInteractive() throws Exception {
        hideStage();
        try {
            Thread.sleep(250); // 창이 사라지고 화면이 다시 그려질 시간
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            Rectangle screenBounds = gd.getDefaultConfiguration().getBounds();
            Robot robot = new Robot();
            BufferedImage fullShot = robot.createScreenCapture(screenBounds);

            Rectangle region = selectRegion(fullShot, screenBounds);
            if (region == null || region.width < 4 || region.height < 4) return null;
            return fullShot.getSubimage(region.x, region.y, region.width, region.height);
        } finally {
            showStage();
        }
    }

    private void hideStage() throws InterruptedException {
        if (stage == null) return;
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> { stage.hide(); latch.countDown(); });
        latch.await();
    }

    private void showStage() {
        if (stage == null) return;
        Platform.runLater(() -> { stage.show(); stage.toFront(); });
    }

    private Rectangle selectRegion(BufferedImage frozenShot, Rectangle screenBounds) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Rectangle> result = new AtomicReference<>();

        SwingUtilities.invokeLater(() -> {
            JWindow overlay = new JWindow();
            overlay.setBounds(screenBounds);
            overlay.setAlwaysOnTop(true);
            overlay.setFocusableWindowState(true);

            Consumer<Rectangle> onDone = rect -> {
                result.set(rect);
                overlay.dispose();
                latch.countDown();
            };

            overlay.setContentPane(new SelectionPanel(frozenShot, onDone));
            overlay.setVisible(true);
            overlay.toFront();
            overlay.requestFocus();
        });

        latch.await();
        return result.get();
    }

    /** 스냅샷 위에 드래그로 사각형을 선택하는 오버레이 패널 */
    private static class SelectionPanel extends JPanel {
        private final BufferedImage frozenShot;
        private final Consumer<Rectangle> onDone;
        private Point start;
        private Point current;

        SelectionPanel(BufferedImage frozenShot, Consumer<Rectangle> onDone) {
            this.frozenShot = frozenShot;
            this.onDone = onDone;
            setOpaque(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            setFocusable(true);

            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    start = e.getPoint();
                    current = e.getPoint();
                    repaint();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    current = e.getPoint();
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    onDone.accept(currentRect());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);

            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) onDone.accept(null);
                }
            });
        }

        private Rectangle currentRect() {
            if (start == null || current == null) return null;
            int x = Math.min(start.x, current.x);
            int y = Math.min(start.y, current.y);
            int w = Math.abs(current.x - start.x);
            int h = Math.abs(current.y - start.y);
            return new Rectangle(x, y, w, h);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.drawImage(frozenShot, 0, 0, null);

            g2.setColor(new Color(0, 0, 0, 110));
            g2.fillRect(0, 0, getWidth(), getHeight());

            Rectangle r = currentRect();
            if (r != null) {
                // 선택 영역만 원본 밝기로 다시 그려서 도드라지게 함
                g2.drawImage(frozenShot,
                        r.x, r.y, r.x + r.width, r.y + r.height,
                        r.x, r.y, r.x + r.width, r.y + r.height, null);
                g2.setColor(new Color(99, 102, 241));
                g2.setStroke(new BasicStroke(2));
                g2.drawRect(r.x, r.y, r.width, r.height);

                String label = r.width + " x " + r.height;
                g2.setColor(Color.WHITE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
                g2.drawString(label, r.x + 4, Math.max(14, r.y - 6));
            } else {
                g2.setColor(Color.WHITE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 15f));
                String hint = "드래그로 캡처할 영역을 선택하세요  (ESC: 취소)";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(hint, (getWidth() - fm.stringWidth(hint)) / 2, 40);
            }
            g2.dispose();
        }
    }
}
