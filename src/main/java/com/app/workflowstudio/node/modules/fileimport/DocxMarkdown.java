package com.app.workflowstudio.node.modules.fileimport;

import org.apache.poi.xwpf.usermodel.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word(docx) → 마크다운. 회의록처럼 구조가 있는 문서를 AI·아웃라인에 그대로 넘길 수 있게 서식을 살린다.
 * <ul>
 *   <li>제목 스타일(Title / 제목 1~6 · Heading 1~6) → {@code #} ~ {@code ######}</li>
 *   <li>글머리 기호·번호 목록 → {@code -} / {@code 1.} (수준만큼 들여쓰기)</li>
 *   <li>표 → 마크다운 표 (첫 줄 = 머리글, 칸 안 줄바꿈은 {@code <br>})</li>
 *   <li>굵게 → {@code **…**}, 하이퍼링크 → {@code [글자](주소)}</li>
 * </ul>
 */
final class DocxMarkdown {

    private static final Pattern HEADING_NAME = Pattern.compile("(?i)^(?:heading|제목)\\s*([1-6])$");
    private static final Pattern HEADING_ID = Pattern.compile("(?i)^(?:heading|제목)?\\s*([1-6])$");

    private DocxMarkdown() {}

    static String convert(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph p) paragraph(sb, p, doc);
            else if (el instanceof XWPFTable t) table(sb, t);
        }
        return sb.toString().replaceAll("\\n{3,}", "\n\n").strip() + "\n";
    }

    private static void paragraph(StringBuilder sb, XWPFParagraph p, XWPFDocument doc) {
        String text = runs(p, doc).strip();
        if (text.isEmpty()) {
            sb.append('\n');
            return;
        }
        boolean list = p.getNumID() != null;
        // 목록 바로 뒤의 일반 문단은 빈 줄로 띄워야 목록 항목에 붙지 않음
        if (!list && sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n' && (sb.length() < 2 || sb.charAt(sb.length() - 2) != '\n'))
            sb.append('\n');
        int level = headingLevel(p, doc);
        if (level > 0) {
            sb.append('\n').append("#".repeat(level)).append(' ').append(text.replace("**", "")).append("\n\n");
            return;
        }
        if (list) {
            int ilvl = p.getNumIlvl() == null ? 0 : p.getNumIlvl().intValue();
            String fmt = numFmt(p, doc, ilvl);
            boolean ordered = fmt != null && !fmt.equalsIgnoreCase("bullet") && !fmt.equalsIgnoreCase("none");
            sb.append("  ".repeat(Math.max(0, ilvl))).append(ordered ? "1. " : "- ").append(text).append('\n');
            return;
        }
        sb.append(text).append("\n\n");
    }

    /** 목록 번호 형식 (bullet / decimal / …) — 문단에 수준(ilvl)이 없어도 0 수준으로 찾아봄 */
    static String numFmt(XWPFParagraph p, XWPFDocument doc, int ilvl) {
        String fmt = p.getNumFmt();
        if (fmt != null) return fmt;
        XWPFNumbering numbering = doc.getNumbering();
        if (numbering == null) return null;
        XWPFNum num = numbering.getNum(p.getNumID());
        if (num == null || num.getCTNum().getAbstractNumId() == null) return null;
        XWPFAbstractNum abs = numbering.getAbstractNum(num.getCTNum().getAbstractNumId().getVal());
        if (abs == null) return null;
        for (var lvl : abs.getCTAbstractNum().getLvlArray()) {
            if (lvl.getIlvl() != null && lvl.getIlvl().intValue() == ilvl && lvl.getNumFmt() != null)
                return lvl.getNumFmt().getVal().toString();
        }
        return null;
    }

    /** 0 = 제목 아님. 스타일 이름(heading 1 / Title — 한글 Word 도 내부 이름은 영어)을 먼저, 없으면 스타일 id */
    static int headingLevel(XWPFParagraph p, XWPFDocument doc) {
        String id = p.getStyleID();
        if (id == null || id.isBlank()) return 0;
        String name = null;
        XWPFStyles styles = doc.getStyles();
        if (styles != null) {
            XWPFStyle st = styles.getStyle(id);
            if (st != null) name = st.getName();
        }
        for (String s : new String[]{name, id}) {
            if (s == null) continue;
            String t = s.trim();
            if (t.equalsIgnoreCase("title") || t.equals("제목")) return 1;
            if (t.equalsIgnoreCase("subtitle") || t.equals("부제")) return 2;
            Matcher m = (s == name ? HEADING_NAME : HEADING_ID).matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    /** 문단 안의 글자 — 굵게·링크 반영 */
    private static String runs(XWPFParagraph p, XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun r : p.getRuns()) {
            String t = r.text();
            if (t == null || t.isEmpty()) continue;
            if (r instanceof XWPFHyperlinkRun hr) {
                XWPFHyperlink link = hr.getHyperlink(doc);
                if (link != null && link.getURL() != null && !link.getURL().isBlank()) {
                    sb.append('[').append(t.strip()).append("](").append(link.getURL()).append(')');
                    continue;
                }
            }
            if (r.isBold() && !t.isBlank()) {
                // 앞뒤 공백은 ** 바깥으로 (** 안쪽 공백은 마크다운에서 굵게가 안 됨)
                String lead = t.substring(0, t.length() - t.stripLeading().length());
                String trail = t.substring(t.stripTrailing().length());
                sb.append(lead).append("**").append(t.strip()).append("**").append(trail);
            } else {
                sb.append(t);
            }
        }
        // 이어진 굵게 조각 합치기: **가****나** → **가나**
        return sb.toString().replace("****", "");
    }

    private static void table(StringBuilder sb, XWPFTable t) {
        List<List<String>> rows = new ArrayList<>();
        int cols = 0;
        for (XWPFTableRow row : t.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell c : row.getTableCells()) cells.add(cell(c));
            cols = Math.max(cols, cells.size());
            rows.add(cells);
        }
        if (rows.isEmpty() || cols == 0) return;
        sb.append('\n');
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            while (r.size() < cols) r.add("");
            sb.append("| ").append(String.join(" | ", r)).append(" |\n");
            if (i == 0) sb.append("|").append(" --- |".repeat(cols)).append('\n');
        }
        sb.append('\n');
    }

    private static String cell(XWPFTableCell c) {
        List<String> parts = new ArrayList<>();
        for (XWPFParagraph p : c.getParagraphs()) {
            String s = p.getText().strip();
            if (!s.isEmpty()) parts.add(s);
        }
        return String.join("<br>", parts).replace("|", "\\|");
    }

    /** 합칠 때 문서 안의 제목을 한 단계씩 내림 (# → ###) — 파일 이름이 ## 이 되도록 */
    static String shiftHeadings(String md, int by) {
        if (by <= 0) return md;
        StringBuilder sb = new StringBuilder();
        for (String line : md.split("\n", -1)) {
            Matcher m = Pattern.compile("^(#{1,6})(\\s.*)$").matcher(line);
            if (m.matches()) line = "#".repeat(Math.min(6, m.group(1).length() + by)) + m.group(2);
            sb.append(line).append('\n');
        }
        return sb.substring(0, sb.length() - 1);
    }
}
