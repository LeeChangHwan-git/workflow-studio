package com.app.workflowstudio.assistant;

import com.app.workflowstudio.assistant.llm.ChatModel;
import com.app.workflowstudio.assistant.llm.ChatModelFactory;
import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.service.PipelineService;
import com.app.workflowstudio.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 파이프라인 도우미 — AI가 도구를 호출해 data/pipelines 의 파이프라인을 실제로 만들고·고치고·(확인 후) 지운다.
 * 대화는 메모리에 보관 (서버 재시작 시 초기화, 최근 30개).
 */
@Service
public class PipelineAssistantService {

    private static final Logger log = LoggerFactory.getLogger(PipelineAssistantService.class);
    private static final int MAX_CONVERSATIONS = 30;

    /** AI가 한 일 (화면 표시용) */
    public record Action(String type, String pipelineId, String name) {}

    /** 도구 호출 기록 (화면의 "작업 과정") */
    public record Step(String tool, boolean ok, String summary) {}

    public record ChatResult(String conversationId, String reply, List<Action> actions, List<Step> steps,
                             String model) {}

    private static class Conversation {
        final ChatModel.Session session;
        final String model;
        final Set<String> pendingDeletes = ConcurrentHashMap.newKeySet();
        long lastUsed = System.currentTimeMillis();

        Conversation(ChatModel.Session session, String model) {
            this.session = session;
            this.model = model;
        }
    }

    private final ChatModelFactory models;
    private final PipelineToolkit kit;
    private final PipelineService pipelines;
    private final ObjectMapper om;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public PipelineAssistantService(ChatModelFactory models, PipelineToolkit kit, PipelineService pipelines, ObjectMapper om) {
        this.models = models;
        this.kit = kit;
        this.pipelines = pipelines;
        this.om = om;
    }

    /**
     * 메시지 처리. conversationId 가 없거나 모르는 id 면 새 대화.
     * @param currentPipelineId 사용자가 지금 보고 있는 파이프라인 (없으면 null) — "이거 고쳐줘" 해석용
     */
    public ChatResult chat(String conversationId, String message, String currentPipelineId) throws Exception {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("메시지를 입력하세요");
        Conversation conv = conversationId == null ? null : conversations.get(conversationId);
        if (conv == null) {
            ChatModel model = models.create();
            conv = new Conversation(model.start(systemPrompt(), tools()), model.describe());
            conversationId = UUID.randomUUID().toString();
            conversations.put(conversationId, conv);
            evictOld();
        }
        conv.lastUsed = System.currentTimeMillis();

        String text = message.trim();
        if (currentPipelineId != null && !currentPipelineId.isBlank()) {
            Pipeline cur = pipelines.findById(currentPipelineId);
            if (cur != null) text += "\n\n(참고: 사용자가 지금 보고 있는 파이프라인 — id=" + cur.getId() + ", 이름=" + cur.getName() + ")";
        }

        List<Action> actions = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        synchronized (conv) {
            ChatModel.Reply reply = conv.session.send(text);
            int max = SettingsService.current().assistant.maxSteps;
            int used = 0;
            while (reply.hasCalls()) {
                if (used >= max) {
                    return new ChatResult(conversationId, (reply.text().isBlank() ? "" : reply.text() + "\n\n")
                            + "⚠️ 작업 단계가 " + max + "번을 넘어 멈췄습니다. 이어서 하려면 \"계속\"이라고 보내세요.",
                            actions, steps, conv.model);
                }
                List<ChatModel.ToolResult> results = new ArrayList<>();
                for (ChatModel.ToolCall call : reply.calls()) {
                    used++;
                    Object result;
                    try {
                        result = execute(conv, call, actions, steps);
                    } catch (Exception e) {
                        log.debug("도구 실패 {}", call.name(), e);
                        steps.add(new Step(call.name(), false, e.getMessage()));
                        result = Map.of("ok", false, "error", String.valueOf(e.getMessage()));
                    }
                    results.add(new ChatModel.ToolResult(call.id(), call.name(), result));
                }
                reply = conv.session.sendToolResults(results);
            }
            String answer = reply.text().isBlank() ? (actions.isEmpty() ? "(응답 없음)" : "완료했습니다.") : reply.text();
            return new ChatResult(conversationId, answer, actions, steps, conv.model);
        }
    }

