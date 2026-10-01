package com.app.workflowstudio.controller;

import com.app.workflowstudio.model.*;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.app.workflowstudio.node.NodeTypeRegistry;
import com.app.workflowstudio.node.RunMode;
import com.app.workflowstudio.service.*;
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
    private final ScreenCaptureService screenCaptureService;

    public PipelineApiController(PipelineService pipelineService,
                                  PipelineRunnerService runnerService,
                                  NodeTypeRegistry nodeTypeRegistry,
                                  ScreenCaptureService screenCaptureService) {
        this.pipelineService = pipelineService;
        this.runnerService = runnerService;
        this.nodeTypeRegistry = nodeTypeRegistry;
        this.screenCaptureService = screenCaptureService;
    }

    // ── 노드 타입 ─────────────────────────────────────────────

    @GetMapping("/node-types")
    public List<NodeTypeInfo> getNodeTypes() {
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
        return ResponseEntity.ok(toDto(state, true));
    }

    /** 파이프라인의 최근 실행 (에디터가 웹훅 테스트 실행을 감지하는 용도, mode=test) */
    @GetMapping("/pipelines/{id}/executions/latest")
    public ResponseEntity<Map<String, Object>> latestExecution(@PathVariable String id,
                                                               @RequestParam(required = false) String mode) {
        RunMode m = mode == null || mode.isBlank() ? null : RunMode.valueOf(mode.toUpperCase());
        PipelineRunnerService.ExecutionState state = runnerService.findLatest(id, m);
        return state == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(toDto(state, false));
    }

    private Map<String, Object> toDto(PipelineRunnerService.ExecutionState state, boolean withLogs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", state.executionId);
        result.put("status", state.status);
        result.put("mode", state.mode.name().toLowerCase());
        result.put("startedAt", state.startedAt);
        if (withLogs) result.put("logs", new ArrayList<>(state.logs));
        return result;
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
}
