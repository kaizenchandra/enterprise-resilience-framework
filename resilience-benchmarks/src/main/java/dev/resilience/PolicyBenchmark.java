package dev.resilience;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class PolicyBenchmark {
    CircuitBreaker circuit;
    Bulkhead bulkhead;
    RetryPolicy retry;
    io.github.resilience4j.circuitbreaker.CircuitBreaker reference;

    @Setup
    public void setup() {
        circuit = new CircuitBreaker(CircuitConfig.defaults(), Ticker.SYSTEM);
        bulkhead = new Bulkhead(100, Duration.ZERO, false);
        retry = RetryPolicy.defaults();
        reference = io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("reference");
    }

    @Benchmark
    public Object circuitSuccess() {
        var t = circuit.acquire();
        t.complete(FailureClassifier.Outcome.SUCCESS);
        return t;
    }

    @Benchmark
    public boolean resilience4jSuccess() {
        boolean permit = reference.tryAcquirePermission();
        if (permit) reference.onSuccess(1, TimeUnit.NANOSECONDS);
        return permit;
    }

    @Benchmark
    public int bulkhead() throws Exception {
        try (var p = bulkhead.acquire(new Deadline(Duration.ofSeconds(1), Ticker.SYSTEM))) {
            return bulkhead.active();
        }
    }

    @Benchmark
    public long retryEvaluation() {
        return retry.delayNanos(2);
    }

    @Benchmark
    public long deadline() {
        return new Deadline(Duration.ofSeconds(1), Ticker.SYSTEM).remainingNanos();
    }
}
