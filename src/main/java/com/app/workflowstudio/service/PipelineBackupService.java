package com.app.workflowstudio.service;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * 파이프라인 자동 백업 — 수정·삭제 직전 상태를 {storage}/backups/pipelines/{id}/{시각}_{이유}.json 으로 보관.
 * 파이프라인마다 최근 N개만 유지 (⚙ 설정 → 백업).
 */
@Service
public class PipelineBackupService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** 백업 하나 (화면용) */
    public record Backup(String pipelineId, String file, String name, String reason, String savedAt, int nodeCount) {}

    private final Path dir;
    private final ObjectMapper om;

    public PipelineBackupService(Path storageDirectory, ObjectMapper om) throws IOException {
        this.dir = storageDirectory.resolve("backups").resolve("pipelines");
        Files.createDirectories(dir);
        this.om = om;
    }

    /** 현재 파일 상태를 백업 (파일이 없으면 무시). reason: 저장 / AI 수정 / 삭제 / 복원 전 … */
    public void backup(Path pipelineFile, String pipelineId, String reason) {
        if (!Files.exists(pipelineFile)) return;
        try {
            Path target = dir.resolve(safe(pipelineId));
            Files.createDirectories(target);
            String fileName = LocalDateTime.now().format(TS) + "_" + safe(reason) + ".json";
            Files.copy(pipelineFile, target.resolve(fileName));
            prune(target);
        } catch (IOException ignore) {
            // 백업 실패가 저장을 막지는 않음
        }
    }

    /** 전체 백업 목록 (최신 순) */
    public List<Backup> list(String pipelineIdOrNull) {
        List<Backup> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(dir)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                if (pipelineIdOrNull != null && !d.getFileName().toString().equals(safe(pipelineIdOrNull))) continue;
                try (Stream<Path> files = Files.list(d)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                        out.add(describe(d.getFileName().toString(), f));
                    }
                }
            }
        } catch (IOException ignore) {
        }
        out.sort(Comparator.comparing(Backup::file).reversed());
        return out;
    }

    /** 백업 파일 읽기 */
    public Pipeline read(String pipelineId, String file) throws IOException {
        Path f = dir.resolve(safe(pipelineId)).resolve(safe(file));
        if (!f.normalize().startsWith(dir) || !Files.exists(f)) throw new IOException("백업을 찾을 수 없습니다: " + file);
        return om.readValue(f.toFile(), Pipeline.class);
    }

    private Backup describe(String pipelineId, Path f) {
        String fn = f.getFileName().toString();
        String base = fn.substring(0, fn.length() - ".json".length());
        int us = base.indexOf('_');
        String ts = us > 0 ? base.substring(0, us) : base;
        String reason = us > 0 ? base.substring(us + 1) : "";
        String savedAt = ts;
        try {
            savedAt = LocalDateTime.parse(ts, TS).withNano(0).toString().replace('T', ' ');
        } catch (Exception ignore) {
        }
        String name = "";
        int nodes = 0;
        try {
            Pipeline p = om.readValue(f.toFile(), Pipeline.class);
            name = p.getName();
            nodes = p.getNodes() == null ? 0 : p.getNodes().size();
        } catch (IOException ignore) {
        }
        return new Backup(pipelineId, fn, name, reason, savedAt, nodes);
    }

    private void prune(Path target) throws IOException {
        int keep = SettingsService.current().backup.keepPerPipeline;
        try (Stream<Path> files = Files.list(target)) {
            List<Path> all = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (int i = 0; i < all.size() - keep; i++) Files.deleteIfExists(all.get(i));
        }
    }

    /** 파일 이름으로 쓸 수 없는 문자 제거 (경로 탈출 방지) */
    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "").replace("..", "").trim();
    }
}
