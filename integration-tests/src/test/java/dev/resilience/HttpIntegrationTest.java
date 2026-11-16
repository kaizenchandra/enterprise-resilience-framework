package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class HttpIntegrationTest {
    HttpServer server;
    ExecutorService httpWorkers;
    ResilienceExecutor executor;
    PolicyRegistry registry;
    HttpClient client;
    String base;
    AtomicInteger count = new AtomicInteger();
    AtomicInteger status = new AtomicInteger(200);
    AtomicLong delay = new AtomicLong();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpWorkers = Executors.newFixedThreadPool(4);
        server.setExecutor(httpWorkers);
        server.createContext(
                "/",
                exchange -> {
                    count.incrementAndGet();
                    try {
                        Thread.sleep(delay.get());
                        byte[] body = "response".getBytes();
                        exchange.sendResponseHeaders(status.get(), body.length);
                        exchange.getResponseBody().write(body);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
        registry = new PolicyRegistry(1, Ticker.SYSTEM);
        var c = PolicyConfig.defaults();
        registry.update(
                "http",
                new PolicyConfig(
                        new CircuitConfig(4, 4, 50, 100, Duration.ofSeconds(1), Duration.ofMillis(30), 2),
                        new RetryPolicy(3, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(2), false, true),
                        2,
                        Duration.ZERO,
                        Duration.ofMillis(500),
                        Duration.ofSeconds(2)));
        executor = new ResilienceExecutor(registry, 4, 4, EventSink.NOOP);
    }

    String call() throws Exception {
        return executor.execute(
                "http",
                RetrySafety.IDEMPOTENT,
                () -> {
                    var response =
                            client.send(
                                    HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofMillis(150)).build(),
                                    HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 429 || response.statusCode() == 503)
                        throw new RetryAfterException(response.statusCode(), Duration.ZERO);
                    if (response.statusCode() >= 500) throw new IOException("HTTP failure");
                    return response.body();
                });
    }

    @AfterEach
    void stop() {
        executor.close();
        server.stop(0);
        httpWorkers.shutdownNow();
        client.close();
    }

    @Test
    void successAndRecovery() throws Exception {
        assertEquals("response", call());
        status.set(500);
        assertThrows(Exception.class, this::call);
        assertEquals(CircuitBreaker.State.OPEN, registry.get("http").circuit().snapshot().state());
        int before = count.get();
        assertThrows(CircuitOpenException.class, this::call);
        assertEquals(before, count.get());
        status.set(200);
        Thread.sleep(40);
        assertEquals("response", call());
        assertEquals("response", call());
        assertEquals(CircuitBreaker.State.CLOSED, registry.get("http").circuit().snapshot().state());
    }

    @Test
    void retryHttp429() throws Exception {
        status.set(429);
        assertThrows(RetryAfterException.class, this::call);
        assertEquals(3, count.get());
    }

    @Test
    void retryHttp503() throws Exception {
        status.set(503);
        assertThrows(RetryAfterException.class, this::call);
        assertEquals(3, count.get());
    }

    @Test
    void socketTimeout() throws Exception {
        delay.set(500);
        assertThrows(Exception.class, this::call);
        assertTrue(count.get() <= 3);
    }

    @Test
    void connectionRefused() throws Exception {
        server.stop(0);
        assertThrows(IOException.class, this::call);
    }

    @Test
    void stageAdapter() throws Exception {
        assertEquals(
                "stage",
                executor
                        .executeStage(
                                "http", RetrySafety.UNSAFE, () -> CompletableFuture.completedFuture("stage"))
                        .get(1, TimeUnit.SECONDS));
    }
}
