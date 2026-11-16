package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

@Tag("external")
class RedisBudgetIntegrationTest {
    static java.net.URI uri;
    String namespace = "test-" + UUID.randomUUID();

    @BeforeAll
    static void config() {
        uri =
                java.net.URI.create(
                        java.util.Objects.requireNonNull(
                                System.getenv("REDIS_TEST_URI"), "Set REDIS_TEST_URI with -Pexternal"));
    }

    Deadline deadline() {
        return new Deadline(Duration.ofSeconds(2), Ticker.SYSTEM);
    }

    @Test
    void budgetIsSharedAcrossSeparateJvmProcesses() throws Exception {
        String java = System.getProperty("java.home") + "/bin/java";
        String cp =
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var first =
                new ProcessBuilder(java, "-cp", cp, "dev.resilience.RetryBudgetClientProcess", namespace)
                        .redirectErrorStream(true)
                        .start();
        var second =
                new ProcessBuilder(java, "-cp", cp, "dev.resilience.RetryBudgetClientProcess", namespace)
                        .redirectErrorStream(true)
                        .start();
        try {
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
            assertTrue(second.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, first.exitValue());
            assertEquals(0, second.exitValue());
            var a =
                    new String(first.getInputStream().readAllBytes())
                            .lines()
                            .filter(x -> x.startsWith("ALLOWED="))
                            .findFirst()
                            .orElseThrow();
            var b =
                    new String(second.getInputStream().readAllBytes())
                            .lines()
                            .filter(x -> x.startsWith("ALLOWED="))
                            .findFirst()
                            .orElseThrow();
            assertEquals(7, Integer.parseInt(a.substring(8)) + Integer.parseInt(b.substring(8)));
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
        }
    }

    @Test
    void twoIndependentClientsShareAtomicTokens() throws Exception {
        try (var a = new RedisRetryBudget(uri, namespace, Set.of("payment"), 12, .00014);
             var b = new RedisRetryBudget(uri, namespace, Set.of("payment"), 12, .00014);
             var threads = Executors.newFixedThreadPool(4)) {
            var allowed = new AtomicInteger();
            var unavailable = new AtomicInteger();
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 80; i++) {
                final int n = i;
                tasks.add(
                        threads.submit(
                                () -> {
                                    var decision = (n % 2 == 0 ? a : b).acquire("payment", deadline());
                                    if (decision == RetryBudget.Decision.ALLOWED) allowed.incrementAndGet();
                                    if (decision == RetryBudget.Decision.UNAVAILABLE) unavailable.incrementAndGet();
                                    return null;
                                }));
            }
            for (var f : tasks) f.get();
            assertEquals(12, allowed.get());
            assertEquals(0, unavailable.get());
        }
    }

    @Test
    void configMismatchFailsClosed() throws Exception {
        try (var a = new RedisRetryBudget(uri, namespace, Set.of("p"), 1, 1);
             var b = new RedisRetryBudget(uri, namespace, Set.of("p"), 2, 1)) {
            assertEquals(RetryBudget.Decision.ALLOWED, a.acquire("p", deadline()));
            assertEquals(RetryBudget.Decision.UNAVAILABLE, b.acquire("p", deadline()));
        }
    }

    @Test
    void refillAndUnknownIdentity() throws Exception {
        try (var a = new RedisRetryBudget(uri, namespace, Set.of("p"), 1, 20)) {
            assertEquals(RetryBudget.Decision.ALLOWED, a.acquire("p", deadline()));
            Thread.sleep(80);
            assertEquals(RetryBudget.Decision.ALLOWED, a.acquire("p", deadline()));
            assertEquals(RetryBudget.Decision.UNAVAILABLE, a.acquire("arbitrary", deadline()));
        }
    }

    @Test
    void unavailableStoreFailsClosed() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            int port = socket.getLocalPort();
            socket.close();
            try (var a =
                         new RedisRetryBudget(
                                 java.net.URI.create("redis://127.0.0.1:" + port), namespace, Set.of("p"), 1, 1)) {
                assertEquals(RetryBudget.Decision.UNAVAILABLE, a.acquire("p", deadline()));
            }
        }
    }

    @Test
    void expiredBudgetDoesNotContactStore() throws Exception {
        try (var a = new RedisRetryBudget(uri, namespace, Set.of("p"), 1, 1)) {
            assertEquals(
                    RetryBudget.Decision.UNAVAILABLE,
                    a.acquire("p", new Deadline(Duration.ZERO, Ticker.SYSTEM)));
        }
    }
}
