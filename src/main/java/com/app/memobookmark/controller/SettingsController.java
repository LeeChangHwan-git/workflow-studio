package com.app.memobookmark.controller;

import com.app.memobookmark.service.AppSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final AppSettingsService settingsService;

    public SettingsController(AppSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("settings", settingsService.load());
        return "settings/index";
    }
}
