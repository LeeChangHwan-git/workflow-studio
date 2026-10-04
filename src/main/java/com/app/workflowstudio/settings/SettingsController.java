package com.app.workflowstudio.settings;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.node.modules.schedule.ScheduleService;
import com.app.workflowstudio.service.PipelineBackupService;
import com.app.workflowstudio.service.PipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/** ⚙ 설정 화면 + 관리자 API (설정 · 상태 · 타이머 · 백업 · 로그) */
@Controller
public class SettingsController {

    private final SettingsService settings;
    private final PipelineService pipelines;
    private final PipelineBackupService backups;
    private final ScheduleService schedules;
    private final Path storage;
    private final String logFile;
    private final String port;

    public SettingsController(SettingsService settings, PipelineService pipelines, PipelineBackupService backups,
                              ScheduleService schedules, Path storageDirectory,
                              @Value("${logging.file.name:}") String logFile,
                              @Value("${server.port:8080}") String port) {
        this.settings = settings;
        this.pipelines = pipelines;
        this.backups = backups;
        this.schedules = schedules;
        this.storage = storageDirectory;
        this.logFile = logFile;
        this.port = port;
    }

    @GetMapping("/settings")
    public String page() {
        return "settings/index";
    }

    // ── 설정 ──

    @GetMapping("/api/settings")
    @ResponseBody
    public Map<String, Object> get() {
        return settings.forView();
    }

    /** 비밀 필드는 null 이면 유지, "" 이면 지움 */
    @PutMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<?> put(@RequestBody JsonNode body) {
        try {
            settings.update(body);
            return ResponseEntity.ok(settings.forView());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ── 상태 ──

    @GetMapping("/api/admin/status")
    @ResponseBody
    public Map<String, Object> status() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        long up = ManagementFactory.getRuntimeMXBean().getUptime();
        Duration d = Duration.ofMillis(up);
        m.put("uptime", d.toDays() > 0 ? d.toDays() + "일 " + d.toHoursPart() + "시간" : d.toHours() + "시간 " + d.toMinutesPart() + "분");
        m.put("pid", ProcessHandle.current().pid());
        m.put("port", port);
        m.put("java", System.getProperty("java.version"));
        m.put("heapUsedMB", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        m.put("heapMaxMB", rt.maxMemory() / 1024 / 1024);
        m.put("threads", Thread.activeCount());
        m.put("storage", storage.toAbsolutePath().normalize().toString());
        m.put("storageMB", Math.round(sizeOf(storage) / 1024.0 / 1024.0 * 10) / 10.0);
        m.put("logFile", logFile.isBlank() ? null : Paths.get(logFile).toAbsolutePath().normalize().toString());
        m.put("pipelines", pipelines.findAll().size());
        m.put("activeTimers", schedules.activeCount());
        m.put("backups", backups.list(null).size());
        return m;
    }

    // ── 타이머 ──

    @GetMapping("/api/admin/timers")
    @ResponseBody
    public List<Map<String, Object>> timers() {
        return schedules.listActive();
    }

    // ── 백업 ──

    @GetMapping("/api/admin/backups")
    @ResponseBody
    public List<Map<String, Object>> backups() {
        Set<String> existing = new HashSet<>();
        for (Pipeline p : pipelines.findAll()) existing.add(p.getId());
        List<Map<String, Object>> out = new ArrayList<>();
        for (PipelineBackupService.Backup b : backups.list(null)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pipelineId", b.pipelineId());
            m.put("file", b.file());
            m.put("name", b.name());
            m.put("reason", b.reason());
            m.put("savedAt", b.savedAt());
            m.put("nodeCount", b.nodeCount());
            m.put("deleted", !existing.contains(b.pipelineId()));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/api/admin/backups/restore")
    @ResponseBody
    public ResponseEntity<?> restore(@RequestBody Map<String, String> req) {
        try {
            Pipeline p = pipelines.restore(req.get("pipelineId"), req.get("file"));
            return ResponseEntity.ok(Map.of("ok", true, "id", p.getId(), "name", p.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ── 로그 ──

    /** 로그 파일 끝부분 (최대 lines 줄, 256KB) */
    @GetMapping("/api/admin/logs")
    @ResponseBody
    public Map<String, Object> logs(@RequestParam(defaultValue = "300") int lines) {
        if (logFile.isBlank()) return Map.of("text", "(파일 로그를 쓰지 않는 실행입니다 — 콘솔에 출력 중)");
        Path f = Paths.get(logFile);
        if (!Files.exists(f)) return Map.of("text", "(로그 파일 없음: " + f.toAbsolutePath() + ")");
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long len = raf.length();
            long start = Math.max(0, len - 256 * 1024);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            List<String> all = Arrays.asList(new String(buf, StandardCharsets.UTF_8).split("\\r?\\n"));
            int n = Math.max(1, Math.min(2000, lines));
            return Map.of("text", String.join("\n", all.subList(Math.max(0, all.size() - n), all.size())));
        } catch (IOException e) {
            return Map.of("text", "(로그를 읽을 수 없습니다: " + e.getMessage() + ")");
        }
    }

    /** 데이터·로그 폴더를 탐색기로 열기 (서버 PC) */
    @PostMapping("/api/admin/open-folder")
    @ResponseBody
    public ResponseEntity<?> openFolder(@RequestBody Map<String, String> req) {
        Path target = "logs".equals(req.get("which")) && !logFile.isBlank()
                ? Paths.get(logFile).toAbsolutePath().getParent() : storage.toAbsolutePath();
        try {
            new ProcessBuilder("explorer.exe", target.normalize().toString()).start();
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "폴더를 열 수 없습니다: " + target));
        }
    }

    private static long sizeOf(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
