package com.example.demo.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShedLock Configuration for Distributed Scheduled Job Locking
 *
 * Purpose:
 * - Ensures only one instance executes scheduled jobs at a time
 * - Uses Redis as the distributed lock provider
 * - Prevents duplicate processing in multi-instance deployments
 *
 * Lock Strategy:
 * - Skip execution if lock is already held (non-blocking)
 * - Lock duration matches job frequency per environment
 * - Automatic lock release on completion or timeout
 *
 * Redis Lock Schema:
 * - Lock entries stored in Redis with key: "homework-backend:{job_name}"
 * - Contains: lock_until, locked_at, locked_by fields
 * - Automatically cleaned up after expiration
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class ShedLockConfig {

    /**
     * Creates Redis-based LockProvider for ShedLock
     *
     * Implementation Details:
     * - Uses Lettuce client (via RedisConnectionFactory)
     * - Stores locks in Redis with TTL
     * - Thread-safe and supports distributed environment
     * - Environment name included in lock identifier
     *
     * @param connectionFactory Redis connection factory (auto-configured by Spring Boot)
     * @return LockProvider instance
     */
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "homework-backend");
    }
}
