package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class TransportChaosTest {
    PolicyRegistry registry;
    ResilienceExecutor executor;

    @BeforeEach
    void setup() {
        registry = new PolicyRegistry(1, Ticker.SYSTEM);
        var c = PolicyConfig.defaults();
        registry.update(
                "fault",
                new PolicyConfig(
                        c.circuit(),
                        new RetryPolicy(2, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(2), false, true),
                        2,
                        Duration.ZERO,
                        Duration.ofMillis(600),
                        Duration.ofSeconds(2)));
        executor = new ResilienceExecutor(registry, 4, 4, EventSink.NOOP);
    }

    @AfterEach
    void close() {
        executor.close();
    }

    @Test
    void actualTcpResetHasBoundedLogicalAttempts() throws Exception {
        var accepted = new AtomicInteger();
        try (var listener = new java.net.ServerSocket(0);
             var acceptor = Executors.newSingleThreadExecutor();
             var client =
                     java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build()) {
            var server =
                    acceptor.submit(
                            () -> {
                                while (!Thread.currentThread().isInterrupted()) {
                                    try (var socket = listener.accept()) {
                                        accepted.incrementAndGet();
                                        socket.setSoLinger(true, 0);
                                    } catch (java.io.IOException e) {
                                        break;
                                    }
                                }
                            });
            var attempts = new AtomicInteger();
            assertThrows(
                    java.io.IOException.class,
                    () ->
                            executor.execute(
                                    "fault",
                                    RetrySafety.IDEMPOTENT,
                                    () -> {
                                        attempts.incrementAndGet();
                                        return client.send(
                                                java.net.http.HttpRequest.newBuilder(
                                                                java.net.URI.create("http://127.0.0.1:" + listener.getLocalPort()))
                                                        .timeout(Duration.ofMillis(300))
                                                        .build(),
                                                java.net.http.HttpResponse.BodyHandlers.ofString());
                                    }));
            assertEquals(2, attempts.get());
            assertTrue(accepted.get() >= 2);
            listener.close();
            server.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void partialResponseIsFailure() throws Exception {
        var server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                x -> {
                    x.sendResponseHeaders(200, 1024);
                    try {
                        x.getResponseBody().write(new byte[]{1});
                    } finally {
                        x.close();
                    }
                });
        server.start();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            assertThrows(
                    java.io.IOException.class,
                    () ->
                            executor.execute(
                                    "fault",
                                    RetrySafety.UNSAFE,
                                    () ->
                                            client.send(
                                                    java.net.http.HttpRequest.newBuilder(
                                                                    java.net.URI.create(
                                                                            "http://127.0.0.1:" + server.getAddress().getPort()))
                                                            .timeout(Duration.ofMillis(300))
                                                            .build(),
                                                    java.net.http.HttpResponse.BodyHandlers.ofString())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dnsFailureIsBounded() throws Exception {
        var attempts = new AtomicInteger();
        assertThrows(
                java.net.UnknownHostException.class,
                () ->
                        executor.execute(
                                "fault",
                                RetrySafety.IDEMPOTENT,
                                () -> {
                                    attempts.incrementAndGet();
                                    return java.net.InetAddress.getAllByName("resilience-chaos.invalid");
                                }));
        assertEquals(2, attempts.get());
    }

    @Test
    void boundedWorkersRejectAndShutdownInterruptsActiveWork() throws Exception {
        var entered = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try (var limited = new ResilienceExecutor(registry, 1, 2, EventSink.NOOP)) {
            var f =
                    limited.executeAsync(
                            "fault",
                            RetrySafety.UNSAFE,
                            () -> {
                                entered.countDown();
                                try {
                                    new CountDownLatch(1).await();
                                    return "bad";
                                } finally {
                                    exited.countDown();
                                }
                            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(
                    RejectedExecutionException.class,
                    () -> limited.execute("fault", RetrySafety.UNSAFE, () -> "bad"));
            limited.close();
            assertTrue(exited.await(1, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> f.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelledStageReceivesCancellation() throws Exception {
        var started = new CountDownLatch(1);
        var stage = new CompletableFuture<String>();
        var result =
                executor.executeStage(
                        "fault",
                        RetrySafety.UNSAFE,
                        () -> {
                            started.countDown();
                            return stage;
                        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        result.cancel(true);
        for (int i = 0; i < 100 && !stage.isCancelled(); i++) Thread.sleep(2);
        assertTrue(stage.isCancelled());
    }

    @Test
    void refreshWhileOldPhysicalWorkRuns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var old = registry.get("fault");
        var f =
                executor.executeAsync(
                        "fault",
                        RetrySafety.UNSAFE,
                        () -> {
                            entered.countDown();
                            release.await();
                            return "old";
                        });
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            old.circuit().forceOpen();
            registry.update("fault", PolicyConfig.defaults());
            assertEquals("new", executor.execute("fault", RetrySafety.UNSAFE, () -> "new"));
        } finally {
            release.countDown();
        }
        assertEquals("old", f.get(1, TimeUnit.SECONDS));
        assertEquals(0, old.bulkhead().active());
        assertEquals(CircuitBreaker.State.CLOSED, registry.get("fault").circuit().snapshot().state());
    }
}