    /** 사용자가 화면에서 삭제를 확인 → 백업 후 삭제 */
    public Action confirmDelete(String conversationId, String pipelineId) {
        Conversation conv = conversations.get(conversationId);
        if (conv == null || !conv.pendingDeletes.remove(pipelineId))
            throw new IllegalArgumentException("이 대화에서 삭제 요청된 파이프라인이 아닙니다");
        Pipeline p = pipelines.findById(pipelineId);
        if (p == null) throw new IllegalArgumentException("이미 없는 파이프라인입니다");
        pipelines.delete(pipelineId, "AI 삭제");
        return new Action("deleted", pipelineId, p.getName());
    }

    public void cancelDelete(String conversationId, String pipelineId) {
        Conversation conv = conversations.get(conversationId);
        if (conv != null) conv.pendingDeletes.remove(pipelineId);
    }

    public void reset(String conversationId) {
        if (conversationId != null) conversations.remove(conversationId);
    }

    /** 연결 확인 — 짧은 질문 하나 (도구 없음) */
    public Map<String, Object> test() {
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            ChatModel model = models.create();
            res.put("model", model.describe());
            long t = System.currentTimeMillis();
            ChatModel.Reply r = model.start("한 단어로만 답하세요.", List.of()).send("연결 확인입니다. '정상'이라고만 답하세요.");
            res.put("ok", true);
            res.put("message", "응답: " + (r.text().length() > 60 ? r.text().substring(0, 60) + "…" : r.text())
                    + " (" + (System.currentTimeMillis() - t) + "ms)");
        } catch (Exception e) {
            res.put("ok", false);
            res.put("message", e.getMessage());
        }
        return res;
    }

    // ── 도구 ────────────────────────────────────────────

    private Object execute(Conversation conv, ChatModel.ToolCall call, List<Action> actions, List<Step> steps) throws Exception {
        JsonNode a = call.args();
        switch (call.name()) {
            case "list_pipelines" -> {
                List<Map<String, Object>> list = pipelines.findAll().stream().map(kit::summary).toList();
                steps.add(new Step("list_pipelines", true, "파이프라인 " + list.size() + "개 확인"));
                return Map.of("pipelines", list);
            }
            case "get_pipeline" -> {
                Pipeline p = find(a.path("id").asText(""));
                steps.add(new Step("get_pipeline", true, "'" + p.getName() + "' 읽음"));
                return kit.toAi(p);
            }
            case "get_node_specs" -> {
                List<String> types = new ArrayList<>();
                a.path("types").forEach(t -> types.add(t.asText()));
                steps.add(new Step("get_node_specs", true, "노드 설명 " + String.join(", ", types)));
                return Map.of("specs", kit.specs(types));
            }
            case "create_pipeline" -> {
                JsonNode ai = pipelineArg(a);
                PipelineToolkit.Built b = kit.build(ai, null);
                if (!b.errors().isEmpty()) {
                    steps.add(new Step("create_pipeline", false, "검증 오류 " + b.errors().size() + "개 → AI가 고치는 중"));
                    return Map.of("ok", false, "errors", b.errors(), "hint", "오류를 고쳐서 create_pipeline 을 다시 호출하세요");
                }
                Pipeline saved = pipelines.create(b.pipeline());
                actions.add(new Action("created", saved.getId(), saved.getName()));
                steps.add(new Step("create_pipeline", true, "'" + saved.getName() + "' 만듦"));
                return Map.of("ok", true, "id", saved.getId(), "warnings", b.warnings());
            }
            case "update_pipeline" -> {
                Pipeline existing = find(a.path("id").asText(""));
                JsonNode ai = pipelineArg(a);
                PipelineToolkit.Built b = kit.build(ai, existing);
                if (!b.errors().isEmpty()) {
                    steps.add(new Step("update_pipeline", false, "검증 오류 " + b.errors().size() + "개 → AI가 고치는 중"));
                    return Map.of("ok", false, "errors", b.errors(), "hint", "오류를 고쳐서 update_pipeline 을 다시 호출하세요");
                }
                Pipeline saved = pipelines.update(existing.getId(), b.pipeline(), "AI 수정");
                actions.add(new Action("updated", saved.getId(), saved.getName()));
                steps.add(new Step("update_pipeline", true, "'" + saved.getName() + "' 수정 (이전 상태 백업됨)"));
                return Map.of("ok", true, "id", saved.getId(), "warnings", b.warnings());
            }
            case "delete_pipeline" -> {
                Pipeline p = find(a.path("id").asText(""));
                conv.pendingDeletes.add(p.getId());
                actions.add(new Action("delete_pending", p.getId(), p.getName()));
                steps.add(new Step("delete_pipeline", true, "'" + p.getName() + "' 삭제 요청 (사용자 확인 대기)"));
                return Map.of("ok", true, "status", "사용자 확인 대기 — 화면에 삭제 확인 버튼이 표시됩니다. 실제 삭제는 사용자가 누를 때 됩니다.");
            }
            default -> throw new IllegalArgumentException("없는 도구: " + call.name());
        }
    }

    private Pipeline find(String id) {
        Pipeline p = id.isBlank() ? null : pipelines.findById(id.trim());
        if (p == null) throw new IllegalArgumentException("파이프라인 id '" + id + "'를 찾을 수 없습니다 — list_pipelines 로 확인하세요");
        return p;
    }

    /** pipeline_json(문자열) 또는 pipeline(객체) */
    private JsonNode pipelineArg(JsonNode a) throws Exception {
        JsonNode p = a.path("pipeline_json");
        if (p.isTextual()) {
            String s = p.asText().trim();
            if (s.startsWith("```")) s = s.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
            try {
                return om.readTree(s);
            } catch (Exception e) {
                throw new IllegalArgumentException("pipeline_json 이 올바른 JSON 이 아닙니다: " + e.getMessage());
            }
        }
        if (p.isObject()) return p;
        if (a.path("pipeline").isObject()) return a.get("pipeline");
        throw new IllegalArgumentException("pipeline_json 인자가 필요합니다");
    }

    static List<ChatModel.ToolSpec> tools() {
        Map<String, Object> noArgs = Map.of("type", "object", "properties", Map.of());
        Map<String, Object> idArg = Map.of("type", "object",
                "properties", Map.of("id", Map.of("type", "string", "description", "파이프라인 id")),
                "required", List.of("id"));
        Map<String, Object> pipelineJson = Map.of("type", "string",
                "description", "파이프라인 전체 JSON 문자열: {\"name\",\"description\",\"nodes\":[{\"id\",\"type\",\"config\":{...}}],"
                        + "\"connections\":[{\"from\",\"to\",\"socket\"?}]}");
        return List.of(
                new ChatModel.ToolSpec("list_pipelines", "저장된 파이프라인 목록 (id, 이름, 설명, 노드 요약)", noArgs),
                new ChatModel.ToolSpec("get_pipeline", "파이프라인 하나의 전체 내용 (수정·삭제 전에 반드시 호출). 비밀값은 *** 로 가려짐", idArg),
                new ChatModel.ToolSpec("get_node_specs", "노드 타입의 설정 항목·선택지·기본값·소켓 상세",
                        Map.of("type", "object",
                                "properties", Map.of("types", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "노드 타입 id 목록 (예: [\"WEBHOOK\",\"AI_AGENT\"])")),
                                "required", List.of("types"))),
                new ChatModel.ToolSpec("create_pipeline", "새 파이프라인을 실제로 저장. 검증 오류가 있으면 저장하지 않고 errors 를 돌려줌",
                        Map.of("type", "object", "properties", Map.of("pipeline_json", pipelineJson), "required", List.of("pipeline_json"))),
                new ChatModel.ToolSpec("update_pipeline", "기존 파이프라인을 통째로 교체 저장 (이전 상태는 자동 백업). 바꾸지 않을 노드도 모두 포함해서 보낼 것",
                        Map.of("type", "object", "properties", Map.of(
                                "id", Map.of("type", "string", "description", "수정할 파이프라인 id"),
                                "pipeline_json", pipelineJson), "required", List.of("id", "pipeline_json"))),
                new ChatModel.ToolSpec("delete_pipeline", "파이프라인 삭제 요청 — 사용자가 화면에서 확인해야 실제로 삭제됨", idArg)
        );
    }

    String systemPrompt() {
        String specs = kit.allSpecsJson();
        boolean inline = specs.length() <= PipelineToolkit.INLINE_SPEC_LIMIT;
        return """
                너는 Workflow Studio(로컬 n8n 같은 자동화 앱)의 **파이프라인 전용 도우미**다. 오늘: %s
                사용자의 요청대로 도구를 호출해서 파이프라인을 **실제로** 만들고·수정하고·삭제 요청한다. 설명만 하고 끝내지 말 것.
                파이프라인과 관계없는 질문에는 "파이프라인 작업만 도와드릴 수 있어요"라고 짧게 답한다.

                ## 작업 순서
                1. 기존 파이프라인을 다룰 때: 이름만 들었으면 list_pipelines → get_pipeline 으로 전체를 읽고 나서 수정.
                2. 새로 만들 때: 필요한 노드 타입의 설정을 확인(아래 설명서%s) → create_pipeline.
                3. update_pipeline 은 파이프라인 **전체**를 다시 보낸다. 바꾸지 않는 노드·연결도 그대로 포함하고 기존 노드 id 는 유지
                   (id 가 같으면 캔버스 위치와 비밀값이 유지됨). 비밀값이 "***" 이면 그대로 "***" 로 보내면 원래 값이 유지된다.
                4. 도구가 errors 를 돌려주면 그 내용대로 고쳐서 다시 호출한다. 같은 실수를 반복하지 말 것.
                5. 삭제는 delete_pipeline (사용자가 화면에서 확인해야 실제 삭제).
                6. 마지막에 한국어로 짧게: 무엇을 했는지, 사용자가 직접 채워야 할 값(API 키·주소·폴더 등)과 다음 할 일(저장된 웹훅 켜기 등).
                   요청이 애매하면 합리적인 기본값으로 만들고 무엇을 가정했는지 알려준다. 정말 필요한 정보만 질문.

                ## 파이프라인 규칙
                - 형식: {"name","description","nodes":[{"id","type","config"}],"connections":[{"from","to"} 또는 {"from","to","socket"}]}
                - 노드 id: 영문·숫자·-·_ (예: hook, agent, save). type 은 설명서의 type 그대로.
                - config 에는 설명서에 있는 설정 이름만 쓴다. select 는 options 문자열을 **글자 그대로** 쓴다. 생략한 설정은 기본값.
                - 트리거(WEBHOOK, SCHEDULE 등)가 시작점. 트리거로 들어가는 화살표는 없다.
                - 화살표(socket 없음) = 실행 흐름, 앞 노드의 출력이 다음 노드의 ${input} 이 된다.
                - 부품 노드(kind=part:…)는 화살표 흐름에 넣지 않고 {"from":부품,"to":본체,"socket":소켓id} 로 본체 소켓에 끼운다.
                  예: AI_AGENT 는 model 소켓에 GEMINI_MODEL 필수, instruction 소켓에 AI_INSTRUCTION(지시문), memory 소켓에 CHAT_MEMORY(선택).
                - 템플릿: ${input} = 직전 노드 출력, ${출력변수명} 또는 ${출력변수명.필드.하위[0]} = 앞 노드 출력(JSON)의 값.
                  각 노드의 outputVar 가 출력변수명 (기본: webhook, timer, agent, http, file, outline …).
                  WEBHOOK 의 outputTemplate 는 요청 JSON 키로 조합 (예: "${q}"), 요청 전체는 ${webhook.request.body.키}.
                  모든 노드의 글자 설정에서 ${date}(2026-10-05) ${time}(09:00) ${datetime}(2026-10-05 09:00) 사용 가능
                  (FILE_WRITE 파일명에서는 파일명용 형식으로 바뀜). SCHEDULE 출력: ${timer.firedAt}.
                - 비밀값(API 키 등)은 모르면 비워 둔다 → 사용자가 ⚙ 설정의 공용 키를 쓰거나 직접 넣는다. 지어내지 말 것.
                - 웹훅 path 는 다른 파이프라인과 겹치지 않게 의미 있는 영문으로. 타이머는 사용자가 원할 때만 active 를 켠다.
                - Windows 경로는 %%USERPROFILE%%\\Desktop\\폴더 형식.
                - AI 에이전트에 넣는 지시문(AI_INSTRUCTION)은 역할·출력 형식·규칙을 구체적으로 쓴다.

                ## 노드 목록
                %s
                %s
                """.formatted(LocalDate.now(), inline ? "" : " — 상세는 get_node_specs",
                kit.catalogIndex(),
                inline ? "## 노드 설명서 (JSON)\n" + specs : "(설정 상세는 get_node_specs 로 확인한 뒤 사용할 것)");
    }

    private void evictOld() {
        if (conversations.size() <= MAX_CONVERSATIONS) return;
        conversations.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().lastUsed))
                .ifPresent(e -> conversations.remove(e.getKey()));
    }
}
