package com.app.workflowstudio.node.modules.approval;

import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.*;
import com.app.workflowstudio.node.modules.ai.AiParts;
import com.app.workflowstudio.node.modules.ai.GeminiClient;
import com.app.workflowstudio.node.modules.filewrite.FileWriteModule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 확인 요청 — 파이프라인을 멈추고 사람에게 내용을 보여 준 뒤 결정을 받는다 (Human-in-the-loop).
 * <ul>
 *   <li>✅ 승인 (그대로 / 고쳐서) → "승인" 출력으로 계속</li>
 *   <li>🔁 다시 + 의견 → 모델 소켓에 AI가 끼워져 있으면 이 노드 안에서 AI가 고쳐 다시 묻고(반복),
 *       없으면 "다시" 출력으로 흘려보냄 (뒤에 새 AI 노드를 이어 ${출력변수.feedback} 로 고치게)</li>
 *   <li>❌ 거절 → "거절" 출력</li>
 * </ul>
 * 출력 내용 = 최종 문서(승인 때 고친 내용 포함). 결정·의견·횟수는 ${출력변수.decision} · .feedback · .rounds
 */
@Component
@Order(160)
public class ApprovalModule implements NodeModule {

    public static final String TYPE = "APPROVAL";
    public static final String OUT_APPROVE = "approve";
    public static final String OUT_RETRY = "retry";
    public static final String OUT_REJECT = "reject";
    static final String SOCKET_MODEL = "model";
    static final String SOCKET_INSTRUCTION = "instruction";
    static final String TIMEOUT_REJECT = "거절로 처리";
    static final String TIMEOUT_APPROVE = "승인으로 처리";

    private final ApprovalService approvals;
    private final GeminiClient gemini;

