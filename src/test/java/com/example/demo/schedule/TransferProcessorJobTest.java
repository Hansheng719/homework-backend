package com.example.demo.schedule;

import com.example.demo.config.SchedulerProperties;
import com.example.demo.facade.TransferFacade;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TransferProcessorJob 單元測試
 *
 * 測試策略：
 * 1. 使用 Mockito mock TransferFacade 和 SchedulerProperties
 * 2. 純單元測試，不啟動 Spring Context
 * 3. 專注於測試調度邏輯、參數傳遞和異常處理
 * 4. 驗證所有異常都被捕獲（不會導致調度器停止）
 *
 * 測試範圍：
 * - processPendingTransfers(): PENDING 轉帳處理
 * - processDebitProcessingTransfers(): DEBIT_PROCESSING 重試
 * - processCreditProcessingTransfers(): CREDIT_PROCESSING 重試
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransferProcessorJob Unit Tests")
class TransferProcessorJobTest {

    @Mock
    private TransferFacade transferFacade;

    @Mock
    private SchedulerProperties schedulerProperties;

    @InjectMocks
    private TransferProcessorJob transferProcessorJob;

    // ==================== processPendingTransfers() Tests ====================

    @Test
    @DisplayName("processPendingTransfers - With successful execution - Calls facade with correct parameters")
    void processPendingTransfers_WithSuccessfulExecution_CallsFacadeWithCorrectParameters() {
        // Given
        when(schedulerProperties.getPendingDelaySeconds()).thenReturn(600);
        when(schedulerProperties.getPendingBatchSize()).thenReturn(100);
        when(transferFacade.processPendingTransfers(600, 100)).thenReturn(5);

        // When
        transferProcessorJob.processPendingTransfers();

        // Then
        verify(schedulerProperties).getPendingDelaySeconds();
        verify(schedulerProperties).getPendingBatchSize();
        verify(transferFacade).processPendingTransfers(600, 100);
    }

