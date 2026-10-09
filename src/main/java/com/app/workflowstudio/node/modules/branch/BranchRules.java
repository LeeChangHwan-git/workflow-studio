package com.app.workflowstudio.node.modules.branch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 분기 규칙 — 규칙 하나 = 출력 하나. 값(기본 ${input})을 연산자·비교값으로 검사한다.
 * 실행(BranchModule)과 설정 화면의 테스트(/api/branch/test)가 같은 규칙으로 판단한다.
 */
public final class BranchRules {

    /** 규칙: id = 출력 연결점 id, label = 카드·로그 표시 이름, subject = 비우면 노드의 "검사할 값" */
    public record Rule(String id, String label, String op, String value, String subject) {}

    /** 연산자 id → 화면 이름 (순서 = 설정 화면 선택지 순서) */
    public static final LinkedHashMap<String, String> OPS = new LinkedHashMap<>();
    static {
        OPS.put("contains", "포함");
        OPS.put("notContains", "포함하지 않음");
        OPS.put("equals", "같음");
        OPS.put("notEquals", "다름");
        OPS.put("startsWith", "~로 시작");
        OPS.put("endsWith", "~로 끝남");
        OPS.put("anyOf", "다음 중 하나 포함 (쉼표로)");
        OPS.put("regex", "정규식에 맞음");
        OPS.put("empty", "비어 있음");
        OPS.put("notEmpty", "비어 있지 않음");
        OPS.put("gt", "숫자 >");
        OPS.put("gte", "숫자 ≥");
        OPS.put("lt", "숫자 <");
        OPS.put("lte", "숫자 ≤");
        OPS.put("numEquals", "숫자 =");
        OPS.put("lenGt", "글자 수 >");
        OPS.put("lenLt", "글자 수 <");
        OPS.put("expr", "식 (고급)");
    }

    public static final String ELSE = "else";
    private static final SpelExpressionParser SPEL = new SpelExpressionParser();

    private BranchRules() {}

    public static List<Rule> parse(String json, ObjectMapper om) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        List<Map<String, Object>> raw;
        try {
            raw = om.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new Exception("분기 규칙 형식이 올바르지 않습니다 (JSON 목록): " + e.getMessage());
        }
        List<Rule> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int i = 0;
        for (Map<String, Object> m : raw) {
            i++;
            String id = str(m.get("id"));
            if (id.isEmpty()) id = "r" + i;
            if (id.equals(ELSE) || !ids.add(id)) throw new Exception("분기 규칙 id '" + id + "'가 중복되거나 예약어입니다");
            String op = str(m.get("op"));
            if (!OPS.containsKey(op)) throw new Exception("규칙 " + i + ": 없는 연산자 '" + op + "' — 가능: " + String.join(", ", OPS.keySet()));
            String label = str(m.get("label"));
            out.add(new Rule(id, label.isEmpty() ? OPS.get(op) + " " + str(m.get("value")) : label, op, str(m.get("value")), str(m.get("subject"))));
        }
        return out;
    }

    /** 규칙 하나 판정. ignoreCase: 글자 비교에서 대소문자 무시 */
    public static boolean matches(Rule r, String subject, boolean ignoreCase) throws Exception {
        String s = subject == null ? "" : subject;
        String v = r.value() == null ? "" : r.value();
        String a = ignoreCase ? s.toLowerCase(Locale.ROOT) : s;
        String b = ignoreCase ? v.toLowerCase(Locale.ROOT) : v;
        return switch (r.op()) {
            case "contains" -> a.contains(b);
            case "notContains" -> !a.contains(b);
            case "equals" -> a.trim().equals(b.trim());
            case "notEquals" -> !a.trim().equals(b.trim());
            case "startsWith" -> a.trim().startsWith(b.trim());
            case "endsWith" -> a.trim().endsWith(b.trim());
            case "anyOf" -> Arrays.stream(b.split(",")).map(String::trim).filter(x -> !x.isEmpty()).anyMatch(a::contains);
            case "regex" -> {
                try {
                    yield Pattern.compile(v, ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0).matcher(s).find();
                } catch (Exception e) {
                    throw new Exception("규칙 '" + r.label() + "': 정규식이 올바르지 않습니다 — " + e.getMessage());
                }
            }
            case "empty" -> s.isBlank();
            case "notEmpty" -> !s.isBlank();
            case "gt", "gte", "lt", "lte", "numEquals" -> {
                Double x = number(s), y = number(v);
                if (y == null) throw new Exception("규칙 '" + r.label() + "': 비교할 숫자가 올바르지 않습니다 — '" + v + "'");
                if (x == null) yield false;   // 값이 숫자가 아니면 맞지 않음
                yield switch (r.op()) {
                    case "gt" -> x > y;
                    case "gte" -> x >= y;
                    case "lt" -> x < y;
                    case "lte" -> x <= y;
                    default -> Math.abs(x - y) < 1e-9;
                };
            }
            case "lenGt", "lenLt" -> {
                Double y = number(v);
                if (y == null) throw new Exception("규칙 '" + r.label() + "': 글자 수가 숫자가 아닙니다 — '" + v + "'");
                yield r.op().equals("lenGt") ? s.strip().length() > y : s.strip().length() < y;
            }
            case "expr" -> expr(r, s);
            default -> false;
        };
    }

    /**
     * 식(고급) — Spring 식 언어(SpEL)를 **읽기 전용 안전 모드**로 (클래스 접근·객체 생성·대입 불가).
     * 변수: #value(글자) · #num(숫자 또는 null) · #len(글자 수) · #json(값이 JSON 이면 Map/List, 아니면 null) · #lines(줄 목록)
     */
    static boolean expr(Rule r, String s) throws Exception {
        String src = r.value() == null ? "" : r.value().trim();
        if (src.isEmpty()) throw new Exception("규칙 '" + r.label() + "': 식이 비어 있습니다");
        SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build();
        ctx.setVariable("value", s);
        ctx.setVariable("num", number(s));
        ctx.setVariable("len", s.strip().length());
        ctx.setVariable("json", json(s));
        ctx.setVariable("lines", s.lines().toList());
        try {
            Expression e = SPEL.parseExpression(src);
            Object out = e.getValue(ctx);
            if (out instanceof Boolean bool) return bool;
            throw new Exception("참/거짓이 나오는 식이어야 합니다 (결과: " + out + ")");
        } catch (org.springframework.expression.ExpressionException e) {
            throw new Exception("규칙 '" + r.label() + "': 식 오류 — " + e.getMessage());
        }
    }

    /** "1,234", " 80점 " 처럼 앞뒤 글자·쉼표가 있어도 숫자 하나면 읽음 */
    static Double number(String s) {
        if (s == null) return null;
        String t = s.trim().replace(",", "");
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            var m = Pattern.compile("^[^\\d\\-]*(-?\\d+(?:\\.\\d+)?)[^\\d]*$").matcher(t);
            return m.matches() ? Double.parseDouble(m.group(1)) : null;
        }
    }

    private static final ObjectMapper OM = new ObjectMapper();

    private static Object json(String s) {
        String t = s == null ? "" : s.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) return null;
        try {
            return OM.readValue(t, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
