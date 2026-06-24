package com.app.memobookmark.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Pipeline {
    private String id;
    private String name;
    private String description;
    private List<PipelineNode> nodes = new ArrayList<>();
    private List<PipelineConnection> connections = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Pipeline() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<PipelineNode> getNodes() { return nodes; }
    public void setNodes(List<PipelineNode> nodes) { this.nodes = nodes; }
    public List<PipelineConnection> getConnections() { return connections; }
    public void setConnections(List<PipelineConnection> connections) { this.connections = connections; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
