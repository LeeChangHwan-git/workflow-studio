package com.app.workflowstudio.service;

import com.app.workflowstudio.model.Pipeline;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.*;

@Service
public class PipelineService {

    /** 파이프라인이 저장·삭제될 때 발행 (타이머 재등록 등에 사용) */
    public record PipelinesChangedEvent(String pipelineId) {}

    private final Path pipelinesDir;
    private final Path imagesDir;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final PipelineBackupService backups;

    public PipelineService(Path storageDirectory, ObjectMapper objectMapper, ApplicationEventPublisher events,
                           PipelineBackupService backups) {
        this.backups = backups;
        this.pipelinesDir = storageDirectory.resolve("pipelines");
        this.imagesDir = pipelinesDir.resolve("images");
        this.objectMapper = objectMapper;
        this.events = events;
    }

    public List<Pipeline> findAll() {
        try {
            return Files.list(pipelinesDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(this::readPipeline)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Pipeline::getUpdatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public Pipeline findById(String id) {
        return readPipeline(pipelinesDir.resolve(id + ".json"));
    }

    public Pipeline create(Pipeline pipeline) {
        pipeline.setId(UUID.randomUUID().toString());
        pipeline.setCreatedAt(LocalDateTime.now());
        pipeline.setUpdatedAt(LocalDateTime.now());
        if (pipeline.getNodes() == null) pipeline.setNodes(new ArrayList<>());
        if (pipeline.getConnections() == null) pipeline.setConnections(new ArrayList<>());
        writePipeline(pipeline);
        return pipeline;
    }

    public Pipeline update(String id, Pipeline pipeline) {
        return update(id, pipeline, "저장");
    }

    /** 수정 — 직전 상태를 자동 백업 (reason: 저장 / AI 수정 …) */
    public Pipeline update(String id, Pipeline pipeline, String reason) {
        Pipeline existing = findById(id);
        if (existing == null) return null;
        backups.backup(pipelinesDir.resolve(id + ".json"), id, reason);
        pipeline.setId(id);
        pipeline.setCreatedAt(existing.getCreatedAt());
        pipeline.setUpdatedAt(LocalDateTime.now());
        writePipeline(pipeline);
        return pipeline;
    }

    public boolean delete(String id) {
        return delete(id, "삭제");
    }

    /** 삭제 — 지우기 전 상태를 백업해 두므로 ⚙ 설정 → 백업에서 되살릴 수 있음 */
    public boolean delete(String id, String reason) {
        Path file = pipelinesDir.resolve(id + ".json");
        backups.backup(file, id, reason);
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) events.publishEvent(new PipelinesChangedEvent(id));
            return deleted;
        } catch (IOException e) {
            return false;
        }
    }

    /** 백업에서 되살리기 — 같은 id 로 저장 (현재 상태가 있으면 그것도 먼저 백업) */
    public Pipeline restore(String pipelineId, String backupFile) throws IOException {
        Pipeline p = backups.read(pipelineId, backupFile);
        p.setId(pipelineId);
        backups.backup(pipelinesDir.resolve(pipelineId + ".json"), pipelineId, "복원 전");
        p.setUpdatedAt(LocalDateTime.now());
        if (p.getCreatedAt() == null) p.setCreatedAt(LocalDateTime.now());
        writePipeline(p);
        return p;
    }

    /** 파이프라인 + 참조 이미지를 ZIP으로 내보내기 */
    public void exportZip(String id, OutputStream out) throws IOException {
        Pipeline pipeline = findById(id);
        if (pipeline == null) throw new IllegalArgumentException("파이프라인을 찾을 수 없습니다: " + id);

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            // pipeline.json
            byte[] pipelineJson = objectMapper.writeValueAsBytes(pipeline);
            zip.putNextEntry(new ZipEntry("pipeline.json"));
            zip.write(pipelineJson);
            zip.closeEntry();

            // 참조된 이미지 파일들
            Set<String> imagePaths = collectImagePaths(pipeline);
            for (String imagePath : imagePaths) {
                Path imageFile = pipelinesDir.resolve(imagePath);
                if (Files.exists(imageFile)) {
                    zip.putNextEntry(new ZipEntry("images/" + imageFile.getFileName()));
                    Files.copy(imageFile, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    /** ZIP에서 파이프라인 + 이미지 가져오기 */
    public Pipeline importZip(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();

        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }

        byte[] pipelineJson = entries.get("pipeline.json");
        if (pipelineJson == null) throw new IllegalArgumentException("pipeline.json이 없는 ZIP 파일입니다");

        Pipeline pipeline = objectMapper.readValue(pipelineJson, Pipeline.class);

        // 이미지 파일 복사 (새 ID 생성으로 충돌 방지)
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().startsWith("images/")) {
                String origFileName = entry.getKey().substring("images/".length());
                Path dest = imagesDir.resolve(origFileName);
                // 동일 파일명이 없을 때만 복사 (이미지는 UUID 기반이므로 충돌 가능성 낮음)
                if (!Files.exists(dest)) {
                    Files.write(dest, entry.getValue());
                }
            }
        }

        // 항상 새 ID로 저장
        pipeline.setId(UUID.randomUUID().toString());
        pipeline.setCreatedAt(LocalDateTime.now());
        pipeline.setUpdatedAt(LocalDateTime.now());
        writePipeline(pipeline);
        return pipeline;
    }

    /** 이미지 저장 (파이프라인 노드 설정에서 사용) */
    public String saveImage(String originalFilename, byte[] data) throws IOException {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        String filename = UUID.randomUUID() + ext;
        Files.write(imagesDir.resolve(filename), data);
        return "images/" + filename;
    }

    public Path getImagePath(String relativePath) {
        return pipelinesDir.resolve(relativePath);
    }

    private Pipeline readPipeline(Path file) {
        if (!Files.exists(file)) return null;
        try {
            return objectMapper.readValue(file.toFile(), Pipeline.class);
        } catch (IOException e) {
            return null;
        }
    }

    private void writePipeline(Pipeline pipeline) {
        try {
            objectMapper.writeValue(pipelinesDir.resolve(pipeline.getId() + ".json").toFile(), pipeline);
            events.publishEvent(new PipelinesChangedEvent(pipeline.getId()));
        } catch (IOException e) {
            throw new RuntimeException("파이프라인 저장 실패", e);
        }
    }

    private Set<String> collectImagePaths(Pipeline pipeline) {
        Set<String> paths = new LinkedHashSet<>();
        if (pipeline.getNodes() == null) return paths;
        for (var node : pipeline.getNodes()) {
            if (node.getConfig() == null) continue;
            Object img = node.getConfig().get("imagePath");
            if (img instanceof String s && !s.isBlank()) paths.add(s);
        }
        return paths;
    }
}
