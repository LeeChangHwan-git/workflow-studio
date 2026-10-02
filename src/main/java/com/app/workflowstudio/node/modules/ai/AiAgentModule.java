package com.app.workflowstudio.node.modules.ai;

import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.*;
import com.app.workflowstudio.node.modules.filewrite.FileWriteModule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * AI 에이전트 — 아래쪽 소켓 3개에 부품을 끼워 구성한다 (n8n AI Agent 와 같은 방식).
 * <ul>
 *   <li>모델 (필수, 1개): 어떤 AI로 답할지 — 현재 Gemini</li>
 *   <li>메모리 (선택, 1개): 대화 ID별 최근 N턴 기억 (파일 저장)</li>
 *   <li>지시문 (선택, 여러 개): 역할·규칙. 직접 입력 또는 파일. 여러 개면 연결 순서대로 이어 붙임</li>
 * </ul>
 * 출력: AI 답변 텍스트. 사용한 모델·대화 ID 등은 ${출력변수.meta} 로 참조.
 */
@Component
@Order(200)
public class AiAgentModule implements NodeModule {

    public static final String TYPE = "AI_AGENT";
    public static final String SOCKET_MODEL = "model";
    public static final String SOCKET_MEMORY = "memory";
    public static final String SOCKET_INSTRUCTION = "instruction";

    private static final long MAX_INSTRUCTION_FILE_BYTES = 512 * 1024;

    private final GeminiClient gemini;
    private final ChatMemoryStore memoryStore;

    public AiAgentModule(GeminiClient gemini, ChatMemoryStore memoryStore) {
        this.gemini = gemini;
        this.memoryStore = memoryStore;
    }

    @Override
    public NodeTypeInfo info() {
        return NodeTypeInfo.withSockets(TYPE, "AI 에이전트", "🤖", "#7C3AED", "AI", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("prompt", "질문 / 요청 (${input} = 앞 노드 출력)", "textarea", "${input}", null),
                new NodeParam("outputVar", "출력 변수명", "text", "agent", null)
        ), List.of(
                new NodeSocket(SOCKET_MODEL, "모델", AiParts.KIND_MODEL, true, false),
                new NodeSocket(SOCKET_MEMORY, "메모리", AiParts.KIND_MEMORY, false, false),
                new NodeSocket(SOCKET_INSTRUCTION, "지시문", AiParts.KIND_INSTRUCTION, false, true)
        ));
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        // 1) 모델
        List<PipelineNode> models = ctx.parts(SOCKET_MODEL);
        if (models.isEmpty()) {
            throw new Exception("'모델' 소켓에 모델 부품(예: Gemini 모델)을 연결하세요");
        }
        PipelineNode modelNode = models.get(0);
        if (!AiParts.GeminiModel.TYPE.equals(modelNode.getType())) {
            throw new Exception("지원하지 않는 모델 부품입니다: " + modelNode.getType());
        }
        String apiKey = GeminiClient.resolveKey(NodeContext.configOf(modelNode, "apiKey", ""));
        String model = NodeContext.configOf(modelNode, "model", AiParts.GeminiModel.DEFAULT_MODEL);
        Double temperature = parseTemperature(NodeContext.configOf(modelNode, "temperature", ""));

        // 2) 지시문 (연결 순서대로)
        List<String> instructions = new ArrayList<>();
        for (PipelineNode ins : ctx.parts(SOCKET_INSTRUCTION)) {
            String text = instructionText(ins, ctx);
            if (!text.isBlank()) instructions.add(text.trim());
        }
        String system = String.join("\n\n", instructions);

        // 3) 메모리
        List<PipelineNode> memories = ctx.parts(SOCKET_MEMORY);
        PipelineNode memNode = memories.isEmpty() ? null : memories.get(0);
        String sessionKey = null;
        List<GeminiClient.Message> history = List.of();
        if (memNode != null) {
            sessionKey = ctx.resolve(NodeContext.configOf(memNode, "sessionKey", "default")).trim();
            if (sessionKey.isEmpty() || sessionKey.contains("${")) {
                throw new Exception("대화 ID를 만들 수 없습니다: '" + NodeContext.configOf(memNode, "sessionKey", "")
                        + "' → '" + sessionKey + "' (참조한 값이 있는지 확인하세요)");
            }
            int turns = parseInt(NodeContext.configOf(memNode, "windowTurns", "10"), 10);
            history = memoryStore.recent(memNode.getId(), sessionKey, turns);
        }

        // 4) 질문
        String prompt = ctx.str("prompt", "${input}");
        if (prompt == null || prompt.isBlank() || prompt.equals("${input}")) {
            throw new Exception("질문이 비어 있습니다 (앞 노드 출력이 없으면 '질문 / 요청'을 직접 입력하세요)");
        }

        // 5) 호출 + 메모리 저장
        String answer = gemini.generate(apiKey, model, system, history, prompt, temperature);
        if (memNode != null) memoryStore.append(memNode.getId(), sessionKey, prompt, answer);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("model", model);
        meta.put("instructions", instructions.size());
        meta.put("sessionKey", sessionKey);
        meta.put("historyMessages", history.size());
        ctx.vars().put(ctx.raw("outputVar", "agent") + ".meta", ctx.objectMapper().writeValueAsString(meta));
        return answer;
    }

    private String instructionText(PipelineNode ins, NodeContext ctx) throws Exception {
        String source = NodeContext.configOf(ins, "source", AiParts.Instruction.SOURCE_TEXT);
        if (AiParts.Instruction.SOURCE_FILE.equals(source)) {
            String raw = NodeContext.configOf(ins, "filePath", "");
            if (raw.isBlank()) throw new Exception("지시문 파일이 지정되지 않았습니다");
            Path p = FileWriteModule.expandPath(ctx.resolve(raw));
            if (!Files.isRegularFile(p)) throw new Exception("지시문 파일을 찾을 수 없습니다: " + p);
            if (Files.size(p) > MAX_INSTRUCTION_FILE_BYTES) throw new Exception("지시문 파일이 너무 큽니다 (512KB 초과): " + p);
            return ctx.resolve(decode(Files.readAllBytes(p)));
        }
        return ctx.resolve(NodeContext.configOf(ins, "text", ""));
    }

    /** UTF-8(BOM 포함)로 읽고, 깨지면 Windows 한글(MS949)로 읽음 */
    static String decode(byte[] bytes) {
        int off = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("MS949"));
        }
    }

    private static Double parseTemperature(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            double t = Double.parseDouble(s.trim());
            return Math.max(0, Math.min(2, t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (Exception e) {
            return def;
        }
    }
}
