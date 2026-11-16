package dev.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("resilience.retry-budget")
public class RetryBudgetProperties {
    private String mode = "local", redisUri = "redis://localhost:6379", namespace = "resilience";
    private String masterName = "resilience", redisPassword, sentinelPassword;
    private java.util.Set<String> sentinels = java.util.Set.of();
    private int capacity = 100;
    private double refillPerSecond = 10;

    public String getMasterName() {
        return masterName;
    }

    public void setMasterName(String value) {
        masterName = value;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public void setRedisPassword(String value) {
        redisPassword = value;
    }

    public String getSentinelPassword() {
        return sentinelPassword;
    }

    public void setSentinelPassword(String value) {
        sentinelPassword = value;
    }

    public java.util.Set<String> getSentinels() {
        return sentinels;
    }

    public void setSentinels(java.util.Set<String> value) {
        sentinels = value;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String v) {
        mode = v;
    }

    public String getRedisUri() {
        return redisUri;
    }

    public void setRedisUri(String v) {
        redisUri = v;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String v) {
        namespace = v;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int v) {
        capacity = v;
    }

    public double getRefillPerSecond() {
        return refillPerSecond;
    }

    public void setRefillPerSecond(double v) {
        refillPerSecond = v;
    }
}
