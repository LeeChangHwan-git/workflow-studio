package com.app.workflowstudio.node;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ${...}} 템플릿 치환 — 엔진(NodeContext)과 모듈(웹훅 출력 구성 등)이 공통으로 사용.
 *
 * <pre>
 * ${name}              → 변수 값
 * ${name.key}          → 값이 JSON(또는 Map)이면 필드 접근
 * ${name.items[0].id}  → 배열 인덱스 + 중첩 필드
 * </pre>
 * 여러 scope를 순서대로 찾아 처음 해석되는 값을 쓴다. 해석 못 한 참조는 원문 그대로 남긴다.
 * 값이 String 이면 그대로, Map/List/숫자 등은 JSON 문자열로, null 은 빈 문자열로 넣는다.
 */
public final class Templates {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private Templates() {}

    public static String resolve(String text, List<? extends Map<String, ?>> scopes, ObjectMapper om) {
        if (text == null) return null;
        Matcher m = VAR_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1).trim();
            Lookup found = lookup(expr, scopes, om);
            m.appendReplacement(sb, Matcher.quoteReplacement(found != null ? stringify(found.value, om) : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 해석 결과 (값이 null 일 수도 있으므로 감싸서 '못 찾음'과 구분) */
    private record Lookup(Object value) {}

    private static Lookup lookup(String expr, List<? extends Map<String, ?>> scopes, ObjectMapper om) {
        for (Map<String, ?> scope : scopes) {
            if (scope == null) continue;
            // 1) 정확히 일치하는 키가 우선
            if (scope.containsKey(expr)) return new Lookup(scope.get(expr));
            // 2) key.path / key[i] 형태
            for (Map.Entry<String, ?> e : scope.entrySet()) {
                String key = e.getKey();
                if (!expr.startsWith(key + ".") && !expr.startsWith(key + "[")) continue;
                Object root = toNavigable(e.getValue(), om);
                if (root == null) continue;
                Lookup r = jsonPath(root, expr.substring(key.length()));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Map/List 는 그대로, 문자열은 JSON 파싱 시도 */
    private static Object toNavigable(Object v, ObjectMapper om) {
        if (v instanceof Map<?, ?> || v instanceof List<?>) return v;
        if (v instanceof String s) {
            try {
                return om.readValue(s.trim(), Object.class);
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    /** ".key", "[0]", ".key[0].nested" 형태 경로로 탐색 */
    private static Lookup jsonPath(Object node, String path) {
        if (path.isEmpty()) return new Lookup(node);
        if (path.startsWith(".")) {
            String rest = path.substring(1);
            int dot = rest.indexOf('.');
            int bracket = rest.indexOf('[');
            int end = (dot < 0 && bracket < 0) ? rest.length()
                    : (dot < 0) ? bracket
                    : (bracket < 0) ? dot
                    : Math.min(dot, bracket);
            if (node instanceof Map<?, ?> map) {
                String key = rest.substring(0, end);
                if (!map.containsKey(key)) return null;
                return jsonPath(map.get(key), rest.substring(end));
            }
        } else if (path.startsWith("[")) {
            int close = path.indexOf(']');
            if (close < 0) return null;
            try {
                int idx = Integer.parseInt(path.substring(1, close));
                if (node instanceof List<?> list && idx >= 0 && idx < list.size())
                    return jsonPath(list.get(idx), path.substring(close + 1));
            } catch (NumberFormatException ignore) {}
        }
        return null;
    }

    public static String stringify(Object v, ObjectMapper om) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }
}
