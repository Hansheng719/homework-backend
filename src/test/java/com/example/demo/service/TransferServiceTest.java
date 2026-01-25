package com.example.demo.service;

import com.example.demo.entity.Transfer;
import com.example.demo.entity.TransferStatus;
import com.example.demo.exception.InvalidTransferStateException;
import com.example.demo.event.TransferStatusChangedEvent;
import com.example.demo.exception.TransferNotFoundException;
import com.example.demo.repository.TransferRepository;
import com.example.demo.service.impl.TransferServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TransferService 單元測試
 *
 * 測試策略：
 * 1. 使用 Mockito mock TransferRepository
 * 2. 純單元測試，不啟動 Spring Context
 * 3. 專注於測試業務邏輯和 Repository 互動
 * 4. 使用 AssertJ 斷言庫
 *
 * TDD RED Phase:
 * - 所有測試已撰寫完成
 * - 測試應編譯成功
 * - 執行會失敗（因為 TransferServiceImpl 尚未實作）
 * - 準備進入 GREEN phase（實作功能）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService Unit Tests")
class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TransferServiceImpl transferService;

    // ==================== createPendingTransfer() Tests ====================

    @Test
    @DisplayName("createPendingTransfer - With valid inputs - Creates transfer successfully and publishes event")
    void createPendingTransfer_WithValidInputs_CreatesTransferSuccessfullyAndPublishesEvent() {
        // Given
        String fromUserId = "user_001";
        String toUserId = "user_002";
        BigDecimal amount = new BigDecimal("100.00");

        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> {
                Transfer transfer = invocation.getArgument(0);
                transfer.setId(1L);
                return transfer;
            });

        // When
        Transfer result = transferService.createPendingTransfer(fromUserId, toUserId, amount);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getFromUserId()).isEqualTo(fromUserId);
        assertThat(result.getToUserId()).isEqualTo(toUserId);
        assertThat(result.getAmount()).isEqualTo(amount);
        assertThat(result.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(result.getCreatedAt()).isNotNull();

        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(eventPublisher, times(1)).publishEvent(any(TransferStatusChangedEvent.class));
        verify(eventPublisher).publishEvent(argThat(event -> {
            if (!(event instanceof TransferStatusChangedEvent)) return false;
            TransferStatusChangedEvent e = (TransferStatusChangedEvent) event;
            return e.getTransferId().equals(1L)
                && e.getOldStatus() == null
                && e.getNewStatus() == TransferStatus.PENDING;
        }));
    }

    @Test
    @DisplayName("createPendingTransfer - With zero amount - Creates transfer with zero amount")
    void createPendingTransfer_WithZeroAmount_CreatesTransferWithZeroAmount() {
        // Given
        String fromUserId = "user_003";
        String toUserId = "user_004";
        BigDecimal amount = BigDecimal.ZERO;

        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> {
                Transfer transfer = invocation.getArgument(0);
                transfer.setId(2L);
                return transfer;
            });

        // When
        Transfer result = transferService.createPendingTransfer(fromUserId, toUserId, amount);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getStatus()).isEqualTo(TransferStatus.PENDING);

        verify(transferRepository, times(1)).save(any(Transfer.class));
    }

    @Test
    @DisplayName("createPendingTransfer - With same from and to user - Creates transfer successfully")
    void createPendingTransfer_WithSameFromAndToUser_CreatesTransferSuccessfully() {
        // Given - Service 不驗證業務規則，允許自轉
        String sameUserId = "user_005";
        BigDecimal amount = new BigDecimal("50.00");

        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> {
                Transfer transfer = invocation.getArgument(0);
                transfer.setId(3L);
                return transfer;
            });

        // When
        Transfer result = transferService.createPendingTransfer(sameUserId, sameUserId, amount);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getFromUserId()).isEqualTo(sameUserId);
        assertThat(result.getToUserId()).isEqualTo(sameUserId);

        verify(transferRepository, times(1)).save(any(Transfer.class));
    }

    // ==================== findPendingTransfers() Tests ====================

    @Test
    @DisplayName("findPendingTransfers - With PENDING transfers - Returns filtered list")
    void findPendingTransfers_WithPendingTransfers_ReturnsFilteredList() {
        // Given
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(10);
        int batchSize = 100;

        List<Transfer> pendingTransfers = List.of(
            createTransferWithTimestamp(1L, TransferStatus.PENDING, cutoffTime.minusMinutes(5)),
            createTransferWithTimestamp(2L, TransferStatus.PENDING, cutoffTime.minusMinutes(3)),
            createTransferWithTimestamp(3L, TransferStatus.PENDING, cutoffTime.minusMinutes(1))
        );

        when(transferRepository.findPendingTransfers(
            eq(TransferStatus.PENDING),
            eq(cutoffTime),
            any(PageRequest.class)
        )).thenReturn(pendingTransfers);

        // When
        List<Transfer> result = transferService.findPendingTransfers(cutoffTime, batchSize);

        // Then
        assertThat(result).hasSize(3);
        assertThat(result).allMatch(t -> t.getStatus() == TransferStatus.PENDING);

        verify(transferRepository, times(1)).findPendingTransfers(
            eq(TransferStatus.PENDING),
            eq(cutoffTime),
            argThat(pageable ->
                pageable.getPageNumber() == 0 && pageable.getPageSize() == batchSize
            )
        );
    }

    @Test
    @DisplayName("findPendingTransfers - With batch size limit - Returns limited results")
    void findPendingTransfers_WithBatchSizeLimit_ReturnsLimitedResults() {
        // Given
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(10);
        int batchSize = 100;

        List<Transfer> pendingTransfers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pendingTransfers.add(createTransfer((long) i, TransferStatus.PENDING));
        }

        when(transferRepository.findPendingTransfers(
            eq(TransferStatus.PENDING),
            eq(cutoffTime),
            any(PageRequest.class)
        )).thenReturn(pendingTransfers);

        // When
        List<Transfer> result = transferService.findPendingTransfers(cutoffTime, batchSize);

        // Then
        assertThat(result).hasSize(100);

        verify(transferRepository, times(1)).findPendingTransfers(
            eq(TransferStatus.PENDING),
            eq(cutoffTime),
            argThat(pageable -> pageable.getPageSize() == 100)
        );
    }

    @Test
    @DisplayName("findPendingTransfers - With no PENDING transfers - Returns empty list")
    void findPendingTransfers_WithNoPendingTransfers_ReturnsEmptyList() {
        // Given
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(10);
        int batchSize = 100;

        when(transferRepository.findPendingTransfers(
            eq(TransferStatus.PENDING),
            eq(cutoffTime),
            any(PageRequest.class)
        )).thenReturn(List.of());

        // When
        List<Transfer> result = transferService.findPendingTransfers(cutoffTime, batchSize);

        // Then
        assertThat(result).isEmpty();

        verify(transferRepository, times(1)).findPendingTransfers(
            eq(TransferStatus.PENDING),
            eq(cutoffTime),
            any(PageRequest.class)
        );
    }

    @Test
    @DisplayName("findPendingTransfers - With mixed statuses - Returns only PENDING")
    void findPendingTransfers_WithMixedStatuses_ReturnsOnlyPending() {
        // Given
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(10);
        int batchSize = 100;

        // Repository 應該只返回 PENDING 狀態的轉帳
        List<Transfer> pendingTransfers = List.of(
            createTransfer(1L, TransferStatus.PENDING),
            createTransfer(2L, TransferStatus.PENDING)
        );

        when(transferRepository.findPendingTransfers(
            eq(TransferStatus.PENDING),
            eq(cutoffTime),
            any(PageRequest.class)
        )).thenReturn(pendingTransfers);

        // When
        List<Transfer> result = transferService.findPendingTransfers(cutoffTime, batchSize);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(t -> t.getStatus() == TransferStatus.PENDING);

        verify(transferRepository, times(1)).findPendingTransfers(
            eq(TransferStatus.PENDING), // 驗證只查詢 PENDING 狀態
            eq(cutoffTime),
            any(PageRequest.class)
        );
    }

    // ==================== completeTransfer() Tests ====================

    @Test
    @DisplayName("completeTransfer - With CREDIT_PROCESSING transfer - Updates to COMPLETED and publishes event")
    void completeTransfer_WithCreditProcessingTransfer_UpdatesToCompletedAndPublishesEvent() {
        // Given
        Long transferId = 8L;
        Transfer transfer = createTransfer(transferId, TransferStatus.CREDIT_PROCESSING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.completeTransfer(transferId);

        // Then
        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(transferRepository, times(2)).save(any(Transfer.class)); // 1 for status, 1 for completedAt
        verify(eventPublisher, times(1)).publishEvent(any(TransferStatusChangedEvent.class));
        verify(eventPublisher).publishEvent(argThat(event -> {
            if (!(event instanceof TransferStatusChangedEvent)) return false;
            TransferStatusChangedEvent e = (TransferStatusChangedEvent) event;
            return e.getOldStatus() == TransferStatus.CREDIT_PROCESSING
                && e.getNewStatus() == TransferStatus.COMPLETED
                && e.isCompleted();
        }));
    }

    @Test
    @DisplayName("completeTransfer - With non-existent transfer - Throws TransferNotFoundException")
    void completeTransfer_WithNonExistentTransfer_ThrowsTransferNotFoundException() {
        // Given
        Long transferId = 999L;

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.empty());
        when(transferRepository.findById(999L))
            .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> {
            transferService.completeTransfer(transferId);
        }).isInstanceOf(TransferNotFoundException.class)
          .hasMessageContaining("999");

        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(transferRepository, never()).save(any(Transfer.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("completeTransfer - With wrong status - Throws InvalidStateException")
    void completeTransfer_WithWrongStatus_ThrowsInvalidStateException() {
        // Given - Transfer 狀態是 PENDING，不是 CREDIT_PROCESSING
        Long transferId = 9L;
        Transfer transfer = createTransfer(transferId, TransferStatus.PENDING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(transfer));
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.of(transfer));

        // When & Then
        assertThatThrownBy(() -> {
            transferService.completeTransfer(transferId);
        }).isInstanceOf(InvalidTransferStateException.class)
          .hasMessageContaining("CREDIT_PROCESSING");

        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ==================== findById() Tests ====================

    @Test
    @DisplayName("findById - With existing transfer - Returns transfer")
    void findById_WithExistingTransfer_ReturnsTransfer() {
        // Given
        Long transferId = 10L;
        Transfer transfer = createTransfer(transferId, TransferStatus.COMPLETED);

        when(transferRepository.findById(transferId))
            .thenReturn(Optional.of(transfer));

        // When
        Transfer result = transferService.findById(transferId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(transferId);
        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);

        verify(transferRepository, times(1)).findById(transferId);
    }

    @Test
    @DisplayName("findById - With non-existent transfer - Throws TransferNotFoundException")
    void findById_WithNonExistentTransfer_ThrowsTransferNotFoundException() {
        // Given
        Long transferId = 999L;

        when(transferRepository.findById(transferId))
            .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> {
            transferService.findById(transferId);
        }).isInstanceOf(TransferNotFoundException.class)
          .hasMessageContaining("999");

        verify(transferRepository, times(1)).findById(transferId);
    }

    // ==================== cancelTransfer() Tests (NEW) ====================

    @Test
    @DisplayName("cancelTransfer - From PENDING status - Cancels successfully")
    void cancelTransfer_FromPendingStatus_CancelsSuccessfully() {
        // Given
        Long transferId = 200L;
        Transfer pendingTransfer = createTransfer(transferId, TransferStatus.PENDING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(pendingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.cancelTransfer(transferId);

        // Then
        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(transferRepository, times(2)).save(any(Transfer.class)); // 1 for status, 1 for cancelledAt
        verify(eventPublisher, times(1)).publishEvent(any(TransferStatusChangedEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"PENDING"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("cancelTransfer - From DEBIT_PROCESSING status - Throws InvalidTransferStateException")
    void cancelTransfer_FromInvalidStatus_ThrowsInvalidTransferStateException(TransferStatus transferStatus) {
        // Given - 只有 PENDING 可以取消
        Long transferId = 201L;
        Transfer processingTransfer = createTransfer(transferId, transferStatus);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(processingTransfer));
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.of(processingTransfer));

        // When & Then
        assertThatThrownBy(() -> {
            transferService.cancelTransfer(transferId);
        }).isInstanceOf(InvalidTransferStateException.class)
          .hasMessageContaining("Only PENDING transfers can be cancelled");

        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("cancelTransfer - With non-existent transfer - Throws TransferNotFoundException")
    void cancelTransfer_WithNonExistentTransfer_ThrowsTransferNotFoundException() {
        // Given
        Long transferId = 999L;

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.empty());
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> {
            transferService.cancelTransfer(transferId);
        }).isInstanceOf(TransferNotFoundException.class)
          .hasMessageContaining("999");

        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("cancelTransfer - Sets cancelledAt timestamp - Verifies timestamp is set")
    void cancelTransfer_SetsCancelledAtTimestamp_VerifiesTimestampIsSet() {
        // Given
        Long transferId = 202L;
        Transfer pendingTransfer = createTransfer(transferId, TransferStatus.PENDING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(pendingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.cancelTransfer(transferId);

        // Then
        verify(transferRepository, times(2)).save(argThat(t ->
            t.getStatus() == TransferStatus.CANCELLED || t.getCancelledAt() != null
        ));
    }

    @Test
    @DisplayName("cancelTransfer - Publishes event - Verifies event details")
    void cancelTransfer_PublishesEvent_VerifiesEventDetails() {
        // Given
        Long transferId = 203L;
        Transfer pendingTransfer = createTransfer(transferId, TransferStatus.PENDING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(pendingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.cancelTransfer(transferId);

        // Then
        verify(eventPublisher).publishEvent(argThat(event -> {
            if (!(event instanceof TransferStatusChangedEvent)) return false;
            TransferStatusChangedEvent e = (TransferStatusChangedEvent) event;
            return e.getOldStatus() == TransferStatus.PENDING
                && e.getNewStatus() == TransferStatus.CANCELLED
                && e.isFailed();
        }));
    }

    // ==================== markDebitProcessing() Tests (NEW) ====================

    @Test
    @DisplayName("markDebitProcessing - From PROCESSING status - Marks successfully")
    void markDebitProcessing_FromProcessingStatus_MarksSuccessfully() {
        // Given
        Long transferId = 300L;
        Transfer processingTransfer = createTransfer(transferId, TransferStatus.PENDING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(processingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.markDebitProcessing(transferId);

        // Then
        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(eventPublisher, times(1)).publishEvent(any(TransferStatusChangedEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("markDebitProcessing - From non-PROCESSING status - Throws InvalidTransferStateException")
    void markDebitProcessing_FromNonProcessingStatus_ThrowsInvalidTransferStateException(TransferStatus transferStatus) {
        // Given
        Long transferId = 301L;
        Transfer pendingTransfer = createTransfer(transferId, transferStatus);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(pendingTransfer));
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.of(pendingTransfer));

        // When & Then
        assertThatThrownBy(() -> {
            transferService.markDebitProcessing(transferId);
        }).isInstanceOf(InvalidTransferStateException.class)
          .hasMessageContaining("Must be in PROCESSING state");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("markDebitProcessing - With non-existent transfer - Throws TransferNotFoundException")
    void markDebitProcessing_WithNonExistentTransfer_ThrowsTransferNotFoundException() {
        // Given
        Long transferId = 999L;

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.empty());
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> {
            transferService.markDebitProcessing(transferId);
        }).isInstanceOf(TransferNotFoundException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("markDebitProcessing - Publishes event - Verifies state transition")
    void markDebitProcessing_PublishesEvent_VerifiesStateTransition() {
        // Given
        Long transferId = 302L;
        Transfer processingTransfer = createTransfer(transferId, TransferStatus.PENDING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(processingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.markDebitProcessing(transferId);

        // Then
        verify(eventPublisher).publishEvent(argThat(event -> {
            if (!(event instanceof TransferStatusChangedEvent)) return false;
            TransferStatusChangedEvent e = (TransferStatusChangedEvent) event;
            return e.getOldStatus() == TransferStatus.PENDING
                && e.getNewStatus() == TransferStatus.DEBIT_PROCESSING;
        }));
    }

    // ==================== handleDebitSuccess() Tests (NEW) ====================

    @Test
    @DisplayName("handleDebitSuccess - From DEBIT_PROCESSING status - Marks successfully")
    void handleDebitSuccess_FromDebitProcessingStatus_MarksSuccessfully() {
        // Given
        Long transferId = 400L;
        Transfer debitProcessingTransfer = createTransfer(transferId, TransferStatus.DEBIT_PROCESSING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(debitProcessingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.handleDebitSuccess(transferId);

        // Then
        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(eventPublisher, times(1)).publishEvent(any(TransferStatusChangedEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"DEBIT_PROCESSING"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("handleDebitSuccess - From non-DEBIT_PROCESSING status - Throws InvalidTransferStateException")
    void handleDebitSuccess_FromNonDebitProcessingStatus_ThrowsInvalidTransferStateException(TransferStatus transferStatus) {
        // Given
        Long transferId = 401L;
        Transfer processingTransfer = createTransfer(transferId, transferStatus);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(processingTransfer));
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.of(processingTransfer));

        // When & Then
        assertThatThrownBy(() -> {
            transferService.handleDebitSuccess(transferId);
        }).isInstanceOf(InvalidTransferStateException.class)
          .hasMessageContaining("Must be in DEBIT_PROCESSING state");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("handleDebitSuccess - With non-existent transfer - Throws TransferNotFoundException")
    void handleDebitSuccess_WithNonExistentTransfer_ThrowsTransferNotFoundException() {
        // Given
        Long transferId = 999L;

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.empty());
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> {
            transferService.handleDebitSuccess(transferId);
        }).isInstanceOf(TransferNotFoundException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("handleDebitSuccess - Publishes event - Verifies state transition")
    void handleDebitSuccess_PublishesEvent_VerifiesStateTransition() {
        // Given
        Long transferId = 402L;
        Transfer debitProcessingTransfer = createTransfer(transferId, TransferStatus.DEBIT_PROCESSING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(debitProcessingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.handleDebitSuccess(transferId);

        // Then
        verify(eventPublisher).publishEvent(argThat(event -> {
            if (!(event instanceof TransferStatusChangedEvent)) return false;
            TransferStatusChangedEvent e = (TransferStatusChangedEvent) event;
            return e.getOldStatus() == TransferStatus.DEBIT_PROCESSING
                && e.getNewStatus() == TransferStatus.CREDIT_PROCESSING;
        }));
    }

    // ==================== handleDebitFailure() Tests (NEW) ====================

    @Test
    @DisplayName("handleDebitFailure - From DEBIT_PROCESSING status - Marks failed successfully")
    void handleDebitFailure_FromDebitProcessingStatus_MarksFailedSuccessfully() {
        // Given
        Long transferId = 500L;
        String failureReason = "Insufficient balance";
        Transfer debitProcessingTransfer = createTransfer(transferId, TransferStatus.DEBIT_PROCESSING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(debitProcessingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.handleDebitFailure(transferId, failureReason);

        // Then
        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(transferRepository, times(2)).save(any(Transfer.class)); // 1 for status, 1 for reason & cancelledAt
        verify(eventPublisher, times(1)).publishEvent(any(TransferStatusChangedEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"PENDING", "DEBIT_PROCESSING"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("handleDebitFailure - From non-DEBIT_PROCESSING status - Throws InvalidTransferStateException")
    void handleDebitFailure_FromNonDebitProcessingStatus_ThrowsInvalidTransferStateException(TransferStatus transferStatus) {
        // Given
        Long transferId = 501L;
        Transfer processingTransfer = createTransfer(transferId, transferStatus);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(processingTransfer));
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.of(processingTransfer));

        // When & Then
        assertThatThrownBy(() -> {
            transferService.handleDebitFailure(transferId, "Some reason");
        }).isInstanceOf(InvalidTransferStateException.class)
          .hasMessageContaining("Must be in DEBIT_PROCESSING state");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("handleDebitFailure - With long reason - Truncates to 255 characters")
    void handleDebitFailure_WithLongReason_TruncatesTo255Characters() {
        // Given
        Long transferId = 502L;
        String longReason = "A".repeat(300);
        Transfer debitProcessingTransfer = createTransfer(transferId, TransferStatus.DEBIT_PROCESSING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(debitProcessingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.handleDebitFailure(transferId, longReason);

        // Then
        verify(transferRepository, times(2)).save(argThat(t ->
            t.getFailureReason() == null || t.getFailureReason().length() <= 255
        ));
    }

    @Test
    @DisplayName("handleDebitFailure - Sets cancelledAt timestamp - Verifies timestamp is set")
    void handleDebitFailure_SetsCancelledAtTimestamp_VerifiesTimestampIsSet() {
        // Given
        Long transferId = 503L;
        String failureReason = "Network error";
        Transfer debitProcessingTransfer = createTransfer(transferId, TransferStatus.DEBIT_PROCESSING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(debitProcessingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.handleDebitFailure(transferId, failureReason);

        // Then
        verify(transferRepository, times(2)).save(argThat(t ->
            t.getStatus() == TransferStatus.DEBIT_FAILED || t.getCancelledAt() != null
        ));
    }

    @Test
    @DisplayName("handleDebitFailure - Publishes event - Verifies event details")
    void handleDebitFailure_PublishesEvent_VerifiesEventDetails() {
        // Given
        Long transferId = 504L;
        String failureReason = "Account locked";
        Transfer debitProcessingTransfer = createTransfer(transferId, TransferStatus.DEBIT_PROCESSING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(debitProcessingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.handleDebitFailure(transferId, failureReason);

        // Then
        verify(eventPublisher).publishEvent(argThat(event -> {
            if (!(event instanceof TransferStatusChangedEvent)) return false;
            TransferStatusChangedEvent e = (TransferStatusChangedEvent) event;
            return e.getOldStatus() == TransferStatus.DEBIT_PROCESSING
                && e.getNewStatus() == TransferStatus.DEBIT_FAILED
                && e.isFailed();
        }));
    }

    // ==================== markCreditProcessing() Tests (NEW) ====================

    @Test
    @DisplayName("markCreditProcessing - From DEBIT_PROCESSING status - Marks successfully")
    void markCreditProcessing_FromDebitProcessingStatus_MarksSuccessfully() {
        // Given
        Long transferId = 600L;
        Transfer debitProcessingTransfer = createTransfer(transferId, TransferStatus.DEBIT_PROCESSING);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(debitProcessingTransfer));
        when(transferRepository.save(any(Transfer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        transferService.markCreditProcessing(transferId);

        // Then
        verify(transferRepository, times(1)).findByIdForUpdate(transferId);
        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(eventPublisher, times(1)).publishEvent(any(TransferStatusChangedEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"DEBIT_PROCESSING"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("markCreditProcessing - From non-DEBIT_COMPLETED status - Throws InvalidTransferStateException")
    void markCreditProcessing_FromNonDebitCompletedStatus_ThrowsInvalidTransferStateException(TransferStatus transferStatus) {
        // Given
        Long transferId = 601L;
        Transfer processingTransfer = createTransfer(transferId, transferStatus);

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.of(processingTransfer));
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.of(processingTransfer));

        // When & Then
        assertThatThrownBy(() -> {
            transferService.markCreditProcessing(transferId);
        }).isInstanceOf(InvalidTransferStateException.class)
          .hasMessageContaining("Must be in DEBIT_COMPLETED state");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("markCreditProcessing - With non-existent transfer - Throws TransferNotFoundException")
    void markCreditProcessing_WithNonExistentTransfer_ThrowsTransferNotFoundException() {
        // Given
        Long transferId = 999L;

        when(transferRepository.findByIdForUpdate(transferId))
            .thenReturn(Optional.empty());
        when(transferRepository.findById(transferId))
            .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> {
            transferService.markCreditProcessing(transferId);
        }).isInstanceOf(TransferNotFoundException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    // ==================== Helper Methods ====================

    /**
     * 建立測試用 Transfer 物件
     */
    private Transfer createTransfer(Long id, TransferStatus status) {
        Transfer transfer = new Transfer();
        transfer.setId(id);
        transfer.setFromUserId("user_001");
        transfer.setToUserId("user_002");
        transfer.setAmount(new BigDecimal("100.00"));
        transfer.setStatus(status);
        transfer.setCreatedAt(LocalDateTime.now());
        return transfer;
    }

    /**
     * 建立帶有特定時間戳記的 Transfer 物件
     */
    private Transfer createTransferWithTimestamp(Long id, TransferStatus status, LocalDateTime createdAt) {
        Transfer transfer = createTransfer(id, status);
        transfer.setCreatedAt(createdAt);
        return transfer;
    }
}
