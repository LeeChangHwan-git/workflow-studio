package com.app.workflowstudio.node;

/**
 * 노드 아래쪽 소켓 — 부품 노드(sub-node)를 끼우는 자리 (n8n의 AI Agent 모델/메모리/도구 소켓과 같은 개념).
 *
 * @param id       소켓 id (연결의 toHandle 값)
 * @param label    캔버스에 표시할 이름
 * @param accepts  끼울 수 있는 부품 종류 — 부품 노드의 {@link NodeTypeInfo#provides()} 와 같아야 연결됨
 * @param required 실행에 꼭 필요한지 (비어 있으면 모듈이 오류 처리)
 * @param multiple 여러 개 끼울 수 있는지
 */
public record NodeSocket(String id, String label, String accepts, boolean required, boolean multiple) {}
