package com.app.memobookmark.service;

import com.app.memobookmark.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

@Service
public class PipelineRunnerService {

    private final PipelineService pipelineService;
    private final AppSettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    // executionId -> execution state
    private final Map<String, ExecutionState> executions = new ConcurrentHashMap<>();

    public PipelineRunnerService(PipelineService pipelineService,
                                  AppSettingsService settingsService,
                                  ObjectMapper objectMapper) {
        this.pipelineService = pipelineService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    public static class ExecutionState {
        public final String executionId;
        public final String pipelineId;
        public volatile String status = "running"; // running | done | error
        public final List<RunLog> logs = Collections.synchronizedList(new ArrayList<>());
        public volatile Thread thread;

        public ExecutionState(String executionId, String pipelineId) {
            this.executionId = executionId;
            this.pipelineId = pipelineId;
        }
    }

    /** 파이프라인 실행 시작 → executionId 반환 */
    public String run(String pipelineId) {
        Pipeline pipeline = pipelineService.findById(pipelineId);
        if (pipeline == null) throw new IllegalArgumentException("파이프라인을 찾을 수 없습니다");

        String execId = UUID.randomUUID().toString();
        ExecutionState state = new ExecutionState(execId, pipelineId);
        executions.put(execId, state);

        Thread t = new Thread(() -> executePipeline(pipeline, state), "pipeline-runner-" + execId);
        t.setDaemon(true);
        state.thread = t;
        t.start();

        return execId;
    }

    public ExecutionState getState(String executionId) {
        return executions.get(executionId);
    }

    public boolean cancel(String executionId) {
        ExecutionState state = executions.get(executionId);
        if (state == null || !"running".equals(state.status)) return false;
        if (state.thread != null) state.thread.interrupt();
        state.status = "error";
        return true;
    }

    // ── 실행 엔진 ──────────────────────────────────────────────

    private void executePipeline(Pipeline pipeline, ExecutionState state) {
        Map<String, Object> context = new HashMap<>(); // nodeId.output → value

        List<PipelineNode> ordered = topoSort(pipeline);

        for (PipelineNode node : ordered) {
            if (Thread.currentThread().isInterrupted()) {
                state.status = "error";
                return;
            }

            RunLog log = new RunLog(node.getId(), node.getType(), "running",
                    "[" + node.getType() + "] 실행 중...");
            state.logs.add(log);

            try {
                String output = executeStep(node, context, state);
                log.setStatus("success");
                log.setMessage("[" + node.getType() + "] 완료");
                log.setOutput(output);
                if (output != null) {
                    // outputVar 설정 있으면 해당 키로, 없으면 nodeId.output 으로 저장
                    Object varName = node.getConfig().get("outputVar");
                    String key = (varName instanceof String s && !s.isBlank())
                            ? s : node.getId() + ".output";
                    context.put(key, output);
                    context.put(node.getId() + ".output", output);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.setStatus("error");
                log.setMessage("실행 취소됨");
                state.status = "error";
                return;
            } catch (Exception e) {
                log.setStatus("error");
                log.setMessage("[" + node.getType() + "] 오류: " + e.getMessage());
                state.status = "error";
                return;
            }
        }

        state.status = "done";
    }

    private String executeStep(PipelineNode node, Map<String, Object> ctx, ExecutionState state)
            throws Exception {

        Map<String, Object> cfg = node.getConfig() != null ? node.getConfig() : Map.of();
        AppSettings settings = settingsService.load();

        return switch (node.getType()) {

            case "OPEN_URL" -> {
                String url = resolveVars(str(cfg, "url"), ctx);
                String browser = str(cfg, "browser", "기본");
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                String[] cmd;
                if (isWindows) {
                    cmd = switch (browser) {
                        case "Edge"   -> new String[]{"cmd", "/c", "start", "msedge", url};
                        case "Chrome" -> new String[]{"cmd", "/c", "start", "chrome", url};
                        default       -> new String[]{"cmd", "/c", "start", url};
                    };
                } else {
                    cmd = switch (browser) {
                        case "Edge"   -> new String[]{"open", "-a", "Microsoft Edge", url};
                        case "Chrome" -> new String[]{"open", "-a", "Google Chrome", url};
                        default       -> new String[]{"open", url};
                    };
                }
                new ProcessBuilder(cmd).start();
                Thread.sleep(1500);
                yield "URL 열기 완료: " + url;
            }

            case "WAIT_IMAGE" -> {
                String imagePath = str(cfg, "imagePath");
                int timeoutSec = intVal(cfg, "timeoutSec", 10);
                if (imagePath == null || imagePath.isBlank())
                    throw new Exception("이미지 경로가 설정되지 않았습니다");
                File imgFile = pipelineService.getImagePath(imagePath).toFile();
                long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Point found = ImageMatcherUtil.findOnScreen(imgFile, settings.getImageMatchThreshold());
                    if (found != null) yield "이미지 발견: " + found;
                    Thread.sleep(settings.getImageMatchRetryMs());
                }
                throw new Exception("이미지를 " + timeoutSec + "초 안에 찾지 못했습니다");
            }

            case "CLICK_IMAGE" -> {
                String imagePath = str(cfg, "imagePath");
                int offsetX = intVal(cfg, "offsetX", 0);
                int offsetY = intVal(cfg, "offsetY", 0);
                int timeoutSec = intVal(cfg, "timeoutSec", 8);
                if (imagePath == null || imagePath.isBlank())
                    throw new Exception("이미지 경로가 설정되지 않았습니다");
                File imgFile = pipelineService.getImagePath(imagePath).toFile();
                Point found = null;
                long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    found = ImageMatcherUtil.findOnScreen(imgFile, settings.getImageMatchThreshold());
                    if (found != null) break;
                    Thread.sleep(settings.getImageMatchRetryMs());
                }
                if (found == null) throw new Exception("이미지를 " + timeoutSec + "초 안에 화면에서 찾지 못했습니다");
                Robot robot = new Robot();
                robot.mouseMove(found.x + offsetX, found.y + offsetY);
                Thread.sleep(100);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(80);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                yield "이미지 클릭 완료: (" + (found.x + offsetX) + ", " + (found.y + offsetY) + ")";
            }

            case "TYPE_TEXT" -> {
                String text = resolveVars(str(cfg, "text", ""), ctx);
                Robot robot = new Robot();
                // 클립보드 경유 입력 (한글 등 멀티바이트 지원)
                java.awt.datatransfer.Clipboard cb =
                        Toolkit.getDefaultToolkit().getSystemClipboard();
                cb.setContents(new java.awt.datatransfer.StringSelection(text), null);
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                int modifier = isWindows ? KeyEvent.VK_CONTROL : KeyEvent.VK_META;
                robot.keyPress(modifier);
                robot.keyPress(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_V);
                robot.keyRelease(modifier);
                Thread.sleep(200);
                yield "텍스트 입력 완료: " + text.substring(0, Math.min(text.length(), 30));
            }

            case "KEY_PRESS" -> {
                String key = str(cfg, "key", "ENTER");
                Robot robot = new Robot();
                int keyCode = switch (key) {
                    case "ENTER"     -> KeyEvent.VK_ENTER;
                    case "TAB"       -> KeyEvent.VK_TAB;
                    case "ESC"       -> KeyEvent.VK_ESCAPE;
                    case "SPACE"     -> KeyEvent.VK_SPACE;
                    case "BACKSPACE" -> KeyEvent.VK_BACK_SPACE;
                    default -> {
                        int code = KeyEvent.getExtendedKeyCodeForChar(key.charAt(0));
                        if (code == KeyEvent.VK_UNDEFINED && key.startsWith("F")) {
                            code = KeyEvent.VK_F1 + (Integer.parseInt(key.substring(1)) - 1);
                        }
                        yield code;
                    }
                };
                robot.keyPress(keyCode);
                Thread.sleep(80);
                robot.keyRelease(keyCode);
                yield key + " 키 입력 완료";
            }

            case "DELAY" -> {
                int seconds = intVal(cfg, "seconds", 1);
                Thread.sleep(seconds * 1000L);
                yield seconds + "초 대기 완료";
            }

            case "SCREENSHOT" -> {
                BufferedImage img = ImageMatcherUtil.captureScreen();
                String varName = str(cfg, "outputVar", "screenshot");
                String filename = varName + "_" + System.currentTimeMillis() + ".png";
                Path dest = pipelineService.getImagePath("images/" + filename);
                ImageIO.write(img, "PNG", dest.toFile());
                String path = "images/" + filename;
                yield path;
            }

            case "CALL_AI" -> {
                String apiKey = settings.getClaudeApiKey();
                if (apiKey == null || apiKey.isBlank())
                    throw new Exception("Claude API 키가 설정되지 않았습니다. 설정 메뉴에서 입력하세요.");
                String prompt = resolveVars(str(cfg, "prompt", ""), ctx);
                String model = str(cfg, "model", settings.getClaudeModel());

                HttpHeaders headers = new HttpHeaders();
                headers.set("x-api-key", apiKey);
                headers.set("anthropic-version", "2023-06-01");
                headers.setContentType(MediaType.APPLICATION_JSON);

                Map<String, Object> body = Map.of(
                        "model", model,
                        "max_tokens", 2048,
                        "messages", List.of(Map.of("role", "user", "content", prompt))
                );
                HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = restTemplate.postForObject(
                        "https://api.anthropic.com/v1/messages", req, Map.class);
                if (resp == null) throw new Exception("API 응답 없음");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
                yield (String) content.get(0).get("text");
            }

            default -> throw new Exception("알 수 없는 노드 타입: " + node.getType());
        };
    }

