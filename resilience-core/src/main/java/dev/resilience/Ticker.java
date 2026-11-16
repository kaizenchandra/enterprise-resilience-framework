package dev.resilience;

@FunctionalInterface
public interface Ticker {
    Ticker SYSTEM = System::nanoTime;

    long read();
}
