package com.app.workflowstudio.node;

import java.util.List;

/**
 * 노드 설정 파라미터 — 설정 패널(ConfigPanel)의 입력 필드 하나에 대응.
 *
 * @param type    text | textarea | select | number | password | image
 *                | webhookUrls (값 없음 — 웹훅 테스트/운영 URL 표시 전용)
 * @param options select 전용 선택지 (그 외 타입은 null)
 */
public record NodeParam(
        String name,
        String label,
        String type,
        Object defaultValue,
        List<String> options
) {}
