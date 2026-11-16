package dev.resilience;

@FunctionalInterface
public interface Sleeper {
    Sleeper SYSTEM = n -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(n);

    void sleep(long nanos) throws InterruptedException;
}
