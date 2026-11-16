package dev.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import redis.clients.jedis.*;

/**
 * Destructive HA probe, run only by scripts/validate-redis-ha.py in its owned Docker network.
 */
public final class HaProbe {
    private static final Path CONTROL = Path.of("/control");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static final String PASSWORD = System.getenv("REDIS_PASSWORD");
    private static final String SENTINEL_PASSWORD = System.getenv("SENTINEL_PASSWORD");
    private static final Set<HostAndPort> SENTINELS =
            Set.of(
                    new HostAndPort("s1", 26379), new HostAndPort("s2", 26379), new HostAndPort("s3", 26379));

    static RedisRetryBudget budget(String namespace) {
        return RedisRetryBudget.sentinel(
                "resilience", SENTINELS, PASSWORD, SENTINEL_PASSWORD, namespace, Set.of("p"), 10, .0002);
    }

    static Deadline deadline() {
        return new Deadline(Duration.ofSeconds(2), Ticker.SYSTEM);
    }

    static void signal(String name) throws Exception {
        Files.writeString(CONTROL.resolve(name), "ready");
        System.out.println("PHASE " + name);
    }

    static void await(String name) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!Files.exists(CONTROL.resolve(name))) {
            if (System.nanoTime() > end) throw new AssertionError("Controller timed out: " + name);
            Thread.sleep(50);
        }
    }

    static void reserve(RedisRetryBudget budget, int n) throws Exception {
        int granted = 0;
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (granted < n && System.nanoTime() < end) {
            var d = budget.acquire("p", deadline());
            if (d == RetryBudget.Decision.ALLOWED) granted++;
            else if (d == RetryBudget.Decision.EXHAUSTED)
                throw new AssertionError("Unexpected token exhaustion");
            else Thread.sleep(50);
        }
        if (granted != n) throw new AssertionError("Store unavailable during setup");
    }

    static void barrier(int replicas) throws Exception {
        try (var sentinel = new Jedis("s1", 26379)) {
            sentinel.auth(SENTINEL_PASSWORD);
            var address = sentinel.sentinelGetMasterAddrByName("resilience");
            try (var primary = new Jedis(address.get(0), Integer.parseInt(address.get(1)))) {
                primary.auth(PASSWORD);
                primary.set("ha-barrier", UUID.randomUUID().toString());
                if (primary.waitReplicas(replicas, 3000) < replicas)
                    throw new AssertionError("Replication barrier failed");
            }
        }
    }

    static Map<String, Object> recover(RedisRetryBudget budget, int expected) throws Exception {
        long start = System.nanoTime(), first = -1;
        int granted = 0, unavailable = 0;
        long end = start + Duration.ofSeconds(75).toNanos();
        while (System.nanoTime() < end) {
            var d = budget.acquire("p", deadline());
            if (d == RetryBudget.Decision.UNAVAILABLE) unavailable++;
            else {
                if (first < 0) first = System.nanoTime();
                if (d == RetryBudget.Decision.ALLOWED) granted++;
                else if (granted == expected) break;
                else throw new AssertionError("Unexpected remaining token count: " + granted);
            }
            Thread.sleep(50);
        }
        if (granted != expected)
            throw new AssertionError("Failover grant mismatch: " + granted + " expected " + expected);
        return Map.of(
                "postFailoverGrants",
                granted,
                "unavailableDecisions",
                unavailable,
                "firstRecoveredMillis",
                (first - start) / 1000000);
    }

    public static void main(String[] args) throws Exception {
        try (var preserved = budget("ha-preserved")) {
            reserve(preserved, 7);
            barrier(2);
            signal("replicated-ready");
            await("primary-killed");
            REPORT.put("replicatedFailover", recover(preserved, 3));
            signal("preserved-done");
            await("replica-paused");
            try (var lost = budget("ha-unreplicated")) {
                reserve(lost, 10);
                signal("unreplicated-written");
                await("second-primary-killed");
                REPORT.put("unreplicatedFailover", recover(lost, 10));
                REPORT.put(
                        "strictDurability",
                        "FAIL: ten acknowledged unreplicated reservations were restored after promotion");
                signal("loss-observed");
            }
            await("replica-rejoined");
            try (var quorum = budget("ha-quorum")) {
                reserve(quorum, 3);
                barrier(1);
                signal("quorum-ready");
                await("quorum-removed");
                int unavailable = 0, allowed = 0;
                long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (System.nanoTime() < end) {
                    var d = quorum.acquire("p", deadline());
                    if (d == RetryBudget.Decision.UNAVAILABLE) unavailable++;
                    if (d == RetryBudget.Decision.ALLOWED) allowed++;
                    Thread.sleep(50);
                }
                if (allowed != 0 || unavailable == 0)
                    throw new AssertionError("Quorum loss did not fail closed");
                REPORT.put("quorumLoss", Map.of("grants", allowed, "unavailableDecisions", unavailable));
                signal("quorum-loss-observed");
                await("quorum-restored");
                REPORT.put("quorumRecovery", recover(quorum, 7));
            }
            REPORT.put("functionalResult", "PASS");
        } catch (Throwable failure) {
            REPORT.put("functionalResult", "FAIL");
            REPORT.put("errorType", failure.getClass().getSimpleName());
            REPORT.put(
                    "error",
                    failure instanceof AssertionError
                            ? failure.getMessage()
                            : failure.getClass().getSimpleName());
            throw failure;
        } finally {
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(CONTROL.resolve("result.json").toFile(), REPORT);
            signal("done");
        }
    }
}
