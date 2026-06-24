package com.app.memobookmark.model;

import java.time.LocalDateTime;

public class RunLog {
    private String nodeId;
    private String nodeType;
    private String status; // running | success | error | skipped
    private String message;
    private String output;
    private LocalDateTime timestamp;

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
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
