package com.app.workflowstudio.model;

/**
 * 노드 연결.
 * <ul>
 *   <li>{@code toHandle == null} : 일반 실행 흐름 (화살표) — 앞 노드 출력이 다음 노드로 전달</li>
 *   <li>{@code toHandle != null} : 부품 연결 — 부품 노드(모델·메모리·지시문 등)를 대상 노드의 소켓(toHandle)에 끼움.
 *       실행 순서·${input}과 무관</li>
 * </ul>
 */
public class PipelineConnection {
    private String fromNodeId;
    private String toNodeId;
    private String toHandle;

    public PipelineConnection() {}

    public PipelineConnection(String fromNodeId, String toNodeId) {
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
    }

    public PipelineConnection(String fromNodeId, String toNodeId, String toHandle) {
        this(fromNodeId, toNodeId);
        this.toHandle = toHandle;
    }

    public String getFromNodeId() { return fromNodeId; }
    public void setFromNodeId(String fromNodeId) { this.fromNodeId = fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public void setToNodeId(String toNodeId) { this.toNodeId = toNodeId; }
    public String getToHandle() { return toHandle; }
    public void setToHandle(String toHandle) { this.toHandle = toHandle; }

    /** 소켓(부품) 연결인지 */
    public boolean socket() { return toHandle != null && !toHandle.isBlank(); }
}