    public ApprovalModule(ApprovalService approvals, GeminiClient gemini) {
        this.approvals = approvals;
        this.gemini = gemini;
    }

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "확인 요청 (승인)", "🙋", "#DB2777", "흐름", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("guide", "사용법", "approvalInfo", null, null),
                new NodeParam("title", "제목 — 확인 창 맨 위 (${...} 사용 가능)", "text", "확인 요청", null),
                new NodeParam("content", "보여 줄 내용 (마크다운, ${input} = 앞 노드 출력)", "textarea", "${input}", null),
                new NodeParam("timeoutMin", "기다릴 시간 (분, 0 = 결정할 때까지 계속)", "number", 60, null),
                new NodeParam("onTimeout", "시간이 지나면", "select", TIMEOUT_REJECT, List.of(TIMEOUT_REJECT, TIMEOUT_APPROVE)),
                new NodeParam("maxRounds", "AI 수정 최대 횟수 (모델 소켓에 AI 를 끼웠을 때)", "number", 5, null),
                new NodeParam("outputVar", "출력 변수명", "text", "approval", null)
        ), false, List.of(
                new NodeSocket(SOCKET_MODEL, "수정 AI (선택)", AiParts.KIND_MODEL, false, false),
                new NodeSocket(SOCKET_INSTRUCTION, "수정 지시문", AiParts.KIND_INSTRUCTION, false, true)
        ), null, List.of(
                new NodeOutput(OUT_APPROVE, "승인"),
                new NodeOutput(OUT_RETRY, "다시"),
                new NodeOutput(OUT_REJECT, "거절")
        ), null);
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        String content = ctx.str("content", "${input}");
        if (content == null || content.equals("${input}")) content = ctx.input() == null ? "" : ctx.input();
        if (content.isBlank()) throw new Exception("보여 줄 내용이 비어 있습니다 (앞 노드 출력이 없으면 '보여 줄 내용'을 직접 입력)");
        String title = ctx.str("title", "확인 요청").trim();
        long timeout = Math.max(0, ctx.intVal("timeoutMin", 60)) * 60_000L;
        int maxRounds = Math.max(1, Math.min(20, ctx.intVal("maxRounds", 5)));
        PipelineNode model = ctx.parts(SOCKET_MODEL).isEmpty() ? null : ctx.parts(SOCKET_MODEL).get(0);
        Object nm = ctx.node().getConfig() == null ? null : ctx.node().getConfig().get("name");
        String nodeName = nm instanceof String s && !s.isBlank() ? s : "확인 요청";

        List<Map<String, String>> history = new ArrayList<>();
        int round = 1;
        ApprovalService.Decision d;
        while (true) {
            boolean canRevise = model != null && round <= maxRounds;
            ApprovalService.Pending p = approvals.open(ctx.executionId(), ctx.pipelineId(), ctx.pipelineName(),
                    ctx.node().getId(), nodeName, title, content, round, canRevise, history, timeout);
            ctx.status("waiting", "🙋 확인 대기 중" + (round > 1 ? " (" + round + "번째)" : "")
                    + " — 화면의 확인 요청 창에서 승인 / 다시 / 거절을 고르세요");
            d = approvals.await(p, timeout);
            if (ApprovalService.TIMEOUT.equals(d.decision())) {
                d = new ApprovalService.Decision(TIMEOUT_APPROVE.equals(ctx.raw("onTimeout", TIMEOUT_REJECT))
                        ? ApprovalService.APPROVE : ApprovalService.REJECT, "(시간 초과)", null);
            }
            if (ApprovalService.RETRY.equals(d.decision()) && canRevise) {
                // 이 노드 안에서 AI 가 의견을 반영해 고치고 다시 묻기
                ctx.status("running", "✍️ AI가 의견을 반영해 다시 쓰는 중… (" + round + "번째 의견)");
                history.add(Map.of("round", String.valueOf(round), "feedback", d.feedback()));
                content = revise(ctx, model, content, d.feedback());
                round++;
                continue;
            }
            break;
        }

        if (ApprovalService.APPROVE.equals(d.decision()) && d.content() != null && !d.content().isBlank()) {
            content = d.content();   // 고쳐서 승인
        }
        String out = ctx.raw("outputVar", "approval");
        String route = switch (d.decision()) {
            case ApprovalService.APPROVE -> OUT_APPROVE;
            case ApprovalService.RETRY -> OUT_RETRY;
            default -> OUT_REJECT;
        };
        ctx.route(List.of(route));
        ctx.vars().put(out + ".decision", route);
        ctx.vars().put(out + ".feedback", d.feedback() == null ? "" : d.feedback());
        ctx.vars().put(out + ".rounds", String.valueOf(round));
        return content;
    }

    /** 모델 부품으로 문서 고치기 — 지시문 소켓 + 기본 편집 규칙 */
    private String revise(NodeContext ctx, PipelineNode model, String content, String feedback) throws Exception {
        if (!AiParts.GeminiModel.TYPE.equals(model.getType()))
            throw new Exception("지원하지 않는 모델 부품입니다: " + model.getType());
        String apiKey = GeminiClient.resolveKey(NodeContext.configOf(model, "apiKey", ""));
        String modelName = NodeContext.configOf(model, "model", AiParts.GeminiModel.DEFAULT_MODEL);
        List<String> system = new ArrayList<>();
        for (PipelineNode ins : ctx.parts(SOCKET_INSTRUCTION)) {
            String src = NodeContext.configOf(ins, "source", AiParts.Instruction.SOURCE_TEXT);
            String text;
            if (AiParts.Instruction.SOURCE_FILE.equals(src)) {
                Path f = FileWriteModule.expandPath(ctx.resolve(NodeContext.configOf(ins, "filePath", "")));
                text = Files.isRegularFile(f) ? Files.readString(f) : "";
            } else {
                text = NodeContext.configOf(ins, "text", "");
            }
            if (!text.isBlank()) system.add(ctx.resolve(text).trim());
        }
        system.add("당신은 문서를 고치는 편집자입니다. 사용자의 의견을 정확히 반영해 문서를 고치고, 고친 문서 전체만 출력하세요. "
                + "의견과 관계없는 부분은 그대로 두고, 원래 형식(마크다운 제목·표·목록)을 유지하며, 설명·인사말·코드 블록(```)을 붙이지 마세요.");
        String prompt = "[사용자 의견]\n" + (feedback == null || feedback.isBlank() ? "(의견 없음 — 더 읽기 좋게 다듬기)" : feedback)
                + "\n\n[현재 문서]\n" + content;
        String out = gemini.generate(apiKey, modelName, String.join("\n\n", system), List.of(), prompt, 0.3);
        String t = out.strip();
        if (t.startsWith("```")) t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").strip();
        return t;
    }
}
