package com.app.workflowstudio.model;

import java.time.LocalDateTime;

public class RunLog {
    private String nodeId;
    private String nodeType;
    private String status; // running | success | error | skipped
    private String message;
    private String output;
    private String outputVar;
    private LocalDateTime timestamp;
    /** 분기 노드가 고른 출력 id 목록 (화면 표시용, 분기 없으면 null) */
    private java.util.List<String> route;

    public java.util.List<String> getRoute() { return route; }
    public void setRoute(java.util.List<String> route) { this.route = route; }

    public RunLog() {}

    public RunLog(String nodeId, String nodeType, String status, String message) {
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.status = status;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
    public String getOutputVar() { return outputVar; }
    public void setOutputVar(String outputVar) { this.outputVar = outputVar; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
