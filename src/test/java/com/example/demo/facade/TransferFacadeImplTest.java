package com.example.demo.facade;

import com.example.demo.entity.Transfer;
import com.example.demo.entity.TransferStatus;
import com.example.demo.facade.impl.TransferFacadeImpl;
import com.example.demo.mq.producer.BalanceChangeProducer;
import com.example.demo.service.BalanceService;
import com.example.demo.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TransferFacadeImpl 單元測試
 *
 * 測試策略：
 * 1. 使用 Mockito mock 所有依賴（TransferService, BalanceService, BalanceChangeProducer）
 * 2. 純單元測試，不啟動 Spring Context
 * 3. 專注於測試編排邏輯和錯誤處理
 * 4. 使用 AssertJ 斷言庫
 *
 * 針對新增的重試排程器方法：
 * - processDebitProcessingTransfers(): 測試扣款重試邏輯
 * - processCreditProcessingTransfers(): 測試加帳重試邏輯
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransferFacadeImpl Unit Tests - Retry Schedulers")
class TransferFacadeImplTest {

    @Mock
    private TransferService transferService;

    @Mock
    private BalanceService balanceService;

    @Mock
    private BalanceChangeProducer balanceChangeProducer;

    @InjectMocks
    private TransferFacadeImpl transferFacade;

    // ==================== processDebitProcessingTransfers() Tests ====================

    @Test
    @DisplayName("processDebitProcessingTransfers - With stuck transfers - Resends MQ and updates timestamps")
    void processDebitProcessingTransfers_WithStuckTransfers_ResendsMQAndUpdatesTimestamps() {
        // Given
        int delaySeconds = 600;
        int batchSize = 100;
        LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(delaySeconds);

        Transfer transfer1 = createTransfer(1L, TransferStatus.DEBIT_PROCESSING, "user_001", "user_002");
        Transfer transfer2 = createTransfer(2L, TransferStatus.DEBIT_PROCESSING, "user_003", "user_004");
        List<Transfer> stuckTransfers = List.of(transfer1, transfer2);

        when(transferService.findDebitProcessingTransfers(any(LocalDateTime.class), eq(batchSize)))
                .thenReturn(stuckTransfers);
        doNothing().when(balanceChangeProducer).sendDebitRequest(anyLong(), anyString(), any(BigDecimal.class));
        doNothing().when(transferService).updateTransferTimestamp(anyLong());

        // When
        int result = transferFacade.processDebitProcessingTransfers(delaySeconds, batchSize);

        // Then
        assertThat(result).isEqualTo(2);
        verify(transferService).findDebitProcessingTransfers(any(LocalDateTime.class), eq(batchSize));

        // Verify MQ messages sent
        verify(balanceChangeProducer).sendDebitRequest(1L, "user_001", new BigDecimal("100.00"));
        verify(balanceChangeProducer).sendDebitRequest(2L, "user_003", new BigDecimal("100.00"));

        // Verify timestamps updated
        verify(transferService).updateTransferTimestamp(1L);
        verify(transferService).updateTransferTimestamp(2L);
    }

    @Test
    @DisplayName("processDebitProcessingTransfers - With no stuck transfers - Returns zero")
    void processDebitProcessingTransfers_WithNoStuckTransfers_ReturnsZero() {
        // Given
        int delaySeconds = 600;
        int batchSize = 100;

        when(transferService.findDebitProcessingTransfers(any(LocalDateTime.class), eq(batchSize)))
                .thenReturn(List.of());

        // When
        int result = transferFacade.processDebitProcessingTransfers(delaySeconds, batchSize);

        // Then
        assertThat(result).isZero();
        verify(transferService).findDebitProcessingTransfers(any(LocalDateTime.class), eq(batchSize));
        verify(balanceChangeProducer, never()).sendDebitRequest(anyLong(), anyString(), any());
        verify(transferService, never()).updateTransferTimestamp(anyLong());
    }

