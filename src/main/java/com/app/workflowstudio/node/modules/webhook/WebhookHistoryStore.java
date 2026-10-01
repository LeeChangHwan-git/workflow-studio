package com.app.workflowstudio.node.modules.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 웹훅 노드별 수신 기록 — {storage}/webhooks/{nodeId}.json 에 최신순으로 저장 (노드당 최대 {@value #MAX_ENTRIES}건).
 * 거부된 요청(URL 꺼짐, 메소드 불일치)도 기록한다. 수동 실행 시 마지막 '실행된' 요청 데이터를 재사용.
 */
@Component
public class WebhookHistoryStore {

    static final int MAX_ENTRIES = 200;

    /** 수신 기록 한 건 */
    public static class Entry {
        public String id;
        public String receivedAt;
        public String mode;          // test | prod
        public String method;
        public int httpStatus;       // 응답 코드 (200 실행, 404 꺼짐, 405 메소드 불허)
        public boolean accepted;     // 파이프라인 실행 여부
        public String message;       // 거부 사유 등
        public String executionId;   // accepted 일 때
        public Map<String, Object> payload;
    }

    private final Path dir;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Entry>> cache = new HashMap<>();

    public WebhookHistoryStore(Path storageDirectory, ObjectMapper objectMapper) throws IOException {
        this.dir = storageDirectory.resolve("webhooks");
        this.objectMapper = objectMapper;
        Files.createDirectories(dir);
    }

    public synchronized void add(String nodeId, Entry entry) {
        List<Entry> list = load(nodeId);
        list.add(0, entry);
        while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
        write(nodeId, list);
    }

    /** 최신순 */
    public synchronized List<Entry> list(String nodeId) {
        return new ArrayList<>(load(nodeId));
    }

    public synchronized void clear(String nodeId) {
        cache.put(nodeId, new ArrayList<>());
        try {
            Files.deleteIfExists(file(nodeId));
        } catch (IOException ignore) {}
    }

    /** 마지막으로 실행까지 된 요청의 payload (없으면 null) */
    public synchronized Map<String, Object> lastAcceptedPayload(String nodeId) {
        return load(nodeId).stream().filter(e -> e.accepted).map(e -> e.payload).findFirst().orElse(null);
    }

    private List<Entry> load(String nodeId) {
        return cache.computeIfAbsent(nodeId, id -> {
            Path f = file(id);
            if (!Files.exists(f)) return new ArrayList<>();
            try {
                return objectMapper.readValue(f.toFile(), new TypeReference<ArrayList<Entry>>() {});
            } catch (IOException e) {
                return new ArrayList<>();
            }
        });
    }

    private void write(String nodeId, List<Entry> list) {
        try {
            objectMapper.writeValue(file(nodeId).toFile(), list);
        } catch (IOException e) {
            throw new RuntimeException("웹훅 수신 기록 저장 실패", e);
        }
    }

    private Path file(String nodeId) {
        // nodeId 는 UUID 형태지만 경로 조작 방지를 위해 안전한 문자만 허용
        return dir.resolve(nodeId.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }
}
