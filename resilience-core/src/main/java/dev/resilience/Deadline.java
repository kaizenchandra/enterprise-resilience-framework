package dev.resilience;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

public final class Deadline {
    private final Ticker ticker;
    private final Deadline parent;
    private final long start, budget;

    public Deadline(Duration duration, Ticker ticker) {
        this(duration, ticker, null);
    }

    private Deadline(Duration duration, Ticker ticker, Deadline parent) {
        this.parent = parent;
        this.ticker = ticker;
        this.budget = Checks.duration(duration, true).toNanos();
        start = ticker.read();
    }

    public long remainingNanos() {
        long local = Math.max(0, budget - (ticker.read() - start));
        return parent == null ? local : Math.min(local, parent.remainingNanos());
    }

    /**
     * A child can never extend its parent, including time spent constructing/submitting it.
     */
    public Deadline capped(Duration duration) {
        return new Deadline(duration, ticker, this);
    }

    public void check() throws TimeoutException {
        if (remainingNanos() == 0) throw new TimeoutException("Overall deadline exhausted");
    }
}
