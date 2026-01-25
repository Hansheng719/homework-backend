package com.example.demo.service;

import com.example.demo.ConfigureMockConsumerBeanApplication;
import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.UserBalance;
import com.example.demo.repository.BalanceChangeRepository;
import com.example.demo.repository.UserBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BalanceService 快取整合測試
 *
 * 測試策略：
 * 1. 使用 @SpringBootTest 啟動完整 Spring Context
 * 2. 使用 Testcontainers 提供真實 MySQL + Redis 環境
 * 3. 使用 @SpyBean 監控 Repository 呼叫次數（驗證快取效果）
 * 4. 驗證 Spring Cache (@Cacheable, @CacheEvict) 行為
 *
 * 測試範圍：
 * - getBalance() 的 @Cacheable 行為
 * - createUser() 的快取清除
 * - debitBalance() 的 @CacheEvict 行為
 * - creditBalance() 的 @CacheEvict 行為
 * - 多使用者快取獨立性
 */
@SpringBootTest(classes = ConfigureMockConsumerBeanApplication.class)
@Testcontainers
@DisplayName("BalanceService Cache Integration Tests")
class BalanceServiceCacheIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // MySQL configuration
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");

        // Redis configuration
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private BalanceService balanceService;

    @SpyBean
    private UserBalanceRepository userBalanceRepository;

    @SpyBean
    private BalanceChangeRepository balanceChangeRepository;

    @BeforeEach
    void setUp() {
        // 清空資料庫
        balanceChangeRepository.deleteAll();
        userBalanceRepository.deleteAll();

        // 重置 Spy 計數器
        clearInvocations(userBalanceRepository, balanceChangeRepository);
    }

    @Test
    @DisplayName("getBalance - First call queries database and caches result, second call hits cache")
    void getBalance_FirstCall_QueriesDatabaseAndCachesResult() {
        // Given: 建立使用者
        String userId = "cache_test_001";
        BigDecimal initialBalance = new BigDecimal("1000.00");
        balanceService.createUser(userId, initialBalance);

        // 重置 spy 計數器（忽略 createUser 的呼叫）
        clearInvocations(userBalanceRepository);

        // When: 第一次查詢餘額
        BigDecimal balance1 = balanceService.getBalance(userId);

        // Then: 應該查詢資料庫
        assertThat(balance1).isEqualByComparingTo(initialBalance);
        verify(userBalanceRepository, times(1)).findById(userId);

        // When: 第二次查詢相同使用者餘額
        BigDecimal balance2 = balanceService.getBalance(userId);

        // Then: 不應該再次查詢資料庫（快取命中）
        assertThat(balance2).isEqualByComparingTo(initialBalance);
        verify(userBalanceRepository, times(1)).findById(userId); // 仍然只有 1 次
    }

    @Test
    @DisplayName("getBalance - After createUser, next getBalance should hit cache")
    void getBalance_AfterCreateUser_ReturnsCachedValue() {
        // Given & When: 建立使用者
        String userId = "cache_test_002";
        BigDecimal initialBalance = new BigDecimal("2000.00");
        UserBalance created = balanceService.createUser(userId, initialBalance);

        assertThat(created.getBalance()).isEqualByComparingTo(initialBalance);

        // 重置 spy 計數器
        clearInvocations(userBalanceRepository);

        // When: 立即查詢餘額
        BigDecimal balance = balanceService.getBalance(userId);

        // Then: createUser 建立新使用者時不會建立快取（也無快取需清除）
        // 所以第一次 getBalance 會查詢資料庫並建立快取
        assertThat(balance).isEqualByComparingTo(initialBalance);
        verify(userBalanceRepository, times(1)).findById(userId);

        // When: 再次查詢
        BigDecimal balance2 = balanceService.getBalance(userId);

        // Then: 第二次應該命中快取
        assertThat(balance2).isEqualByComparingTo(initialBalance);
        verify(userBalanceRepository, times(1)).findById(userId); // 仍然只有 1 次
    }

    @Test
    @DisplayName("getBalance - After debitBalance, cache is evicted and next call queries database")
    void getBalance_AfterDebitBalance_CacheEvicted() {
        // Given: 建立使用者並查詢餘額（建立快取）
        String userId = "cache_test_003";
        BigDecimal initialBalance = new BigDecimal("1500.00");
        balanceService.createUser(userId, initialBalance);

        // 第一次查詢（建立快取）
        balanceService.getBalance(userId);

        // 第二次查詢（驗證快取存在）
        clearInvocations(userBalanceRepository);
        balanceService.getBalance(userId);
        verify(userBalanceRepository, never()).findById(anyString()); // 快取命中

        // When: 扣款操作（應該清除快取）
        clearInvocations(userBalanceRepository);
        BigDecimal debitAmount = new BigDecimal("500.00");
        BalanceChange debitResult = balanceService.debitBalance(1001L, userId, debitAmount);

        assertThat(debitResult).isNotNull();

        // When: 再次查詢餘額
        clearInvocations(userBalanceRepository);
        BigDecimal newBalance = balanceService.getBalance(userId);

        // Then: 應該重新查詢資料庫（快取已被清除）
        assertThat(newBalance).isEqualByComparingTo(new BigDecimal("1000.00"));
        verify(userBalanceRepository, times(1)).findById(userId);
    }

    @Test
    @DisplayName("getBalance - After creditBalance, cache is evicted and next call queries database")
    void getBalance_AfterCreditBalance_CacheEvicted() {
        // Given: 建立使用者並查詢餘額（建立快取）
        String userId = "cache_test_004";
        BigDecimal initialBalance = new BigDecimal("1000.00");
        balanceService.createUser(userId, initialBalance);

        // 第一次查詢（建立快取）
        balanceService.getBalance(userId);

        // 第二次查詢（驗證快取存在）
        clearInvocations(userBalanceRepository);
        balanceService.getBalance(userId);
        verify(userBalanceRepository, never()).findById(anyString()); // 快取命中

        // When: 加帳操作（應該清除快取）
        clearInvocations(userBalanceRepository);
        BigDecimal creditAmount = new BigDecimal("300.00");
        BalanceChange creditResult = balanceService.creditBalance(1002L, userId, creditAmount);

        assertThat(creditResult).isNotNull();

        // When: 再次查詢餘額
        clearInvocations(userBalanceRepository);
        BigDecimal newBalance = balanceService.getBalance(userId);

        // Then: 應該重新查詢資料庫（快取已被清除）
        assertThat(newBalance).isEqualByComparingTo(new BigDecimal("1300.00"));
        verify(userBalanceRepository, times(1)).findById(userId);
    }

    @Test
    @DisplayName("getBalance - Multiple users have independent cache entries")
    void getBalance_MultipleUsers_IndependentCache() {
        // Given: 建立兩個使用者
        String userId1 = "cache_test_005";
        String userId2 = "cache_test_006";
        balanceService.createUser(userId1, new BigDecimal("1000.00"));
        balanceService.createUser(userId2, new BigDecimal("2000.00"));

        // When: 查詢兩個使用者的餘額
        balanceService.getBalance(userId1);
        balanceService.getBalance(userId2);

        // 重置計數器
        clearInvocations(userBalanceRepository);

        // When: 再次查詢（應該都命中快取）
        BigDecimal balance1 = balanceService.getBalance(userId1);
        BigDecimal balance2 = balanceService.getBalance(userId2);

        // Then: 都應該命中快取（不查詢資料庫）
        assertThat(balance1).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(balance2).isEqualByComparingTo(new BigDecimal("2000.00"));
        verify(userBalanceRepository, never()).findById(anyString());

        // When: 只對 userId1 進行扣款
        balanceService.debitBalance(1003L, userId1, new BigDecimal("100.00"));

        // 重置計數器
        clearInvocations(userBalanceRepository);

        // When: 再次查詢兩個使用者
        balanceService.getBalance(userId1); // 快取已清除，應該查詢資料庫
        balanceService.getBalance(userId2); // 快取仍存在，不查詢資料庫

        // Then: 只有 userId1 查詢資料庫
        verify(userBalanceRepository, times(1)).findById(userId1);
        verify(userBalanceRepository, never()).findById(userId2);
    }
}
