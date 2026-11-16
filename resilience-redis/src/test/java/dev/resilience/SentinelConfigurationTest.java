package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.HostAndPort;

class SentinelConfigurationTest {
    @Test
    void invalidTopologyRejectedBeforeNetworkDiscovery() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RedisRetryBudget.sentinel("m", Set.of(), null, null, "budget", Set.of("p"), 10, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RedisRetryBudget.sentinel(
                                "bad/name",
                                Set.of(new HostAndPort("localhost", 26379)),
                                null,
                                null,
                                "budget",
                                Set.of("p"),
                                10,
                                1));
    }

    @Test
    void invalidCapacityRejectedBeforeNetworkDiscovery() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RedisRetryBudget.sentinel(
                                "m",
                                Set.of(new HostAndPort("localhost", 26379)),
                                null,
                                null,
                                "budget",
                                Set.of("p"),
                                0,
                                1));
    }
}
