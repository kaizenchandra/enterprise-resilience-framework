package dev.resilience;

/**
 * Independent process used by the fleet-budget test; emits only counts, never Redis credentials.
 */
public final class RetryBudgetClientProcess {
    public static void main(String[] args) throws Exception {
        int allowed = 0;
        try (var budget =
                     new RedisRetryBudget(
                             java.net.URI.create(System.getenv("REDIS_TEST_URI")),
                             args[0],
                             java.util.Set.of("p"),
                             7,
                             .0001)) {
            for (int i = 0; i < 30; i++)
                if (budget.acquire("p", new Deadline(java.time.Duration.ofSeconds(2), Ticker.SYSTEM))
                        == RetryBudget.Decision.ALLOWED) allowed++;
        }
        System.out.println("ALLOWED=" + allowed);
    }
}
