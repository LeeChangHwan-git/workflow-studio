package com.app.memobookmark.service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ImageMatcherUtil {

    /**
     * 화면에서 템플릿 이미지를 찾아 중심 좌표를 반환.
     * threshold: 0~1 (픽셀 일치율, 기본 0.90)
     */
    public static Point findOnScreen(File templateFile, double threshold) throws Exception {
        Robot robot = new Robot();
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        Rectangle screenBounds = gd.getDefaultConfiguration().getBounds();

        BufferedImage screen = robot.createScreenCapture(screenBounds);
        BufferedImage template = ImageIO.read(templateFile);
        if (template == null) throw new IOException("이미지 파일을 읽을 수 없습니다: " + templateFile);

        return findImage(screen, template, threshold);
    }

    public static Point findImage(BufferedImage screen, BufferedImage template, double threshold) {
        int sw = screen.getWidth(),  sh = screen.getHeight();
        int tw = template.getWidth(), th = template.getHeight();

        if (tw > sw || th > sh) return null;

        // 외곽 픽셀 샘플로 빠른 1차 필터링
        int[] tSamples = buildSamples(template);
        int total = tw * th;
        long minMatch = (long) Math.ceil(total * threshold);

        for (int x = 0; x <= sw - tw; x++) {
            for (int y = 0; y <= sh - th; y++) {
                if (matchesFull(screen, template, x, y, minMatch, total)) {
                    return new Point(x + tw / 2, y + th / 2);
                }
            }
        }
        return null;
    }

    private static boolean matchesFull(BufferedImage screen, BufferedImage template,
                                        int sx, int sy, long minMatch, int total) {
        int tw = template.getWidth(), th = template.getHeight();
        long matched = 0;
        long needed = minMatch;

        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                if (colorClose(screen.getRGB(sx + x, sy + y), template.getRGB(x, y))) {
                    matched++;
                }
            }
            // 남은 픽셀을 모두 맞춰도 부족하면 조기 종료
            int remaining = (th - y - 1) * tw;
            if (matched + remaining < needed) return false;
        }
        return matched >= minMatch;
    }

    private static boolean colorClose(int c1, int c2) {
        int r = ((c1 >> 16) & 0xFF) - ((c2 >> 16) & 0xFF);
        int g = ((c1 >>  8) & 0xFF) - ((c2 >>  8) & 0xFF);
        int b = ( c1        & 0xFF) - ( c2        & 0xFF);
        return (r * r + g * g + b * b) < 900; // 각 채널 ±30 이내
    }

    private static int[] buildSamples(BufferedImage img) {
        return new int[0]; // 예약 (필요 시 코너 샘플 필터링 확장)
    }

    /** 전체 화면 스크린샷을 파일로 저장하고 경로 반환 */
    public static BufferedImage captureScreen() throws AWTException {
        Robot robot = new Robot();
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        Rectangle bounds = gd.getDefaultConfiguration().getBounds();
        return robot.createScreenCapture(bounds);
    }
}
