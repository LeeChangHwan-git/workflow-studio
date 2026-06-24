package com.app.memobookmark.model;

import java.util.HashMap;
import java.util.Map;

public class PipelineNode {
    private String id;
    private String type;
    private double posX;
    private double posY;
    private Map<String, Object> config = new HashMap<>();

    public PipelineNode() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public double getPosX() { return posX; }
    public void setPosX(double posX) { this.posX = posX; }
    public double getPosY() { return posY; }
    public void setPosY(double posY) { this.posY = posY; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
}
