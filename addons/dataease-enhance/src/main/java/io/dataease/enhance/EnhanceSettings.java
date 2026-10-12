package io.dataease.enhance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All enhanced identity endpoints are disabled unless explicitly opted in. */
@ConfigurationProperties(prefix = "dataease.enhance")
public class EnhanceSettings {
    private boolean enabled = false;
    private String tokenSecret;
    private String bootstrapPassword;
    private int sessionMinutes = 60;
    private int maxLoginFailures = 5;
    private int lockMinutes = 15;
    private int nodeId = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String tokenSecret) { this.tokenSecret = tokenSecret; }
    public String getBootstrapPassword() { return bootstrapPassword; }
    public void setBootstrapPassword(String bootstrapPassword) { this.bootstrapPassword = bootstrapPassword; }
    public int getSessionMinutes() { return sessionMinutes; }
    public void setSessionMinutes(int sessionMinutes) { this.sessionMinutes = sessionMinutes; }
    public int getMaxLoginFailures() { return maxLoginFailures; }
    public void setMaxLoginFailures(int maxLoginFailures) { this.maxLoginFailures = maxLoginFailures; }
    public int getLockMinutes() { return lockMinutes; }
    public void setLockMinutes(int lockMinutes) { this.lockMinutes = lockMinutes; }
    public int getNodeId() { return nodeId; }
    public void setNodeId(int nodeId) { this.nodeId = nodeId; }
}