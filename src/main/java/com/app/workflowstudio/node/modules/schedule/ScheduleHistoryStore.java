package com.app.workflowstudio.node.modules.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 타이머 실행 기록 — {storage}/schedules/{파이프라인id}_{노드id}.json, 최신순 최대 {@value #MAX_ENTRIES}건 */
@Component
public class ScheduleHistoryStore {

    static final int MAX_ENTRIES = 100;

    public static class Entry {
        public String at;           // 실행(또는 건너뜀) 시각
        public String result;       // fired | skipped | error
        public String trigger;      // timer | now (지금 실행 버튼)
        public String executionId;
        public String message;
    }

    private final Path dir;
    private final ObjectMapper om;
    private final Map<String, List<Entry>> cache = new HashMap<>();

    public ScheduleHistoryStore(Path storageDirectory, ObjectMapper om) throws IOException {
        this.dir = storageDirectory.resolve("schedules");
        this.om = om;
        Files.createDirectories(dir);
    }

    public synchronized void add(String key, Entry e) {
        List<Entry> list = load(key);
        list.add(0, e);
        while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
        try {
            om.writeValue(file(key).toFile(), list);
        } catch (IOException ignore) {
            // 기록 실패가 타이머 실행을 막지 않도록 무시 (메모리에는 남음)
        }
    }

    public synchronized List<Entry> list(String key) {
        return new ArrayList<>(load(key));
    }

    private List<Entry> load(String key) {
        return cache.computeIfAbsent(key, k -> {
            Path f = file(k);
            if (!Files.exists(f)) return new ArrayList<>();
            try {
                return om.readValue(f.toFile(), new TypeReference<ArrayList<Entry>>() {});
            } catch (IOException e) {
                return new ArrayList<>();
            }
        });
    }

    private Path file(String key) {
        return dir.resolve(key.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }
}
