package com.app.memobookmark.service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ImageMatcherUtil {

    /** 1차 필터링에 쓸 최대 샘플 포인트 개수 */
    private static final int SAMPLE_COUNT = 64;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "image-match-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * 화면에서 템플릿 이미지를 찾아 중심 좌표를 반환.
     * threshold: 0~1 (픽셀 일치율, 기본 0.90)
     */
    public static Point findOnScreen(File templateFile, double threshold) throws Exception {
        Robot robot = new Robot();
        Rectangle screenBounds = getAllScreenBounds();

        BufferedImage screen = robot.createScreenCapture(screenBounds);
        BufferedImage template = ImageIO.read(templateFile);
        if (template == null) throw new IOException("이미지 파일을 읽을 수 없습니다: " + templateFile);

        return findImage(screen, template, threshold);
    }

    public static Point findImage(BufferedImage screen, BufferedImage template, double threshold) {
        int sw = screen.getWidth(),  sh = screen.getHeight();
        int tw = template.getWidth(), th = template.getHeight();

        if (tw > sw || th > sh) return null;

        // getRGB(x,y)를 픽셀마다 호출하면 호출 오버헤드가 커서, 배열로 한 번에 읽어와 처리한다.
        int[] screenPixels = screen.getRGB(0, 0, sw, sh, null, 0, sw);
        int[] templatePixels = template.getRGB(0, 0, tw, th, null, 0, tw);

        int total = tw * th;
        long minMatch = (long) Math.ceil(total * threshold);

        // 템플릿에서 배경色과 가장 다른(=글자/아이콘 등 특징적인) 픽셀들을 골라 1차 필터링에 쓴다.
        // 균일한 그리드 샘플을 쓰면 버튼처럼 배경이 넓은 이미지에서 배경색 영역과 우연히 대부분
        // 일치해버려 조기 탈락이 거의 안 걸리고 전체 비교(matchesFull)까지 넘어가는 경우가 많았다.
        int[] sampleOffsets = buildSampleOffsets(templatePixels, tw, th);
        long sampleNeeded = (long) Math.ceil(sampleOffsets.length * threshold);

        int maxX = sw - tw;
        int maxY = sh - th;

        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), maxY + 1));
        AtomicBoolean stop = new AtomicBoolean(false);

        if (threads == 1) {
            return scanRows(screenPixels, sw, templatePixels, tw, th, 0, maxY, maxX,
                    sampleOffsets, sampleNeeded, minMatch, stop);
        }

        int rowsPerThread = (maxY + threads) / threads;
        List<Future<Point>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int fromY = t * rowsPerThread;
            int toY = Math.min(maxY, fromY + rowsPerThread - 1);
            if (fromY > toY) continue;
            futures.add(POOL.submit(() -> scanRows(screenPixels, sw, templatePixels, tw, th,
                    fromY, toY, maxX, sampleOffsets, sampleNeeded, minMatch, stop)));
        }

        AtomicReference<Point> found = new AtomicReference<>();
        for (Future<Point> f : futures) {
            try {
                Point p = f.get();
                if (p != null) found.compareAndSet(null, p);
            } catch (InterruptedException | ExecutionException ignored) {
                // 다른 스레드에서 이미 찾았거나 취소된 경우 무시
            }
        }
        return found.get();
    }

    private static Point scanRows(int[] screenPixels, int sw, int[] templatePixels, int tw, int th,
                                   int fromY, int toY, int maxX,
                                   int[] sampleOffsets, long sampleNeeded, long minMatch,
                                   AtomicBoolean stop) {
        for (int y = fromY; y <= toY; y++) {
            if (stop.get()) return null;
            for (int x = 0; x <= maxX; x++) {
                if (!passesSample(screenPixels, sw, templatePixels, tw, x, y, sampleOffsets, sampleNeeded)) continue;
                if (matchesFull(screenPixels, sw, templatePixels, tw, th, x, y, minMatch)) {
                    stop.set(true);
                    return new Point(x + tw / 2, y + th / 2);
                }
            }
        }
        return null;
    }

    /** 샘플 포인트만으로 빠르게 후보를 걸러낸다 (전체 비교 없이 즉시 reject) */
    private static boolean passesSample(int[] screenPixels, int sw, int[] templatePixels, int tw,
                                         int sx, int sy, int[] sampleOffsets, long sampleNeeded) {
        long matched = 0;
        long remaining = sampleOffsets.length;
        for (int off : sampleOffsets) {
            int tx = off % tw, ty = off / tw;
            int screenIdx = (sy + ty) * sw + (sx + tx);
            if (colorClose(screenPixels[screenIdx], templatePixels[off])) matched++;
            remaining--;
            if (matched + remaining < sampleNeeded) return false;
        }
        return matched >= sampleNeeded;
    }

    private static boolean matchesFull(int[] screenPixels, int sw, int[] templatePixels, int tw, int th,
                                        int sx, int sy, long minMatch) {
        long matched = 0;
        for (int y = 0; y < th; y++) {
            int screenRow = (sy + y) * sw + sx;
            int templateRow = y * tw;
            for (int x = 0; x < tw; x++) {
                if (colorClose(screenPixels[screenRow + x], templatePixels[templateRow + x])) matched++;
            }
            // 남은 픽셀을 모두 맞춰도 부족하면 조기 종료
            int remaining = (th - y - 1) * tw;
            if (matched + remaining < minMatch) return false;
        }
        return matched >= minMatch;
    }

    private static boolean colorClose(int c1, int c2) {
        int r = ((c1 >> 16) & 0xFF) - ((c2 >> 16) & 0xFF);
        int g = ((c1 >>  8) & 0xFF) - ((c2 >>  8) & 0xFF);
        int b = ( c1        & 0xFF) - ( c2        & 0xFF);
        return (r * r + g * g + b * b) < 900; // 각 채널 ±30 이내
    }

    /**
     * 템플릿 평균색과 가장 차이가 큰(=배경이 아닌 특징적인) 픽셀 오프셋을 최대
     * {@link #SAMPLE_COUNT}개 골라, 차이가 큰 순서대로 정렬해 반환한다.
     * 이런 픽셀은 화면의 무관한 배경 영역과 우연히 일치할 확률이 낮아 조기 탈락에 유리하다.
     */
    private static int[] buildSampleOffsets(int[] templatePixels, int tw, int th) {
        int total = tw * th;
        long sumR = 0, sumG = 0, sumB = 0;
        for (int p : templatePixels) {
            sumR += (p >> 16) & 0xFF;
            sumG += (p >> 8) & 0xFF;
            sumB += p & 0xFF;
        }
        int avgR = (int) (sumR / total), avgG = (int) (sumG / total), avgB = (int) (sumB / total);

        int[] distance = new int[total];
        Integer[] offsets = new Integer[total];
        for (int i = 0; i < total; i++) {
            distance[i] = distanceFromAvg(templatePixels[i], avgR, avgG, avgB);
            offsets[i] = i;
        }

        Arrays.sort(offsets, (a, b) -> Integer.compare(distance[b], distance[a]));

        int count = Math.min(SAMPLE_COUNT, total);
        int[] arr = new int[count];
        for (int i = 0; i < count; i++) arr[i] = offsets[i];
        return arr;
    }

    private static int distanceFromAvg(int c, int avgR, int avgG, int avgB) {
        int r = ((c >> 16) & 0xFF) - avgR;
        int g = ((c >> 8) & 0xFF) - avgG;
        int b = (c & 0xFF) - avgB;
        return r * r + g * g + b * b;
    }

    /** 전체 화면 스크린샷을 파일로 저장하고 경로 반환 */
    public static BufferedImage captureScreen() throws AWTException {
        Robot robot = new Robot();
        return robot.createScreenCapture(getAllScreenBounds());
    }

    /** 연결된 모든 모니터를 합친 가상 영역 반환 */
    private static Rectangle getAllScreenBounds() {
        Rectangle bounds = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
        }
        return bounds;
    }
}
