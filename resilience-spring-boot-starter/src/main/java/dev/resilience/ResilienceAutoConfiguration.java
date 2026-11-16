package dev.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@AutoConfiguration
@EnableConfigurationProperties({ResilienceProperties.class, RetryBudgetProperties.class})
@Import(ResilienceWebConfiguration.class)
@EnableAspectJAutoProxy
public class ResilienceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public PolicyRegistry policyRegistry(ResilienceProperties p) {
        var registry = new PolicyRegistry(100, Ticker.SYSTEM);
        p.getInstances().forEach((n, c) -> registry.update(n, c.config()));
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public EventSink resilienceEvents(ObjectProvider<MeterRegistry> meters, PolicyRegistry registry) {
        var m = meters.getIfAvailable();
        return m == null ? EventSink.NOOP : new ResilienceTelemetry(m, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskContext resilienceTaskContext() {
        return new ObservabilityTaskContext();
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryBudget retryBudget(RetryBudgetProperties p, PolicyRegistry registry) {
        return switch (p.getMode()) {
            case "local" -> new LocalRetryBudget(
                    registry.snapshot().keySet(), p.getCapacity(), p.getRefillPerSecond(), Ticker.SYSTEM);
            case "redis" -> new RedisRetryBudget(
                    java.net.URI.create(p.getRedisUri()),
                    p.getNamespace(),
                    registry.snapshot().keySet(),
                    p.getCapacity(),
                    p.getRefillPerSecond());
            case "redis-sentinel" -> RedisRetryBudget.sentinel(
                    p.getMasterName(),
                    p.getSentinels().stream()
                            .map(redis.clients.jedis.HostAndPort::from)
                            .collect(java.util.stream.Collectors.toSet()),
                    p.getRedisPassword(),
                    p.getSentinelPassword(),
                    p.getNamespace(),
                    registry.snapshot().keySet(),
                    p.getCapacity(),
                    p.getRefillPerSecond());
            default -> throw new IllegalArgumentException(
                    "Retry budget mode must be local, redis or redis-sentinel");
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ResilienceExecutor resilienceExecutor(
            PolicyRegistry registry,
            ResilienceProperties p,
            EventSink events,
            RetryBudget budget,
            TaskContext context) {
        return new ResilienceExecutor(
                registry,
                p.getWorkers(),
                p.getAsyncCalls(),
                events,
                Ticker.SYSTEM,
                Sleeper.SYSTEM,
                FailureClassifier.DEFAULT,
                budget,
                context);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResilienceAspect resilienceAspect(ResilienceExecutor e) {
        return new ResilienceAspect(e);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResilienceEndpoint resilienceEndpoint(PolicyRegistry r) {
        return new ResilienceEndpoint(r);
    }
}
