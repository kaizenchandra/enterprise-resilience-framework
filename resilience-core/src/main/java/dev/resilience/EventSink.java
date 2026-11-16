package dev.resilience;

@FunctionalInterface
public interface EventSink {
    EventSink NOOP = (p, e, v) -> {
    };

    static EventSink safe(EventSink sink) {
        return (p, e, v) -> {
            try {
                sink.accept(p, e, v);
            } catch (RuntimeException ignored) {
                /* Telemetry must not fail a call. */
            }
        };
    }

    void accept(String policy, String event, long value);
}
