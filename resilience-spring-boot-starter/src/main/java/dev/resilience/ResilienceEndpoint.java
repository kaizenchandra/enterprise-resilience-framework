package dev.resilience;

import java.util.*;

import org.springframework.boot.actuate.endpoint.annotation.*;

/**
 * Read-only. No remote administrative mutation surface.
 */
@Endpoint(id = "resilience")
public class ResilienceEndpoint {
    private final PolicyRegistry registry;

    public ResilienceEndpoint(PolicyRegistry registry) {
        this.registry = registry;
    }

    @ReadOperation
    public Map<String, Object> read() {
        Map<String, Object> states = new TreeMap<>();
        registry
                .snapshot()
                .forEach(
                        (n, p) ->
                                states.put(
                                        n,
                                        Map.of(
                                                "version",
                                                p.version(),
                                                "circuit",
                                                p.circuit().snapshot(),
                                                "active",
                                                p.bulkhead().active())));
        return states;
    }
}
