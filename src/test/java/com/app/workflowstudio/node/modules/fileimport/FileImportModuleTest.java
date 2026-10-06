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

    private NodeContext ctx(Map<String, Object> config) {
        PipelineNode node = new PipelineNode();
        node.setId("import");
        node.setType(FileImportModule.TYPE);
        node.setConfig(new HashMap<>(config));
        return new NodeContext(node, new HashMap<>(), null, RunMode.MANUAL, null, om);
    }
}
