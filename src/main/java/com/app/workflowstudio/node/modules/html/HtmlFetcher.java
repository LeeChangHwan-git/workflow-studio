package com.app.workflowstudio.node.modules.html;

import com.app.workflowstudio.service.HttpSender;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 웹 페이지 가져오기 — 사내 레거시 게시판을 고려:
 * EUC-KR 등 문자셋 자동 판별(응답 헤더 → meta 태그), 리다이렉트 중 받은 쿠키 유지, 브라우저 같은 User-Agent.
 */
@Component
public class HtmlFetcher {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");
    private static final Pattern CHARSET = Pattern.compile("charset=\\s*\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36 WorkflowStudio";

    /** 가져온 페이지 */
    public record Page(String finalUrl, int status, String charset, Document doc, int bytes) {}

    public Page fetch(String url, List<HttpSender.Header> headers, int timeoutSec) throws Exception {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) throw new Exception("페이지 주소(URL)를 입력하세요");
        if (u.contains("${")) throw new Exception("URL에 해석되지 않은 변수가 있습니다: " + u);
        URI uri;
        try {
            uri = URI.create(u);
        } catch (IllegalArgumentException e) {
            throw new Exception("URL 형식이 올바르지 않습니다: " + u);
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")))
            throw new Exception("URL은 http:// 또는 https:// 로 시작해야 합니다: " + u);

        // 요청마다 쿠키 저장소를 새로 — 로그인 리다이렉트 중 받은 쿠키는 이어서 보내되, 요청끼리 섞이지 않게
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(new CookieManager())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(Math.max(1, timeoutSec))).GET();
        boolean hasUa = false, hasAccept = false;
        if (headers != null) {
            for (HttpSender.Header h : headers) {
                String k = h.key().trim();
                if (RESTRICTED.contains(k.toLowerCase(Locale.ROOT))) continue;
                if (k.equalsIgnoreCase("user-agent")) hasUa = true;
                if (k.equalsIgnoreCase("accept")) hasAccept = true;
                b.header(k, h.value() == null ? "" : h.value());
            }
        }
        if (!hasUa) b.header("User-Agent", USER_AGENT);
        if (!hasAccept) b.header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
        b.header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8");

        HttpResponse<byte[]> resp;
        try {
            resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.ConnectException e) {
            throw new Exception("서버에 연결할 수 없습니다: " + u);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Exception(timeoutSec + "초 안에 응답이 없습니다: " + u);
        }
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        if (body.length > MAX_BYTES) throw new Exception("페이지가 너무 큽니다 (" + body.length / 1024 / 1024 + "MB, 최대 5MB)");
        if (resp.statusCode() >= 400)
            throw new Exception("HTTP " + resp.statusCode() + " 응답 — " + u
                    + (resp.statusCode() == 401 || resp.statusCode() == 403 ? " (로그인이 필요하면 요청 헤더에 Cookie 를 넣으세요)" : ""));

        String finalUrl = resp.uri().toString();
        String charset = resp.headers().firstValue("content-type").map(HtmlFetcher::charsetOf).orElse(null);
        Document doc = Jsoup.parse(new ByteArrayInputStream(body), charset, finalUrl);   // null 이면 meta 태그로 판별
        String used = charset != null ? charset : doc.charset().name();
        return new Page(finalUrl, resp.statusCode(), used, doc, body.length);
    }

    /** "text/html; charset=EUC-KR" → "EUC-KR" (지원 안 하는 이름이면 null → meta 태그로 판별) */
    static String charsetOf(String contentType) {
        Matcher m = CHARSET.matcher(contentType);
        if (!m.find()) return null;
        String cs = m.group(1);
        try {
            return Charset.isSupported(cs) ? cs : null;
        } catch (Exception e) {
            return null;
        }
    }
}