    @Test
    @DisplayName("processDebitProcessingTransfers - With MQ send failure - Continues processing other transfers")
    void processDebitProcessingTransfers_WithMQSendFailure_ContinuesProcessingOtherTransfers() {
        // Given
        int delaySeconds = 600;
        int batchSize = 100;

        Transfer transfer1 = createTransfer(1L, TransferStatus.DEBIT_PROCESSING, "user_001", "user_002");
        Transfer transfer2 = createTransfer(2L, TransferStatus.DEBIT_PROCESSING, "user_003", "user_004");
        List<Transfer> stuckTransfers = List.of(transfer1, transfer2);

        when(transferService.findDebitProcessingTransfers(any(LocalDateTime.class), eq(batchSize)))
                .thenReturn(stuckTransfers);

        // First transfer fails to send MQ
        doThrow(new RuntimeException("MQ error"))
                .when(balanceChangeProducer).sendDebitRequest(eq(1L), anyString(), any());
        // Second transfer succeeds
        doNothing()
                .when(balanceChangeProducer).sendDebitRequest(eq(2L), anyString(), any());

        // When
        int result = transferFacade.processDebitProcessingTransfers(delaySeconds, batchSize);

        // Then
        assertThat(result).isEqualTo(1); // Only transfer2 succeeded
        verify(balanceChangeProducer).sendDebitRequest(eq(1L), anyString(), any());
        verify(balanceChangeProducer).sendDebitRequest(eq(2L), anyString(), any());

        // Timestamp updated only for successful transfer
        verify(transferService, never()).updateTransferTimestamp(1L);
        verify(transferService).updateTransferTimestamp(2L);
    }

    @Test
    @DisplayName("processDebitProcessingTransfers - With timestamp update failure - Continues processing")
    void processDebitProcessingTransfers_WithTimestampUpdateFailure_ContinuesProcessing() {
        // Given
        int delaySeconds = 600;
        int batchSize = 100;

        Transfer transfer1 = createTransfer(1L, TransferStatus.DEBIT_PROCESSING, "user_001", "user_002");
        Transfer transfer2 = createTransfer(2L, TransferStatus.DEBIT_PROCESSING, "user_003", "user_004");
        List<Transfer> stuckTransfers = List.of(transfer1, transfer2);

        when(transferService.findDebitProcessingTransfers(any(LocalDateTime.class), eq(batchSize)))
                .thenReturn(stuckTransfers);
        doNothing().when(balanceChangeProducer).sendDebitRequest(anyLong(), anyString(), any());

        // First transfer timestamp update fails
        doThrow(new RuntimeException("DB error"))
                .when(transferService).updateTransferTimestamp(1L);
        // Second transfer succeeds
        doNothing()
                .when(transferService).updateTransferTimestamp(2L);

        // When
        int result = transferFacade.processDebitProcessingTransfers(delaySeconds, batchSize);

        // Then
        assertThat(result).isEqualTo(1); // Only transfer2 fully succeeded
        verify(balanceChangeProducer, times(2)).sendDebitRequest(anyLong(), anyString(), any());
        verify(transferService).updateTransferTimestamp(1L);
        verify(transferService).updateTransferTimestamp(2L);
    }

    // ==================== processCreditProcessingTransfers() Tests ====================

    @Test
    @DisplayName("processCreditProcessingTransfers - With stuck transfers - Resends MQ and updates timestamps")
    void processCreditProcessingTransfers_WithStuckTransfers_ResendsMQAndUpdatesTimestamps() {
        // Given
        int delaySeconds = 600;
        int batchSize = 100;

        Transfer transfer1 = createTransfer(1L, TransferStatus.CREDIT_PROCESSING, "user_001", "user_002");
        Transfer transfer2 = createTransfer(2L, TransferStatus.CREDIT_PROCESSING, "user_003", "user_004");
        List<Transfer> stuckTransfers = List.of(transfer1, transfer2);

        when(transferService.findCreditProcessingTransfers(any(LocalDateTime.class), eq(batchSize)))
                .thenReturn(stuckTransfers);
        doNothing().when(balanceChangeProducer).sendCreditRequest(anyLong(), anyString(), any(BigDecimal.class));
        doNothing().when(transferService).updateTransferTimestamp(anyLong());

        // When
        int result = transferFacade.processCreditProcessingTransfers(delaySeconds, batchSize);

        // Then
        assertThat(result).isEqualTo(2);
        verify(transferService).findCreditProcessingTransfers(any(LocalDateTime.class), eq(batchSize));

        // Verify MQ messages sent (note: credit uses toUserId, not fromUserId)
        verify(balanceChangeProducer).sendCreditRequest(1L, "user_002", new BigDecimal("100.00"));
        verify(balanceChangeProducer).sendCreditRequest(2L, "user_004", new BigDecimal("100.00"));

        // Verify timestamps updated
        verify(transferService).updateTransferTimestamp(1L);
        verify(transferService).updateTransferTimestamp(2L);
    }

