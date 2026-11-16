package dev.resilience;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.*;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.time.Duration;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Header is a relative remaining duration; nanoTime never crosses a host boundary.
 */
public final class DeadlinePropagationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Resilience-Timeout-Millis";
    private static final TextMapGetter<HttpServletRequest> GETTER =
            new TextMapGetter<>() {
                public Iterable<String> keys(HttpServletRequest r) {
                    return java.util.Collections.list(r.getHeaderNames());
                }

                public String get(HttpServletRequest r, String k) {
                    return r.getHeader(k);
                }
            };
    private final Duration maximum;

    public DeadlinePropagationFilter(Duration maximum) {
        this.maximum = Checks.duration(maximum, false);
    }

    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = request.getHeader(HEADER);
        long millis = maximum.toMillis();
        if (value != null) {
            if (!value.matches("[0-9]{1,8}")) {
                response.sendError(400, "Invalid deadline budget");
                return;
            }
            long supplied = Long.parseLong(value);
            if (supplied == 0) {
                response.sendError(408, "Deadline exhausted");
                return;
            }
            millis = Math.min(millis, supplied);
        }
        var inherited = ResilienceContext.deadline();
        var deadline =
                inherited == null
                        ? new Deadline(Duration.ofMillis(millis), Ticker.SYSTEM)
                        : inherited.capped(Duration.ofMillis(millis));
        var trace = W3CTraceContextPropagator.getInstance().extract(Context.current(), request, GETTER);
        try (var scope = ResilienceContext.withDeadline(deadline);
             var os = trace.makeCurrent()) {
            chain.doFilter(request, response);
        }
    }
}
