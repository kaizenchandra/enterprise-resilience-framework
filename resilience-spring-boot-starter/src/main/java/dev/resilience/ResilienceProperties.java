package dev.resilience;

import java.time.Duration;
import java.util.*;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("resilience")
public class ResilienceProperties {
    private Map<String, Instance> instances = new LinkedHashMap<>();
    private int workers = 100, asyncCalls = 100;

    public Map<String, Instance> getInstances() {
        return instances;
    }

    public void setInstances(Map<String, Instance> v) {
        instances = v;
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int v) {
        workers = v;
    }

    public int getAsyncCalls() {
        return asyncCalls;
    }

    public void setAsyncCalls(int v) {
        asyncCalls = v;
    }

    public static class Instance {
        private int concurrency = 50, maxAttempts = 3, windowSize = 100, minimumCalls = 20, probes = 5;
        private Duration overall = Duration.ofSeconds(2),
                perAttempt = Duration.ofMillis(500),
                bulkheadWait = Duration.ZERO,
                openDuration = Duration.ofSeconds(30),
                slowDuration = Duration.ofMillis(400),
                initialDelay = Duration.ofMillis(50),
                maxDelay = Duration.ofSeconds(1);
        private double failureRate = 50, slowRate = 100;

        public PolicyConfig config() {
            return new PolicyConfig(
                    new CircuitConfig(
                            windowSize, minimumCalls, failureRate, slowRate, slowDuration, openDuration, probes),
                    new RetryPolicy(maxAttempts, initialDelay, maxDelay, overall, true, true),
                    concurrency,
                    bulkheadWait,
                    perAttempt,
                    overall);
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int v) {
            concurrency = v;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int v) {
            maxAttempts = v;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int v) {
            windowSize = v;
        }

        public int getMinimumCalls() {
            return minimumCalls;
        }

        public void setMinimumCalls(int v) {
            minimumCalls = v;
        }

        public int getProbes() {
            return probes;
        }

        public void setProbes(int v) {
            probes = v;
        }

        public Duration getOverall() {
            return overall;
        }

        public void setOverall(Duration v) {
            overall = v;
        }

        public Duration getPerAttempt() {
            return perAttempt;
        }

        public void setPerAttempt(Duration v) {
            perAttempt = v;
        }

        public Duration getBulkheadWait() {
            return bulkheadWait;
        }

        public void setBulkheadWait(Duration v) {
            bulkheadWait = v;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration v) {
            openDuration = v;
        }

        public Duration getSlowDuration() {
            return slowDuration;
        }

        public void setSlowDuration(Duration v) {
            slowDuration = v;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration v) {
            initialDelay = v;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration v) {
            maxDelay = v;
        }

        public double getFailureRate() {
            return failureRate;
        }

        public void setFailureRate(double v) {
            failureRate = v;
        }

        public double getSlowRate() {
            return slowRate;
        }

        public void setSlowRate(double v) {
            slowRate = v;
        }
    }
}
