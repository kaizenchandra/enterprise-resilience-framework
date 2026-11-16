package dev.resilience;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * All state and ring mutations are linearized on this object's monitor. No user callbacks run under
 * it.
 */
public final class CircuitBreaker {
    private final CircuitConfig config;
    private final Ticker ticker;
    private final byte[] ring;
    private State state = State.CLOSED;
    private long generation, opened, ignored, rejected, transitions;
    private int count, failures, slow, cursor, allocated, completed;
    public CircuitBreaker(CircuitConfig config, Ticker ticker) {
        this.config = config;
        this.ticker = ticker;
        ring = new byte[config.windowSize()];
    }

    public synchronized Ticket acquire() {
        if (state == State.OPEN && ticker.read() - opened >= config.openDuration().toNanos())
            transition(State.HALF_OPEN);
        if (state == State.OPEN
                || state == State.FORCED_OPEN
                || (state == State.HALF_OPEN && allocated >= config.probes())) {
            rejected++;
            throw new CircuitOpenException();
        }
        if (state == State.HALF_OPEN) allocated++;
        return new Ticket(generation, state, ticker.read());
    }

    private synchronized void record(Ticket t, FailureClassifier.Outcome result) {
        if (t.version != generation || t.admitted == State.DISABLED) return;
        if (result == FailureClassifier.Outcome.IGNORE) {
            ignored++;
            if (state == State.HALF_OPEN) allocated--;
            return;
        }
        boolean failed = result == FailureClassifier.Outcome.FAILURE,
                isSlow = ticker.read() - t.start >= config.slowDuration().toNanos();
        if (state == State.HALF_OPEN) {
            completed++;
            if (failed || isSlow) {
                transition(State.OPEN);
            } else if (completed == config.probes()) transition(State.CLOSED);
            return;
        }
        if (state != State.CLOSED) return;
        if (count == ring.length) {
            byte old = ring[cursor];
            failures -= old & 1;
            slow -= (old >> 1) & 1;
        } else count++;
        ring[cursor] = (byte) ((failed ? 1 : 0) | (isSlow ? 2 : 0));
        cursor = (cursor + 1) % ring.length;
        failures += failed ? 1 : 0;
        slow += isSlow ? 1 : 0;
        if (count >= config.minimumCalls()
                && (100.0 * failures / count >= config.failureRate()
                || 100.0 * slow / count >= config.slowRate())) transition(State.OPEN);
    }

    private void transition(State next) {
        state = next;
        generation++;
        transitions++;
        allocated = completed = 0;
        count = failures = slow = cursor = 0;
        java.util.Arrays.fill(ring, (byte) 0);
        if (next == State.OPEN) opened = ticker.read();
    }

    public synchronized void reset() {
        transition(State.CLOSED);
    }

    public synchronized void forceOpen() {
        transition(State.FORCED_OPEN);
    }

    public synchronized void disable() {
        transition(State.DISABLED);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, count, failures, slow, ignored, rejected, transitions);
    }

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN,
        FORCED_OPEN,
        DISABLED
    }

    public record Snapshot(
            State state,
            int calls,
            int failures,
            int slow,
            long ignored,
            long rejected,
            long transitions) {
    }

    public final class Ticket {
        private final long version, start;
        private final State admitted;
        private final AtomicBoolean done = new AtomicBoolean();

        private Ticket(long version, State admitted, long start) {
            this.version = version;
            this.admitted = admitted;
            this.start = start;
        }

        public void complete(FailureClassifier.Outcome outcome) {
            java.util.Objects.requireNonNull(outcome);
            if (done.compareAndSet(false, true)) record(this, outcome);
        }
    }
}
