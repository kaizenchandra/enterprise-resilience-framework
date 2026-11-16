package dev.resilience.paymentservicesimulator;

import dev.resilience.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class PaymentServiceSimulatorApplication {
    private final java.util.concurrent.atomic.AtomicReference<Fault> fault =
            new java.util.concurrent.atomic.AtomicReference<>(new Fault(0, 0, 503, false));
    private final java.util.concurrent.atomic.LongAdder calls =
            new java.util.concurrent.atomic.LongAdder();
    @org.springframework.beans.factory.annotation.Value("${demo.admin-token}")
    String token;

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceSimulatorApplication.class, args);
    }

    @PostMapping("/fault")
    public void fault(@RequestHeader("X-Admin-Token") String supplied, @RequestBody Fault value) {
        if (!java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN);
        fault.set(value);
    }

    @GetMapping("/context")
    public java.util.Map<String, Object> context() {
        var d = ResilienceContext.deadline();
        return java.util.Map.of(
                "remainingMillis",
                d == null ? -1 : d.remainingNanos() / 1000000,
                "traceId",
                io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId());
    }

    @GetMapping("/calls")
    public long calls() {
        return calls.sum();
    }

    @GetMapping("/quote")
    public org.springframework.http.ResponseEntity<String> quote() throws InterruptedException {
        calls.increment();
        var f = fault.get();
        Thread.sleep(f.delayMillis());
        if (f.partial())
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body("{\"partial\":");
        if (java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < f.failurePercent())
            return org.springframework.http.ResponseEntity.status(f.status())
                    .header("Retry-After", "1")
                    .body("unavailable");
        return org.springframework.http.ResponseEntity.ok("quote-available");
    }

    private record Fault(int failurePercent, long delayMillis, int status, boolean partial) {
        Fault {
            if (failurePercent < 0
                    || failurePercent > 100
                    || delayMillis < 0
                    || delayMillis > 10000
                    || status < 400
                    || status > 599) throw new IllegalArgumentException("Invalid fault settings");
        }
    }
}
