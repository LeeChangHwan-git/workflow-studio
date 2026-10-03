package com.app.workflowstudio.node.modules.filewrite;

import com.app.workflowstudio.node.NodeContext;
import com.app.workflowstudio.node.NodeModule;
import com.app.workflowstudio.node.NodeParam;
import com.app.workflowstudio.node.NodeTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 파일 쓰기 (Windows 전용) — 받은 내용을 원하는 폴더에 원하는 확장자 파일로 저장한다.
 * 출력: 저장 결과 JSON ({@code ${file.path}} 등으로 다음 노드에서 참조).
 */
@Component
@Order(100)
public class FileWriteModule implements NodeModule {

    public static final String TYPE = "FILE_WRITE";

    static final String CUSTOM_EXT = "직접 입력";
    static final List<String> EXTENSIONS = List.of("txt", "md", "json", "csv", "log", "html", "xml", "yaml", CUSTOM_EXT);

    static final String OVERWRITE = "덮어쓰기";
    static final String APPEND = "뒤에 이어쓰기";
    static final String RENAME = "새 이름으로 저장 (이름 (1).txt)";
    static final String FAIL = "오류로 중단";

    static final String UTF8 = "UTF-8";
    static final String UTF8_BOM = "UTF-8 (BOM 포함, 엑셀용 CSV)";
    static final String MS949 = "EUC-KR (MS949)";

    private static final Pattern ENV_VAR = Pattern.compile("%([A-Za-z0-9_()]+)%");
    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");
    private static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    /** 실수로 시스템 폴더를 덮어쓰지 않도록 막는 경로 (소문자, 접두어 비교) */
    private static final List<String> PROTECTED_DIRS = List.of(
            "c:\\windows", "c:\\program files", "c:\\program files (x86)", "c:\\programdata");

    @Override
    public NodeTypeInfo info() {
        return new NodeTypeInfo(TYPE, "파일 쓰기", "💾", "#F59E0B", "파일", List.of(
                new NodeParam("name", "노드 이름 (캔버스에 표시)", "text", "", null),
                new NodeParam("folder", "저장 폴더 (%USERPROFILE%, ~ 사용 가능)", "folder",
                        "%USERPROFILE%\\Desktop", null),
                new NodeParam("fileName", "파일명 (확장자 제외, ${date} ${time} ${datetime} 사용 가능)", "text",
                        "output_${datetime}", null),
                new NodeParam("extension", "확장자", "select", "txt", EXTENSIONS),
                new NodeParam("customExtension", "확장자 직접 입력 (예: sql)", "text", "", null, "extension=" + CUSTOM_EXT),
                new NodeParam("content", "내용 (${input} = 앞 노드 출력)", "textarea", "${input}", null),
                new NodeParam("ifExists", "같은 이름 파일이 있으면", "select", OVERWRITE,
                        List.of(OVERWRITE, APPEND, RENAME, FAIL)),
                new NodeParam("encoding", "인코딩", "select", UTF8, List.of(UTF8, UTF8_BOM, MS949)),
                new NodeParam("prettyJson", "json 확장자일 때 보기 좋게 정렬", "select", "예", List.of("예", "아니오")),
                new NodeParam("outputVar", "출력 변수명", "text", "file", null)
        ));
    }

