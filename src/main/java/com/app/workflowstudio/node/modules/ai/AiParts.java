package com.app.workflowstudio.node.modules.ai;

import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeParam;
import com.app.workflowstudio.node.NodeTypeInfo;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 에이전트 소켓에 끼우는 부품 노드들 (모델 · 메모리 · 지시문).
 * 부품은 단독 실행되지 않고, {@link AiAgentModule}이 소켓에 끼워진 부품의 설정값을 읽어 사용한다.
 */
public final class AiParts {

    /** 부품 종류 = 에이전트 소켓의 accepts 값 */
    public static final String KIND_MODEL = "ai_model";
    public static final String KIND_MEMORY = "ai_memory";
    public static final String KIND_INSTRUCTION = "ai_instruction";

    public static final String CATEGORY = "AI 부품";

    private AiParts() {}

    /** 부품 노드는 실행 엔진이 건너뛰므로 호출될 일이 없음 */
    private abstract static class Part implements NodeModule {
        @Override
        public String execute(NodeContext ctx) throws Exception {
            throw new Exception("부품 노드는 AI 에이전트의 소켓에 연결해서 사용합니다");
        }
    }

    // ── 모델: Gemini ─────────────────────────────────────────────
    @Component
    @Order(210)
    public static class GeminiModel extends Part {
        public static final String TYPE = "GEMINI_MODEL";
        public static final String DEFAULT_MODEL = "gemini-flash-latest";

        @Override
        public NodeTypeInfo info() {
            return NodeTypeInfo.part(TYPE, "Gemini 모델", "✨", "#4285F4", CATEGORY, List.of(
                    new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                    new NodeParam("apiKey", "API 키 (비우면 GEMINI_API_KEY 환경 변수, 무료 발급: aistudio.google.com/apikey)",
                            "password", "", null),
                    new NodeParam("model", "모델 (키·모델을 바꾸면 자동으로 연결 확인)", "geminiModel", DEFAULT_MODEL, null),
                    new NodeParam("temperature", "온도 0~2 (낮을수록 일관, 높을수록 다양)", "number", 0.7, null)
            ), KIND_MODEL);
        }
    }

    // ── 메모리: 최근 N턴 + 파일 저장 ──────────────────────────────
    @Component
    @Order(220)
    public static class ChatMemory extends Part {
        public static final String TYPE = "CHAT_MEMORY";

        @Override
        public NodeTypeInfo info() {
            return NodeTypeInfo.part(TYPE, "대화 메모리", "🧠", "#8B5CF6", CATEGORY, List.of(
                    new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                    new NodeParam("sessionKey", "대화 ID — 같은 ID끼리 대화를 이어서 기억 (예: ${webhook.body.userId})",
                            "text", "default", null),
                    new NodeParam("windowTurns", "기억할 최근 대화 수 (질문+답 = 1턴)", "number", 10, null),
                    new NodeParam("memoryInfo", "저장된 대화", "memoryInfo", null, null)
            ), KIND_MEMORY);
        }
    }

    // ── 지시문: 직접 입력 또는 파일 ───────────────────────────────
    @Component
    @Order(230)
    public static class Instruction extends Part {
        public static final String TYPE = "AI_INSTRUCTION";
        public static final String SOURCE_TEXT = "직접 입력";
        public static final String SOURCE_FILE = "파일";

        @Override
        public NodeTypeInfo info() {
            return NodeTypeInfo.part(TYPE, "지시문", "📜", "#10B981", CATEGORY, List.of(
                    new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                    new NodeParam("source", "지시문 가져오기", "select", SOURCE_TEXT, List.of(SOURCE_TEXT, SOURCE_FILE)),
                    new NodeParam("text", "지시문 (${...} 사용 가능)", "textarea",
                            "당신은 친절하고 정확한 한국어 비서입니다.", null, "source=" + SOURCE_TEXT),
                    new NodeParam("filePath", "지시문 파일 (txt/md, 실행할 때마다 다시 읽음 — 파일 안에서도 ${...} 사용 가능)",
                            "file", "", null, "source=" + SOURCE_FILE)
            ), KIND_INSTRUCTION);
        }
    }
}
