package com.app.workflowstudio.assistant.llm;

import com.app.workflowstudio.settings.AppSettings;
import com.app.workflowstudio.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** ⚙ 설정의 "AI 도우미" 값으로 대화 모델을 만든다 (새 제공자를 붙일 때 여기에 추가) */
@Component
public class ChatModelFactory {

    private final ObjectMapper om;
    private final String geminiBaseUrl;

    public ChatModelFactory(ObjectMapper om,
                            @Value("${app.gemini.base-url:https://generativelanguage.googleapis.com}") String geminiBaseUrl) {
        this.om = om;
        this.geminiBaseUrl = geminiBaseUrl;
    }

    public ChatModel create() throws Exception {
        return create(SettingsService.current().assistant);
    }

    public ChatModel create(AppSettings.Assistant a) throws Exception {
        if (AppSettings.Assistant.OPENAI.equals(a.provider)) {
            if (blank(a.openaiBaseUrl)) throw new Exception("⚙ 설정 → AI 도우미에서 OpenAI 호환 주소를 입력하세요 (Ollama: http://localhost:11434/v1)");
            if (blank(a.openaiModel)) throw new Exception("⚙ 설정 → AI 도우미에서 모델 이름을 입력하세요 (예: qwen3:8b)");
            return new OpenAiChatModel(om, a.openaiBaseUrl, a.openaiApiKey, a.openaiModel.trim(), a.temperature);
        }
        String key = SettingsService.firstNonBlank(a.geminiApiKey,
                SettingsService.current().credentials.geminiApiKey, System.getenv("GEMINI_API_KEY"));
        if (key == null) throw new Exception("Gemini API 키가 없습니다 — ⚙ 설정 → 공용 키 또는 AI 도우미에 넣으세요 (무료 발급: aistudio.google.com/apikey)");
        if (blank(a.geminiModel)) throw new Exception("⚙ 설정 → AI 도우미에서 Gemini 모델을 입력하세요");
        return new GeminiChatModel(om, geminiBaseUrl, key, a.geminiModel.trim(), a.temperature);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
