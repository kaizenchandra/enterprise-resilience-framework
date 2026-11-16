package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;

class ConnectionPoolChaosTest {
    @Test
    void connectionPoolSaturationOnVirtualWorkerCancelsAndRecovers() throws Exception {
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:resilience;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(250);
        try (var pool = new com.zaxxer.hikari.HikariDataSource(config)) {
            var registry = new PolicyRegistry(1, Ticker.SYSTEM);
            var c = PolicyConfig.defaults();
            registry.update(
                    "db",
                    new PolicyConfig(
                            c.circuit(),
                            c.retry(),
                            1,
                            Duration.ZERO,
                            Duration.ofMillis(100),
                            Duration.ofSeconds(1)));
            try (var executor = new ResilienceExecutor(registry, 4, 4, EventSink.NOOP)) {
                try (var occupied = pool.getConnection()) {
                    assertEquals(1, pool.getHikariPoolMXBean().getActiveConnections());
                    assertThrows(
                            TimeoutException.class,
                            () ->
                                    executor.execute(
                                            "db",
                                            RetrySafety.UNSAFE,
                                            () -> {
                                                assertTrue(Thread.currentThread().isVirtual());
                                                try (var connection = pool.getConnection()) {
                                                    return "unexpected";
                                                }
                                            }));
                    for (int i = 0; i < 100 && registry.get("db").bulkhead().active() != 0; i++)
                        Thread.sleep(5);
                    assertEquals(0, registry.get("db").bulkhead().active());
                    assertEquals(1, pool.getHikariPoolMXBean().getActiveConnections());
                }
                assertEquals(
                        42,
                        executor.execute(
                                "db",
                                RetrySafety.UNSAFE,
                                () -> {
                                    try (var connection = pool.getConnection();
                                         var statement = connection.createStatement();
                                         var result = statement.executeQuery("SELECT 42")) {
                                        result.next();
                                        return result.getInt(1);
                                    }
                                }));
                assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            }
        }
    }
}
