package com.app.memobookmark.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Outline API 연동 테스트용 Mock 서버.
 * 설정에서 outlineUrl = "http://localhost:{port}/outline-mock" 으로 지정해 사용한다.
 */
@RestController
@RequestMapping("/outline-mock/api")
public class OutlineMockController {

    @PostMapping("/collections.list")
    public Map<String, Object> collectionsList() {
        return Map.of("data", List.of(
            Map.of("id", "col-001", "name", "📢 전사 공지", "description", "전사 공지사항 모음"),
            Map.of("id", "col-002", "name", "🛠 개발팀",    "description", "개발팀 기술 문서"),
            Map.of("id", "col-003", "name", "📊 경영지원",  "description", "경영지원 자료")
        ), "ok", true, "status", 200);
    }

    @PostMapping("/documents.list")
    public Map<String, Object> documentsList(@RequestBody(required = false) Map<String, Object> body) {
        String collId = body != null ? String.valueOf(body.getOrDefault("collectionId", "")) : "";

        List<Map<String, Object>> docs = switch (collId) {
            case "col-001" -> List.of(
                Map.of("id", "doc-101", "title", "2026년 2분기 전사 목표 공유", "collectionId", "col-001"),
                Map.of("id", "doc-102", "title", "6월 타운홀 미팅 안내",         "collectionId", "col-001"),
                Map.of("id", "doc-103", "title", "사내 보안 정책 업데이트",       "collectionId", "col-001"),
                Map.of("id", "doc-104", "title", "휴가 신청 프로세스 변경 안내",  "collectionId", "col-001")
            );
            case "col-002" -> List.of(
                Map.of("id", "doc-201", "title", "API 설계 가이드",             "collectionId", "col-002"),
                Map.of("id", "doc-202", "title", "코드리뷰 프로세스",            "collectionId", "col-002"),
                Map.of("id", "doc-203", "title", "배포 가이드라인",              "collectionId", "col-002")
            );
            default -> List.of(
                Map.of("id", "doc-101", "title", "2026년 2분기 전사 목표 공유", "collectionId", "col-001"),
                Map.of("id", "doc-102", "title", "6월 타운홀 미팅 안내",         "collectionId", "col-001"),
                Map.of("id", "doc-201", "title", "API 설계 가이드",             "collectionId", "col-002"),
                Map.of("id", "doc-202", "title", "코드리뷰 프로세스",            "collectionId", "col-002"),
                Map.of("id", "doc-103", "title", "사내 보안 정책 업데이트",      "collectionId", "col-001")
            );
        };
        return Map.of("data", docs, "ok", true, "status", 200);
    }

    @PostMapping("/documents.search")
    public Map<String, Object> documentsSearch(@RequestBody Map<String, Object> body) {
        String query = String.valueOf(body.getOrDefault("query", ""));
        return Map.of("data", List.of(
            Map.of(
                "document", Map.of("id", "doc-101", "title", "2026년 2분기 전사 목표 공유"),
                "context",  "검색어 \"" + query + "\"와 관련된 내용이 포함된 문서입니다. 전사 OKR 달성률 및 분기 목표를 다룹니다."
            ),
            Map.of(
                "document", Map.of("id", "doc-103", "title", "사내 보안 정책 업데이트"),
                "context",  "\"" + query + "\" 관련: 접근 권한 재검토 및 보안 패치 일정을 포함합니다."
            )
        ), "ok", true, "status", 200);
    }

    @PostMapping("/documents.export")
    public Map<String, Object> documentsExport(@RequestBody Map<String, Object> body) {
        String id = String.valueOf(body.getOrDefault("id", ""));
        String md = switch (id) {
            case "doc-101" -> """
                # 2026년 2분기 전사 목표 공유

                ## 개요
                본 문서는 2026년 2분기(4~6월) 전사 OKR 및 주요 목표를 공유합니다.

                ## 핵심 목표 (OKR)

                ### Objective 1: 고객 만족도 향상
                - KR1: NPS 점수 45 → 60 달성
                - KR2: 고객 응답 시간 24h → 4h 이내

                ### Objective 2: 제품 안정성 강화
                - KR1: 서비스 가용성 99.5% → 99.9%
                - KR2: 치명적 버그 처리 시간 72h → 24h

                ### Objective 3: 팀 역량 강화
                - KR1: 전 직원 OKR 이해도 평가 80점 이상
                - KR2: 1인당 월 1회 이상 사내 스터디 참여

                ## 일정
                | 구분 | 일정 |
                |------|------|
                | 중간 점검 | 2026-05-15 |
                | 최종 리뷰 | 2026-06-30 |

                ---
                *작성: 경영기획팀*
                """;
            case "doc-103" -> """
                # 사내 보안 정책 업데이트 (2026.06)

                ## 변경 사항 요약
                1. **패스워드 정책 강화**: 최소 12자, 특수문자 필수
                2. **MFA 의무화**: 2026-07-01부터 전 직원 적용
                3. **접근 권한 재검토**: 분기별 권한 감사 실시

                ## 조치 사항
                - [ ] 패스워드 변경 (기한: 2026-06-30)
                - [ ] MFA 앱 설치 및 등록
                - [ ] 불필요 권한 반납 신청
                """;
            default -> "# 문서 내용\n\n문서 ID `" + id + "` 의 내용입니다.\n\n> (Mock 데이터)";
        };
        return Map.of("data", md, "ok", true, "status", 200);
    }
}
