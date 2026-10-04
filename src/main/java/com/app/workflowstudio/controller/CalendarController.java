package com.app.workflowstudio.controller;

import com.app.workflowstudio.model.CalendarEvent;
import com.app.workflowstudio.node.modules.schedule.ScheduleService;
import com.app.workflowstudio.service.CalendarEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/calendar")
public class CalendarController {

    private final CalendarEventService service;
    private final ObjectMapper objectMapper;
    private final ScheduleService schedules;

    public CalendarController(CalendarEventService service, ObjectMapper objectMapper, ScheduleService schedules) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.schedules = schedules;
    }

    @GetMapping
    public String calendar(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            Model model) throws Exception {

        LocalDate today = LocalDate.now();
        int y = (year != null) ? year : today.getYear();
        int m = (month != null) ? month : today.getMonthValue();
        YearMonth ym = YearMonth.of(y, m);

        // 이번 달 이벤트
        List<CalendarEvent> monthEvents = service.findByYearMonth(y, m);

        // 날짜별 이벤트 맵 (JS에 전달할 JSON)
        Map<String, List<Map<String, String>>> eventsMap = new LinkedHashMap<>();
        for (CalendarEvent e : monthEvents) {
            String key = e.getDate().toString();
            eventsMap.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(Map.of(
                            "id",      e.getId(),
                            "title",   e.getTitle() != null ? e.getTitle() : "",
                            "content", e.getContent() != null ? e.getContent() : ""
                    ));
        }
        String eventsJson = objectMapper.writeValueAsString(eventsMap);

        // 달력 주(week) 행 계산
        LocalDate firstDay = ym.atDay(1);
        // 일요일=0 기준 오프셋
        int offset = firstDay.getDayOfWeek().getValue() % 7;
        LocalDate cursor = firstDay.minusDays(offset);

        List<List<LocalDate>> weeks = new ArrayList<>();
        while (cursor.isBefore(ym.atEndOfMonth().plusDays(1))) {
            List<LocalDate> week = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                week.add(cursor);
                cursor = cursor.plusDays(1);
            }
            weeks.add(week);
        }

        // 타이머 일정 (화면에 보이는 첫 주 일요일 ~ 마지막 주 토요일) — 예정 + 지난 실행
        LocalDate gridStart = weeks.get(0).get(0);
        LocalDate gridEnd = weeks.get(weeks.size() - 1).get(6);
        model.addAttribute("timersJson", objectMapper.writeValueAsString(schedules.calendar(gridStart, gridEnd)));

        YearMonth prevYm = ym.minusMonths(1);
        YearMonth nextYm = ym.plusMonths(1);

        model.addAttribute("ym", ym);
        model.addAttribute("today", today);
        model.addAttribute("weeks", weeks);
        model.addAttribute("eventsJson", eventsJson);
        model.addAttribute("prevYear", prevYm.getYear());
        model.addAttribute("prevMonth", prevYm.getMonthValue());
        model.addAttribute("nextYear", nextYm.getYear());
        model.addAttribute("nextMonth", nextYm.getMonthValue());
        model.addAttribute("currentYear", y);
        model.addAttribute("currentMonth", m);

        return "calendar/index";
    }

    @PostMapping
    public String create(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String title,
            @RequestParam String content,
            @RequestParam int year,
            @RequestParam int month) {
        CalendarEvent event = new CalendarEvent();
        event.setDate(date);
        event.setTitle(title);
        event.setContent(content);
        service.create(event);
        return "redirect:/calendar?year=" + year + "&month=" + month;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id,
                         @RequestParam int year,
                         @RequestParam int month) {
        service.delete(id);
        return "redirect:/calendar?year=" + year + "&month=" + month;
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable String id,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @RequestParam(required = false) String title,
                         @RequestParam String content,
                         @RequestParam int year,
                         @RequestParam int month) {
        CalendarEvent updated = new CalendarEvent();
        updated.setDate(date);
        updated.setTitle(title);
        updated.setContent(content);
        service.update(id, updated);
        return "redirect:/calendar?year=" + year + "&month=" + month;
    }
}
