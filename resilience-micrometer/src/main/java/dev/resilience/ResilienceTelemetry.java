package dev.resilience;

import io.micrometer.core.instrument.*;
import io.opentelemetry.api.trace.Span;

import java.util.concurrent.TimeUnit;

public final class ResilienceTelemetry implements EventSink {
    private final MeterRegistry meters;

    public ResilienceTelemetry(MeterRegistry meters, PolicyRegistry registry) {
        this.meters = meters;
        registry
                .snapshot()
                .forEach(
                        (name, initial) -> {
                            Gauge.builder(
                                            "resilience.circuit.failure.rate",
                                            registry,
                                            r -> {
                                                var s = r.get(name).circuit().snapshot();
                                                return s.calls() == 0 ? 0 : 100.0 * s.failures() / s.calls();
                                            })
                                    .tag("policy", name)
                                    .register(meters);
                            Gauge.builder(
                                            "resilience.circuit.slow.rate",
                                            registry,
                                            r -> {
                                                var s = r.get(name).circuit().snapshot();
                                                return s.calls() == 0 ? 0 : 100.0 * s.slow() / s.calls();
                                            })
                                    .tag("policy", name)
                                    .register(meters);
                            Gauge.builder(
                                            "resilience.circuit.state",
                                            registry,
                                            r -> r.get(name).circuit().snapshot().state().ordinal())
                                    .tag("policy", name)
                                    .register(meters);
                            Gauge.builder(
                                            "resilience.bulkhead.active", registry, r -> r.get(name).bulkhead().active())
                                    .tag("policy", name)
                                    .register(meters);
                            Gauge.builder(
                                            "resilience.bulkhead.available",
                                            registry,
                                            r -> r.get(name).bulkhead().available())
                                    .tag("policy", name)
                                    .register(meters);
                            Gauge.builder(
                                            "resilience.circuit.transitions",
                                            registry,
                                            r -> r.get(name).circuit().snapshot().transitions())
                                    .tag("policy", name)
                                    .register(meters);
                        });
    }

    public void accept(String policy, String event, long value) {
        if (event.equals("latency") || event.equals("retry_delay"))
            Timer.builder("resilience." + event)
                    .tag("policy", policy)
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(value, TimeUnit.NANOSECONDS);
        else meters.counter("resilience." + event, "policy", policy).increment(value);
        Span.current()
                .setAttribute("resilience.policy", policy)
                .setAttribute("resilience.event", event);
    }
}
