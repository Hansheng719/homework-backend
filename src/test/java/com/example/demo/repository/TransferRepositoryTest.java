package com.example.demo.repository;

import com.example.demo.ConfigureMockConsumerBeanApplication;
import com.example.demo.entity.Transfer;
import com.example.demo.entity.TransferStatus;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * TransferRepository 測試 - TDD Green Phase
 *
 * 測試策略：
 * 1. 使用 @SpringBootTest + Testcontainers 進行整合測試
 * 2. 使用真實的 MySQL 資料庫容器
 * 3. 使用 @Transactional 自動回滾測試資料
 * 4. 從 Happy Path 開始測試
 *
 * 測試範圍：
 * - 基本 CRUD 操作 (save, findById)
 * - 自訂查詢方法 (findByIdForUpdate, findPendingTransfers)
 * - 分頁查詢 (findByFromUserIdOrToUserId)
 * - 資料持久化驗證
 */
@Slf4j
@SpringBootTest(classes = ConfigureMockConsumerBeanApplication.class)
@Testcontainers
@Transactional
@DisplayName("TransferRepository Tests")
class TransferRepositoryTest {

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
    private TransferRepository transferRepository;

    // ==================== Happy Path Tests ====================

    @Test
    @DisplayName("save - With valid transfer - Persists to database and generates ID")
    void save_WithValidTransfer_PersistsToDatabase() {
        // Given
        Transfer transfer = createTransfer("user_001", "user_002", new BigDecimal("150.00"));

        // When
        Transfer savedTransfer = transferRepository.save(transfer);

        // Then
        assertThat(savedTransfer.getId()).isNotNull();
        assertThat(savedTransfer.getFromUserId()).isEqualTo("user_001");
        assertThat(savedTransfer.getToUserId()).isEqualTo("user_002");
        assertThat(savedTransfer.getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(savedTransfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(savedTransfer.getCreatedAt()).isNotNull();

        // Verify it can be retrieved from database
        Optional<Transfer> found = transferRepository.findById(savedTransfer.getId());
        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("findById - With existing transfer ID - Returns complete transfer object")
    void findById_WithExistingTransferId_ReturnsCompleteObject() {
        // Given
        Transfer originalTransfer = createTransfer("user_003", "user_004", new BigDecimal("200.50"));
        Transfer savedTransfer = transferRepository.save(originalTransfer);

        // When
        Optional<Transfer> resultOpt = transferRepository.findById(savedTransfer.getId());

        // Then - 使用 usingRecursiveComparison 完整比較物件
        assertThat(resultOpt)
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "updatedAt") // 忽略自動生成的欄位
                .isEqualTo(Transfer.builder()
                        .fromUserId("user_003")
                        .toUserId("user_004")
                        .amount(new BigDecimal("200.50"))
                        .status(TransferStatus.PENDING)
                        .build());
    }

    @Test
    @DisplayName("findById - With non-existing ID - Returns empty")
    void findById_WithNonExistingId_ReturnsEmpty() {
        // When
        Optional<Transfer> resultOpt = transferRepository.findById(99999L);

        // Then
        assertThat(resultOpt).isEmpty();
    }

    @Test
    @DisplayName("findPendingTransfers - With transfers before cutoff time - Returns only pending transfers")
    void findPendingTransfers_WithTransfersBeforeCutoffTime_ReturnsOnlyPendingTransfers() {
        // Given - 建立多筆不同狀態和時間的轉帳
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime elevenMinutesAgo = now.minusMinutes(11);
        LocalDateTime fiveMinutesAgo = now.minusMinutes(5);

        // 應該被查詢到：PENDING + 11分鐘前
        Transfer oldPending1 = createTransferWithTime("user_001", "user_002",
                new BigDecimal("100.00"), TransferStatus.PENDING, elevenMinutesAgo);
        transferRepository.save(oldPending1);

        Transfer oldPending2 = createTransferWithTime("user_003", "user_004",
                new BigDecimal("200.00"), TransferStatus.PENDING, elevenMinutesAgo);
        transferRepository.save(oldPending2);

        // 不應該被查詢到：PENDING 但只有 5 分鐘前（未超過 10 分鐘）
        Transfer recentPending = createTransferWithTime("user_005", "user_006",
                new BigDecimal("300.00"), TransferStatus.PENDING, fiveMinutesAgo);
        Transfer saved = transferRepository.save(recentPending);

        // 不應該被查詢到：已經是 DEBIT_PROCESSING 狀態
        Transfer processingTransfer = createTransferWithTime("user_007", "user_008",
                new BigDecimal("400.00"), TransferStatus.DEBIT_PROCESSING, elevenMinutesAgo);
        transferRepository.save(processingTransfer);

        LocalDateTime cutoffTime = now.minusMinutes(10);
        Pageable pageable = PageRequest.of(0, 100);

        // When
        List<Transfer> pendingTransfers = transferRepository.findPendingTransfers(
                TransferStatus.PENDING,
                cutoffTime,
                pageable
        );

        // Then
        assertThat(pendingTransfers).hasSize(2);
        assertThat(pendingTransfers)
                .extracting(Transfer::getFromUserId)
                .containsExactlyInAnyOrder("user_001", "user_003");

        // 驗證全都是 PENDING 狀態
        assertThat(pendingTransfers)
                .allMatch(t -> t.getStatus() == TransferStatus.PENDING);

        // 驗證全都在 cutoff time 之前
        assertThat(pendingTransfers)
                .allMatch(t -> t.getCreatedAt().isBefore(cutoffTime) ||
                              t.getCreatedAt().isEqual(cutoffTime));
    }

    @Test
    @DisplayName("findPendingTransfers - With limit - Returns limited results ordered by createdAt")
    void findPendingTransfers_WithLimit_ReturnsLimitedResults() {
        // Given - 建立 5 筆 PENDING 轉帳
        LocalDateTime elevenMinutesAgo = LocalDateTime.now().minusMinutes(15);

        for (int i = 1; i <= 5; i++) {
            Transfer transfer = createTransferWithTime(
                    "user_" + i,
                    "user_" + (i + 10),
                    new BigDecimal("100.00"),
                    TransferStatus.PENDING,
                    elevenMinutesAgo.minusMinutes(i) // 不同時間點
            );
            transferRepository.save(transfer);
            transferRepository.flush();
            log.info("{}", transfer.getId());
        }

        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(10);
        Pageable pageable = PageRequest.of(0, 3); // 只取 3 筆

        List<Transfer> all = transferRepository.findAll();

        // When
        List<Transfer> pendingTransfers = transferRepository.findPendingTransfers(
                TransferStatus.PENDING,
                cutoffTime,
                pageable
        );

        // Then
        assertThat(pendingTransfers).hasSize(3);

        // 驗證按照 createdAt 升序排列（最早的先處理）
        assertThat(pendingTransfers)
                .extracting(Transfer::getCreatedAt)
                .isSorted();
    }

    @Test
    @DisplayName("findByIdForUpdate - With existing transfer - Returns transfer with pessimistic lock")
    void findByIdForUpdate_WithExistingTransfer_ReturnsTransferWithLock() {
        // Given
        Transfer transfer = createTransfer("user_001", "user_002", new BigDecimal("500.00"));
        Transfer savedTransfer = transferRepository.save(transfer);

        // When
        Optional<Transfer> resultOpt = transferRepository.findByIdForUpdate(savedTransfer.getId());

        // Then
        assertThat(resultOpt).isPresent();
        assertThat(resultOpt.get())
                .usingRecursiveComparison()
                .isEqualTo(savedTransfer);
    }

    @Test
    @DisplayName("findByIdForUpdate - With non-existing ID - Returns empty")
    void findByIdForUpdate_WithNonExistingId_ReturnsEmpty() {
        // When
        Optional<Transfer> resultOpt = transferRepository.findByIdForUpdate(99999L);

        // Then
        assertThat(resultOpt).isEmpty();
    }

    @Test
    @DisplayName("findByFromUserIdOrToUserId - With userId as sender - Returns transfers")
    void findByFromUserIdOrToUserId_WithUserIdAsSender_ReturnsTransfers() {
        // Given
        String userId = "user_001";

        // user_001 作為付款方
        Transfer transfer1 = createTransfer(userId, "user_002", new BigDecimal("100.00"));
        transferRepository.save(transfer1);

        Transfer transfer2 = createTransfer(userId, "user_003", new BigDecimal("200.00"));
        transferRepository.save(transfer2);

        // user_001 作為收款方
        Transfer transfer3 = createTransfer("user_004", userId, new BigDecimal("300.00"));
        transferRepository.save(transfer3);

        // 其他使用者的轉帳（不應查詢到）
        Transfer transfer4 = createTransfer("user_005", "user_006", new BigDecimal("400.00"));
        transferRepository.save(transfer4);

        Pageable pageable = PageRequest.of(0, 10);

        // When
        Page<Transfer> resultPage = transferRepository.findByFromUserIdOrToUserId(
                userId,
                userId,
                pageable
        );

        // Then
        assertThat(resultPage.getContent()).hasSize(3);
        assertThat(resultPage.getTotalElements()).isEqualTo(3);

        // 驗證全都與 user_001 相關
        assertThat(resultPage.getContent())
                .allMatch(t -> t.getFromUserId().equals(userId) ||
                              t.getToUserId().equals(userId));
    }

    @Test
    @DisplayName("findByFromUserIdOrToUserId - With pagination - Returns correct page")
    void findByFromUserIdOrToUserId_WithPagination_ReturnsCorrectPage() {
        // Given
        String userId = "user_100";

        // 建立 5 筆轉帳
        for (int i = 1; i <= 5; i++) {
            Transfer transfer = createTransfer(userId, "user_" + i, new BigDecimal("100.00"));
            transferRepository.save(transfer);
        }

        Pageable firstPage = PageRequest.of(0, 2);  // 第 1 頁，每頁 2 筆
        Pageable secondPage = PageRequest.of(1, 2); // 第 2 頁，每頁 2 筆

        // When
        Page<Transfer> page1 = transferRepository.findByFromUserIdOrToUserId(
                userId, userId, firstPage);
        Page<Transfer> page2 = transferRepository.findByFromUserIdOrToUserId(
                userId, userId, secondPage);

        // Then
        assertThat(page1.getContent()).hasSize(2);
        assertThat(page1.getTotalElements()).isEqualTo(5);
        assertThat(page1.getTotalPages()).isEqualTo(3);

        assertThat(page2.getContent()).hasSize(2);
        assertThat(page2.getTotalElements()).isEqualTo(5);
    }

    @Test
    @DisplayName("save - With status update - Persists status change")
    void save_WithStatusUpdate_PersistsStatusChange() {
        // Given
        Transfer transfer = createTransfer("user_001", "user_002", new BigDecimal("100.00"));
        Transfer savedTransfer = transferRepository.save(transfer);

        // When - 更新狀態
        savedTransfer.setStatus(TransferStatus.DEBIT_PROCESSING);
        Transfer updatedTransfer = transferRepository.save(savedTransfer);

        // Then
        assertThat(updatedTransfer.getStatus()).isEqualTo(TransferStatus.DEBIT_PROCESSING);

        // 從資料庫重新查詢驗證
        Optional<Transfer> found = transferRepository.findById(savedTransfer.getId());
        assertThat(found)
                .isPresent()
                .get()
                .extracting(Transfer::getStatus)
                .isEqualTo(TransferStatus.DEBIT_PROCESSING);
    }

    @Test
    @DisplayName("save - With completedAt timestamp - Persists completion time")
    void save_WithCompletedAtTimestamp_PersistsCompletionTime() {
        // Given
        Transfer transfer = createTransfer("user_001", "user_002", new BigDecimal("100.00"));
        Transfer savedTransfer = transferRepository.save(transfer);

        LocalDateTime completionTime = LocalDateTime.now();

        // When
        savedTransfer.setStatus(TransferStatus.COMPLETED);
        savedTransfer.setCompletedAt(completionTime);
        Transfer completedTransfer = transferRepository.save(savedTransfer);

        // Then
        assertThat(completedTransfer.getCompletedAt()).isNotNull();
        assertThat(completedTransfer.getCompletedAt()).isEqualTo(completionTime);

        // 從資料庫重新查詢驗證
        Optional<Transfer> found = transferRepository.findById(savedTransfer.getId());
        assertThat(found)
                .isPresent()
                .get()
                .satisfies(t -> {
                    assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
                    assertThat(t.getCompletedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("save - With cancelledAt timestamp - Persists cancellation time")
    void save_WithCancelledAtTimestamp_PersistsCancellationTime() {
        // Given
        Transfer transfer = createTransfer("user_001", "user_002", new BigDecimal("100.00"));
        Transfer savedTransfer = transferRepository.save(transfer);

        LocalDateTime cancellationTime = LocalDateTime.now();

        // When
        savedTransfer.setStatus(TransferStatus.CANCELLED);
        savedTransfer.setCancelledAt(cancellationTime);
        Transfer cancelledTransfer = transferRepository.save(savedTransfer);

        // Then
        assertThat(cancelledTransfer.getCancelledAt()).isNotNull();
        assertThat(cancelledTransfer.getCancelledAt()).isEqualTo(cancellationTime);
    }

    @Test
    @DisplayName("save - With failure reason - Persists failure message")
    void save_WithFailureReason_PersistsFailureMessage() {
        // Given
        Transfer transfer = createTransfer("user_001", "user_002", new BigDecimal("100.00"));
        Transfer savedTransfer = transferRepository.save(transfer);

        String failureReason = "Insufficient balance";

        // When
        savedTransfer.setStatus(TransferStatus.DEBIT_FAILED);
        savedTransfer.setFailureReason(failureReason);
        Transfer failedTransfer = transferRepository.save(savedTransfer);

        // Then
        assertThat(failedTransfer.getFailureReason()).isEqualTo(failureReason);

        // 從資料庫重新查詢驗證
        Optional<Transfer> found = transferRepository.findById(savedTransfer.getId());
        assertThat(found)
                .isPresent()
                .get()
                .satisfies(t -> {
                    assertThat(t.getStatus()).isEqualTo(TransferStatus.DEBIT_FAILED);
                    assertThat(t.getFailureReason()).isEqualTo(failureReason);
                });
    }

    // ==================== Helper Methods ====================

    /**
     * 建立基本的 Transfer 物件（PENDING 狀態，當前時間）
     */
    private Transfer createTransfer(String fromUserId, String toUserId, BigDecimal amount) {
        return Transfer.builder()
                .fromUserId(fromUserId)
                .toUserId(toUserId)
                .amount(amount)
                .status(TransferStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * 建立指定時間和狀態的 Transfer 物件
     */
    private Transfer createTransferWithTime(String fromUserId, String toUserId,
                                           BigDecimal amount, TransferStatus status,
                                           LocalDateTime createdAt) {
        return Transfer.builder()
                .fromUserId(fromUserId)
                .toUserId(toUserId)
                .amount(amount)
                .status(status)
                .createdAt(createdAt)
                .build();
    }
}
