package dev.resilience;

import java.util.concurrent.Callable;

/**
 * Snapshot captured on submission; opened and closed on the executing thread.
 */
@FunctionalInterface
public interface TaskContext {
    TaskContext FRAMEWORK =
            () -> {
                var deadline = ResilienceContext.deadline();
                return () -> ResilienceContext.withDeadline(deadline);
            };

    Snapshot capture();

    interface Snapshot {
        ResilienceContext.Scope open();

        default <T> Callable<T> wrap(Callable<T> task) {
            return () -> {
                try (var scope = open()) {
                    return task.call();
                }
            };
        }
    }
}
