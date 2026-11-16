package dev.resilience;

public final class CircuitOpenException extends RuntimeException {
    public CircuitOpenException() {
        super("Circuit admission rejected");
    }
}
