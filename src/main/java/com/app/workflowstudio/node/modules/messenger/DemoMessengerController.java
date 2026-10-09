package com.app.workflowstudio.node.modules.messenger;

import com.app.workflowstudio.settings.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 시연용 사내 메신저 — 회사 메신저 API 를 흉내 내는 받는 쪽.
 * 모든 쪽지는 쪽지함(/demo-messenger)에 남기고(메모리, 최근 100개), 그중 <b>나(이 PC 사용자, ⚙ 설정 demo.messengerMyId)</b>
 * 에게 온 쪽지만 화면 오른쪽 아래에 쪽지 창을 띄운다 — 다른 사람에게 보낸 쪽지는 그 사람 PC에 뜨는 것이므로.
 */
@Controller
@RequestMapping("/demo-messenger")
public class DemoMessengerController {

    /** 시연용 사원 목록 (사번 → 이름·부서). 없는 사번도 받되 "사번 …"으로 표시 */
    static final String ME_DEFAULT = "20240001";
    static final Map<String, String> EMPLOYEES = new LinkedHashMap<>();
    static {
        EMPLOYEES.put(ME_DEFAULT, "나 (Workflow Studio)");
        EMPLOYEES.put("20231234", "홍길동 (FW팀)");
        EMPLOYEES.put("20220567", "김영희 (HW팀)");
        EMPLOYEES.put("20210089", "이철수 (QA팀)");
        EMPLOYEES.put("20190012", "박민수 (PM)");
        EMPLOYEES.put("20200345", "정보안 (보안팀)");
    }

    private static final int MAX = 100;
    private final List<Map<String, Object>> notes = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private final SettingsService settings;

    public DemoMessengerController(SettingsService settings) {
        this.settings = settings;
    }

    /** 나(이 PC)의 사번 */
    String myId() {
        var s = settings.get();
        return s == null || s.demo == null ? ME_DEFAULT : s.demo.messengerMyId;
    }

    /** 사번 → "이름 · 사번" (나면 이름 대신 "나") */
    String label(String empId) {
        String name = EMPLOYEES.get(empId);
        if (empId.equals(myId())) return (name != null && !empId.equals(ME_DEFAULT) ? "나 · " + name : "나") + " · " + empId;
        return name != null ? name + " · " + empId : "사번 " + empId;
    }

    @GetMapping
    public String page() {
        return "demo/messenger";
    }

    /** 쪽지 보내기 {empId, message, sender?} → {ok, id, to, shown} — 회사 메신저 API 와 같은 자리 */
    @PostMapping("/api/notes")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, Object> req) {
        String empId = str(req.get("empId"));
        String message = str(req.get("message"));
        String sender = str(req.get("sender"));
        if (empId.isEmpty()) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "empId(사번)가 없습니다"));
        if (message.isEmpty()) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "message(내용)가 없습니다"));
        if (message.length() > 4000) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "내용은 4000자까지 보낼 수 있습니다"));
        if (sender.isEmpty()) sender = "Workflow Studio";

        String name = EMPLOYEES.get(empId);
        String toLabel = label(empId);
        boolean toMe = empId.equals(myId());
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", seq.incrementAndGet());
        note.put("time", time);
        note.put("empId", empId);
        note.put("to", toLabel);
        note.put("known", name != null || toMe);
        note.put("toMe", toMe);
        note.put("sender", sender);
        note.put("message", message);
        notes.add(0, note);
        while (notes.size() > MAX) notes.remove(notes.size() - 1);

        if (toMe) NotePopup.show(sender, message, time);   // 다른 사람 쪽지는 그 사람 PC 에 뜨는 것 — 이 PC 에는 안 띄움

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("id", note.get("id"));
        res.put("to", toLabel);
        res.put("toMe", toMe);
        res.put("shown", toMe && NotePopup.available());
        return ResponseEntity.ok(res);
    }

    /** 받은 쪽지 (최신 순) */
    @GetMapping("/api/notes")
    @ResponseBody
    public Map<String, Object> list() {
        Map<String, String> emps = new LinkedHashMap<>();
        for (String id : EMPLOYEES.keySet()) emps.put(id, label(id).replaceAll(" · " + id + "$", ""));
        return Map.of("notes", notes, "employees", emps, "myId", myId());
    }

    /** 나(이 PC)의 사번 바꾸기 {empId} — ⚙ 설정 demo.messengerMyId 에 저장 */
    @PostMapping("/api/me")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setMe(@RequestBody Map<String, Object> req) throws java.io.IOException {
        String id = str(req.get("empId"));
        if (!id.matches("[A-Za-z0-9_-]{1,32}")) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "사번은 영문·숫자 32자까지"));
        var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.putObject("demo").put("messengerMyId", id);
        settings.update(node);
        return ResponseEntity.ok(Map.of("ok", true, "myId", myId()));
    }

    @DeleteMapping("/api/notes")
    @ResponseBody
    public Map<String, Object> clear() {
        notes.clear();
        return Map.of("ok", true);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