    @Test
    @DisplayName("processPendingTransfers - With facade exception - Catches and logs exception")
    void processPendingTransfers_WithFacadeException_CatchesAndLogsException() {
        // Given
        when(schedulerProperties.getPendingDelaySeconds()).thenReturn(600);
        when(schedulerProperties.getPendingBatchSize()).thenReturn(100);
        when(transferFacade.processPendingTransfers(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Database error"));

        // When
        transferProcessorJob.processPendingTransfers();

        // Then - No exception thrown (verify job doesn't fail)
        verify(transferFacade).processPendingTransfers(600, 100);
        // Exception logged but not rethrown
    }

    @Test
    @DisplayName("processPendingTransfers - With zero transfers processed - Completes normally")
    void processPendingTransfers_WithZeroTransfersProcessed_CompletesNormally() {
        // Given
        when(schedulerProperties.getPendingDelaySeconds()).thenReturn(600);
        when(schedulerProperties.getPendingBatchSize()).thenReturn(100);
        when(transferFacade.processPendingTransfers(600, 100)).thenReturn(0);

        // When
        transferProcessorJob.processPendingTransfers();

        // Then
        verify(transferFacade).processPendingTransfers(600, 100);
    }

    // ==================== processDebitProcessingTransfers() Tests ====================

    @Test
    @DisplayName("processDebitProcessingTransfers - With successful execution - Calls facade with correct parameters")
    void processDebitProcessingTransfers_WithSuccessfulExecution_CallsFacadeWithCorrectParameters() {
        // Given
        when(schedulerProperties.getDebitProcessingDelaySeconds()).thenReturn(600);
        when(schedulerProperties.getDebitProcessingBatchSize()).thenReturn(100);
        when(transferFacade.processDebitProcessingTransfers(600, 100)).thenReturn(3);

        // When
        transferProcessorJob.processDebitProcessingTransfers();

        // Then
        verify(schedulerProperties).getDebitProcessingDelaySeconds();
        verify(schedulerProperties).getDebitProcessingBatchSize();
        verify(transferFacade).processDebitProcessingTransfers(600, 100);
    }

    @Test
    @DisplayName("processDebitProcessingTransfers - With facade exception - Catches and logs exception")
    void processDebitProcessingTransfers_WithFacadeException_CatchesAndLogsException() {
        // Given
        when(schedulerProperties.getDebitProcessingDelaySeconds()).thenReturn(600);
        when(schedulerProperties.getDebitProcessingBatchSize()).thenReturn(100);
        when(transferFacade.processDebitProcessingTransfers(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("MQ connection error"));

        // When
        transferProcessorJob.processDebitProcessingTransfers();

        // Then - No exception thrown (verify job doesn't fail)
        verify(transferFacade).processDebitProcessingTransfers(600, 100);
        // Exception logged but not rethrown
    }

    @Test
    @DisplayName("processDebitProcessingTransfers - With zero transfers retried - Completes normally")
    void processDebitProcessingTransfers_WithZeroTransfersRetried_CompletesNormally() {
        // Given
        when(schedulerProperties.getDebitProcessingDelaySeconds()).thenReturn(600);
        when(schedulerProperties.getDebitProcessingBatchSize()).thenReturn(100);
        when(transferFacade.processDebitProcessingTransfers(600, 100)).thenReturn(0);

        // When
        transferProcessorJob.processDebitProcessingTransfers();

        // Then
        verify(transferFacade).processDebitProcessingTransfers(600, 100);
    }

    @Test
    @DisplayName("processDebitProcessingTransfers - With different config values - Uses correct parameters")
    void processDebitProcessingTransfers_WithDifferentConfigValues_UsesCorrectParameters() {
        // Given
        when(schedulerProperties.getDebitProcessingDelaySeconds()).thenReturn(300);
        when(schedulerProperties.getDebitProcessingBatchSize()).thenReturn(50);
        when(transferFacade.processDebitProcessingTransfers(300, 50)).thenReturn(2);

        // When
        transferProcessorJob.processDebitProcessingTransfers();

        // Then
        verify(transferFacade).processDebitProcessingTransfers(300, 50);
    }

    // ==================== processCreditProcessingTransfers() Tests ====================

    @Test
    @DisplayName("processCreditProcessingTransfers - With successful execution - Calls facade with correct parameters")
    void processCreditProcessingTransfers_WithSuccessfulExecution_CallsFacadeWithCorrectParameters() {
        // Given
        when(schedulerProperties.getCreditProcessingDelaySeconds()).thenReturn(600);
        when(schedulerProperties.getCreditProcessingBatchSize()).thenReturn(100);
        when(transferFacade.processCreditProcessingTransfers(600, 100)).thenReturn(2);

        // When
        transferProcessorJob.processCreditProcessingTransfers();

        // Then
        verify(schedulerProperties).getCreditProcessingDelaySeconds();
        verify(schedulerProperties).getCreditProcessingBatchSize();
        verify(transferFacade).processCreditProcessingTransfers(600, 100);
    }

    @Test
    @DisplayName("processCreditProcessingTransfers - With facade exception - Catches and logs exception")
    void processCreditProcessingTransfers_WithFacadeException_CatchesAndLogsException() {
        // Given
        when(schedulerProperties.getCreditProcessingDelaySeconds()).thenReturn(600);
        when(schedulerProperties.getCreditProcessingBatchSize()).thenReturn(100);
        when(transferFacade.processCreditProcessingTransfers(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Balance service unavailable"));

        // When
        transferProcessorJob.processCreditProcessingTransfers();

        // Then - No exception thrown (verify job doesn't fail)
        verify(transferFacade).processCreditProcessingTransfers(600, 100);
        // Exception logged but not rethrown
    }

    @Test
    @DisplayName("processCreditProcessingTransfers - With zero transfers retried - Completes normally")
    void processCreditProcessingTransfers_WithZeroTransfersRetried_CompletesNormally() {
        // Given
        when(schedulerProperties.getCreditProcessingDelaySeconds()).thenReturn(600);
        when(schedulerProperties.getCreditProcessingBatchSize()).thenReturn(100);
        when(transferFacade.processCreditProcessingTransfers(600, 100)).thenReturn(0);

        // When
        transferProcessorJob.processCreditProcessingTransfers();

        // Then
        verify(transferFacade).processCreditProcessingTransfers(600, 100);
    }

    @Test
    @DisplayName("processCreditProcessingTransfers - With different config values - Uses correct parameters")
    void processCreditProcessingTransfers_WithDifferentConfigValues_UsesCorrectParameters() {
        // Given
        when(schedulerProperties.getCreditProcessingDelaySeconds()).thenReturn(900);
        when(schedulerProperties.getCreditProcessingBatchSize()).thenReturn(150);
        when(transferFacade.processCreditProcessingTransfers(900, 150)).thenReturn(4);

        // When
        transferProcessorJob.processCreditProcessingTransfers();

        // Then
        verify(transferFacade).processCreditProcessingTransfers(900, 150);
    }

    // ==================== @SchedulerLock Annotation Tests ====================

    @Test
    @DisplayName("processPendingTransfers - Has @SchedulerLock annotation - Configured correctly")
    void processPendingTransfers_HasSchedulerLockAnnotation_ConfiguredCorrectly() throws NoSuchMethodException {
        // Given
        Method method = TransferProcessorJob.class.getMethod("processPendingTransfers");

        // When
        SchedulerLock annotation = method.getAnnotation(SchedulerLock.class);

        // Then
        assertNotNull(annotation, "Method should have @SchedulerLock annotation");
        assertEquals("processPendingTransfers", annotation.name());
        assertTrue(annotation.lockAtMostFor().contains("pending-lock-at-most-seconds"),
                "lockAtMostFor should reference pending-lock-at-most-seconds property");
        assertTrue(annotation.lockAtLeastFor().contains("pending-lock-at-least-seconds"),
                "lockAtLeastFor should reference pending-lock-at-least-seconds property");
    }

    @Test
    @DisplayName("processDebitProcessingTransfers - Has @SchedulerLock annotation - Configured correctly")
    void processDebitProcessingTransfers_HasSchedulerLockAnnotation_ConfiguredCorrectly() throws NoSuchMethodException {
        // Given
        Method method = TransferProcessorJob.class.getMethod("processDebitProcessingTransfers");

        // When
        SchedulerLock annotation = method.getAnnotation(SchedulerLock.class);

        // Then
        assertNotNull(annotation, "Method should have @SchedulerLock annotation");
        assertEquals("processDebitProcessingTransfers", annotation.name());
        assertTrue(annotation.lockAtMostFor().contains("debit-processing-lock-at-most-seconds"),
                "lockAtMostFor should reference debit-processing-lock-at-most-seconds property");
        assertTrue(annotation.lockAtLeastFor().contains("debit-processing-lock-at-least-seconds"),
                "lockAtLeastFor should reference debit-processing-lock-at-least-seconds property");
    }

    @Test
    @DisplayName("processCreditProcessingTransfers - Has @SchedulerLock annotation - Configured correctly")
    void processCreditProcessingTransfers_HasSchedulerLockAnnotation_ConfiguredCorrectly() throws NoSuchMethodException {
        // Given
        Method method = TransferProcessorJob.class.getMethod("processCreditProcessingTransfers");

        // When
        SchedulerLock annotation = method.getAnnotation(SchedulerLock.class);

        // Then
        assertNotNull(annotation, "Method should have @SchedulerLock annotation");
        assertEquals("processCreditProcessingTransfers", annotation.name());
        assertTrue(annotation.lockAtMostFor().contains("credit-processing-lock-at-most-seconds"),
                "lockAtMostFor should reference credit-processing-lock-at-most-seconds property");
        assertTrue(annotation.lockAtLeastFor().contains("credit-processing-lock-at-least-seconds"),
                "lockAtLeastFor should reference credit-processing-lock-at-least-seconds property");
    }
}
