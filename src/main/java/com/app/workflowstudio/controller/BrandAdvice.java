package com.app.workflowstudio.controller;

import com.app.workflowstudio.settings.SettingsService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 모든 화면의 상단 메뉴(fragments/nav)가 쓸 로고 종류 — ⚙ 설정 / 🎨 /brand 에서 고름 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class BrandAdvice {

    private final SettingsService settings;

    public BrandAdvice(SettingsService settings) {
        this.settings = settings;
    }

    @ModelAttribute("brandLogo")
    public String brandLogo() {
        var s = settings.get();
        return s == null || s.appearance == null ? "bench" : s.appearance.logo;
    }
}
