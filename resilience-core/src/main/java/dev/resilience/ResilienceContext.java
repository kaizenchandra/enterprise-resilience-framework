package dev.resilience;

/**
 * Only framework deadline state is implicit; arbitrary ThreadLocals are never copied.
 */
public final class ResilienceContext {
    private static final ThreadLocal<Deadline> CURRENT = new ThreadLocal<>();

    private ResilienceContext() {
    }

    public static Deadline deadline() {
        return CURRENT.get();
    }

    public static Scope withDeadline(Deadline deadline) {
        var previous = CURRENT.get();
        if (deadline == null) CURRENT.remove();
        else CURRENT.set(deadline);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        void close();
    }
}
