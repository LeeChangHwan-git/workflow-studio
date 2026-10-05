package com.app.workflowstudio.node.modules.html;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.QueryParser;
import org.jsoup.select.Selector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 선택자(CSS)로 HTML에서 값 뽑기.
 *
 * <pre>
 * 행(반복 항목) 선택자: tr.list_row          ← 게시글 한 줄
 * 항목(한 줄에 하나):  이름: 선택자 @속성
 *   제목: td.subject a                     ← 글자
 *   링크: td.subject a @href               ← 주소 (상대 주소는 절대 주소로)
 *   날짜: td:nth-of-type(4)
 *   번호: @data-no                         ← 선택자를 비우면 행 자신
 * </pre>
 * 행 선택자가 있으면 행마다 항목 선택자를 행 안에서 찾고(첫 번째), 없으면 문서 전체에서 항목별로 모두 찾아 순서대로 짝짓는다.
 * 모든 항목이 빈 행(예: 표 머리글)은 버린다.
 */
public final class HtmlExtractor {

    /** 가져올 항목 하나 */
    public record Field(String name, String selector, String attr) {}

    private static final Pattern LINE = Pattern.compile("^\\s*([^:]+?)\\s*:\\s*(.*?)\\s*(?:@([\\w:.-]+))?\\s*$");

    private HtmlExtractor() {}

    /** "이름: 선택자 @속성" 줄 목록 해석 (빈 줄·#주석 무시) */
    public static List<Field> parseFields(String text) throws Exception {
        List<Field> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int lineNo = 0;
        for (String raw : (text == null ? "" : text).split("\\r?\\n")) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) throw new Exception("가져올 항목 " + lineNo + "번째 줄 형식 오류 — \"이름: 선택자 @속성\" (예: 제목: td.title a): " + line);
            String name = m.group(1).trim(), sel = m.group(2).trim(), attr = m.group(3) == null ? "text" : m.group(3);
            if (!names.add(name)) throw new Exception("가져올 항목 이름 '" + name + "'이(가) 중복됩니다");
            if (!sel.isEmpty()) validate(sel, "항목 '" + name + "'");
            out.add(new Field(name, sel, attr));
        }
        if (out.isEmpty()) throw new Exception("가져올 항목이 없습니다 — 예: 제목: td.title a");
        return out;
    }

    public static void validate(String selector, String where) throws Exception {
        try {
            QueryParser.parse(selector);
        } catch (Selector.SelectorParseException e) {
            throw new Exception(where + "의 선택자가 올바르지 않습니다: " + selector + " (" + e.getMessage() + ")");
        }
    }

    /** 추출 — 결과는 항목 이름 → 값 (LinkedHashMap, 항목 순서 유지) */
    public static List<Map<String, String>> extract(Document doc, String rowSelector, List<Field> fields, int limit) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        if (rowSelector != null && !rowSelector.isBlank()) {
            validate(rowSelector.trim(), "행 선택자");
            for (Element row : doc.select(rowSelector.trim())) {
                Map<String, String> r = new LinkedHashMap<>();
                boolean any = false;
                for (Field f : fields) {
                    Element el = f.selector().isEmpty() ? row : row.selectFirst(f.selector());
                    String v = el == null ? "" : value(el, f.attr());
                    any |= !v.isEmpty();
                    r.put(f.name(), v);
                }
                if (any) rows.add(r);
                if (rows.size() >= limit) break;
            }
            return rows;
        }
        // 행 없이: 항목별로 전부 찾아 순서대로 짝짓기
        List<Elements> found = new ArrayList<>();
        int max = 0;
        for (Field f : fields) {
            Elements els = f.selector().isEmpty() ? new Elements(doc.body()) : doc.select(f.selector());
            found.add(els);
            max = Math.max(max, els.size());
        }
        for (int i = 0; i < max && rows.size() < limit; i++) {
            Map<String, String> r = new LinkedHashMap<>();
            boolean any = false;
            for (int j = 0; j < fields.size(); j++) {
                Elements els = found.get(j);
                String v = i < els.size() ? value(els.get(i), fields.get(j).attr()) : "";
                any |= !v.isEmpty();
                r.put(fields.get(j).name(), v);
            }
            if (any) rows.add(r);
        }
        return rows;
    }

    /** 선택자에 맞은 요소 수 (행이 있으면 행 수, 없으면 항목별 최대) — 값이 비어서 0개가 됐는지 구분용 */
    public static int matchedElements(Document doc, String rowSelector, List<Field> fields) {
        if (rowSelector != null && !rowSelector.isBlank()) return doc.select(rowSelector.trim()).size();
        int max = 0;
        for (Field f : fields) if (!f.selector().isEmpty()) max = Math.max(max, doc.select(f.selector()).size());
        return max;
    }

    /** text(기본) · html · href/src(절대 주소) · 그 밖의 속성 */
    static String value(Element el, String attr) {
        return switch (attr.toLowerCase(Locale.ROOT)) {
            case "text" -> el.text().trim();
            case "html" -> el.html().trim();
            case "href", "src", "action", "data-src" -> {
                String abs = el.absUrl(attr);
                yield abs.isEmpty() ? el.attr(attr).trim() : abs;
            }
            default -> el.attr(attr).trim();
        };
    }
}
