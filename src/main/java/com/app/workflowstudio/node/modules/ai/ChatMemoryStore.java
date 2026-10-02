package com.app.workflowstudio.node.modules.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 대화 메모리 파일 저장소 — {storage}/agent-memory/{메모리 노드 id}/{대화 ID 해시}.json
 * 대화 ID(sessionKey)별로 최근 {@value #MAX_MESSAGES}개 메시지를 보관하고, 읽을 때 창 크기(최근 N턴)만큼 잘라 쓴다.
 */
@Component
public class ChatMemoryStore {

    static final int MAX_MESSAGES = 200;

    public static class Conversation {
        public String sessionKey;
        public List<Entry> messages = new ArrayList<>();
    }

    public static class Entry {
        public String role;   // user | model
        public String text;
        public String at;
    }

    private final Path root;
    private final ObjectMapper om;

    public ChatMemoryStore(Path storageDirectory, ObjectMapper om) throws IOException {
        this.root = storageDirectory.resolve("agent-memory");
        this.om = om;
        Files.createDirectories(root);
    }

    /** 최근 turns 턴(= 질문+답 2개씩) */
    public synchronized List<GeminiClient.Message> recent(String memoryNodeId, String sessionKey, int turns) {
        List<Entry> all = load(memoryNodeId, sessionKey).messages;
        int from = Math.max(0, all.size() - Math.max(0, turns) * 2);
        List<GeminiClient.Message> out = new ArrayList<>();
        for (Entry e : all.subList(from, all.size())) out.add(new GeminiClient.Message(e.role, e.text));
        return out;
    }

    public synchronized void append(String memoryNodeId, String sessionKey, String userText, String modelText) throws IOException {
        Conversation c = load(memoryNodeId, sessionKey);
        c.sessionKey = sessionKey;
        c.messages.add(entry("user", userText));
        c.messages.add(entry("model", modelText));
        while (c.messages.size() > MAX_MESSAGES) c.messages.remove(0);
        Path f = file(memoryNodeId, sessionKey);
        Files.createDirectories(f.getParent());
        Files.writeString(f, om.writerWithDefaultPrettyPrinter().writeValueAsString(c), StandardCharsets.UTF_8);
    }

    /** 이 메모리 노드의 대화 ID 목록과 메시지 수 */
    public synchronized List<Map<String, Object>> sessions(String memoryNodeId) throws IOException {
        Path dir = root.resolve(safe(memoryNodeId));
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    Conversation c = om.readValue(f.toFile(), Conversation.class);
                    out.add(Map.of("sessionKey", String.valueOf(c.sessionKey), "messages", c.messages.size()));
                } catch (IOException ignore) {}
            }
        }
        return out;
    }

    /** 이 메모리 노드의 모든 대화 삭제 */
    public synchronized void clear(String memoryNodeId) throws IOException {
        Path dir = root.resolve(safe(memoryNodeId));
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) Files.deleteIfExists(f);
        }
        Files.deleteIfExists(dir);
    }

    private Conversation load(String memoryNodeId, String sessionKey) {
        Path f = file(memoryNodeId, sessionKey);
        if (!Files.exists(f)) {
            Conversation c = new Conversation();
            c.sessionKey = sessionKey;
            return c;
        }
        try {
            return om.readValue(f.toFile(), new TypeReference<Conversation>() {});
        } catch (IOException e) {
            Conversation c = new Conversation();
            c.sessionKey = sessionKey;
            return c;
        }
    }

    private Path file(String memoryNodeId, String sessionKey) {
        return root.resolve(safe(memoryNodeId)).resolve(sha1(sessionKey) + ".json");
    }

    private static Entry entry(String role, String text) {
        Entry e = new Entry();
        e.role = role;
        e.text = text;
        e.at = LocalDateTime.now().toString();
        return e;
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
