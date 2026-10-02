package com.app.workflowstudio.node.modules.filewrite;

import com.app.workflowstudio.model.Pipeline;
import com.app.workflowstudio.model.PipelineConnection;
import com.app.workflowstudio.model.PipelineNode;
import com.app.workflowstudio.model.RunLog;
import com.app.workflowstudio.service.PipelineRunnerService;
import com.app.workflowstudio.service.PipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/workflow-studio-filewrite-test",
        "app.open-browser=false"
})
class FileWriteModuleTest {

    @Autowired MockMvc mvc;
    @Autowired PipelineService pipelineService;
    @Autowired PipelineRunnerService runner;
    @Autowired ObjectMapper objectMapper;

    @TempDir Path dir;

    @BeforeEach
    void windowsOnly() {
        assumeTrue(FileWriteModule.isWindows(), "Windows 전용 모듈");
    }

    // ── 규칙 단위 테스트 ──

    @Test
    void 파일명은_Windows_규칙에_맞게_정리된다() {
        assertThat(FileWriteModule.safeFileName("a<b>c:d\"e/f\\g|h?i*j")).isEqualTo("a_b_c_d_e_f_g_h_i_j");
        assertThat(FileWriteModule.safeFileName("report. . ")).isEqualTo("report");
        assertThat(FileWriteModule.safeFileName("con")).isEqualTo("_con");
        assertThat(FileWriteModule.safeFileName("  ")).isEqualTo("output");
    }

    @Test
    void 확장자_선택과_직접입력() throws Exception {
        assertThat(FileWriteModule.extension("csv", "")).isEqualTo("csv");
        assertThat(FileWriteModule.extension("직접 입력", ".sql")).isEqualTo("sql");
        assertThatThrownBy(() -> FileWriteModule.extension("직접 입력", "")).hasMessageContaining("직접 입력");
        assertThatThrownBy(() -> FileWriteModule.extension("직접 입력", "a/b")).hasMessageContaining("쓸 수 없는");
    }

    @Test
    void 폴더는_전체경로여야_하고_시스템폴더는_막힌다() throws Exception {
        assertThat(FileWriteModule.resolveFolder("%USERPROFILE%\\Desktop").toString())
                .isEqualTo(System.getenv("USERPROFILE") + "\\Desktop");
        assertThat(FileWriteModule.resolveFolder("~\\docs").toString())
                .isEqualTo(System.getProperty("user.home") + "\\docs");
        assertThatThrownBy(() -> FileWriteModule.resolveFolder("relative\\dir")).hasMessageContaining("전체 경로");
        assertThatThrownBy(() -> FileWriteModule.resolveFolder("C:\\Windows\\Temp")).hasMessageContaining("시스템 폴더");
        assertThatThrownBy(() -> FileWriteModule.resolveFolder("%NO_SUCH_VAR_X%\\a")).hasMessageContaining("환경 변수");
    }

    // ── 웹훅 → 파일 쓰기 통합 ──

    @Test
    void 웹훅으로_받은_내용을_원하는_확장자로_저장한다() throws Exception {
        String path = "fw-" + UUID.randomUUID();
        pipelineService.create(pipeline(path, Map.of(
                "folder", dir.toString(),
                "fileName", "주문_${input.body.id}",
                "extension", "json",
                "content", "${input}",
                "outputVar", "file")));

        JsonNode out = fire(path, "{\"id\":7,\"item\":\"사과\"}");
        Path written = dir.resolve("주문_7.json");
        assertThat(out.get("path").asText()).isEqualTo(written.toString());
        assertThat(out.get("mode").asText()).isEqualTo("created");
        // json 확장자 → 보기 좋게 정렬되어 저장
        String text = Files.readString(written, StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(text).path("body").path("item").asText()).isEqualTo("사과");
        assertThat(text).contains("\n");
    }

