package com.app.memobookmark.service;

import com.app.memobookmark.model.AppSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

@Service
public class AppSettingsService {

    private final Path settingsFile;
    private final ObjectMapper objectMapper;

    public AppSettingsService(Path storageDirectory, ObjectMapper objectMapper) {
        this.settingsFile = storageDirectory.resolve("settings.json");
        this.objectMapper = objectMapper;
    }

    public AppSettings load() {
        if (!settingsFile.toFile().exists()) return new AppSettings();
        try {
            return objectMapper.readValue(settingsFile.toFile(), AppSettings.class);
        } catch (IOException e) {
            return new AppSettings();
        }
    }

    public AppSettings save(AppSettings settings) {
        try {
            objectMapper.writeValue(settingsFile.toFile(), settings);
            return settings;
        } catch (IOException e) {
            throw new RuntimeException("설정 저장 실패", e);
        }
    }
}
