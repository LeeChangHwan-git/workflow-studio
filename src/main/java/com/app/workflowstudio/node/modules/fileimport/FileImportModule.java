package com.app.workflowstudio.node.modules.fileimport;

import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeParam;
import com.app.workflowstudio.node.NodeTypeInfo;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.text.Collator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    static final String MODE_FILE = "파일 하나";
    static final String MODE_FOLDER = "폴더의 여러 파일 (합치기)";
    static final String SORT_NAME = "이름순";
    static final String SORT_OLD = "수정 시각 — 오래된 것부터";
    static final String SORT_NEW = "수정 시각 — 최신 것부터";
    static final String JOIN_HEADING = "파일마다 ## 파일 이름 제목을 붙여 합치기";
    static final String JOIN_PLAIN = "내용만 이어 붙이기 (--- 구분선)";

    static final String UTF8 = "UTF-8";
    static final String MS949 = "EUC-KR (MS949)";

    /** 메모리 보호를 위한 파일 크기 상한 */
    static final long MAX_FILE_BYTES = 25L * 1024 * 1024;

    private static final Pattern ENV_VAR = Pattern.compile("%([A-Za-z0-9_()]+)%");

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "파일 가져오기", "📂", "#0D9488", "파일", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("mode", "가져오기", "select", MODE_FILE, List.of(MODE_FILE, MODE_FOLDER)),
                new NodeParam("filePath", "가져올 파일 (전체 경로, %USERPROFILE%·~ 사용 가능) — 찾아보기 창에서 '모든 파일'로 바꿔 선택",
                        "file", "", null, "mode=" + MODE_FILE),
                new NodeParam("folder", "폴더 (%USERPROFILE%·~ 사용 가능)", "folder", "", null, "mode=" + MODE_FOLDER),
                new NodeParam("pattern", "파일 이름 패턴 — ; 로 여러 개 (예: *.docx;*.doc · 회의록_*.docx)", "text", "*.docx;*.doc", null,
                        "mode=" + MODE_FOLDER),
                new NodeParam("sort", "합치는 순서", "select", SORT_NAME, List.of(SORT_NAME, SORT_OLD, SORT_NEW), "mode=" + MODE_FOLDER),
                new NodeParam("recentDays", "최근 N일 안에 수정된 파일만 (0 = 전부)", "number", 0, null, "mode=" + MODE_FOLDER),
                new NodeParam("maxFiles", "최대 파일 수", "number", 50, null, "mode=" + MODE_FOLDER),
                new NodeParam("join", "합치는 방식", "select", JOIN_HEADING, List.of(JOIN_HEADING, JOIN_PLAIN), "mode=" + MODE_FOLDER),
                new NodeParam("encoding", "텍스트 인코딩 (pdf/docx/xlsx/pptx/html 파일은 무시됨)", "select", UTF8, List.of(UTF8, MS949)),
                new NodeParam("maxChars", "AI에 넘길 최대 글자수 (넘으면 앞부분만, 0=무제한 — 여러 파일이면 합친 글 기준)", "number", 20000, null),
                new NodeParam("outputVar", "출력 변수명", "text", "file", null)
        ));
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        if (MODE_FOLDER.equals(ctx.raw("mode", MODE_FILE))) return folder(ctx);
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

    /**
     * 폴더 모드 — 패턴에 맞는 파일을 모두 읽어 하나로 합친다 (하위 폴더는 보지 않음, ~$ 로 시작하는 Word 임시 파일 제외).
     * 출력: {folder, pattern, count, files:[{fileName, path, modified, charCount}], items:[{fileName, title, content}],
     *        content(합친 글), charCount, truncated}
     */
    private String folder(NodeContext ctx) throws Exception {
        String raw = ctx.resolve(ctx.raw("folder", ""));
        if (raw == null || raw.isBlank()) throw new Exception("폴더를 입력하세요");
        Path dir = resolvePath(raw);
        if (!Files.isDirectory(dir)) throw new Exception("폴더를 찾을 수 없습니다: " + dir);

        String pattern = ctx.resolve(ctx.raw("pattern", "*.docx;*.doc")).trim();
        List<PathMatcher> matchers = new ArrayList<>();
        for (String p : pattern.split("[;,]")) {
            if (p.isBlank()) continue;
            // 윈도우 파일 이름은 대소문자 구분 없음 → 소문자로 비교
            matchers.add(dir.getFileSystem().getPathMatcher("glob:" + p.trim().toLowerCase(Locale.ROOT)));
        }
        if (matchers.isEmpty()) throw new Exception("파일 이름 패턴을 입력하세요 (예: *.docx)");

        int recentDays = Math.max(0, ctx.intVal("recentDays", 0));
        Instant since = recentDays > 0 ? Instant.now().minus(Duration.ofDays(recentDays)) : null;
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("~$"))
                    .filter(p -> {
                        Path name = Paths.get(p.getFileName().toString().toLowerCase(Locale.ROOT));
                        return matchers.stream().anyMatch(m -> m.matches(name));
                    })
                    .filter(p -> since == null || modified(p).isAfter(since))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        String sort = ctx.raw("sort", SORT_NAME);
        Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString(), Collator.getInstance(Locale.KOREAN));
        files.sort(SORT_OLD.equals(sort) ? Comparator.comparing(FileImportModule::modified)
                : SORT_NEW.equals(sort) ? Comparator.comparing(FileImportModule::modified).reversed() : byName);
        int maxFiles = Math.max(1, Math.min(500, ctx.intVal("maxFiles", 50)));
        if (files.size() > maxFiles) files = new ArrayList<>(files.subList(0, maxFiles));
        if (files.isEmpty()) {
            throw new Exception("'" + pattern + "' 에 맞는 파일이 없습니다: " + dir + (since != null ? " (최근 " + recentDays + "일)" : ""));
        }

        Charset charset = MS949.equals(ctx.raw("encoding", UTF8)) ? Charset.forName("MS949") : StandardCharsets.UTF_8;
        boolean headings = !JOIN_PLAIN.equals(ctx.raw("join", JOIN_HEADING));
        List<Map<String, Object>> fileInfo = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (Path f : files) {
            long size = Files.size(f);
            if (size > MAX_FILE_BYTES) throw new Exception("파일이 너무 큽니다 (최대 25MB): " + f);
            String name = f.getFileName().toString();
            String text;
            try {
                text = FileTextExtractor.extract(f, extensionOf(name), charset).strip();
            } catch (Exception e) {
                throw new Exception(name + " 을(를) 읽을 수 없습니다 — " + e.getMessage());
            }
            String title = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;

            if (!all.isEmpty()) all.append(headings ? "\n\n" : "\n\n---\n\n");
            if (headings) all.append("## ").append(title).append("\n\n").append(DocxMarkdown.shiftHeadings(text, 2));
            else all.append(text);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("fileName", name);
            info.put("path", f.toString());
            info.put("modified", LocalDateTime.ofInstant(modified(f), ZoneId.systemDefault()).withNano(0).toString());
            info.put("charCount", text.length());
            fileInfo.add(info);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", name);
            item.put("title", title);
            item.put("content", text);
            items.add(item);
        }

        String content = all.toString();
        int fullLen = content.length();
        int max = ctx.intVal("maxChars", 20000);
        boolean truncated = max > 0 && fullLen > max;
        if (truncated) content = content.substring(0, max);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folder", dir.toString());
        out.put("pattern", pattern);
        out.put("count", files.size());
        out.put("files", fileInfo);
        out.put("items", items);
        out.put("charCount", fullLen);
        out.put("truncated", truncated);
        out.put("content", content);
        return ctx.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    private static Instant modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
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
