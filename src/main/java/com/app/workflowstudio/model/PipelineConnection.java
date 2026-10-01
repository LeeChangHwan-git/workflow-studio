package com.app.workflowstudio.model;

public class PipelineConnection {
    private String fromNodeId;
    private String toNodeId;

    public PipelineConnection() {}

    public PipelineConnection(String fromNodeId, String toNodeId) {
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
    }

    public String getFromNodeId() { return fromNodeId; }
    public void setFromNodeId(String fromNodeId) { this.fromNodeId = fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public void setToNodeId(String toNodeId) { this.toNodeId = toNodeId; }
}
