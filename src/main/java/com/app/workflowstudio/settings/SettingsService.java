package com.app.workflowstudio.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 앱 설정 읽기/저장. 모듈의 정적 메소드(키 해석 등)에서도 쓰도록 현재 값을 {@link #current()}로 제공한다.
 */
@Service
public class SettingsService {

    /** 화면에 값을 내려보내지 않는 비밀 필드 ("섹션.필드") */
    public static final List<String> SECRET_FIELDS = List.of(
            "credentials.geminiApiKey", "credentials.outlineApiKey",
            "assistant.geminiApiKey", "assistant.openaiApiKey");

    private static volatile AppSettings current = new AppSettings();

    private final Path file;
    private final ObjectMapper om;

    public SettingsService(Path storageDirectory, ObjectMapper om) {
        this.file = storageDirectory.resolve("settings.json");
        this.om = om;
        current = load();
    }

    /** 현재 설정 (어디서나 읽기 전용으로 사용) */
    public static AppSettings current() {
        return current;
    }

    public AppSettings get() {
        return current;
    }

    /** 화면용: 비밀값은 null 로 비우고 secrets 에 "설정됨" 여부와 끝 4자리만 */
    public Map<String, Object> forView() {
        ObjectNode tree = om.valueToTree(current);
        Map<String, Object> secrets = new LinkedHashMap<>();
        for (String f : SECRET_FIELDS) {
            String[] p = f.split("\\.");
            ObjectNode section = (ObjectNode) tree.path(p[0]);
            String v = section.path(p[1]).asText("");
            secrets.put(f, v.isBlank() ? null : "…" + v.substring(Math.max(0, v.length() - 4)));
            section.putNull(p[1]);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("settings", tree);
        res.put("secrets", secrets);
        return res;
    }

    /**
     * 화면에서 받은 설정 저장. 비밀 필드가 null(또는 없음)이면 기존 값 유지, "" 이면 지움.
     */
    public synchronized AppSettings update(JsonNode incoming) throws IOException {
        ObjectNode merged = om.valueToTree(current);
        incoming.fields().forEachRemaining(section -> {
            if (!section.getValue().isObject() || !merged.has(section.getKey())) return;
            ObjectNode target = (ObjectNode) merged.get(section.getKey());
            section.getValue().fields().forEachRemaining(f -> {
                boolean secret = SECRET_FIELDS.contains(section.getKey() + "." + f.getKey());
                if (secret && (f.getValue() == null || f.getValue().isNull())) return;
                target.set(f.getKey(), f.getValue());
            });
        });
        AppSettings next = om.treeToValue(merged, AppSettings.class);
        sanitize(next);
        save(next);
        current = next;
        return next;
    }

    private static void sanitize(AppSettings s) {
        if (!AppSettings.Assistant.OPENAI.equals(s.assistant.provider)) s.assistant.provider = AppSettings.Assistant.GEMINI;
        s.assistant.maxSteps = Math.max(2, Math.min(30, s.assistant.maxSteps));
        s.assistant.temperature = Math.max(0, Math.min(2, s.assistant.temperature));
        s.backup.keepPerPipeline = Math.max(1, Math.min(200, s.backup.keepPerPipeline));
        if (s.appearance == null) s.appearance = new AppSettings.Appearance();
        if (s.demo == null) s.demo = new AppSettings.Demo();
        if (s.modules == null) s.modules = new AppSettings.Modules();
        if (s.modules.disabled == null) s.modules.disabled = new java.util.ArrayList<>();
        s.modules.disabled = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(
                s.modules.disabled.stream().filter(x -> x != null && !x.isBlank()).map(String::trim).toList()));
        if (s.messenger == null) s.messenger = new AppSettings.Messenger();
        if (s.messenger.groups == null) s.messenger.groups = new java.util.ArrayList<>();
        s.messenger.groups.removeIf(g -> g == null || g.name == null);
        for (var g : s.messenger.groups) {
            g.name = g.name.replaceAll("^[@\\s]+|\\s+$", "").replaceAll("[\\s,;@]+", "_");
            if (g.members == null) g.members = "";
            if (g.description == null) g.description = "";
            g.members = String.join(", ", g.memberIds());
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        s.messenger.groups.removeIf(g -> g.name.isEmpty() || !seen.add(g.name.toLowerCase()));
        if (s.demo.messengerMyId == null || s.demo.messengerMyId.isBlank()) s.demo.messengerMyId = new AppSettings.Demo().messengerMyId;
        s.demo.messengerMyId = s.demo.messengerMyId.trim();
        if (!AppSettings.Appearance.LOGOS.contains(s.appearance.logo)) s.appearance.logo = AppSettings.Appearance.LOGOS.get(0);
    }

    private AppSettings load() {
        if (!Files.exists(file)) return new AppSettings();
        try {
            AppSettings s = om.readValue(file.toFile(), AppSettings.class);
            if (s.credentials == null) s.credentials = new AppSettings.Credentials();
            if (s.assistant == null) s.assistant = new AppSettings.Assistant();
            if (s.backup == null) s.backup = new AppSettings.Backup();
            if (s.appearance == null) s.appearance = new AppSettings.Appearance();
            sanitize(s);
            return s;
        } catch (IOException e) {
            return new AppSettings();
        }
    }

    private void save(AppSettings s) throws IOException {
        Path tmp = file.resolveSibling("settings.json.tmp");
        om.writeValue(tmp.toFile(), s);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ── 공용 키 해석 (노드 값 → 설정 → 환경 변수) ──

    public static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
}
