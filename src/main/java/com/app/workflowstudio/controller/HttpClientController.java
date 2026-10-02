package com.app.workflowstudio.controller;

import com.app.workflowstudio.service.HttpSender;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;

/**
 * 간이 HTTP 클라이언트 (Postman 류) — 웹훅 테스트 등에 사용.
 * 브라우저에서 직접 보내면 외부 URL은 CORS에 막히므로 서버가 대신 요청을 보내고 결과를 돌려준다.
 */
@Controller
public class HttpClientController {

    private final HttpSender sender;

    public HttpClientController(HttpSender sender) {
        this.sender = sender;
    }

    @GetMapping("/http")
    public String page() {
        return "http/index";
    }

    public record SendRequest(String method, String url, List<HttpSender.Header> headers, String body) {}

    @PostMapping("/api/http-client/send")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendRequest req) {
        Map<String, Object> res = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            HttpSender.Result r = sender.send(req.method(), req.url(), req.headers(), req.body(), Duration.ofSeconds(30));
            res.put("status", r.status());
            res.put("durationMs", r.durationMs());
            res.put("size", r.size());
            res.put("headers", r.headers());
            res.put("body", r.body());
            res.put("truncated", r.truncated());
            if (!r.skippedHeaders().isEmpty()) res.put("skippedHeaders", r.skippedHeaders());
            return ResponseEntity.ok(res);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            res.put("error", "요청이 중단되었습니다");
        } catch (Exception e) {
            res.put("error", e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
        }
        res.put("durationMs", (System.nanoTime() - start) / 1_000_000);
        return ResponseEntity.ok(res);
    }
}
