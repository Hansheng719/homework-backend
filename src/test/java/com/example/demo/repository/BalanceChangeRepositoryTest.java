package com.example.demo.repository;

import com.example.demo.ConfigureMockConsumerBeanApplication;
import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.BalanceChangeStatus;
import com.example.demo.entity.BalanceChangeType;
import com.example.demo.mq.consumer.BalanceChangeConsumer;
import com.example.demo.mq.consumer.BalanceChangeResultConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * BalanceChangeRepository 測試 - TDD Green Phase
 *
 * 測試策略：
 * 1. 使用 @SpringBootTest + Testcontainers 進行整合測試
 * 2. 使用真實的 MySQL 資料庫容器
 * 3. 使用 @Transactional 自動回滾測試資料
 * 4. 從 Happy Path 開始測試
 *
 * 測試範圍：
 * - 基本 save 操作
 * - findByExternalIdAndType（冪等性檢查）
 * - 唯一約束驗證 (external_id, type)
 * - 完整物件欄位持久化
 */
@SpringBootTest(classes = ConfigureMockConsumerBeanApplication.class)
@Testcontainers
@Transactional
@DisplayName("BalanceChangeRepository Tests")
class BalanceChangeRepositoryTest {

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
    private BalanceChangeRepository balanceChangeRepository;

    // ==================== Happy Path Tests ====================

    @Test
    @DisplayName("save - With valid balance change - Persists to database and generates ID")
    void save_WithValidBalanceChange_PersistsToDatabase() {
        // Given
        BalanceChange balanceChange = createBalanceChange(
                12345L,
                BalanceChangeType.TRANSFER_OUT,
                "user_001",
                new BigDecimal("-150.00")
        );

        // When
        BalanceChange savedBalanceChange = balanceChangeRepository.save(balanceChange);

        // Then
        assertThat(savedBalanceChange.getId()).isNotNull();
        assertThat(savedBalanceChange.getExternalId()).isEqualTo(12345L);
        assertThat(savedBalanceChange.getType()).isEqualTo(BalanceChangeType.TRANSFER_OUT);
        assertThat(savedBalanceChange.getUserId()).isEqualTo("user_001");
        assertThat(savedBalanceChange.getAmount()).isEqualByComparingTo(new BigDecimal("-150.00"));
        assertThat(savedBalanceChange.getStatus()).isEqualTo(BalanceChangeStatus.PROCESSING);
        assertThat(savedBalanceChange.getCreatedAt()).isNotNull();

        // Verify it can be retrieved from database
        Optional<BalanceChange> found = balanceChangeRepository.findById(savedBalanceChange.getId());
        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("save - With complete balance change fields - Persists all fields correctly")
    void save_WithCompleteBalanceChangeFields_PersistsAllFieldsCorrectly() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        BalanceChange balanceChange = BalanceChange.builder()
                .externalId(99999L)
                .type(BalanceChangeType.TRANSFER_IN)
                .userId("user_002")
                .amount(new BigDecimal("200.50"))
                .relatedId("12345")
                .status(BalanceChangeStatus.COMPLETED)
                .balanceBefore(new BigDecimal("1000.00"))
                .balanceAfter(new BigDecimal("1200.50"))
                .createdAt(now)
                .completedAt(now.plusSeconds(1))
                .build();

        // When
        BalanceChange savedBalanceChange = balanceChangeRepository.save(balanceChange);

        // Then - 使用 usingRecursiveComparison 完整比較物件
        Optional<BalanceChange> foundOpt = balanceChangeRepository.findById(savedBalanceChange.getId());

        assertThat(foundOpt)
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFields("id") // 忽略自動生成的 ID
                .isEqualTo(BalanceChange.builder()
                        .externalId(99999L)
                        .type(BalanceChangeType.TRANSFER_IN)
                        .userId("user_002")
                        .amount(new BigDecimal("200.50"))
                        .relatedId("12345")
                        .status(BalanceChangeStatus.COMPLETED)
                        .balanceBefore(new BigDecimal("1000.00"))
                        .balanceAfter(new BigDecimal("1200.50"))
                        .createdAt(now)
                        .completedAt(now.plusSeconds(1))
                        .build());
    }

    @Test
    @DisplayName("findByExternalIdAndType - With existing balance change - Returns complete object")
    void findByExternalIdAndType_WithExistingBalanceChange_ReturnsCompleteObject() {
        // Given
        Long externalId = 12345L;
        BalanceChangeType type = BalanceChangeType.TRANSFER_OUT;

        BalanceChange balanceChange = createBalanceChange(
                externalId,
                type,
                "user_001",
                new BigDecimal("-150.00")
        );
        balanceChange.setBalanceBefore(new BigDecimal("1000.00"));
        balanceChange.setBalanceAfter(new BigDecimal("850.00"));
        balanceChange.setStatus(BalanceChangeStatus.COMPLETED);
        balanceChange.setCompletedAt(LocalDateTime.now());

        BalanceChange savedBalanceChange = balanceChangeRepository.save(balanceChange);

        // When
        Optional<BalanceChange> resultOpt = balanceChangeRepository
                .findByExternalIdAndType(externalId, type);

        // Then
        assertThat(resultOpt)
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .isEqualTo(savedBalanceChange);
    }