    @Test
    @DisplayName("processCreditProcessingTransfers - With no stuck transfers - Returns zero")
    void processCreditProcessingTransfers_WithNoStuckTransfers_ReturnsZero() {
        // Given
        int delaySeconds = 600;
        int batchSize = 100;

        when(transferService.findCreditProcessingTransfers(any(LocalDateTime.class), eq(batchSize)))
                .thenReturn(List.of());

        // When
        int result = transferFacade.processCreditProcessingTransfers(delaySeconds, batchSize);

        // Then
        assertThat(result).isZero();
        verify(transferService).findCreditProcessingTransfers(any(LocalDateTime.class), eq(batchSize));
        verify(balanceChangeProducer, never()).sendCreditRequest(anyLong(), anyString(), any());
        verify(transferService, never()).updateTransferTimestamp(anyLong());
    }

    @Test
    @DisplayName("processCreditProcessingTransfers - With MQ send failure - Continues processing other transfers")
    void processCreditProcessingTransfers_WithMQSendFailure_ContinuesProcessingOtherTransfers() {
        // Given
        int delaySeconds = 600;
        int batchSize = 100;

        Transfer transfer1 = createTransfer(1L, TransferStatus.CREDIT_PROCESSING, "user_001", "user_002");
        Transfer transfer2 = createTransfer(2L, TransferStatus.CREDIT_PROCESSING, "user_003", "user_004");
        List<Transfer> stuckTransfers = List.of(transfer1, transfer2);

        when(transferService.findCreditProcessingTransfers(any(LocalDateTime.class), eq(batchSize)))
                .thenReturn(stuckTransfers);

        // First transfer fails to send MQ
        doThrow(new RuntimeException("MQ error"))
                .when(balanceChangeProducer).sendCreditRequest(eq(1L), anyString(), any());
        // Second transfer succeeds
        doNothing()
                .when(balanceChangeProducer).sendCreditRequest(eq(2L), anyString(), any());

        // When
        int result = transferFacade.processCreditProcessingTransfers(delaySeconds, batchSize);

        // Then
        assertThat(result).isEqualTo(1); // Only transfer2 succeeded
        verify(balanceChangeProducer).sendCreditRequest(eq(1L), anyString(), any());
        verify(balanceChangeProducer).sendCreditRequest(eq(2L), anyString(), any());

        // Timestamp updated only for successful transfer
        verify(transferService, never()).updateTransferTimestamp(1L);
        verify(transferService).updateTransferTimestamp(2L);
    }

    @Test
    @DisplayName("processCreditProcessingTransfers - With query failure - Throws RuntimeException")
    void processCreditProcessingTransfers_WithQueryFailure_ThrowsRuntimeException() {
        // Given
        int delaySeconds = 600;
        int batchSize = 100;

        when(transferService.findCreditProcessingTransfers(any(LocalDateTime.class), eq(batchSize)))
                .thenThrow(new RuntimeException("Database connection error"));

        // When / Then
        assertThatThrownBy(() ->
                transferFacade.processCreditProcessingTransfers(delaySeconds, batchSize)
        )
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process CREDIT_PROCESSING transfers");

        verify(transferService).findCreditProcessingTransfers(any(LocalDateTime.class), eq(batchSize));
        verify(balanceChangeProducer, never()).sendCreditRequest(anyLong(), anyString(), any());
    }

    // ==================== Helper Methods ====================

    /**
     * 建立測試用 Transfer 物件
     */
    private Transfer createTransfer(Long id, TransferStatus status, String fromUserId, String toUserId) {
        Transfer transfer = new Transfer();
        transfer.setId(id);
        transfer.setFromUserId(fromUserId);
        transfer.setToUserId(toUserId);
        transfer.setAmount(new BigDecimal("100.00"));
        transfer.setStatus(status);
        transfer.setCreatedAt(LocalDateTime.now().minusMinutes(15));
        transfer.setUpdatedAt(LocalDateTime.now().minusMinutes(15));
        return transfer;
    }
}
