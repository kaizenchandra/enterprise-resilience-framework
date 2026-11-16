package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class ContextPropagationTest {
    @AfterEach
    void clear() {
        org.slf4j.MDC.clear();
    }

    @Test
    void snapshotRestoresMdcTraceAndDeadlineOnFailure() throws Exception {
        var span =
                io.opentelemetry.api.trace.Span.wrap(
                        io.opentelemetry.api.trace.SpanContext.create(
                                "01234567890123456789012345678901",
                                "0123456789012345",
                                io.opentelemetry.api.trace.TraceFlags.getSampled(),
                                io.opentelemetry.api.trace.TraceState.getDefault()));
        org.slf4j.MDC.put("request", "captured");
        var deadline = new Deadline(Duration.ofSeconds(1), Ticker.SYSTEM);
        TaskContext.Snapshot snapshot;
        try (var os = span.makeCurrent();
             var ds = ResilienceContext.withDeadline(deadline)) {
            snapshot = new ObservabilityTaskContext().capture();
        }
        org.slf4j.MDC.put("request", "worker");
        assertThrows(
                IllegalStateException.class,
                () -> {
                    try (var scope = snapshot.open()) {
                        assertEquals("captured", org.slf4j.MDC.get("request"));
                        assertEquals(
                                span.getSpanContext(), io.opentelemetry.api.trace.Span.current().getSpanContext());
                        assertSame(deadline, ResilienceContext.deadline());
                        throw new IllegalStateException();
                    }
                });
        assertEquals("worker", org.slf4j.MDC.get("request"));
        assertNull(ResilienceContext.deadline());
        assertFalse(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid());
    }

    @Test
    void coordinatorAndAttemptCarryContext() throws Exception {
        var registry = new PolicyRegistry(1, Ticker.SYSTEM);
        registry.update("p", PolicyConfig.defaults());
        org.slf4j.MDC.put("request", "async");
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
            assertEquals(
                    "async",
                    e.executeAsync("p", RetrySafety.UNSAFE, () -> org.slf4j.MDC.get("request"))
                            .get(1, TimeUnit.SECONDS));
            assertEquals(
                    "async",
                    e.executeStage(
                                    "p",
                                    RetrySafety.UNSAFE,
                                    () -> CompletableFuture.completedFuture(org.slf4j.MDC.get("request")))
                            .get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void filterRejectsMalformedAndExpiredAndClamps() throws Exception {
        var filter = new DeadlinePropagationFilter(Duration.ofMillis(100));
        for (String header : List.of("-1", "999999999999999999", "NaN", "1.5")) {
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            request.addHeader(DeadlinePropagationFilter.HEADER, header);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("Must reject"));
            assertEquals(400, response.getStatus());
        }
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader(DeadlinePropagationFilter.HEADER, "0");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("expired"));
        assertEquals(408, response.getStatus());
        request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader(DeadlinePropagationFilter.HEADER, "99999999");
        filter.doFilter(
                request,
                new org.springframework.mock.web.MockHttpServletResponse(),
                (req, res) -> assertTrue(ResilienceContext.deadline().remainingNanos() <= 100000000L));
        assertNull(ResilienceContext.deadline());
    }
}
