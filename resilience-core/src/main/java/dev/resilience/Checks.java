package dev.resilience;

import java.time.Duration;
import java.util.Objects;

public final class Checks {
    private Checks() {
    }

    public static Duration duration(Duration d, boolean zero) {
        Objects.requireNonNull(d);
        if (d.isNegative() || (!zero && d.isZero()) || d.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Duration must fit in one day");
        return d;
    }

    public static int range(int n, int max) {
        if (n < 1 || n > max) throw new IllegalArgumentException("Value outside 1.." + max);
        return n;
    }

    public static String name(String n) {
        if (n == null || !n.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}"))
            throw new IllegalArgumentException("Invalid policy name");
        return n;
    }
}
