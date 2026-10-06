package com.app.workflowstudio.node.modules.fileimport;

import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeParam;
import com.app.workflowstudio.node.NodeTypeInfo;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 파일 가져오기 — 지정한 파일을 읽어 다음 노드(주로 AI 에이전트)로 넘긴다. 읽기만 하므로 OS 구분 없이 동작.
 * txt/md/csv/json 등은 그대로 텍스트로, html은 태그를 뺀 본문만, pdf·docx·xlsx·pptx 는 각각 뽑아서 텍스트화한다.
 * 출력: {@code {path, fileName, extension, bytes, charCount, truncated, content}} — 다음 노드는 {@code ${file.content}}로 사용.
 */
@Component
@Order(95)
public class FileImportModule implements NodeModule {

    public static final String TYPE = "FILE_IMPORT";

    static final String UTF8 = "UTF-8";
    static final String MS949 = "EUC-KR (MS949)";

    /** 메모리 보호를 위한 파일 크기 상한 */
    static final long MAX_FILE_BYTES = 25L * 1024 * 1024;

    private static final Pattern ENV_VAR = Pattern.compile("%([A-Za-z0-9_()]+)%");

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "파일 가져오기", "📂", "#0D9488", "파일", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("filePath", "가져올 파일 (전체 경로, %USERPROFILE%·~ 사용 가능) — 찾아보기 창에서 '모든 파일'로 바꿔 선택",
                        "file", "", null),
                new NodeParam("encoding", "텍스트 인코딩 (pdf/docx/xlsx/pptx/html 파일은 무시됨)", "select", UTF8, List.of(UTF8, MS949)),
                new NodeParam("maxChars", "AI에 넘길 최대 글자수 (넘으면 앞부분만, 0=무제한)", "number", 20000, null),
                new NodeParam("outputVar", "출력 변수명", "text", "file", null)
        ));
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        String raw = ctx.resolve(ctx.raw("filePath", ""));
        if (raw == null || raw.isBlank()) throw new Exception("가져올 파일 경로를 입력하세요");
        Path file = resolvePath(raw);
        if (!Files.exists(file)) throw new Exception("파일을 찾을 수 없습니다: " + file);
        if (!Files.isRegularFile(file)) throw new Exception("폴더가 아니라 파일을 선택하세요: " + file);

        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new Exception("파일이 너무 큽니다 (" + (size / 1024 / 1024) + "MB, 최대 " + (MAX_FILE_BYTES / 1024 / 1024) + "MB): " + file);
        }

        String fileName = file.getFileName().toString();
        String ext = extensionOf(fileName);
        String encodingName = ctx.raw("encoding", UTF8);
        Charset charset = MS949.equals(encodingName) ? Charset.forName("MS949") : StandardCharsets.UTF_8;

        String content = FileTextExtractor.extract(file, ext, charset);

        int fullLen = content.length();
        int max = ctx.intVal("maxChars", 20000);
        boolean truncated = max > 0 && fullLen > max;
        if (truncated) content = content.substring(0, max);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.toString());
        out.put("fileName", fileName);
        out.put("extension", ext);
        out.put("bytes", size);
        out.put("charCount", fullLen);
        out.put("truncated", truncated);
        out.put("content", content);
        return ctx.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** %ENV% 와 ~ 를 펼친 전체 경로 — 현재 OS 의 경로 구분자를 그대로 쓴다 (Windows C:\...  · mac/Linux /...) */
    static Path resolvePath(String raw) throws Exception {
        String s = raw.trim();
        Matcher m = ENV_VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = System.getenv(m.group(1));
            if (val == null) throw new Exception("환경 변수를 찾을 수 없습니다: %" + m.group(1) + "%");
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        s = sb.toString();
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) {
            s = System.getProperty("user.home") + s.substring(1);
        }

        Path p;
        try {
            p = Paths.get(s).normalize();
        } catch (InvalidPathException e) {
            throw new Exception("경로가 올바르지 않습니다: " + raw);
        }
        if (!p.isAbsolute()) throw new Exception("전체 경로여야 합니다 (예: C:\\Users\\... 또는 /Users/...): " + raw);
        return p;
    }
}
