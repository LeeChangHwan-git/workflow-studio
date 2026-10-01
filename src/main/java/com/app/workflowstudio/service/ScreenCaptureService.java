package com.app.workflowstudio.service;

import org.springframework.stereotype.Service;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 화면 영역을 드래그로 선택해 캡처하는 서비스.
 * 투명 오버레이를 각 모니터에 띄우고, 선택 완료 후 Robot으로 해당 영역만 캡처한다.
 * frozen screenshot 방식 대신 투명 창을 사용하므로 Retina/혼합DPI 환경에서도 정상 표시된다.
 */
@Service
public class ScreenCaptureService {

    /** 취소 시 null 반환 */
    public BufferedImage captureRegionInteractive() throws Exception {
        // 순서: 오버레이로 영역 선택 → 오버레이가 완전히 사라질 때까지 대기 → 해당 영역만 캡처
        Rectangle screenRect = selectRegion();
        if (screenRect == null || screenRect.width < 4 || screenRect.height < 4) return null;

        // 오버레이 창 닫힘(페이드 애니메이션 포함)이 끝날 때까지 대기
        Thread.sleep(300);

        Rectangle allBounds = getAllScreenBounds();
        BufferedImage fullShot = captureAllScreens(new Robot(), allBounds);

        int imgX = screenRect.x - allBounds.x;
        int imgY = screenRect.y - allBounds.y;
        int w = Math.min(screenRect.width, fullShot.getWidth() - imgX);
        int h = Math.min(screenRect.height, fullShot.getHeight() - imgY);
        return fullShot.getSubimage(imgX, imgY, w, h);
    }

    /** 연결된 모든 모니터를 합친 가상 영역 반환 */
    private static Rectangle getAllScreenBounds() {
        Rectangle bounds = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
        }
        return bounds;
    }

    /**
     * 각 모니터를 개별 캡처 후 allBounds 크기의 이미지에 합친다.
     * createScreenCapture(allBounds) 한 번으로 찍으면 외부 모니터 영역이
     * 깨지는 macOS 렌더링 문제를 방지한다.
     */
    private static BufferedImage captureAllScreens(Robot robot, Rectangle allBounds) {
        BufferedImage combined = new BufferedImage(allBounds.width, allBounds.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combined.createGraphics();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle sb = gd.getDefaultConfiguration().getBounds();
            g.drawImage(robot.createScreenCapture(sb), sb.x - allBounds.x, sb.y - allBounds.y, null);
        }
        g.dispose();
        return combined;
    }

    /**
     * 모니터별로 투명 JWindow 오버레이를 띄운다.
     * frozen screenshot 없이 실제 화면을 그대로 보여주므로 DPI 문제가 없다.
     * 반환값은 AWT 가상 화면 좌표계의 Rectangle (음수 x 포함 가능).
     */
    private Rectangle selectRegion() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Rectangle> result = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);

        SwingUtilities.invokeLater(() -> {
            List<JWindow> overlays = new ArrayList<>();

            Runnable closeAll = () -> {
                for (JWindow w : overlays) w.dispose();
                latch.countDown();
            };

            for (GraphicsDevice gd : GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle sb = gd.getDefaultConfiguration().getBounds();

                JWindow overlay = new JWindow(gd.getDefaultConfiguration());
                overlay.setBackground(new Color(0, 0, 0, 0)); // 투명 배경 (per-pixel translucency)
                overlay.setBounds(sb);
                overlay.setAlwaysOnTop(true);
                overlay.setFocusableWindowState(true);

                final int ox = sb.x, oy = sb.y;
                Consumer<Rectangle> onDone = rect -> {
                    if (done.compareAndSet(false, true)) {
                        if (rect != null) {
                            // 패널 로컬 좌표 → AWT 가상 화면 좌표
                            result.set(new Rectangle(ox + rect.x, oy + rect.y, rect.width, rect.height));
                        }
                        SwingUtilities.invokeLater(closeAll);
                    }
                };

                overlay.setContentPane(new SelectionPanel(onDone));
                overlays.add(overlay);
            }

            for (JWindow ov : overlays) {
                ov.setVisible(true);
                ov.toFront();
            }
            if (!overlays.isEmpty()) overlays.get(0).requestFocus();
        });

        latch.await();
        return result.get();
    }

    /** 투명 오버레이 위에서 드래그로 사각형을 선택하는 패널 */
    private static class SelectionPanel extends JPanel {
        private final Consumer<Rectangle> onDone;
        private Point start;
        private Point current;

        SelectionPanel(Consumer<Rectangle> onDone) {
            this.onDone = onDone;
            setOpaque(false);
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
            return new Rectangle(
                    Math.min(start.x, current.x),
                    Math.min(start.y, current.y),
                    Math.abs(current.x - start.x),
                    Math.abs(current.y - start.y)
            );
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();

            // 전체 반투명 어둠 처리
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, getWidth(), getHeight());

            Rectangle r = currentRect();
            if (r != null) {
                // 선택 영역은 완전히 투명하게 → 실제 화면이 그대로 보임
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR));
                g2.fillRect(r.x, r.y, r.width, r.height);

                // 선택 테두리
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
                g2.setColor(new Color(99, 102, 241));
                g2.setStroke(new BasicStroke(2));
                g2.drawRect(r.x, r.y, r.width, r.height);

                // 크기 레이블
                String label = r.width + " x " + r.height;
                g2.setColor(Color.WHITE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
                g2.drawString(label, r.x + 4, Math.max(14, r.y - 6));
            } else {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
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
