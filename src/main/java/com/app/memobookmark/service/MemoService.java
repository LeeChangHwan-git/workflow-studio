package com.app.memobookmark.service;

import com.app.memobookmark.model.Memo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class MemoService {

    private final Path storageDirectory;
    private final ObjectMapper objectMapper;
    private final Path memoFile;

    public MemoService(Path storageDirectory, ObjectMapper objectMapper) {
        this.storageDirectory = storageDirectory;
        this.objectMapper = objectMapper;
        this.memoFile = storageDirectory.resolve("memos.json");
    }

    public List<Memo> findAll() {
        return loadMemos();
    }

    public List<Memo> findByCategory(String category) {
        return loadMemos().stream()
                .filter(m -> category.equals(m.getCategory()))
                .toList();
    }

    public List<Memo> search(String keyword) {
        String lk = keyword.toLowerCase();
        return loadMemos().stream()
                .filter(m -> (m.getTitle() != null && m.getTitle().toLowerCase().contains(lk))
                          || (m.getContent() != null && m.getContent().toLowerCase().contains(lk)))
                .toList();
    }

    public Memo findById(String id) {
        return loadMemos().stream()
                .filter(m -> id.equals(m.getId()))
                .findFirst()
                .orElse(null);
    }

    public Memo create(Memo memo) {
        List<Memo> memos = loadMemos();
        memo.setId(UUID.randomUUID().toString());
        memo.setCreatedAt(LocalDateTime.now());
        memo.setUpdatedAt(LocalDateTime.now());
        memos.add(memo);
        saveMemos(memos);
        return memo;
    }

    public Memo update(String id, Memo updated) {
        List<Memo> memos = loadMemos();
        for (int i = 0; i < memos.size(); i++) {
            if (id.equals(memos.get(i).getId())) {
                updated.setId(id);
                updated.setCreatedAt(memos.get(i).getCreatedAt());
                updated.setUpdatedAt(LocalDateTime.now());
                memos.set(i, updated);
                saveMemos(memos);
                return updated;
            }
        }
        return null;
    }

    public boolean delete(String id) {
        List<Memo> memos = loadMemos();
        boolean removed = memos.removeIf(m -> id.equals(m.getId()));
        if (removed) saveMemos(memos);
        return removed;
    }

    private List<Memo> loadMemos() {
        if (!memoFile.toFile().exists()) return new ArrayList<>();
        try {
            return objectMapper.readValue(memoFile.toFile(), new TypeReference<List<Memo>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void saveMemos(List<Memo> memos) {
        try {
            objectMapper.writeValue(memoFile.toFile(), memos);
        } catch (IOException e) {
            throw new RuntimeException("메모 저장 실패", e);
        }
    }
}
