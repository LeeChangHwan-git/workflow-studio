package com.app.memobookmark.service;

import com.app.memobookmark.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
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
    private final MemoService memoService;
    private final BookmarkService bookmarkService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String CLAUDE_API = "https://api.anthropic.com/v1/messages";

    // executionId -> execution state
    private final Map<String, ExecutionState> executions = new ConcurrentHashMap<>();

    public PipelineRunnerService(PipelineService pipelineService,
                                  AppSettingsService settingsService,
                                  MemoService memoService,
                                  BookmarkService bookmarkService,
                                  ObjectMapper objectMapper) {
        this.pipelineService = pipelineService;
        this.settingsService = settingsService;
        this.memoService     = memoService;
        this.bookmarkService = bookmarkService;
        this.objectMapper    = objectMapper;
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

            case "HTTP_REQUEST" -> {
                String method   = str(cfg, "method", "GET");
                String url      = resolveVars(str(cfg, "url", ""), ctx);
                String hdrsRaw  = str(cfg, "headers", "");
                String bodyType = str(cfg, "bodyType", "없음");
                String bodyRaw  = resolveVars(str(cfg, "body", ""), ctx);

                HttpHeaders hdrs = new HttpHeaders();
                if (hdrsRaw != null && !hdrsRaw.isBlank()) {
                    for (String line : hdrsRaw.split("\\r?\\n")) {
                        int ci = line.indexOf(':');
                        if (ci > 0) hdrs.set(line.substring(0, ci).trim(), line.substring(ci + 1).trim());
                    }
                }

                HttpEntity<String> entity;
                switch (bodyType) {
                    case "JSON" -> { hdrs.setContentType(MediaType.APPLICATION_JSON); entity = new HttpEntity<>(bodyRaw, hdrs); }
                    case "Form" -> { hdrs.setContentType(MediaType.APPLICATION_FORM_URLENCODED); entity = new HttpEntity<>(bodyRaw, hdrs); }
                    case "Raw"  -> { entity = new HttpEntity<>(bodyRaw, hdrs); }
                    default     -> { entity = new HttpEntity<>(hdrs); }
                }

                String responseBody;
                int statusCode;
                try {
                    ResponseEntity<String> resp = restTemplate.exchange(
                            url, HttpMethod.valueOf(method), entity, String.class);
                    statusCode   = resp.getStatusCode().value();
                    responseBody = resp.getBody() != null ? resp.getBody() : "";
                } catch (HttpStatusCodeException ex) {
                    statusCode   = ex.getStatusCode().value();
                    responseBody = ex.getResponseBodyAsString();
                }

                // JSON이면 pretty-print, 아니면 그대로
                String pretty;
                try { pretty = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(objectMapper.readTree(responseBody)); }
                catch (Exception e) { pretty = responseBody; }

                yield "HTTP " + statusCode + "\n" + pretty;
            }

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

            case "OUTLINE_COLLECTIONS" -> executeOutline("컬렉션 목록",      cfg, ctx);
            case "OUTLINE_DOCS"        -> executeOutline("문서 목록",         cfg, ctx);
            case "OUTLINE_SEARCH"      -> executeOutline("문서 검색",         cfg, ctx);
            case "OUTLINE_EXPORT"      -> executeOutline("문서 내보내기(MD)", cfg, ctx);
            // 레거시 노드 타입 호환
            case "OUTLINE"             -> executeOutline(str(cfg, "operation", "문서 목록"), cfg, ctx);

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
                        CLAUDE_API, req, Map.class);
                if (resp == null) throw new Exception("API 응답 없음");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
                yield (String) content.get(0).get("text");
            }

            case "AI_AGENT" -> {
                String apiKey = settings.getClaudeApiKey();
                if (apiKey == null || apiKey.isBlank())
                    throw new Exception("Claude API 키가 설정되지 않았습니다. 설정 메뉴에서 입력하세요.");

                String model       = str(cfg, "model", settings.getClaudeModel());
                String sysPrompt   = resolveVars(str(cfg, "systemPrompt",
                        "당신은 유능한 AI 에이전트입니다. 제공된 도구를 활용해 사용자의 요청을 정확하게 완수하세요."), ctx);
                String userInput   = resolveVars(str(cfg, "userInput", ""), ctx);
                String toolsConfig = str(cfg, "tools", "없음");
                int    maxIter     = intVal(cfg, "maxIter", 5);

                List<Map<String, Object>> agentTools = buildAgentTools(toolsConfig);

                HttpHeaders hdrs = new HttpHeaders();
                hdrs.set("x-api-key", apiKey);
                hdrs.set("anthropic-version", "2023-06-01");
                hdrs.setContentType(MediaType.APPLICATION_JSON);

                // 대화 메시지 누적 리스트
                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(Map.of("role", "user", "content", userInput));

                StringBuilder trace   = new StringBuilder();
                String        answer  = null;

                for (int iter = 0; iter < maxIter; iter++) {
                    Map<String, Object> reqBody = new LinkedHashMap<>();
                    reqBody.put("model",     model);
                    reqBody.put("max_tokens", 4096);
                    reqBody.put("system",    sysPrompt);
                    reqBody.put("messages",  messages);
                    if (!agentTools.isEmpty()) reqBody.put("tools", agentTools);

                    @SuppressWarnings("unchecked")
                    Map<String, Object> resp = restTemplate.postForObject(
                            CLAUDE_API, new HttpEntity<>(reqBody, hdrs), Map.class);
                    if (resp == null) throw new Exception("API 응답 없음");

                    String stopReason = (String) resp.get("stop_reason");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> content =
                            (List<Map<String, Object>>) resp.get("content");

                    // 텍스트 블록 수집
                    String textBlock = content.stream()
                            .filter(c -> "text".equals(c.get("type")))
                            .map(c -> (String) c.get("text"))
                            .filter(t -> t != null && !t.isBlank())
                            .findFirst().orElse(null);

                    if (!"tool_use".equals(stopReason)) {
                        // 최종 응답
                        answer = textBlock != null ? textBlock : "";
                        break;
                    }

                    // 도구 호출 — 에이전트 중간 발언 기록
                    if (textBlock != null) trace.append(textBlock).append("\n\n");

                    // 어시스턴트 turn 추가 (tool_use 블록 포함 전체 content)
                    messages.add(Map.of("role", "assistant", "content", content));

                    // 각 tool_use 블록을 실행하고 결과 수집
                    List<Map<String, Object>> toolResults = new ArrayList<>();
                    for (Map<String, Object> block : content) {
                        if (!"tool_use".equals(block.get("type"))) continue;
                        String toolId   = (String) block.get("id");
                        String toolName = (String) block.get("name");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> toolInput = (Map<String, Object>) block.get("input");

                        trace.append("▶ 도구 호출: ").append(toolName)
                             .append(" ").append(toolInput).append("\n");
                        String result;
                        try {
                            result = executeTool(toolName, toolInput);
                            trace.append("◀ 결과: ")
                                 .append(result, 0, Math.min(result.length(), 300))
                                 .append(result.length() > 300 ? "..." : "")
                                 .append("\n\n");
                        } catch (Exception e) {
                            result = "오류: " + e.getMessage();
                            trace.append("◀ ").append(result).append("\n\n");
                        }

                        Map<String, Object> tr = new LinkedHashMap<>();
                        tr.put("type",        "tool_result");
                        tr.put("tool_use_id", toolId);
                        tr.put("content",     result);
                        toolResults.add(tr);
                    }

                    // tool_result를 user turn으로 추가
                    messages.add(Map.of("role", "user", "content", toolResults));
                }

                if (answer == null)
                    answer = "최대 도구 호출 횟수(" + maxIter + ")에 도달했습니다.";

                // 도구 사용 내역이 있으면 앞에 붙여 반환 (실행 로그에 표시)
                yield trace.length() > 0
                        ? "=== 에이전트 실행 과정 ===\n" + trace + "\n=== 최종 답변 ===\n" + answer
                        : answer;
            }

            default -> throw new Exception("알 수 없는 노드 타입: " + node.getType());
        };
    }

    // ── Outline API 공통 실행 ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String executeOutline(String operation, Map<String, Object> cfg, Map<String, Object> ctx) throws Exception {
        AppSettings s = settingsService.load();
        String baseUrl = s.getOutlineUrl();
        String token   = s.getOutlineToken();
        if (baseUrl == null || baseUrl.isBlank())
            throw new Exception("Outline URL이 설정되지 않았습니다. 설정 메뉴에서 입력하세요.");
        if (token == null || token.isBlank())
            throw new Exception("Outline API 토큰이 설정되지 않았습니다. 설정 메뉴에서 입력하세요.");

        String collId = resolveVars(str(cfg, "collectionId", ""), ctx);
        String docId  = resolveVars(str(cfg, "documentId",   ""), ctx);
        String query  = resolveVars(str(cfg, "query",        ""), ctx);
        int    limit  = intVal(cfg, "limit", 50);

        HttpHeaders hdrs = new HttpHeaders();
        hdrs.set("Authorization", "Bearer " + token);
        hdrs.setContentType(MediaType.APPLICATION_JSON);
        String apiBase = baseUrl.replaceAll("/+$", "") + "/api";

        return switch (operation) {
            case "컬렉션 목록" -> {
                Map<String, Object> body = Map.of("limit", limit);
                Map<String, Object> resp = restTemplate.postForObject(
                        apiBase + "/collections.list", new HttpEntity<>(body, hdrs), Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
                StringBuilder sb = new StringBuilder("# 아웃라인 컬렉션 목록\n\n");
                for (Map<String, Object> col : data)
                    sb.append("- **").append(col.get("name")).append("**")
                      .append("  *(ID: `").append(col.get("id")).append("`)*\n");
                yield sb.toString();
            }
            case "문서 목록" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("limit", limit);
                if (collId != null && !collId.isBlank()) body.put("collectionId", collId);
                Map<String, Object> resp = restTemplate.postForObject(
                        apiBase + "/documents.list", new HttpEntity<>(body, hdrs), Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
                StringBuilder sb = new StringBuilder("# 아웃라인 문서 목록\n\n");
                for (Map<String, Object> doc : data)
                    sb.append("- ").append(doc.get("title"))
                      .append("  *(ID: `").append(doc.get("id")).append("`)*\n");
                yield sb.toString();
            }
            case "문서 검색" -> {
                if (query == null || query.isBlank()) throw new Exception("검색어(query)가 비어있습니다.");
                Map<String, Object> body = Map.of("query", query, "limit", limit);
                Map<String, Object> resp = restTemplate.postForObject(
                        apiBase + "/documents.search", new HttpEntity<>(body, hdrs), Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
                StringBuilder sb = new StringBuilder("# 검색 결과: \"" + query + "\"\n\n");
                for (Map<String, Object> item : data) {
                    Map<String, Object> doc = (Map<String, Object>) item.get("document");
                    sb.append("## ").append(doc.get("title")).append("\n");
                    sb.append("ID: `").append(doc.get("id")).append("`\n");
                    if (item.get("context") != null)
                        sb.append("> ").append(item.get("context")).append("\n");
                    sb.append("\n");
                }
                yield sb.toString();
            }
            case "문서 내보내기(MD)" -> {
                if (docId == null || docId.isBlank()) throw new Exception("문서 ID(documentId)가 비어있습니다.");
                Map<String, Object> body = Map.of("id", docId);
                Map<String, Object> resp = restTemplate.postForObject(
                        apiBase + "/documents.export", new HttpEntity<>(body, hdrs), Map.class);
                yield (String) resp.get("data");
            }
            default -> throw new Exception("알 수 없는 Outline 작업: " + operation);
        };
    }

    // ── AI 에이전트 도구 정의 ────────────────────────────────────────

    private List<Map<String, Object>> buildAgentTools(String toolsConfig) {
        List<Map<String, Object>> tools = new ArrayList<>();
        boolean http   = toolsConfig.contains("HTTP")  || toolsConfig.contains("전체");
        boolean search = toolsConfig.contains("검색")  || toolsConfig.contains("전체");

        if (http) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("method",  Map.of("type", "string", "description", "HTTP 메소드",
                                        "enum", List.of("GET","POST","PUT","DELETE","PATCH")));
            props.put("url",     Map.of("type", "string",  "description", "요청 URL"));
            props.put("headers", Map.of("type", "object",  "description", "HTTP 헤더 key-value (선택)"));
            props.put("body",    Map.of("type", "string",  "description", "요청 바디 (POST/PUT 시, 선택)"));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type",       "object");
            schema.put("properties", props);
            schema.put("required",   List.of("method", "url"));

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name",         "http_request");
            tool.put("description",  "외부 URL에 HTTP 요청을 보내고 응답을 반환합니다. REST API 호출, 웹 데이터 조회에 사용합니다.");
            tool.put("input_schema", schema);
            tools.add(tool);
        }

        if (search) {
            tools.add(makeSingleQueryTool("search_memo",
                    "앱에 저장된 메모를 키워드로 검색합니다. 계정 정보, 비밀번호, 업무 메모를 찾을 때 사용합니다."));
            tools.add(makeSingleQueryTool("search_bookmark",
                    "앱에 저장된 북마크를 키워드로 검색합니다. 자주 방문하는 사이트 URL을 찾을 때 사용합니다."));
        }

        return tools;
    }

    private Map<String, Object> makeSingleQueryTool(String name, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type",       "object");
        schema.put("properties", Map.of("query",
                Map.of("type", "string", "description", "검색 키워드")));
        schema.put("required",   List.of("query"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name",         name);
        tool.put("description",  description);
        tool.put("input_schema", schema);
        return tool;
    }

    // ── AI 에이전트 도구 실행 ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String executeTool(String toolName, Map<String, Object> input) throws Exception {
        return switch (toolName) {

            case "http_request" -> {
                String method = String.valueOf(input.getOrDefault("method", "GET"));
                String url    = (String) input.get("url");
                if (url == null || url.isBlank()) throw new Exception("url 파라미터가 필요합니다");

                HttpHeaders hdrs = new HttpHeaders();
                Object headersObj = input.get("headers");
                if (headersObj instanceof Map<?, ?> hm) {
                    hm.forEach((k, v) -> hdrs.set(String.valueOf(k), String.valueOf(v)));
                }

                String bodyStr = input.get("body") instanceof String s ? s : null;
                HttpEntity<String> entity = bodyStr != null
                        ? new HttpEntity<>(bodyStr, hdrs)
                        : new HttpEntity<>(hdrs);

                String responseBody;
                int    statusCode;
                try {
                    ResponseEntity<String> resp = restTemplate.exchange(
                            url, HttpMethod.valueOf(method), entity, String.class);
                    statusCode   = resp.getStatusCode().value();
                    responseBody = resp.getBody() != null ? resp.getBody() : "";
                } catch (HttpStatusCodeException ex) {
                    statusCode   = ex.getStatusCode().value();
                    responseBody = ex.getResponseBodyAsString();
                }

                try {
                    responseBody = objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(objectMapper.readTree(responseBody));
                } catch (Exception ignored) {}

                yield "HTTP " + statusCode + "\n" + responseBody;
            }

            case "search_memo" -> {
                String query = String.valueOf(input.getOrDefault("query", ""));
                var memos = memoService.search(query);
                if (memos.isEmpty()) yield "검색 결과 없음 (키워드: " + query + ")";
                StringBuilder sb = new StringBuilder();
                memos.forEach(m -> sb.append("제목: ").append(m.getTitle())
                        .append(" | 카테고리: ").append(m.getCategory())
                        .append("\n내용: ").append(m.getContent()).append("\n---\n"));
                yield sb.toString();
            }

            case "search_bookmark" -> {
                String query = String.valueOf(input.getOrDefault("query", ""));
                var bookmarks = bookmarkService.search(query);
                if (bookmarks.isEmpty()) yield "검색 결과 없음 (키워드: " + query + ")";
                StringBuilder sb = new StringBuilder();
                bookmarks.forEach(b -> sb.append("이름: ").append(b.getName())
                        .append(" | URL: ").append(b.getUrl())
                        .append(" | 카테고리: ").append(b.getCategory())
                        .append("\n설명: ").append(b.getDescription()).append("\n---\n"));
                yield sb.toString();
            }

            default -> throw new Exception("알 수 없는 도구: " + toolName);
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
