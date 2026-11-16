package dev.resilience;

import java.net.URI;
import java.time.Duration;
import java.util.*;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.*;

/**
 * One atomic server-time token bucket per configured identity, shared by all fleet members.
 */
public final class RedisRetryBudget implements RetryBudget, AutoCloseable {
    private static final int IO_MILLIS = 50;
    private static final String SCRIPT =
            """
                    local cap=tonumber(ARGV[1]);local refill=tonumber(ARGV[2]);local t=redis.call('TIME');local now=tonumber(t[1])*1000+tonumber(t[2])/1000
                    local old=redis.call('HMGET',KEYS[1],'tokens','last','cap','refill');local tokens=tonumber(old[1]) or cap;local last=tonumber(old[2]) or now
                    if old[3] and (tonumber(old[3])~=cap or tonumber(old[4])~=refill) then return -1 end
                    now=math.max(now,last);tokens=math.min(cap,tokens+(now-last)*refill/1000);local allowed=0
                    if tokens>=1 then tokens=tokens-1;allowed=1 end
                    redis.call('HSET',KEYS[1],'tokens',tokens,'last',now,'cap',cap,'refill',refill)
                    redis.call('PEXPIRE',KEYS[1],math.ceil(cap/refill*2000+1000));return allowed
                    """;
    private final redis.clients.jedis.util.Pool<Jedis> pool;
    private final Set<String> policies;
    private final String namespace;
    private final int capacity;
    private final double refill;

    public RedisRetryBudget(
            URI uri, String namespace, Set<String> policies, int capacity, double refill) {
        this(
                namespace,
                policies,
                capacity,
                refill,
                () -> new JedisPool(poolConfig(), uri, IO_MILLIS, IO_MILLIS));
    }

    private RedisRetryBudget(
            String namespace,
            Set<String> policies,
            int capacity,
            double refill,
            java.util.function.Supplier<redis.clients.jedis.util.Pool<Jedis>> factory) {
        Checks.name(namespace);
        Checks.range(capacity, 1000000);
        if (!Double.isFinite(refill) || refill <= 0 || refill > 1000000 || capacity / refill > 86400)
            throw new IllegalArgumentException("Invalid refill horizon");
        if (policies.size() > 1000) throw new IllegalArgumentException("Too many policy keys");
        policies.forEach(Checks::name);
        this.policies = Set.copyOf(policies);
        this.namespace = namespace;
        this.capacity = capacity;
        this.refill = refill;
        pool = factory.get();
    }

    /**
     * Sentinel discovery is administrative construction; requests never rediscover a primary inline.
     */
    public static RedisRetryBudget sentinel(
            String masterName,
            Set<HostAndPort> sentinels,
            String password,
            String sentinelPassword,
            String namespace,
            Set<String> policies,
            int capacity,
            double refill) {
        Checks.name(masterName);
        if (sentinels.isEmpty() || sentinels.size() > 5)
            throw new IllegalArgumentException("One to five Sentinels required");
        var endpoints = Set.copyOf(sentinels);
        return new RedisRetryBudget(
                namespace,
                policies,
                capacity,
                refill,
                () -> {
                    var data =
                            DefaultJedisClientConfig.builder()
                                    .connectionTimeoutMillis(IO_MILLIS)
                                    .socketTimeoutMillis(IO_MILLIS)
                                    .password(password)
                                    .build();
                    var control =
                            DefaultJedisClientConfig.builder()
                                    .connectionTimeoutMillis(200)
                                    .socketTimeoutMillis(200)
                                    .password(sentinelPassword)
                                    .build();
                    return new JedisSentinelPool(masterName, endpoints, poolConfig(), data, control);
                });
    }

    private static GenericObjectPoolConfig<Jedis> poolConfig() {
        var config = new GenericObjectPoolConfig<Jedis>();
        config.setMaxTotal(8);
        config.setMaxIdle(8);
        config.setMinIdle(0);
        config.setBlockWhenExhausted(false);
        config.setMaxWait(Duration.ZERO);
        return config;
    }

    public Decision acquire(String policy, Deadline deadline) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        // Reserve two bounded socket phases before touching the pool. Initial requests never call
        // Redis.
        if (!policies.contains(policy)
                || deadline.remainingNanos() < Duration.ofMillis(2 * IO_MILLIS + 5).toNanos())
            return Decision.UNAVAILABLE;
        try (var jedis = pool.getResource()) {
            var result =
                    ((Number)
                            jedis.eval(
                                    SCRIPT,
                                    List.of(namespace + ":retry:" + policy),
                                    List.of(Integer.toString(capacity), Double.toString(refill))))
                            .longValue();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (deadline.remainingNanos() == 0) return Decision.UNAVAILABLE;
            return result == 1
                    ? Decision.ALLOWED
                    : result == 0 ? Decision.EXHAUSTED : Decision.UNAVAILABLE;
        } catch (redis.clients.jedis.exceptions.JedisException e) {
            return Decision.UNAVAILABLE;
        }
    }

    public void close() {
        pool.close();
    }
}
