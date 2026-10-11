package com.app.workflowstudio.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 앱 설정 ({storage}/settings.json). 필드를 추가하면 기존 파일에 없던 값은 기본값으로 채워진다.
 * 비밀값(키)은 화면으로 내려보내지 않는다 — {@link SettingsService#SECRET_FIELDS}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

    public Credentials credentials = new Credentials();
    public Assistant assistant = new Assistant();
    public Backup backup = new Backup();
    public Appearance appearance = new Appearance();
    public Demo demo = new Demo();
    public Messenger messenger = new Messenger();
    public Modules modules = new Modules();

    /** 모듈 켜기/끄기 — 꺼진 모듈은 팔레트에서 숨김 (이미 놓인 노드는 그대로 실행) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Modules {
        public java.util.List<String> disabled = new java.util.ArrayList<>();
    }

    /** 공용 자격 증명 — 노드에서 비워 두면 이 값을 쓴다 (노드 → 설정 → 환경 변수 순) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Credentials {
        public String geminiApiKey = "";
        public String outlineBaseUrl = "";
        public String outlineApiKey = "";
    }

    /** 파이프라인 도우미 AI 연결 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Assistant {
        public static final String GEMINI = "gemini";
        public static final String OPENAI = "openai";

        /** gemini | openai (OpenAI 호환: Ollama, LM Studio, vLLM, OpenRouter 등) */
        public String provider = GEMINI;
        public String geminiModel = "gemini-flash-latest";
        /** 비우면 공용 Gemini 키 */
        public String geminiApiKey = "";
        public String openaiBaseUrl = "http://localhost:11434/v1";
        public String openaiModel = "qwen3:8b";
        /** Ollama 는 필요 없음 */
        public String openaiApiKey = "";
        public double temperature = 0.2;
        /** 요청 한 번에 AI가 도구를 부를 수 있는 최대 횟수 */
        public int maxSteps = 12;
    }

    /** 사내 메신저 — 받는 사람 그룹 (쪽지 노드에서 @그룹이름 으로 사용) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Messenger {
        public java.util.List<Group> groups = new java.util.ArrayList<>(java.util.List.of(
                new Group("FW팀", "20240001, 20231234", "펌웨어 팀 전체 (나 포함)"),
                new Group("HW-FW협업", "20240001, 20231234, 20220567", "HW·FW 연동 이슈 공유"),
                new Group("품질", "20210089, 20200345", "QA · 보안 담당"),
                new Group("리더", "20190012", "PM · 팀장 보고용")));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Group {
        /** 이름 — 공백·쉼표 없이 (쪽지 노드에서 @이름) */
        public String name = "";
        /** 사번 목록 (쉼표·공백·줄바꿈 구분) */
        public String members = "";
        public String description = "";

        public Group() {}

        public Group(String name, String members, String description) {
            this.name = name;
            this.members = members;
            this.description = description;
        }

        public java.util.List<String> memberIds() {
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (String s : (members == null ? "" : members).split("[,;\\s]+"))
                if (!s.isBlank() && !ids.contains(s.trim())) ids.add(s.trim());
            return ids;
        }
    }

    /** 시연용 기능 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Demo {
        /** 시연용 메신저에서 "나(이 PC)" 의 사번 — 이 사번으로 온 쪽지만 화면에 쪽지 창이 뜸 */
        public String messengerMyId = "20240001";
    }

    /** 화면 꾸미기 — 상단 로고 (후보: /brand) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Appearance {
        public static final java.util.List<String> LOGOS = java.util.List.of("bench", "blocks", "table", "flow");
        public String logo = "bench";
    }

    /** 파이프라인 자동 백업 (저장·수정·삭제 직전 상태) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Backup {
        public int keepPerPipeline = 20;
    }
}