    @Test
    void 같은_이름_처리와_인코딩() throws Exception {
        String path = "fw-" + UUID.randomUUID();
        Pipeline p = pipelineService.create(pipeline(path, new HashMap<>(Map.of(
                "folder", dir.toString(),
                "fileName", "log",
                "extension", "직접 입력",
                "customExtension", "sql",
                "content", "${input.body.v};",
                "ifExists", "덮어쓰기",
                "encoding", "UTF-8 (BOM 포함, 엑셀용 CSV)"))));
        Path file = dir.resolve("log.sql");

        fire(path, "{\"v\":\"가\"}");
        byte[] bytes = Files.readAllBytes(file);
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);          // BOM
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)).isEqualTo("가;");

        setFileConfig(p, "ifExists", "뒤에 이어쓰기");
        setFileConfig(p, "encoding", "UTF-8");
        fire(path, "{\"v\":\"나\"}");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).endsWith("가;나;");

        setFileConfig(p, "ifExists", "새 이름으로 저장 (이름 (1).txt)");
        JsonNode renamed = fire(path, "{\"v\":\"다\"}");
        assertThat(renamed.get("fileName").asText()).isEqualTo("log (1).sql");

        setFileConfig(p, "ifExists", "오류로 중단");
        String execId = fireRaw(path, "{\"v\":\"라\"}");
        PipelineRunnerService.ExecutionState state = await(execId);
        assertThat(state.status).isEqualTo("error");
        assertThat(state.logs.get(state.logs.size() - 1).getMessage()).contains("이미 있습니다");

        setFileConfig(p, "ifExists", "덮어쓰기");
        setFileConfig(p, "encoding", "EUC-KR (MS949)");
        fire(path, "{\"v\":\"한글\"}");
        assertThat(new String(Files.readAllBytes(file), Charset.forName("MS949"))).isEqualTo("한글;");
    }

    // ── helpers ──

    private Pipeline pipeline(String path, Map<String, Object> fileConfig) {
        PipelineNode hook = node(path, "WEBHOOK", Map.of("path", path, "testEnabled", "켜짐", "httpMethod", "POST"));
        PipelineNode fw = node("fw", FileWriteModule.TYPE, fileConfig);
        PipelineConnection c = new PipelineConnection();
        c.setFromNodeId(path);
        c.setToNodeId("fw");
        Pipeline p = new Pipeline();
        p.setName("file write test");
        p.setNodes(new ArrayList<>(List.of(hook, fw)));
        p.setConnections(new ArrayList<>(List.of(c)));
        return p;
    }

    private void setFileConfig(Pipeline p, String key, String value) {
        Pipeline cur = pipelineService.findById(p.getId());
        cur.getNodes().stream().filter(n -> n.getId().equals("fw")).findFirst().orElseThrow().getConfig().put(key, value);
        pipelineService.update(p.getId(), cur);
    }

    private PipelineNode node(String id, String type, Map<String, Object> config) {
        PipelineNode n = new PipelineNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new HashMap<>(config));
        return n;
    }

    /** 웹훅 호출 → 실행 완료 대기 → 파일 쓰기 노드 출력(JSON) */
    private JsonNode fire(String path, String body) throws Exception {
        PipelineRunnerService.ExecutionState state = await(fireRaw(path, body));
        RunLog fwLog = state.logs.stream().filter(l -> l.getNodeId().equals("fw")).findFirst().orElseThrow();
        assertThat(fwLog.getStatus()).as(fwLog.getMessage()).isEqualTo("success");
        return objectMapper.readTree(fwLog.getOutput());
    }

    private String fireRaw(String path, String body) throws Exception {
        String res = mvc.perform(post("/webhook-test/" + path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res).get("executionId").asText();
    }

    private PipelineRunnerService.ExecutionState await(String execId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            PipelineRunnerService.ExecutionState s = runner.getState(execId);
            if (s != null && !"running".equals(s.status)) return s;
            Thread.sleep(100);
        }
        throw new AssertionError("실행이 끝나지 않음: " + execId);
    }
}
