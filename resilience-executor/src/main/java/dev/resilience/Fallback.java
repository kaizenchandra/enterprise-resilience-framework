package dev.resilience;

public interface Fallback<T> {
    boolean eligible(Exception failure);

    T apply(Exception failure) throws Exception;
}
