package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class HttpPropagationTest {
    @Test
    void inboundWorkerOutboundRoundTrip() throws Exception {
        var observed = new AtomicReference<String>();
        var remaining = new AtomicLong();
        var server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                x -> {
                    observed.set(x.getRequestHeaders().getFirst("traceparent"));
                    remaining.set(
                            Long.parseLong(x.getRequestHeaders().getFirst(DeadlinePropagationFilter.HEADER)));
                    x.sendResponseHeaders(200, 2);
                    x.getResponseBody().write("ok".getBytes());
                    x.close();
                });
        server.start();
        try (var http = new ResilienceHttpClient()) {
            var registry = new PolicyRegistry(1, Ticker.SYSTEM);
            registry.update("p", PolicyConfig.defaults());
            try (var e =
                         new ResilienceExecutor(
                                 registry,
                                 4,
                                 4,
                                 EventSink.NOOP,
                                 Ticker.SYSTEM,
                                 Sleeper.SYSTEM,
                                 FailureClassifier.DEFAULT,
                                 RetryBudget.UNLIMITED,
                                 new ObservabilityTaskContext())) {
                var request = new org.springframework.mock.web.MockHttpServletRequest();
                request.addHeader(DeadlinePropagationFilter.HEADER, "350");
                request.addHeader("traceparent", "00-01234567890123456789012345678901-0123456789012345-01");
                new DeadlinePropagationFilter(Duration.ofSeconds(2))
                        .doFilter(
                                request,
                                new org.springframework.mock.web.MockHttpServletResponse(),
                                (req, res) -> {
                                    try {
                                        assertEquals(
                                                "ok",
                                                e.executeAsync(
                                                                "p",
                                                                RetrySafety.UNSAFE,
                                                                () ->
                                                                        http.send(
                                                                                        java.net.http.HttpRequest.newBuilder(
                                                                                                        java.net.URI.create(
                                                                                                                "http://127.0.0.1:"
                                                                                                                        + server.getAddress().getPort()))
                                                                                                .header(DeadlinePropagationFilter.HEADER, "99999")
                                                                                                .GET()
                                                                                                .build(),
                                                                                        java.net.http.HttpResponse.BodyHandlers.ofString())
                                                                                .body())
                                                        .get(1, TimeUnit.SECONDS));
                                    } catch (Exception error) {
                                        throw new java.io.IOException(error);
                                    }
                                });
                assertEquals("00-01234567890123456789012345678901-0123456789012345-01", observed.get());
                assertTrue(remaining.get() > 0 && remaining.get() <= 350);
                assertNull(ResilienceContext.deadline());
            }
        } finally {
            server.stop(0);
        }
    }
}