    // ── 위상 정렬 (연결 순서대로 실행) ─────────────────────────

    private List<PipelineNode> topoSort(Pipeline pipeline) {
        if (pipeline.getNodes() == null) return List.of();
        if (pipeline.getConnections() == null || pipeline.getConnections().isEmpty()) {
            return new ArrayList<>(pipeline.getNodes());
        }

        Map<String, PipelineNode> nodeMap = new LinkedHashMap<>();
        for (PipelineNode n : pipeline.getNodes()) nodeMap.put(n.getId(), n);

        Map<String, List<String>> adj = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeMap.keySet()) {
            adj.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }
        for (PipelineConnection c : pipeline.getConnections()) {
            adj.get(c.getFromNodeId()).add(c.getToNodeId());
            inDegree.merge(c.getToNodeId(), 1, Integer::sum);
        }

        Queue<String> queue = new ArrayDeque<>();
        inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .forEach(queue::add);

        List<PipelineNode> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            PipelineNode node = nodeMap.get(id);
            if (node != null) result.add(node);
            for (String next : adj.getOrDefault(id, List.of())) {
                inDegree.merge(next, -1, Integer::sum);
                if (inDegree.get(next) == 0) queue.add(next);
            }
        }
        return result;
    }

    // ── 유틸 ────────────────────────────────────────────────────

    private String resolveVars(String text, Map<String, Object> ctx) {
        if (text == null) return null;
        String result = text;
        for (Map.Entry<String, Object> entry : ctx.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private String str(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        return v instanceof String s ? s : null;
    }

    private String str(Map<String, Object> cfg, String key, String def) {
        Object v = cfg.get(key);
        return v instanceof String s && !s.isBlank() ? s : def;
    }

    private int intVal(Map<String, Object> cfg, String key, int def) {
        Object v = cfg.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignore) {}
        }
        return def;
    }
}
