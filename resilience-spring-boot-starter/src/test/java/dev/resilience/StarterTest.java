package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class StarterTest {
    final org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withConfiguration(
                            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                    ResilienceAutoConfiguration.class))
                    .withPropertyValues("resilience.instances.test.max-attempts=1");

    @Test
    void dottedPolicyName() {
        runner
                .withPropertyValues("resilience.instances[payment-service.quote].max-attempts=2")
                .run(
                        ctx ->
                                assertEquals(
                                        2,
                                        ctx.getBean(PolicyRegistry.class)
                                                .get("payment-service.quote")
                                                .config()
                                                .retry()
                                                .maxAttempts()));
    }

    @Test
    void bindsAndConfigures() {
        runner.run(
                ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertEquals(
                            1, ctx.getBean(PolicyRegistry.class).get("test").config().retry().maxAttempts());
                    assertEquals(1, ctx.getBeansOfType(ResilienceExecutor.class).size());
                });
    }

    @Test
    void invalidFailsStartup() {
        runner
                .withPropertyValues("resilience.instances.test.concurrency=0")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @Test
    void proxyAndSelfInvocation() {
        runner
                .withUserConfiguration(Config.class)
                .run(
                        ctx -> {
                            var d = ctx.getBean(Demo.class);
                            var r = ctx.getBean(PolicyRegistry.class);
                            assertEquals("ok", d.external());
                            assertEquals(1, r.get("test").circuit().snapshot().calls());
                            assertEquals("ok", d.self());
                            assertEquals(1, r.get("test").circuit().snapshot().calls());
                            assertThrows(IllegalArgumentException.class, d::fail);
                            assertThrows(IllegalArgumentException.class, d::async);
                        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Config {
        @org.springframework.context.annotation.Bean
        Demo demo() {
            return new Demo();
        }
    }

    public static class Demo {
        AtomicInteger count = new AtomicInteger();

        @Resilient("test")
        public String external() {
            count.incrementAndGet();
            return "ok";
        }

        public String self() {
            return external();
        }

        @Resilient("test")
        public String fail() {
            throw new IllegalArgumentException("domain");
        }

        @Resilient("test")
        public CompletableFuture<String> async() {
            return CompletableFuture.completedFuture("x");
        }
    }
}
