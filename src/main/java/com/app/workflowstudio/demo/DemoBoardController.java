package com.app.workflowstudio.demo;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 시연용 사내 게시판 (그룹웨어 흉내) — 브라우저로만 보는 옛날식 게시판을 "웹 페이지 추출" 노드로 읽어 오는 시연용.
 * 로그인 쿠키({@value #COOKIE}={@value #SESSION})가 있어야 목록이 보인다 (없으면 로그인 화면) → 요청 헤더에 쿠키를 넣는 연습.
 * 글 날짜는 오늘 기준으로 매일 바뀜.
 */
@Controller
@RequestMapping("/demo-board")
public class DemoBoardController {

    public static final String COOKIE = "GW_SESSION";
    public static final String SESSION = "demo-7f3a91c2";

    public record Post(int no, String category, String title, String writer, String dept, LocalDate date, int views, String body) {}

    /** API(POST /demo-board/api/posts)로 쓴 글 — 메모리, 서버를 다시 켜면 사라짐 */
    private static final List<Post> written = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.concurrent.atomic.AtomicInteger nextNo = new java.util.concurrent.atomic.AtomicInteger(1049);

    /** 오늘 기준 글 목록 (최신 순) — API 로 쓴 글이 맨 위 */
    static List<Post> posts() {
        LocalDate t = LocalDate.now();
        List<Post> p = new ArrayList<>(written);
        p.add(new Post(1048, "보안", "OTA 서버 TLS 인증서 교체 일정 안내 (10/20 02:00~04:00)", "정보안", "보안팀", t, 37,
                "OTA 배포 서버 인증서가 만료 예정이라 교체합니다. 펌웨어에 고정(pinning)된 루트 인증서가 있는 제품은 사전 업데이트가 필요합니다. 펌웨어 담당자는 10/15까지 영향 모델을 회신해 주세요."));
        p.add(new Post(1047, "총무", "10월 넷째 주 사내 식당 메뉴", "총무팀", "총무팀", t, 211,
                "월: 김치찌개 / 화: 돈가스 / 수: 비빔밥 / 목: 짜장면 / 금: 특식(삼계탕)"));
        p.add(new Post(1046, "개발", "부트로더 서명 키 HSM 이관 — 빌드 파이프라인 변경", "박민수", "PM", t, 52,
                "펌웨어 서명 키를 빌드 서버 파일에서 HSM 으로 옮깁니다. 10/24 부터 서명 단계가 HSM API 호출로 바뀌며, 로컬 서명은 불가합니다. 부트로더 v2.3 검증 절차는 그대로입니다."));
        p.add(new Post(1045, "인사", "2026 하반기 체육대회 참가 신청", "인사팀", "인사팀", t.minusDays(1), 145,
                "11/8(토) 올림픽공원. 종목: 축구, 피구, 계주. 팀별 10명 이내 신청."));
        p.add(new Post(1044, "품질", "센서보드 9월 필드 불량 — Wi-Fi 재연결 실패 12건 분석 요청", "이철수", "QA팀", t.minusDays(1), 64,
                "필드 반품 12건 중 9건이 DHCP 갱신 실패 후 재연결 안 됨. 펌웨어 0.9.8 이하에서만 발생. FW팀 원인 분석과 핫픽스 일정 회신 바랍니다."));
        p.add(new Post(1043, "총무", "주차장 B2 도색 공사 (10/25~26)", "총무팀", "총무팀", t.minusDays(1), 98,
                "공사 기간 B2 주차 불가, B3 이용 바랍니다."));
        p.add(new Post(1042, "개발", "MCU 벤더 SDK 3.2 보안 패치 배포 (CVE-2026-1187)", "정보안", "보안팀", t.minusDays(2), 71,
                "BLE 스택 버퍼 오버플로 취약점. SDK 3.2.1 로 올리고 재빌드 필요. 해당 MCU 사용 제품: 센서보드, 게이트웨이."));
        p.add(new Post(1041, "경조사", "[부고] HW팀 김영희 책임 부친상", "인사팀", "인사팀", t.minusDays(2), 120,
                "삼가 고인의 명복을 빕니다. 빈소: 서울대병원 장례식장 3호실"));
        p.add(new Post(1040, "HW", "메인보드 Rev.D 핀맵 변경 — UART2 / GPIO12 재할당", "김영희", "HW팀", t.minusDays(3), 58,
                "Rev.D 부터 UART2 가 PA9/PA10 → PB6/PB7, GPIO12 는 LED 대신 센서 인터럽트. 펌웨어 보드 정의 파일 수정 필요."));
        p.add(new Post(1039, "교육", "법정 의무교육(개인정보보호) 이수 안내 — 10/31 마감", "인사팀", "인사팀", t.minusDays(3), 302,
                "미이수 시 인사 평가 반영. 사내 교육 사이트에서 1시간 온라인 수강."));
        p.add(new Post(1038, "생산", "양산 라인 펌웨어 굽기 지그 교체 (11월 첫 주)", "박민수", "PM", t.minusDays(4), 33,
                "구형 JTAG 지그를 SWD 일괄 기록 지그로 교체합니다. 양산용 바이너리는 서명된 .bin 만 허용."));
        p.add(new Post(1037, "마케팅", "신제품 런칭 행사 자원봉사자 모집", "마케팅팀", "마케팅팀", t.minusDays(4), 77,
                "11/15 코엑스. 부스 안내 인원 6명 모집, 특근 수당 지급."));
        p.add(new Post(1036, "인프라", "빌드 서버 정기 점검 (토 22:00~일 06:00) — CI 중단", "인프라팀", "인프라팀", t.minusDays(5), 41,
                "점검 시간 동안 Jenkins·아티팩트 저장소 사용 불가. 야간 자동 빌드는 일요일 오전으로 미뤄집니다."));
        p.add(new Post(1035, "복지", "단체 상해보험 갱신 및 가족 추가 신청", "인사팀", "인사팀", t.minusDays(6), 88,
                "10/30 까지 신청서 제출."));
        return p;
    }

    @GetMapping
    public String list(@CookieValue(name = COOKIE, required = false) String session, Model model) {
        if (!SESSION.equals(session)) return "demo/board-login";
        model.addAttribute("posts", posts());
        model.addAttribute("today", LocalDate.now());
        return "demo/board";
    }

    @GetMapping("/{no}")
    public String view(@PathVariable int no, @CookieValue(name = COOKIE, required = false) String session, Model model) {
        if (!SESSION.equals(session)) return "demo/board-login";
        Post post = posts().stream().filter(x -> x.no() == no).findFirst().orElse(null);
        if (post == null) return "redirect:/demo-board";
        model.addAttribute("post", post);
        return "demo/board-post";
    }

    /** 시연용 로그인 — 아무 아이디/비밀번호나 받아 쿠키를 심음 */
    @PostMapping("/login")
    public String login(HttpServletResponse res) {
        Cookie c = new Cookie(COOKIE, SESSION);
        c.setPath("/demo-board");
        c.setHttpOnly(true);
        res.addCookie(c);
        return "redirect:/demo-board";
    }

    /**
     * 글쓰기 API (회사 그룹웨어 API 흉내) — 쿠키 GW_SESSION 필요.
     * {category?, title, content, writer?} → {result:"OK", no, url} · 쿠키 없으면 401, 제목·내용 없으면 400
     */
    @PostMapping("/api/posts")
    @ResponseBody
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> write(
            @CookieValue(name = COOKIE, required = false) String session,
            @RequestBody java.util.Map<String, Object> req, jakarta.servlet.http.HttpServletRequest http) {
        if (!SESSION.equals(session))
            return org.springframework.http.ResponseEntity.status(401).body(java.util.Map.of("result", "UNAUTHORIZED", "message", "로그인이 필요합니다 (쿠키 GW_SESSION)"));
        String title = str(req.get("title")), content = str(req.get("content"));
        if (title.isEmpty() || content.isEmpty())
            return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("result", "BAD_REQUEST", "message", "title, content 는 꼭 있어야 합니다"));
        String category = str(req.get("category"));
        String writer = str(req.get("writer"));
        int no = nextNo.getAndIncrement();
        written.add(0, new Post(no, category.isEmpty() ? "일반" : category, title.length() > 120 ? title.substring(0, 120) : title,
                writer.isEmpty() ? "나" : writer, "FW팀", LocalDate.now(), 0, content));
        String url = http.getScheme() + "://" + http.getServerName() + ":" + http.getServerPort() + "/demo-board/" + no;
        return org.springframework.http.ResponseEntity.ok(java.util.Map.of("result", "OK", "no", no, "url", url));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    @PostMapping("/logout")
    public String logout(HttpServletResponse res) {
        Cookie c = new Cookie(COOKIE, "");
        c.setPath("/demo-board");
        c.setMaxAge(0);
        res.addCookie(c);
        return "redirect:/demo-board";
    }
}
