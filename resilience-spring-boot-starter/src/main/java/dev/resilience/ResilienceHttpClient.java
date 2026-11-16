package dev.resilience;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;

import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * JDK HTTP adapter: strips caller-supplied propagation headers and injects trusted current context.
 */
public final class ResilienceHttpClient implements AutoCloseable {
    private final HttpClient client;

    public ResilienceHttpClient() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws java.io.IOException, InterruptedException, TimeoutException {
        var builder =
                HttpRequest.newBuilder(
                        request,
                        (name, value) ->
                                !name.equalsIgnoreCase(DeadlinePropagationFilter.HEADER)
                                        && !name.equalsIgnoreCase("traceparent")
                                        && !name.equalsIgnoreCase("tracestate"));
        var deadline = ResilienceContext.deadline();
        if (deadline != null) {
            deadline.check();
            long remaining = deadline.remainingNanos();
            long millis = remaining / 1000000 - 2;
            if (millis <= 0) throw new TimeoutException("Insufficient HTTP deadline reserve");
            builder.header(DeadlinePropagationFilter.HEADER, Long.toString(millis));
            Duration timeout = Duration.ofNanos(remaining);
            if (request.timeout().isPresent() && request.timeout().get().compareTo(timeout) < 0)
                timeout = request.timeout().get();
            builder.timeout(timeout);
        }
        W3CTraceContextPropagator.getInstance()
                .inject(Context.current(), builder, (carrier, key, value) -> carrier.header(key, value));
        return client.send(builder.build(), handler);
    }

    public void close() {
        client.close();
    }
}
