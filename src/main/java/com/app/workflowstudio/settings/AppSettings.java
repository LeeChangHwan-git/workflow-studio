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

    /** 파이프라인 자동 백업 (저장·수정·삭제 직전 상태) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Backup {
        public int keepPerPipeline = 20;
    }
}
