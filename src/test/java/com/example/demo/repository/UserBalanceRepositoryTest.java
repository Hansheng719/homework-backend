package com.example.demo.repository;

import com.example.demo.ConfigureMockConsumerBeanApplication;
import com.example.demo.entity.UserBalance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UserBalanceRepository 測試 - TDD Green Phase
 *
 * 測試策略：
 * 1. 使用 @SpringBootTest + Testcontainers 進行整合測試
 * 2. 使用真實的 MySQL 資料庫容器
 * 3. 從 Happy Path 開始測試
 *
 * 測試範圍：
 * - 基本 CRUD 操作 (save, findById, existsById)
 * - 悲觀鎖查詢 (findByIdForUpdate)
 * - 樂觀鎖版本控制 (version)
 * - 餘額更新操作
 * - 資料持久化驗證
 */
@SpringBootTest(classes = ConfigureMockConsumerBeanApplication.class)
@Testcontainers
@DisplayName("UserBalanceRepository Tests")
class UserBalanceRepositoryTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private UserBalanceRepository userBalanceRepository;

    // ==================== Happy Path Tests ====================

    @Test
    @DisplayName("save - With valid user balance - Persists to database")
    void save_WithValidUserBalance_PersistsToDatabase() {
        // Given
        UserBalance userBalance = createUserBalance("user_001", new BigDecimal("1000.00"));

        // When
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // Then
        assertThat(savedUserBalance.getUserId()).isEqualTo("user_001");
        assertThat(savedUserBalance.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(savedUserBalance.getCreatedAt()).isNotNull();
        assertThat(savedUserBalance.getVersion()).isEqualTo(0L); // 初始版本號

        // Verify it can be retrieved from database
        Optional<UserBalance> found = userBalanceRepository.findById("user_001");
        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("save - With complete user balance fields - Persists all fields correctly")
    void save_WithCompleteUserBalanceFields_PersistsAllFieldsCorrectly() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        UserBalance userBalance = UserBalance.builder()
                .userId("user_002")
                .balance(new BigDecimal("5000.50"))
                .createdAt(now)
                .version(null)
                .build();

        // When
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // Then - 使用 usingRecursiveComparison 完整比較物件
        Optional<UserBalance> foundOpt = userBalanceRepository.findById("user_002");

        assertThat(foundOpt)
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .isEqualTo(UserBalance.builder()
                        .userId("user_002")
                        .balance(new BigDecimal("5000.50"))
                        .createdAt(now)
                        .version(0L)
                        .version(0L)
                        .build());
    }

    @Test
    @DisplayName("findById - With existing userId - Returns complete user balance object")
    void findById_WithExistingUserId_ReturnsCompleteUserBalanceObject() {
        // Given
        UserBalance userBalance = createUserBalance("user_003", new BigDecimal("2500.00"));
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // When
        Optional<UserBalance> resultOpt = userBalanceRepository.findById("user_003");

        // Then - 完整比較所有欄位
        assertThat(resultOpt)
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .isEqualTo(savedUserBalance);
    }

    @Test
    @DisplayName("findById - With non-existing userId - Returns empty")
    void findById_WithNonExistingUserId_ReturnsEmpty() {
        // When
        Optional<UserBalance> resultOpt = userBalanceRepository.findById("non_existing_user");

        // Then
        assertThat(resultOpt).isEmpty();
    }

    @Test
    @DisplayName("existsById - With existing userId - Returns true")
    void existsById_WithExistingUserId_ReturnsTrue() {
        // Given
        UserBalance userBalance = createUserBalance("user_004", new BigDecimal("1500.00"));
        userBalanceRepository.save(userBalance);

        // When
        boolean exists = userBalanceRepository.existsById("user_004");

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsById - With non-existing userId - Returns false")
    void existsById_WithNonExistingUserId_ReturnsFalse() {
        // When
        boolean exists = userBalanceRepository.existsById("non_existing_user");

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("save - Update existing user balance - Updates balance successfully")
    void save_UpdateExistingUserBalance_UpdatesBalanceSuccessfully() {
        // Given
        UserBalance userBalance = createUserBalance("user_005", new BigDecimal("1000.00"));
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // When - 更新餘額
        savedUserBalance.setBalance(new BigDecimal("1500.00"));
        UserBalance updatedUserBalance = userBalanceRepository.save(savedUserBalance);

        // Then
        assertThat(updatedUserBalance.getBalance()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(updatedUserBalance.getVersion()).isEqualTo(1L); // 版本號遞增

        // 從資料庫重新查詢驗證
        Optional<UserBalance> found = userBalanceRepository.findById("user_005");
        assertThat(found)
                .isPresent()
                .get()
                .satisfies(ub -> {
                    assertThat(ub.getBalance()).isEqualByComparingTo(new BigDecimal("1500.00"));
                    assertThat(ub.getVersion()).isEqualTo(1L);
                });
    }

    @Test
    @DisplayName("save - Deduct balance - Updates balance correctly")
    void save_DeductBalance_UpdatesBalanceCorrectly() {
        // Given
        UserBalance userBalance = createUserBalance("user_006", new BigDecimal("1000.00"));
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // When - 扣除餘額
        BigDecimal deductAmount = new BigDecimal("150.00");
        savedUserBalance.setBalance(savedUserBalance.getBalance().subtract(deductAmount));
        UserBalance updatedUserBalance = userBalanceRepository.save(savedUserBalance);

        // Then
        assertThat(updatedUserBalance.getBalance()).isEqualByComparingTo(new BigDecimal("850.00"));

        // 驗證從資料庫查詢
        Optional<UserBalance> found = userBalanceRepository.findById("user_006");
        assertThat(found)
                .isPresent()
                .get()
                .extracting(UserBalance::getBalance)
                .isEqualTo(new BigDecimal("850.00"));
    }

    @Test
    @DisplayName("save - Add balance - Updates balance correctly")
    void save_AddBalance_UpdatesBalanceCorrectly() {
        // Given
        UserBalance userBalance = createUserBalance("user_007", new BigDecimal("1000.00"));
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // When - 增加餘額
        BigDecimal addAmount = new BigDecimal("500.00");
        savedUserBalance.setBalance(savedUserBalance.getBalance().add(addAmount));
        UserBalance updatedUserBalance = userBalanceRepository.save(savedUserBalance);

        // Then
        assertThat(updatedUserBalance.getBalance()).isEqualByComparingTo(new BigDecimal("1500.00"));

        // 驗證從資料庫查詢
        Optional<UserBalance> found = userBalanceRepository.findById("user_007");
        assertThat(found)
                .isPresent()
                .get()
                .extracting(UserBalance::getBalance)
                .isEqualTo(new BigDecimal("1500.00"));
    }

    @Test
    @Transactional
    @DisplayName("findByIdForUpdate - With existing userId - Returns user balance with pessimistic lock")
    void findByIdForUpdate_WithExistingUserId_ReturnsUserBalanceWithLock() {
        // Given
        UserBalance userBalance = createUserBalance("user_008", new BigDecimal("2000.00"));
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // When
        Optional<UserBalance> resultOpt = userBalanceRepository.findByIdForUpdate("user_008");

        // Then
        assertThat(resultOpt).isPresent();
        assertThat(resultOpt.get())
                .usingRecursiveComparison()
                .isEqualTo(savedUserBalance);
    }

    @Test
    @Transactional
    @DisplayName("findByIdForUpdate - With non-existing userId - Returns empty")
    void findByIdForUpdate_WithNonExistingUserId_ReturnsEmpty() {
        // When
        Optional<UserBalance> resultOpt = userBalanceRepository.findByIdForUpdate("non_existing_user");

        // Then
        assertThat(resultOpt).isEmpty();
    }

    @Test
    @DisplayName("save - With optimistic locking - Version increments on each update")
    void save_WithOptimisticLocking_VersionIncrementsOnEachUpdate() {
        // Given
        UserBalance userBalance = createUserBalance("user_009", new BigDecimal("1000.00"));
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);
        assertThat(savedUserBalance.getVersion()).isEqualTo(0L);

        // When - 第一次更新
        savedUserBalance.setBalance(new BigDecimal("1100.00"));
        UserBalance updated1 = userBalanceRepository.save(savedUserBalance);

        // Then - 版本號遞增
        assertThat(updated1.getVersion()).isEqualTo(1L);

        // When - 第二次更新
        updated1.setBalance(new BigDecimal("1200.00"));
        UserBalance updated2 = userBalanceRepository.save(updated1);

        // Then - 版本號再次遞增
        assertThat(updated2.getVersion()).isEqualTo(2L);

        // 驗證從資料庫查詢
        Optional<UserBalance> found = userBalanceRepository.findById("user_009");
        assertThat(found)
                .isPresent()
                .get()
                .satisfies(ub -> {
                    assertThat(ub.getBalance()).isEqualByComparingTo(new BigDecimal("1200.00"));
                    assertThat(ub.getVersion()).isEqualTo(2L);
                });
    }

    @Test
    @DisplayName("save - With stale version - Throws OptimisticLockException")
    void save_WithStaleVersion_ThrowsOptimisticLockException() {
        // Given
        UserBalance userBalance = createUserBalance("user_010", new BigDecimal("1000.00"));
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // 模擬兩個事務同時讀取同一筆記錄
        UserBalance transaction1 = userBalanceRepository.findById("user_010").get();
        UserBalance transaction2 = userBalanceRepository.findById("user_010").get();

        // When - Transaction 1 更新成功
        transaction1.setBalance(new BigDecimal("1100.00"));
        userBalanceRepository.save(transaction1);
        userBalanceRepository.flush(); // 強制寫入資料庫

        // Then - Transaction 2 使用舊版本更新應該拋出 OptimisticLockException
        transaction2.setBalance(new BigDecimal("1200.00"));
        assertThatThrownBy(() -> {
            userBalanceRepository.save(transaction2);
            userBalanceRepository.flush(); // 強制執行 SQL 觸發樂觀鎖檢查
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("save - CreatedAt is immutable - Does not update createdAt on modification")
    void save_CreatedAtIsImmutable_DoesNotUpdateCreatedAtOnModification() {
        // Given
        LocalDateTime originalCreatedAt = LocalDateTime.now().minusDays(1);
        UserBalance userBalance = UserBalance.builder()
                .userId("user_011")
                .balance(new BigDecimal("1000.00"))
                .createdAt(originalCreatedAt)
                .version(null)
                .build();
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // When - 更新餘額
        savedUserBalance.setBalance(new BigDecimal("1500.00"));
        // 嘗試修改 createdAt（但應該被忽略）
        savedUserBalance.setCreatedAt(LocalDateTime.now());
        UserBalance updatedUserBalance = userBalanceRepository.save(savedUserBalance);

        // Then - createdAt 應該保持不變
        Optional<UserBalance> found = userBalanceRepository.findById("user_011");
        assertThat(found)
                .isPresent()
                .get()
                .extracting(UserBalance::getCreatedAt)
                .isEqualTo(originalCreatedAt);
    }

    @Test
    @DisplayName("save - With zero balance - Persists zero balance correctly")
    void save_WithZeroBalance_PersistsZeroBalanceCorrectly() {
        // Given
        UserBalance userBalance = createUserBalance("user_012", BigDecimal.ZERO);

        // When
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // Then
        assertThat(savedUserBalance.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // 驗證從資料庫查詢
        Optional<UserBalance> found = userBalanceRepository.findById("user_012");
        assertThat(found)
                .isPresent()
                .get()
                .extracting(UserBalance::getBalance)
                .usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("save - With large balance - Persists large amount correctly")
    void save_WithLargeBalance_PersistsLargeAmountCorrectly() {
        // Given - 測試大金額 (接近 DECIMAL(15,2) 上限)
        UserBalance userBalance = createUserBalance("user_013", new BigDecimal("9999999999999.99"));

        // When
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // Then
        assertThat(savedUserBalance.getBalance()).isEqualByComparingTo(new BigDecimal("9999999999999.99"));

        // 驗證從資料庫查詢
        Optional<UserBalance> found = userBalanceRepository.findById("user_013");
        assertThat(found)
                .isPresent()
                .get()
                .extracting(UserBalance::getBalance)
                .isEqualTo(new BigDecimal("9999999999999.99"));
    }

    @Test
    @DisplayName("save - With precise decimal balance - Preserves decimal precision")
    void save_WithPreciseDecimalBalance_PreservesDecimalPrecision() {
        // Given - 測試精確的小數點（2位）
        UserBalance userBalance = createUserBalance("user_014", new BigDecimal("123.45"));

        // When
        UserBalance savedUserBalance = userBalanceRepository.save(userBalance);

        // Then
        assertThat(savedUserBalance.getBalance()).isEqualByComparingTo(new BigDecimal("123.45"));

        // 驗證從資料庫查詢
        Optional<UserBalance> found = userBalanceRepository.findById("user_014");
        assertThat(found)
                .isPresent()
                .get()
                .satisfies(ub -> {
                    assertThat(ub.getBalance()).isEqualByComparingTo(new BigDecimal("123.45"));
                    assertThat(ub.getBalance().scale()).isEqualTo(2); // 精度為 2 位小數
                });
    }

    // ==================== Helper Methods ====================

    /**
     * 建立基本的 UserBalance 物件
     */
    private UserBalance createUserBalance(String userId, BigDecimal balance) {
        return UserBalance.builder()
                .userId(userId)
                .balance(balance)
                .createdAt(LocalDateTime.now())
                .version(null)
                .build();
    }
}
