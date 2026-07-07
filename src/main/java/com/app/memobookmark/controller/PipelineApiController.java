package com.app.memobookmark.controller;

import com.app.memobookmark.model.*;
import com.app.memobookmark.service.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;

@RestController
@RequestMapping("/api")
public class PipelineApiController {

    private final PipelineService pipelineService;
    private final PipelineRunnerService runnerService;
    private final NodeTypeRegistry nodeTypeRegistry;
    private final AppSettingsService settingsService;
    private final ScreenCaptureService screenCaptureService;

    public PipelineApiController(PipelineService pipelineService,
                                  PipelineRunnerService runnerService,
                                  NodeTypeRegistry nodeTypeRegistry,
                                  AppSettingsService settingsService,
                                  ScreenCaptureService screenCaptureService) {
        this.pipelineService = pipelineService;
        this.runnerService = runnerService;
        this.nodeTypeRegistry = nodeTypeRegistry;
        this.settingsService = settingsService;
        this.screenCaptureService = screenCaptureService;
    }

    // ── 노드 타입 ─────────────────────────────────────────────

    @GetMapping("/node-types")
    public List<NodeTypeRegistry.NodeTypeInfo> getNodeTypes() {
        return nodeTypeRegistry.getAll();
    }

    // ── 파이프라인 CRUD ────────────────────────────────────────

    @GetMapping("/pipelines")
    public List<Pipeline> listPipelines() {
        return pipelineService.findAll();
    }

    @PostMapping("/pipelines")
    public ResponseEntity<Pipeline> createPipeline(@RequestBody Pipeline pipeline) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pipelineService.create(pipeline));
    }

    @GetMapping("/pipelines/{id}")
    public ResponseEntity<Pipeline> getPipeline(@PathVariable String id) {
        Pipeline p = pipelineService.findById(id);
        return p != null ? ResponseEntity.ok(p) : ResponseEntity.notFound().build();
    }

    @PutMapping("/pipelines/{id}")
    public ResponseEntity<Pipeline> updatePipeline(@PathVariable String id,
                                                    @RequestBody Pipeline pipeline) {
        Pipeline updated = pipelineService.update(id, pipeline);
        return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/pipelines/{id}")
    public ResponseEntity<Void> deletePipeline(@PathVariable String id) {
        return pipelineService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ── 실행 ──────────────────────────────────────────────────

    @PostMapping("/pipelines/{id}/run")
    public ResponseEntity<Map<String, String>> runPipeline(@PathVariable String id) {
        try {
            String execId = runnerService.run(id);
            return ResponseEntity.ok(Map.of("executionId", execId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/executions/{execId}")
    public ResponseEntity<Map<String, Object>> getExecution(@PathVariable String execId) {
        PipelineRunnerService.ExecutionState state = runnerService.getState(execId);
        if (state == null) return ResponseEntity.notFound().build();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", state.executionId);
        result.put("status", state.status);
        result.put("logs", new ArrayList<>(state.logs));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/executions/{execId}/cancel")
    public ResponseEntity<Void> cancelExecution(@PathVariable String execId) {
        return runnerService.cancel(execId)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    // ── 이미지 업로드 ─────────────────────────────────────────

    @PostMapping("/images")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            String path = pipelineService.saveImage(file.getOriginalFilename(), file.getBytes());
            return ResponseEntity.ok(Map.of("imagePath", path));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 화면 캡처 (드래그로 영역 선택 → 바로 저장) ─────────────
    @PostMapping("/capture")
    public ResponseEntity<Map<String, String>> captureRegion() {
        try {
            BufferedImage img = screenCaptureService.captureRegionInteractive();
            if (img == null) {
                return ResponseEntity.ok(Map.of("cancelled", "true"));
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            String path = pipelineService.saveImage("capture.png", baos.toByteArray());
            return ResponseEntity.ok(Map.of("imagePath", path));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 이미지 서빙 ───────────────────────────────────────────

    @GetMapping("/images/{filename}")
    public ResponseEntity<byte[]> serveImage(@PathVariable String filename) {
        try {
            var imgPath = pipelineService.getImagePath("images/" + filename);
            if (!imgPath.toFile().exists()) return ResponseEntity.notFound().build();
            byte[] data = java.nio.file.Files.readAllBytes(imgPath);
            String ct = filename.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(ct)).body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── ZIP 내보내기 / 가져오기 ───────────────────────────────

    @GetMapping("/pipelines/{id}/export")
    public void exportPipeline(@PathVariable String id, HttpServletResponse response) throws Exception {
        Pipeline p = pipelineService.findById(id);
        if (p == null) { response.setStatus(404); return; }
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"pipeline-" + id + ".zip\"");
        pipelineService.exportZip(id, response.getOutputStream());
    }

    @PostMapping("/pipelines/import")
    public ResponseEntity<Pipeline> importPipeline(@RequestParam("file") MultipartFile file) {
        try {
            Pipeline pipeline = pipelineService.importZip(file.getInputStream());
            return ResponseEntity.status(HttpStatus.CREATED).body(pipeline);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ── 설정 ──────────────────────────────────────────────────

    @GetMapping("/settings")
    public AppSettings getSettings() {
        return settingsService.load();
    }

    @PutMapping("/settings")
    public AppSettings updateSettings(@RequestBody AppSettings settings) {
        return settingsService.save(settings);
    }
}
