package com.app.workflowstudio.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/memos";
    }

    /** 🍪 로그인 쿠키 넣는 법 (요청 헤더 칸의 ❓ 버튼이 별도 창으로 띄움) */
    @GetMapping("/guide/cookie")
    public String cookieGuide() {
        return "guide/cookie";
    }
}
