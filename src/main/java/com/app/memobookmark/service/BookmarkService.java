package com.app.memobookmark.service;

import com.app.memobookmark.model.Bookmark;
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
public class BookmarkService {

    private final Path storageDirectory;
    private final ObjectMapper objectMapper;
    private final Path bookmarkFile;

    public BookmarkService(Path storageDirectory, ObjectMapper objectMapper) {
        this.storageDirectory = storageDirectory;
        this.objectMapper = objectMapper;
        this.bookmarkFile = storageDirectory.resolve("bookmarks.json");
    }

    public List<Bookmark> findAll() {
        return loadBookmarks();
    }

    public List<Bookmark> findByCategory(String category) {
        return loadBookmarks().stream()
                .filter(b -> category.equals(b.getCategory()))
                .toList();
    }

    public List<Bookmark> search(String keyword) {
        String lk = keyword.toLowerCase();
        return loadBookmarks().stream()
                .filter(b -> (b.getName() != null && b.getName().toLowerCase().contains(lk))
                          || (b.getUrl() != null && b.getUrl().toLowerCase().contains(lk))
                          || (b.getDescription() != null && b.getDescription().toLowerCase().contains(lk)))
                .toList();
    }

    public Bookmark findById(String id) {
        return loadBookmarks().stream()
                .filter(b -> id.equals(b.getId()))
                .findFirst()
                .orElse(null);
    }

    public Bookmark create(Bookmark bookmark) {
        List<Bookmark> bookmarks = loadBookmarks();
        bookmark.setId(UUID.randomUUID().toString());
        bookmark.setCreatedAt(LocalDateTime.now());
        bookmark.setUpdatedAt(LocalDateTime.now());
        bookmarks.add(bookmark);
        saveBookmarks(bookmarks);
        return bookmark;
    }

    public Bookmark update(String id, Bookmark updated) {
        List<Bookmark> bookmarks = loadBookmarks();
        for (int i = 0; i < bookmarks.size(); i++) {
            if (id.equals(bookmarks.get(i).getId())) {
                updated.setId(id);
                updated.setCreatedAt(bookmarks.get(i).getCreatedAt());
                updated.setUpdatedAt(LocalDateTime.now());
                bookmarks.set(i, updated);
                saveBookmarks(bookmarks);
                return updated;
            }
        }
        return null;
    }

    public boolean delete(String id) {
        List<Bookmark> bookmarks = loadBookmarks();
        boolean removed = bookmarks.removeIf(b -> id.equals(b.getId()));
        if (removed) saveBookmarks(bookmarks);
        return removed;
    }

    private List<Bookmark> loadBookmarks() {
        if (!bookmarkFile.toFile().exists()) return new ArrayList<>();
        try {
            return objectMapper.readValue(bookmarkFile.toFile(), new TypeReference<List<Bookmark>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void saveBookmarks(List<Bookmark> bookmarks) {
        try {
            objectMapper.writeValue(bookmarkFile.toFile(), bookmarks);
        } catch (IOException e) {
            throw new RuntimeException("북마크 저장 실패", e);
        }
    }
}
