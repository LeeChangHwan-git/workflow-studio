package com.app.memobookmark.model;

public class AppSettings {
    private String claudeApiKey = "";
    private String claudeModel = "claude-sonnet-4-6";
    private String geminiApiKey = "";
    private double imageMatchThreshold = 0.90;
    private int imageMatchRetryMs = 500;
    private String outlineUrl = "";
    private String outlineToken = "";

    public AppSettings() {}

    public String getClaudeApiKey() { return claudeApiKey; }
    public void setClaudeApiKey(String claudeApiKey) { this.claudeApiKey = claudeApiKey; }
    public String getClaudeModel() { return claudeModel; }
    public void setClaudeModel(String claudeModel) { this.claudeModel = claudeModel; }
    public String getGeminiApiKey() { return geminiApiKey; }
    public void setGeminiApiKey(String geminiApiKey) { this.geminiApiKey = geminiApiKey; }
    public double getImageMatchThreshold() { return imageMatchThreshold; }
    public void setImageMatchThreshold(double imageMatchThreshold) { this.imageMatchThreshold = imageMatchThreshold; }
    public int getImageMatchRetryMs() { return imageMatchRetryMs; }
    public void setImageMatchRetryMs(int imageMatchRetryMs) { this.imageMatchRetryMs = imageMatchRetryMs; }
    public String getOutlineUrl() { return outlineUrl; }
    public void setOutlineUrl(String outlineUrl) { this.outlineUrl = outlineUrl; }
    public String getOutlineToken() { return outlineToken; }
    public void setOutlineToken(String outlineToken) { this.outlineToken = outlineToken; }
}