    @Test
    @DisplayName("findByExternalIdAndType - With non-existing external ID - Returns empty")
    void findByExternalIdAndType_WithNonExistingExternalId_ReturnsEmpty() {
        // When
        Optional<BalanceChange> resultOpt = balanceChangeRepository
                .findByExternalIdAndType(99999L, BalanceChangeType.TRANSFER_OUT);

        // Then
        assertThat(resultOpt).isEmpty();
    }

    @Test
    @DisplayName("findByExternalIdAndType - With different type - Returns empty")
    void findByExternalIdAndType_WithDifferentType_ReturnsEmpty() {
        // Given
        Long externalId = 12345L;

        // 保存 TRANSFER_OUT 類型
        BalanceChange balanceChange = createBalanceChange(
                externalId,
                BalanceChangeType.TRANSFER_OUT,
                "user_001",
                new BigDecimal("-150.00")
        );
        balanceChangeRepository.save(balanceChange);

        // When - 查詢 TRANSFER_IN 類型（不同的 type）
        Optional<BalanceChange> resultOpt = balanceChangeRepository
                .findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_IN);

        // Then
        assertThat(resultOpt).isEmpty();
    }

    @Test
    @DisplayName("findByExternalIdAndType - Idempotency check - Returns existing record on duplicate request")
    void findByExternalIdAndType_IdempotencyCheck_ReturnsExistingRecordOnDuplicateRequest() {
        // Given - 第一次處理
        Long externalId = 12345L;
        BalanceChangeType type = BalanceChangeType.TRANSFER_OUT;

        BalanceChange firstBalanceChange = createBalanceChange(
                externalId,
                type,
                "user_001",
                new BigDecimal("-150.00")
        );
        firstBalanceChange.setStatus(BalanceChangeStatus.COMPLETED);
        BalanceChange saved = balanceChangeRepository.save(firstBalanceChange);

        // When - 模擬重複請求，檢查是否已存在
        Optional<BalanceChange> existingOpt = balanceChangeRepository
                .findByExternalIdAndType(externalId, type);

        // Then - 應該找到已存在的記錄
        assertThat(existingOpt)
                .isPresent()
                .get()
                .satisfies(existing -> {
                    assertThat(existing.getId()).isEqualTo(saved.getId());
                    assertThat(existing.getStatus()).isEqualTo(BalanceChangeStatus.COMPLETED);
                });
    }

    @Test
    @DisplayName("save - With duplicate external_id and type - Throws constraint violation exception")
    void save_WithDuplicateExternalIdAndType_ThrowsConstraintViolationException() {
        // Given
        Long externalId = 12345L;
        BalanceChangeType type = BalanceChangeType.TRANSFER_OUT;

        // 第一筆記錄
        BalanceChange first = createBalanceChange(externalId, type, "user_001", new BigDecimal("-100.00"));
        balanceChangeRepository.save(first);

        // 第二筆記錄（相同 external_id 和 type）
        BalanceChange duplicate = createBalanceChange(externalId, type, "user_001", new BigDecimal("-100.00"));

        // When & Then - 應該拋出唯一約束違反異常
        assertThatThrownBy(() -> {
            balanceChangeRepository.save(duplicate);
            balanceChangeRepository.flush(); // 強制執行 SQL 觸發約束檢查
        }).isInstanceOf(Exception.class)
          .hasMessageContaining("Duplicate");
    }

    @Test
    @DisplayName("save - Same external_id but different type - Saves successfully")
    void save_SameExternalIdButDifferentType_SavesSuccessfully() {
        // Given
        Long externalId = 12345L;

        // TRANSFER_OUT 類型
        BalanceChange transferOut = createBalanceChange(
                externalId,
                BalanceChangeType.TRANSFER_OUT,
                "user_001",
                new BigDecimal("-150.00")
        );
        balanceChangeRepository.save(transferOut);

        // TRANSFER_IN 類型（相同 externalId，不同 type）
        BalanceChange transferIn = createBalanceChange(
                externalId,
                BalanceChangeType.TRANSFER_IN,
                "user_002",
                new BigDecimal("150.00")
        );

        // When
        BalanceChange savedTransferIn = balanceChangeRepository.save(transferIn);

        // Then - 應該成功保存
        assertThat(savedTransferIn.getId()).isNotNull();

        // 驗證兩筆記錄都存在
        Optional<BalanceChange> foundOut = balanceChangeRepository
                .findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_OUT);
        Optional<BalanceChange> foundIn = balanceChangeRepository
                .findByExternalIdAndType(externalId, BalanceChangeType.TRANSFER_IN);

        assertThat(foundOut).isPresent();
        assertThat(foundIn).isPresent();
        assertThat(foundOut.get().getId()).isNotEqualTo(foundIn.get().getId());
    }

    @Test
    @DisplayName("save - With status update from PROCESSING to COMPLETED - Persists status change")
    void save_WithStatusUpdateFromProcessingToCompleted_PersistsStatusChange() {
        // Given
        BalanceChange balanceChange = createBalanceChange(
                12345L,
                BalanceChangeType.TRANSFER_OUT,
                "user_001",
                new BigDecimal("-150.00")
        );
        balanceChange.setStatus(BalanceChangeStatus.PROCESSING);
        balanceChange.setBalanceBefore(new BigDecimal("1000.00"));
        BalanceChange savedBalanceChange = balanceChangeRepository.save(balanceChange);

        // When - 更新為 COMPLETED
        savedBalanceChange.setStatus(BalanceChangeStatus.COMPLETED);
        savedBalanceChange.setBalanceAfter(new BigDecimal("850.00"));
        savedBalanceChange.setCompletedAt(LocalDateTime.now());
        BalanceChange updatedBalanceChange = balanceChangeRepository.save(savedBalanceChange);

        // Then
        assertThat(updatedBalanceChange.getStatus()).isEqualTo(BalanceChangeStatus.COMPLETED);
        assertThat(updatedBalanceChange.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("850.00"));
        assertThat(updatedBalanceChange.getCompletedAt()).isNotNull();

        // 從資料庫重新查詢驗證
        Optional<BalanceChange> found = balanceChangeRepository.findById(savedBalanceChange.getId());
        assertThat(found)
                .isPresent()
                .get()
                .satisfies(bc -> {
                    assertThat(bc.getStatus()).isEqualTo(BalanceChangeStatus.COMPLETED);
                    assertThat(bc.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("850.00"));
                    assertThat(bc.getCompletedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("save - With failure reason - Persists failure information")
    void save_WithFailureReason_PersistsFailureInformation() {
        // Given
        BalanceChange balanceChange = createBalanceChange(
                12345L,
                BalanceChangeType.TRANSFER_OUT,
                "user_001",
                new BigDecimal("-150.00")
        );
        BalanceChange savedBalanceChange = balanceChangeRepository.save(balanceChange);

        String failureReason = "Insufficient balance: required 150.00, available 100.00";

        // When
        savedBalanceChange.setStatus(BalanceChangeStatus.FAILED);
        savedBalanceChange.setFailureReason(failureReason);
        BalanceChange failedBalanceChange = balanceChangeRepository.save(savedBalanceChange);

        // Then
        assertThat(failedBalanceChange.getFailureReason()).isEqualTo(failureReason);

        // 從資料庫重新查詢驗證
        Optional<BalanceChange> found = balanceChangeRepository.findById(savedBalanceChange.getId());
        assertThat(found)
                .isPresent()
                .get()
                .satisfies(bc -> {
                    assertThat(bc.getStatus()).isEqualTo(BalanceChangeStatus.FAILED);
                    assertThat(bc.getFailureReason()).isEqualTo(failureReason);
                });
    }

    @Test
    @DisplayName("save - With balance before and after - Persists balance tracking")
    void save_WithBalanceBeforeAndAfter_PersistsBalanceTracking() {
        // Given
        BalanceChange balanceChange = createBalanceChange(
                12345L,
                BalanceChangeType.TRANSFER_OUT,
                "user_001",
                new BigDecimal("-150.00")
        );
        balanceChange.setBalanceBefore(new BigDecimal("1000.00"));
        balanceChange.setBalanceAfter(new BigDecimal("850.00"));

        // When
        BalanceChange savedBalanceChange = balanceChangeRepository.save(balanceChange);

        // Then
        assertThat(savedBalanceChange.getBalanceBefore()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(savedBalanceChange.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("850.00"));

        // Verify from database
        Optional<BalanceChange> found = balanceChangeRepository.findById(savedBalanceChange.getId());
        assertThat(found)
                .isPresent()
                .get()
                .satisfies(bc -> {
                    assertThat(bc.getBalanceBefore()).isEqualByComparingTo(new BigDecimal("1000.00"));
                    assertThat(bc.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("850.00"));
                });
    }

    // ==================== Helper Methods ====================

    /**
     * 建立基本的 BalanceChange 物件（PROCESSING 狀態）
     */
    private BalanceChange createBalanceChange(Long externalId, BalanceChangeType type,
                                             String userId, BigDecimal amount) {
        return BalanceChange.builder()
                .externalId(externalId)
                .type(type)
                .userId(userId)
                .amount(amount)
                .status(BalanceChangeStatus.PROCESSING)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
