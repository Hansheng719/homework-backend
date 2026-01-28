package com.example.demo.schedule;

import com.example.demo.ConfigureMockConsumerBeanApplication;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ShedLock Integration Tests
 *
 * Test Strategy:
 * - Start full Spring Context with Redis
 * - Verify LockProvider bean exists
 * - Test lock acquisition and release
 * - Verify lock behavior (skip on conflict)
 *
 * Requirements:
 * - Redis must be running on localhost:6379
 * - Tests use 'local' profile configuration
 */
@SpringBootTest(classes = ConfigureMockConsumerBeanApplication.class)
@ActiveProfiles("local")
@DisplayName("ShedLock Integration Tests")
class ShedLockIntegrationTest {

    @Autowired
    private LockProvider lockProvider;

    @Test
    @DisplayName("LockProvider - Bean exists - Successfully autowired")
    void lockProvider_BeanExists_SuccessfullyAutowired() {
        // Then
        assertNotNull(lockProvider, "LockProvider should be autowired from Spring context");
    }

    @Test
    @DisplayName("Lock acquisition - First attempt - Successfully acquires lock")
    void lockAcquisition_FirstAttempt_SuccessfullyAcquiresLock() {
        // Given
        String lockName = "test-lock-" + System.currentTimeMillis();
        Instant now = Instant.now();
        Duration lockAtMostFor = Duration.ofSeconds(10);
        Duration lockAtLeastFor = Duration.ofSeconds(0);

        LockConfiguration lockConfig = new LockConfiguration(now, lockName, lockAtMostFor, lockAtLeastFor);

        // When
        Optional<SimpleLock> lock = lockProvider.lock(lockConfig);

        // Then
        assertTrue(lock.isPresent(), "First lock attempt should succeed");

        // Cleanup
        lock.ifPresent(SimpleLock::unlock);
    }

    @Test
    @DisplayName("Lock acquisition - Concurrent attempt - Second attempt fails")
    void lockAcquisition_ConcurrentAttempt_SecondAttemptFails() {
        // Given
        String lockName = "test-concurrent-lock-" + System.currentTimeMillis();
        Instant now = Instant.now();
        Duration lockAtMostFor = Duration.ofSeconds(10);
        Duration lockAtLeastFor = Duration.ofSeconds(0);

        LockConfiguration firstLockConfig = new LockConfiguration(now, lockName, lockAtMostFor, lockAtLeastFor);
        LockConfiguration secondLockConfig = new LockConfiguration(now, lockName, lockAtMostFor, lockAtLeastFor);

        // When - First lock
        Optional<SimpleLock> firstLock = lockProvider.lock(firstLockConfig);

        // When - Second lock attempt (should fail)
        Optional<SimpleLock> secondLock = lockProvider.lock(secondLockConfig);

        // Then
        assertTrue(firstLock.isPresent(), "First lock should succeed");
        assertFalse(secondLock.isPresent(), "Second lock should fail (already locked)");

        // Cleanup
        firstLock.ifPresent(SimpleLock::unlock);
    }

    @Test
    @DisplayName("Lock release - After unlock - Can be acquired again")
    void lockRelease_AfterUnlock_CanBeAcquiredAgain() {
        // Given
        String lockName = "test-reacquire-lock-" + System.currentTimeMillis();
        Instant now = Instant.now();
        Duration lockAtMostFor = Duration.ofSeconds(10);
        Duration lockAtLeastFor = Duration.ofSeconds(0);

        LockConfiguration lockConfig = new LockConfiguration(now, lockName, lockAtMostFor, lockAtLeastFor);

        // When - First acquisition
        Optional<SimpleLock> firstLock = lockProvider.lock(lockConfig);
        assertTrue(firstLock.isPresent(), "First lock should succeed");
        firstLock.ifPresent(SimpleLock::unlock);

        // When - Second acquisition after unlock
        Optional<SimpleLock> secondLock = lockProvider.lock(lockConfig);

        // Then
        assertTrue(secondLock.isPresent(), "Second lock should succeed after first lock is released");

        // Cleanup
        secondLock.ifPresent(SimpleLock::unlock);
    }

    @Test
    @DisplayName("Lock duration - Short TTL - Lock expires automatically")
    void lockDuration_ShortTTL_LockExpiresAutomatically() throws InterruptedException {
        // Given
        String lockName = "test-expiry-lock-" + System.currentTimeMillis();
        Instant now = Instant.now();
        Duration lockAtMostFor = Duration.ofSeconds(2); // Very short lock duration
        Duration lockAtLeastFor = Duration.ofSeconds(0);

        LockConfiguration lockConfig = new LockConfiguration(now, lockName, lockAtMostFor, lockAtLeastFor);

        // When - Acquire lock with 2 second TTL
        Optional<SimpleLock> firstLock = lockProvider.lock(lockConfig);
        assertTrue(firstLock.isPresent(), "First lock should succeed");

        // Wait for lock to expire (do not call unlock)
        Thread.sleep(3000); // Wait 3 seconds for lock to expire

        // When - Try to acquire lock again after expiry
        Optional<SimpleLock> secondLock = lockProvider.lock(lockConfig);

        // Then
        assertTrue(secondLock.isPresent(), "Second lock should succeed after first lock expires");

        // Cleanup
        secondLock.ifPresent(SimpleLock::unlock);
    }
}
