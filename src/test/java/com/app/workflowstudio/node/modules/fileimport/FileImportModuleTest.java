package com.app.workflowstudio.node.modules.fileimport;

import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.RunMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 파일 가져오기 노드 실행 — 읽기 전용이라 OS 구분 없이 돈다 (현재 OS 에서 실제로 끝까지 실행) */
class FileImportModuleTest {

    private final FileImportModule module = new FileImportModule();
    private final ObjectMapper om = new ObjectMapper();

    @TempDir Path dir;

    @Test
    void 파일을_읽어_content로_내보낸다() throws Exception {
        Path f = dir.resolve("note.txt");
        Files.writeString(f, "안녕 파일 가져오기", StandardCharsets.UTF_8);

        JsonNode out = om.readTree(module.execute(ctx(Map.of("filePath", f.toString()))));
        assertThat(out.get("content").asText()).isEqualTo("안녕 파일 가져오기");
        assertThat(out.get("fileName").asText()).isEqualTo("note.txt");
        assertThat(out.get("extension").asText()).isEqualTo("txt");
        assertThat(out.get("truncated").asBoolean()).isFalse();
        assertThat(out.get("charCount").asInt()).isEqualTo("안녕 파일 가져오기".length());
    }

    @Test
    void maxChars를_넘으면_앞부분만_자르고_표시한다() throws Exception {
        Path f = dir.resolve("long.txt");
        String full = "가".repeat(50);
        Files.writeString(f, full, StandardCharsets.UTF_8);

        JsonNode out = om.readTree(module.execute(ctx(Map.of("filePath", f.toString(), "maxChars", 10))));
        assertThat(out.get("content").asText()).isEqualTo("가".repeat(10));
        assertThat(out.get("truncated").asBoolean()).isTrue();
        assertThat(out.get("charCount").asInt()).isEqualTo(50);
    }

    @Test
    void maxChars가_0이면_자르지_않는다() throws Exception {
        Path f = dir.resolve("long2.txt");
        String full = "나".repeat(30);
        Files.writeString(f, full, StandardCharsets.UTF_8);

        JsonNode out = om.readTree(module.execute(ctx(Map.of("filePath", f.toString(), "maxChars", 0))));
        assertThat(out.get("content").asText()).isEqualTo(full);
        assertThat(out.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void 파일이_없으면_오류() {
        Path missing = dir.resolve("없음.txt");
        assertThatThrownBy(() -> module.execute(ctx(Map.of("filePath", missing.toString()))))
                .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    void 폴더를_선택하면_오류() {
        assertThatThrownBy(() -> module.execute(ctx(Map.of("filePath", dir.toString()))))
                .hasMessageContaining("폴더가 아니라 파일");
    }

    @Test
    void 경로가_비어있으면_오류() {
        assertThatThrownBy(() -> module.execute(ctx(Map.of("filePath", ""))))
                .hasMessageContaining("파일 경로를 입력하세요");
    }

    @Test
    void EUC_KR_인코딩_옵션으로_읽을_수_있다() throws Exception {
        Path f = dir.resolve("ms949.txt");
        Files.write(f, "한글 인코딩".getBytes(java.nio.charset.Charset.forName("MS949")));

        JsonNode out = om.readTree(module.execute(ctx(Map.of("filePath", f.toString(), "encoding", "EUC-KR (MS949)"))));
        assertThat(out.get("content").asText()).isEqualTo("한글 인코딩");
    }

    @Test
    void docx_파일도_노드_실행_경로를_그대로_통과한다() throws Exception {
        Path f = dir.resolve("report.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("분기 보고서 초안");
            try (var out = new FileOutputStream(f.toFile())) {
                doc.write(out);
            }
        }

        JsonNode out = om.readTree(module.execute(ctx(Map.of("filePath", f.toString()))));
        assertThat(out.get("content").asText()).contains("분기 보고서 초안");
        assertThat(out.get("extension").asText()).isEqualTo("docx");
    }

    @Test
    void 물결표는_홈_디렉터리로_펼쳐진다() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        Path f = Files.createTempFile(home, "file-import-test-", ".txt");
        try {
            Files.writeString(f, "홈 디렉터리 테스트");
            String tilde = "~/" + home.relativize(f);
            JsonNode out = om.readTree(module.execute(ctx(Map.of("filePath", tilde))));
            assertThat(out.get("content").asText()).isEqualTo("홈 디렉터리 테스트");
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void 환경변수를_펼쳐서_경로를_찾는다() throws Exception {
        Path f = dir.resolve("env.txt");
        Files.writeString(f, "env var ok");
        String varName = System.getenv("HOME") != null ? "HOME" : "USERPROFILE";
        String home = System.getenv(varName);
        assertThat(home).isNotNull();
        String rest = Path.of(home).relativize(f).toString();
        JsonNode out = om.readTree(module.execute(ctx(Map.of("filePath", "%" + varName + "%/" + rest))));
        assertThat(out.get("content").asText()).isEqualTo("env var ok");
    }

    @Test
    void 폴더의_회의록_Word_파일을_순서대로_마크다운으로_합친다() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("회의록"));
        DocxMarkdownTest.writeMeeting(folder.resolve("2026-10-06_FW회의.docx"), "FW 회의 1", "결정 A");
        DocxMarkdownTest.writeMeeting(folder.resolve("2026-10-07_FW회의.DOCX"), "FW 회의 2", "결정 B");   // 대문자 확장자
        Files.writeString(folder.resolve("메모.txt"), "패턴에 안 맞음");
        Files.writeString(folder.resolve("~$2026-10-07_FW회의.docx"), "Word 임시 파일");                      // 제외

        JsonNode out = om.readTree(module.execute(ctx(Map.of("mode", FileImportModule.MODE_FOLDER,
                "folder", folder.toString(), "pattern", "*.docx", "maxChars", 0))));

        assertThat(out.get("count").asInt()).isEqualTo(2);
        assertThat(out.get("files").get(0).get("fileName").asText()).isEqualTo("2026-10-06_FW회의.docx");
        String md = out.get("content").asText();
        // 파일 이름이 ## 제목, 문서 안의 제목은 두 단계 내려감, 표·목록 유지
        assertThat(md).startsWith("## 2026-10-06_FW회의\n\n### FW 회의 1\n");
        assertThat(md).contains("\n## 2026-10-07_FW회의\n\n### FW 회의 2\n");
        assertThat(md).contains("| 홍길동 | FW 리드 |").contains("1. 결정 A").contains("1. 결정 B");
        assertThat(md.indexOf("결정 A")).isLessThan(md.indexOf("결정 B"));
        assertThat(out.get("items").get(1).get("title").asText()).isEqualTo("2026-10-07_FW회의");
        assertThat(out.get("items").get(1).get("content").asText()).startsWith("# FW 회의 2");   // 파일별 원래 내용
    }

    @Test
    void 폴더_모드_정렬_최근_N일_최대_개수_구분선() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("notes"));
        Path old = Files.writeString(folder.resolve("a.txt"), "오래된");
        Path mid = Files.writeString(folder.resolve("b.txt"), "중간");
        Path neu = Files.writeString(folder.resolve("c.txt"), "최신");
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(now - 10L * 86_400_000));
        Files.setLastModifiedTime(mid, java.nio.file.attribute.FileTime.fromMillis(now - 2L * 86_400_000));
        Files.setLastModifiedTime(neu, java.nio.file.attribute.FileTime.fromMillis(now - 3_600_000));

