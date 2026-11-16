package dev.resilience;

import java.io.IOException;
import java.time.Duration;

public final class RetryAfterException extends IOException {
    private final Duration delay;

    public RetryAfterException(int status, Duration delay) {
        super("Transient HTTP status " + status);
        this.delay = Checks.duration(delay, true);
    }

    public Duration delay() {
        return delay;
    }
}
