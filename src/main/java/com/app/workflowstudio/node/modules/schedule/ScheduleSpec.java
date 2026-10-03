package com.app.workflowstudio.node.modules.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 타이머 노드 설정 → 실행 규칙. 4가지 방식 중 하나만 채워진다.
 * <ul>
 *   <li>일정 간격: {@link #interval} (최소 1분)</li>
 *   <li>매일·요일 지정 시각 / 고급(cron): {@link #cron} (Spring 6자리 cron)</li>
 *   <li>한 번만: {@link #once}</li>
 * </ul>
 */
public record ScheduleSpec(String description, CronExpression cron, Duration interval, LocalDateTime once) {

    public static final String MODE_INTERVAL = "일정 간격";
    public static final String MODE_DAILY = "매일·요일 지정 시각";
    public static final String MODE_CRON = "고급 (cron)";
    public static final String MODE_ONCE = "한 번만";
    public static final List<String> MODES = List.of(MODE_INTERVAL, MODE_DAILY, MODE_CRON, MODE_ONCE);

    public static final String UNIT_MIN = "분";
    public static final String UNIT_HOUR = "시간";

    /** 요일 선택지 → cron 요일 필드 */
    public static final LinkedHashMap<String, String> DAYS = new LinkedHashMap<>();
    static {
        DAYS.put("매일", "*");
        DAYS.put("평일 (월~금)", "MON-FRI");
        DAYS.put("주말 (토·일)", "SAT,SUN");
        DAYS.put("월요일", "MON");
        DAYS.put("화요일", "TUE");
        DAYS.put("수요일", "WED");
        DAYS.put("목요일", "THU");
        DAYS.put("금요일", "FRI");
        DAYS.put("토요일", "SAT");
        DAYS.put("일요일", "SUN");
    }

    static final DateTimeFormatter ONCE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final Duration MIN_INTERVAL = Duration.ofMinutes(1);
    static final Duration MAX_INTERVAL = Duration.ofDays(31);

    /** 노드 설정값 → 규칙 (잘못된 값이면 사용자에게 보여줄 메시지로 예외) */
    public static ScheduleSpec parse(Map<String, Object> config) throws Exception {
        String mode = str(config, "mode", MODE_INTERVAL);
        switch (mode) {
            case MODE_INTERVAL -> {
                int value = intVal(config, "intervalValue", 5);
                String unit = str(config, "intervalUnit", UNIT_MIN);
                if (value < 1) throw new Exception("간격은 1 이상이어야 합니다");
                Duration d = UNIT_HOUR.equals(unit) ? Duration.ofHours(value) : Duration.ofMinutes(value);
                if (d.compareTo(MIN_INTERVAL) < 0) throw new Exception("간격은 최소 1분입니다");
                if (d.compareTo(MAX_INTERVAL) > 0) throw new Exception("간격이 너무 깁니다 (최대 31일) — '매일·요일 지정 시각'을 쓰세요");
                return new ScheduleSpec(value + unit + "마다", null, d, null);
            }
            case MODE_DAILY -> {
                LocalTime t = parseTime(str(config, "time", "09:00"));
                String daysLabel = str(config, "days", "매일");
                String days = DAYS.get(daysLabel);
                if (days == null) throw new Exception("요일 선택이 올바르지 않습니다: " + daysLabel);
                CronExpression cron = CronExpression.parse("0 " + t.getMinute() + " " + t.getHour() + " * * " + days);
                return new ScheduleSpec(daysLabel.replaceAll(" \\(.*\\)", "") + " " + t, cron, null, null);
            }
            case MODE_CRON -> {
                String expr = str(config, "cron", "").trim().replaceAll("\\s+", " ");
                if (expr.isEmpty()) throw new Exception("cron 식을 입력하세요 (예: 0 9 * * MON-FRI)");
                // 리눅스 crontab 5자리(분 시 일 월 요일)도 받아서 초(0)를 앞에 붙임
                String six = expr.split(" ").length == 5 ? "0 " + expr : expr;
                try {
                    return new ScheduleSpec("cron: " + expr, CronExpression.parse(six), null, null);
                } catch (IllegalArgumentException e) {
                    throw new Exception("cron 식이 올바르지 않습니다: " + e.getMessage());
                }
            }
            case MODE_ONCE -> {
                String raw = str(config, "onceAt", "").trim();
                LocalDateTime at;
                try {
                    at = LocalDateTime.parse(raw, ONCE_FORMAT);
                } catch (DateTimeParseException e) {
                    throw new Exception("날짜·시각 형식이 올바르지 않습니다: '" + raw + "' (예: 2026-10-05 09:00)");
                }
                return new ScheduleSpec(at.format(ONCE_FORMAT) + " 한 번", null, null, at);
            }
            default -> throw new Exception("알 수 없는 실행 방식: " + mode);
        }
    }

    /**
     * from 이후 다음 실행 시각 최대 count개.
     * 간격 방식은 anchor(등록 시각)부터 interval 배수로 계산 (anchor가 없으면 from 기준).
     */
    public List<LocalDateTime> nextRuns(LocalDateTime from, LocalDateTime anchor, int count) {
        List<LocalDateTime> out = new ArrayList<>();
        if (cron != null) {
            LocalDateTime t = from;
            for (int i = 0; i < count; i++) {
                t = cron.next(t);
                if (t == null) break;
                out.add(t);
            }
        } else if (interval != null) {
            LocalDateTime base = anchor != null ? anchor : from;
            long periods = Math.max(0, Duration.between(base, from).toMillis() / interval.toMillis());
            LocalDateTime t = base.plus(interval.multipliedBy(periods));
            while (!t.isAfter(from)) t = t.plus(interval);
            for (int i = 0; i < count; i++) {
                out.add(t);
                t = t.plus(interval);
            }
        } else if (once != null && once.isAfter(from)) {
            out.add(once);
        }
        return out;
    }

    static LocalTime parseTime(String raw) throws Exception {
        String s = raw == null ? "" : raw.trim();
        try {
            return LocalTime.parse(s.length() == 4 && s.charAt(1) == ':' ? "0" + s : s);
        } catch (DateTimeParseException e) {
            throw new Exception("시각 형식이 올바르지 않습니다: '" + raw + "' (예: 09:00, 18:30)");
        }
    }

    private static String str(Map<String, Object> c, String key, String def) {
        Object v = c.get(key);
        return v instanceof String s && !s.isBlank() ? s : (v instanceof Number n ? String.valueOf(n) : def);
    }

    private static int intVal(Map<String, Object> c, String key, int def) {
        Object v = c.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }
}