        JsonNode newest = om.readTree(module.execute(ctx(Map.of("mode", FileImportModule.MODE_FOLDER, "folder", folder.toString(),
                "pattern", "*.txt", "sort", FileImportModule.SORT_NEW, "join", FileImportModule.JOIN_PLAIN))));
        assertThat(newest.get("content").asText()).isEqualTo("최신\n\n---\n\n중간\n\n---\n\n오래된");

        JsonNode recent = om.readTree(module.execute(ctx(Map.of("mode", FileImportModule.MODE_FOLDER, "folder", folder.toString(),
                "pattern", "*.txt", "recentDays", 7))));
        assertThat(recent.get("count").asInt()).isEqualTo(2);

        JsonNode limited = om.readTree(module.execute(ctx(Map.of("mode", FileImportModule.MODE_FOLDER, "folder", folder.toString(),
                "pattern", "*.txt", "maxFiles", 1))));
        assertThat(limited.get("items").get(0).get("fileName").asText()).isEqualTo("a.txt");

        assertThatThrownBy(() -> module.execute(ctx(Map.of("mode", FileImportModule.MODE_FOLDER, "folder", folder.toString(),
                "pattern", "*.docx")))).hasMessageContaining("'*.docx' 에 맞는 파일이 없습니다");
        assertThatThrownBy(() -> module.execute(ctx(Map.of("mode", FileImportModule.MODE_FOLDER,
                "folder", folder.resolve("없음").toString())))).hasMessageContaining("폴더를 찾을 수 없습니다");
    }

    private NodeContext ctx(Map<String, Object> config) {
        PipelineNode node = new PipelineNode();
        node.setId("import");
        node.setType(FileImportModule.TYPE);
        node.setConfig(new HashMap<>(config));
        return new NodeContext(node, new HashMap<>(), null, RunMode.MANUAL, null, om);
    }
}