    @Override
    public String execute(NodeContext ctx) throws Exception {
        if (!isWindows()) {
            throw new Exception("파일 쓰기 모듈은 Windows 전용입니다 (현재 OS: " + System.getProperty("os.name") + ")");
        }

        Map<String, Object> timeVars = timeVars(LocalDateTime.now());

        // 폴더
        String folderRaw = ctx.resolve(ctx.raw("folder", "%USERPROFILE%\\Desktop"), timeVars);
        Path folder = resolveFolder(folderRaw);

        // 파일명 + 확장자
        String ext = extension(ctx.raw("extension", "txt"), ctx.raw("customExtension", ""));
        String baseName = safeFileName(ctx.resolve(ctx.raw("fileName", "output_${datetime}"), timeVars));
        Path target = folder.resolve(ext.isEmpty() ? baseName : baseName + "." + ext);

        // 내용
        String content = ctx.resolve(ctx.raw("content", "${input}"), timeVars);
        if (content == null) content = "";
        if ("json".equalsIgnoreCase(ext) && "예".equals(ctx.raw("prettyJson", "예"))) {
            content = prettyJson(content, ctx.objectMapper());
        }

        // 인코딩
        String encodingName = ctx.raw("encoding", UTF8);
        Charset charset = MS949.equals(encodingName) ? Charset.forName("MS949") : StandardCharsets.UTF_8;
        boolean bom = UTF8_BOM.equals(encodingName);

        // 쓰기
        Files.createDirectories(folder);
        String ifExists = ctx.raw("ifExists", OVERWRITE);
        String mode;
        if (Files.exists(target)) {
            switch (ifExists) {
                case FAIL -> throw new Exception("같은 이름의 파일이 이미 있습니다: " + target);
                case APPEND -> {
                    byte[] bytes = content.getBytes(charset);
                    Files.write(target, bytes, StandardOpenOption.APPEND);
                    mode = "appended";
                }
                case RENAME -> {
                    target = uniquePath(target);
                    write(target, content, charset, bom);
                    mode = "renamed";
                }
                default -> {
                    write(target, content, charset, bom);
                    mode = "overwritten";
                }
            }
        } else {
            write(target, content, charset, bom);
            mode = "created";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", target.toString());
        out.put("folder", target.getParent().toString());
        out.put("fileName", target.getFileName().toString());
        out.put("extension", ext);
        out.put("mode", mode);
        out.put("encoding", encodingName);
        out.put("bytes", Files.size(target));
        return ctx.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    // ── 헬퍼 (패키지 내 테스트에서도 사용) ──────────────────────────

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** %ENV% 와 ~ 를 펼친 전체 경로 (파일·폴더 공용) — 상대 경로·잘못된 경로·없는 환경 변수는 오류 */
    public static Path expandPath(String raw) throws Exception {
        if (raw == null || raw.isBlank()) throw new Exception("경로가 비어 있습니다");
        String s = raw.trim().replace('/', '\\');

        Matcher m = ENV_VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = System.getenv(m.group(1));
            if (val == null) throw new Exception("환경 변수를 찾을 수 없습니다: %" + m.group(1) + "%");
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        s = sb.toString();
        if (s.equals("~") || s.startsWith("~\\")) s = System.getProperty("user.home") + s.substring(1);

        Path p;
        try {
            p = Paths.get(s).normalize();
        } catch (InvalidPathException e) {
            throw new Exception("경로가 올바르지 않습니다: " + raw);
        }
        if (!p.isAbsolute()) throw new Exception("C:\\... 같은 전체 경로여야 합니다: " + raw);
        return p;
    }

    /** 저장 폴더: 경로를 펼치고 보호 폴더가 아닌지, 파일이 아닌지 확인 */
    public static Path resolveFolder(String raw) throws Exception {
        if (raw == null || raw.isBlank()) throw new Exception("저장 폴더가 비어 있습니다");
        Path p = expandPath(raw);

        String lower = p.toString().toLowerCase(Locale.ROOT);
        for (String dir : PROTECTED_DIRS) {
            if (lower.equals(dir) || lower.startsWith(dir + "\\")) {
                throw new Exception("시스템 폴더에는 쓸 수 없습니다: " + p);
            }
        }
        if (Files.exists(p) && !Files.isDirectory(p)) throw new Exception("폴더가 아니라 파일입니다: " + p);
        return p;
    }

    /** 선택값 또는 직접 입력값 → 점 없이 정리된 확장자 (빈 값이면 확장자 없음) */
    static String extension(String selected, String custom) throws Exception {
        String ext = CUSTOM_EXT.equals(selected) ? (custom == null ? "" : custom) : selected;
        ext = ext.trim().replaceAll("^\\.+", "");
        if (CUSTOM_EXT.equals(selected) && ext.isEmpty()) throw new Exception("확장자를 직접 입력하세요");
        if (!ext.matches("[A-Za-z0-9_\\-]{0,20}")) throw new Exception("확장자에 쓸 수 없는 문자가 있습니다: " + ext);
        return ext;
    }

    /** Windows 파일명 규칙에 맞게 정리 (금지 문자 → _, 끝 공백·점 제거, 예약어 회피) */
    static String safeFileName(String name) {
        String s = name == null ? "" : INVALID_NAME_CHARS.matcher(name.trim()).replaceAll("_");
        s = s.replaceAll("[ .]+$", "");
        if (s.isEmpty()) s = "output";
        if (RESERVED_NAMES.contains(s.toUpperCase(Locale.ROOT))) s = "_" + s;
        if (s.length() > 200) s = s.substring(0, 200);
        return s;
    }

    /** 이름 (1).ext, 이름 (2).ext … 중 비어 있는 경로 */
    static Path uniquePath(Path target) {
        String file = target.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        String ext = dot > 0 ? file.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path p = target.resolveSibling(base + " (" + i + ")" + ext);
            if (!Files.exists(p)) return p;
        }
    }

    /** 날짜/시간 변수 ${date} ${time} ${datetime} ${timestamp} — 타이머 등 다른 모듈도 사용 */
    public static Map<String, Object> timeVars(LocalDateTime now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        m.put("time", now.format(DateTimeFormatter.ofPattern("HH-mm-ss")));
        m.put("datetime", now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        m.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return m;
    }

    private static String prettyJson(String content, ObjectMapper om) {
        try {
            return om.writerWithDefaultPrettyPrinter().writeValueAsString(om.readValue(content, Object.class));
        } catch (Exception e) {
            return content; // JSON이 아니면 그대로
        }
    }

    private static void write(Path target, String content, Charset charset, boolean bom) throws Exception {
        byte[] body = content.getBytes(charset);
        if (bom) {
            byte[] withBom = new byte[body.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(body, 0, withBom, 3, body.length);
            body = withBom;
        }
        Files.write(target, body);
    }
}
