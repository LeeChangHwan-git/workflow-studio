package com.app.workflowstudio.node;

/**
 * 노드의 출력 연결점(오른쪽) 하나 — 여러 개면 분기 (예: 승인 / 다시 / 거절, 규칙 1 / 규칙 2 / 그 외).
 * 모듈은 실행 중 {@link NodeContext#route(java.util.Collection)} 로 이번에 흘려보낼 출력을 고른다.
 *
 * @param id    연결에 저장되는 값 ({@code PipelineConnection.fromHandle})
 * @param label 카드에 표시할 이름
 */
public record NodeOutput(String id, String label) {}
