package com.app.workflowstudio.node;

import java.util.List;

/**
 * 노드 설정 파라미터 — 설정 패널(ConfigPanel)의 입력 필드 하나에 대응.
 *
 * @param type     text | textarea | select | number | password | image | folder | file
 *                 | webhookUrls · webhookTemplate (웹훅 전용) | geminiModel · memoryInfo (AI 부품 전용)
 * @param options  select 전용 선택지 (그 외 타입은 null)
 * @param showWhen 조건부 표시 "다른파라미터=값" 또는 "다른파라미터!=값" (예: "source=파일"). null 이면 항상 표시
 */
public record NodeParam(
        String name,
        String label,
        String type,
        Object defaultValue,
        List<String> options,
        String showWhen
) {
    public NodeParam(String name, String label, String type, Object defaultValue, List<String> options) {
        this(name, label, type, defaultValue, options, null);
    }
}
