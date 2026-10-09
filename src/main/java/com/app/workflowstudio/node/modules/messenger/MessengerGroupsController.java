package com.app.workflowstudio.node.modules.messenger;

import com.app.workflowstudio.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/** 메신저 그룹 목록 (⚙ 설정 → 👥 메신저 그룹 / 쪽지 노드의 그룹 칩) — 저장은 PUT /api/settings {messenger:{groups}} */
@RestController
public class MessengerGroupsController {

    private final SettingsService settings;

    public MessengerGroupsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/messenger/groups")
    public Map<String, Object> groups() {
        var s = settings.get();
        String me = s.demo == null ? DemoMessengerController.ME_DEFAULT : s.demo.messengerMyId;
        List<Map<String, Object>> out = new ArrayList<>();
        for (var g : s.messenger.groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", g.name);
            m.put("description", g.description);
            m.put("members", g.memberIds().stream().map(id -> Map.of("id", id, "name", nameOf(id, me))).toList());
            out.add(m);
        }
        Map<String, String> people = new LinkedHashMap<>();
        for (var e : DemoMessengerController.EMPLOYEES.entrySet()) people.put(e.getKey(), nameOf(e.getKey(), me));
        return Map.of("groups", out, "people", people, "myId", me);
    }

    private static String nameOf(String id, String me) {
        String n = DemoMessengerController.EMPLOYEES.get(id);
        if (id.equals(me)) return n == null || id.equals(DemoMessengerController.ME_DEFAULT) ? "나" : "나 · " + n;
        return n == null ? "사번 " + id : n;
    }
}
