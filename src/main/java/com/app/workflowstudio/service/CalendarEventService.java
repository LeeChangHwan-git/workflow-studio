package com.app.workflowstudio.service;

import com.app.workflowstudio.model.CalendarEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CalendarEventService {

    private final ObjectMapper objectMapper;
    private final Path dataFile;

    public CalendarEventService(Path storageDirectory, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dataFile = storageDirectory.resolve("calendar.json");
    }

    public List<CalendarEvent> findAll() {
        return load();
    }

    public List<CalendarEvent> findByYearMonth(int year, int month) {
        return load().stream()
                .filter(e -> e.getDate().getYear() == year && e.getDate().getMonthValue() == month)
                .toList();
    }

    public List<CalendarEvent> findByDate(LocalDate date) {
        return load().stream()
                .filter(e -> date.equals(e.getDate()))
                .toList();
    }

    public CalendarEvent findById(String id) {
        return load().stream().filter(e -> id.equals(e.getId())).findFirst().orElse(null);
    }

    public CalendarEvent create(CalendarEvent event) {
        List<CalendarEvent> list = load();
        event.setId(UUID.randomUUID().toString());
        event.setCreatedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        list.add(event);
        save(list);
        return event;
    }

    public CalendarEvent update(String id, CalendarEvent updated) {
        List<CalendarEvent> list = load();
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).getId())) {
                updated.setId(id);
                updated.setCreatedAt(list.get(i).getCreatedAt());
                updated.setUpdatedAt(LocalDateTime.now());
                list.set(i, updated);
                save(list);
                return updated;
            }
        }
        return null;
    }

    public boolean delete(String id) {
        List<CalendarEvent> list = load();
        boolean removed = list.removeIf(e -> id.equals(e.getId()));
        if (removed) save(list);
        return removed;
    }

    private List<CalendarEvent> load() {
        if (!dataFile.toFile().exists()) return new ArrayList<>();
        try {
            return objectMapper.readValue(dataFile.toFile(), new TypeReference<List<CalendarEvent>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void save(List<CalendarEvent> list) {
        try {
            objectMapper.writeValue(dataFile.toFile(), list);
        } catch (IOException e) {
            throw new RuntimeException("달력 데이터 저장 실패", e);
        }
    }
}
