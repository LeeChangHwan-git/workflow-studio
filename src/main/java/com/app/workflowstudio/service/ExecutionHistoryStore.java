package com.app.workflowstudio.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 끝난 실행의 요약 기록 — {storage}/executions.json, 최신순 최대 {@value #MAX_ENTRIES}건.
 * 실행 상세(노드 로그)는 메모리에만 있고, 홈 대시보드의 통계·최근 실행은 이 기록으로 만든다 (서버를 다시 켜도 남음).
 */
@Component
public class ExecutionHistoryStore {

    static final int MAX_ENTRIES = 1000;

    public static class Entry {
        public String executionId;
        public String pipelineId;
        public String pipelineName;
        public String mode;          // MANUAL | TEST | PROD | SCHEDULE
        public String status;        // done | error
        public String startedAt;
        public String endedAt;
        public long durationMs;
        public int nodes;            // 실행된 노드 수 (건너뜀 제외)
        public String errorNode;     // 실패한 노드 이름
        public String message;       // 오류 메시지
    }

    private final Path file;
    private final ObjectMapper om;
    private List<Entry> entries;

    public ExecutionHistoryStore(Path storageDirectory, ObjectMapper om) {
        this.file = storageDirectory.resolve("executions.json");
        this.om = om;
    }

    public synchronized void add(Entry e) {
        List<Entry> list = load();
        list.add(0, e);
        while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
        try {
            om.writeValue(file.toFile(), list);
        } catch (IOException ignore) {
            // 기록 실패가 실행 결과를 바꾸지 않도록 무시 (메모리에는 남음)
        }
    }

    public synchronized List<Entry> list() {
        return new ArrayList<>(load());
    }

    private List<Entry> load() {
        if (entries == null) {
            entries = new ArrayList<>();
            if (Files.exists(file)) {
                try {
                    entries = om.readValue(file.toFile(), new TypeReference<ArrayList<Entry>>() {});
                } catch (IOException ignore) {
                    // 깨진 파일이면 새로 시작
                }
            }
        }
        return entries;
    }
}
