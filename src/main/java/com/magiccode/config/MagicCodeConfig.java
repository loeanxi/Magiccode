package com.magiccode.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MagicCodeConfig {
    
    private String provider = "anthropic";
    
    @JsonProperty("anthropic")
    private AnthropicConfig anthropic = new AnthropicConfig();
    
    @JsonProperty("openai")
    private OpenAIConfig openai = new OpenAIConfig();
    
    @JsonProperty("remote")
    private RemoteConfig remote = new RemoteConfig();
    
    @JsonProperty("session")
    private SessionConfig session = new SessionConfig();
    
    @JsonProperty("mcp")
    private McpConfig mcp = new McpConfig();
    
    public String getProvider() {
        return provider;
    }
    
    public void setProvider(String provider) {
        this.provider = provider;
    }
    
    public AnthropicConfig getAnthropic() {
        return anthropic;
    }
    
    public void setAnthropic(AnthropicConfig anthropic) {
        this.anthropic = anthropic;
    }
    
    public OpenAIConfig getOpenai() {
        return openai;
    }
    
    public void setOpenai(OpenAIConfig openai) {
        this.openai = openai;
    }
    
    public RemoteConfig getRemote() {
        return remote;
    }
    
    public void setRemote(RemoteConfig remote) {
        this.remote = remote;
    }
    
    public SessionConfig getSession() {
        return session;
    }
    
    public void setSession(SessionConfig session) {
        this.session = session;
    }
    
    public McpConfig getMcp() {
        return mcp;
    }
    
    public void setMcp(McpConfig mcp) {
        this.mcp = mcp;
    }
    
    // 嵌套配置类
    public static class AnthropicConfig {
        @JsonProperty("api-key")
        private String apiKey = "";
        private String model = "claude-3-5-sonnet-20241022";
        @JsonProperty("base-url")
        private String baseUrl = "https://api.anthropic.com";
        
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
    
    public static class OpenAIConfig {
        @JsonProperty("api-key")
        private String apiKey = "";
        private String model = "gpt-4o";
        @JsonProperty("base-url")
        private String baseUrl = "https://api.openai.com";
        
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
    
    public static class RemoteConfig {
        private boolean enabled = false;
        private int port = 18888;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }
    
    public static class SessionConfig {
        @JsonProperty("max-history")
        private int maxHistory = 100;
        @JsonProperty("context-window")
        private int contextWindow = 200000;
        
        public int getMaxHistory() { return maxHistory; }
        public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }
        public int getContextWindow() { return contextWindow; }
        public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }
    }
    
    public static class McpConfig {
        private java.util.List<String> servers = new java.util.ArrayList<>();
        
        public java.util.List<String> getServers() { return servers; }
        public void setServers(java.util.List<String> servers) { this.servers = servers; }
    }
}
