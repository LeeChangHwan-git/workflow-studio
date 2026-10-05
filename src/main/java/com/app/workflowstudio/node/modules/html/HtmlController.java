package com.app.workflowstudio.node.modules.html;

import com.app.workflowstudio.node.modules.http.HttpRequestModule;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 🔍 HTML 분석기 API (웹 페이지 추출 노드 설정 화면) */
@RestController
@RequestMapping("/api/html")
public class HtmlController {

    private final HtmlFetcher fetcher;

    public HtmlController(HtmlFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * 페이지를 가져와 분석기에서 보여 줄 안전한 HTML로 바꿔 돌려줌 {url, headers}
     * → {ok, finalUrl, status, charset, title, bytes, html, frames:[url]} | {ok:false, message}
     */
    @PostMapping("/fetch")
    public Map<String, Object> fetch(@RequestBody Map<String, String> req) {
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            HtmlFetcher.Page page = fetcher.fetch(req.get("url"),
                    HttpRequestModule.parseHeaders(req.getOrDefault("headers", "")), 30);
            Document doc = page.doc();
            res.put("ok", true);
            res.put("finalUrl", page.finalUrl());
            res.put("status", page.status());
            res.put("charset", page.charset());
            res.put("title", doc.title());
            res.put("bytes", page.bytes());
            res.put("frames", frames(doc));
            res.put("html", sanitize(doc, page.finalUrl()));
        } catch (Exception e) {
            res.put("ok", false);
            res.put("message", e.getMessage() == null ? "가져올 수 없습니다" : e.getMessage());
        }
        return res;
    }

    /** 서버 기준 추출 미리보기 {url, headers, rowSelector, fields, limit} → {ok, count, items} | {ok:false, message} */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Map<String, String> req) {
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            List<HtmlExtractor.Field> fields = HtmlExtractor.parseFields(req.get("fields"));
            HtmlFetcher.Page page = fetcher.fetch(req.get("url"),
                    HttpRequestModule.parseHeaders(req.getOrDefault("headers", "")), 30);
            int limit = 20;
            try {
                limit = Math.max(1, Math.min(200, Integer.parseInt(req.getOrDefault("limit", "20"))));
            } catch (NumberFormatException ignore) {
            }
            List<Map<String, String>> items = HtmlExtractor.extract(page.doc(), req.get("rowSelector"), fields, limit);
            res.put("ok", true);
            res.put("count", items.size());
            res.put("items", items);
        } catch (Exception e) {
            res.put("ok", false);
            res.put("message", e.getMessage());
        }
        return res;
    }

    /** 프레임(frameset/iframe)으로 된 페이지면 안쪽 페이지 주소들 — 실제 내용이 거기 있음 */
    static List<String> frames(Document doc) {
        List<String> out = new ArrayList<>();
        for (Element f : doc.select("frame[src], iframe[src]")) {
            String abs = f.absUrl("src");
            if (!abs.isEmpty() && abs.startsWith("http") && !out.contains(abs)) out.add(abs);
        }
        return out;
    }

    /**
     * 분석기 화면용: 스크립트·자동 이동·이벤트 속성 제거, 프레임은 안내 상자로, 상대 주소는 원래 사이트 기준(base).
     * 화면에서는 스크립트를 막은 iframe(sandbox) 안에 넣어서 보여 준다.
     */
    static String sanitize(Document original, String baseUrl) {
        Document doc = original.clone();
        doc.select("script, noscript, object, embed, applet, meta[http-equiv=refresh], base").remove();
        for (Element f : doc.select("frame, iframe")) {
            Element box = new Element("div").attr("data-ws-frame", f.absUrl("src"))
                    .attr("style", "border:2px dashed #D97706;padding:10px;margin:6px;font:13px sans-serif;color:#D97706")
                    .text("[프레임] " + f.absUrl("src"));
            f.replaceWith(box);
        }
        doc.select("frameset").tagName("div");
        for (Element el : doc.getAllElements()) {
            List<String> drop = new ArrayList<>();
            el.attributes().forEach(a -> {
                String k = a.getKey().toLowerCase(Locale.ROOT);
                String v = a.getValue().trim().toLowerCase(Locale.ROOT);
                if (k.startsWith("on") || ((k.equals("href") || k.equals("src") || k.equals("action")) && v.startsWith("javascript:")))
                    drop.add(a.getKey());
            });
            drop.forEach(el::removeAttr);
        }
        doc.head().prependElement("base").attr("href", baseUrl);
        doc.head().prependElement("meta").attr("charset", "utf-8");
        doc.outputSettings().charset("UTF-8");
        return doc.outerHtml();
    }
}
