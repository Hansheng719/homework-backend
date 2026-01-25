package com.example.demo.event;

import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.BalanceChangeStatus;
import com.example.demo.entity.BalanceChangeType;
import com.example.demo.mq.producer.BalanceChangeProducer;
import com.example.demo.service.BalanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BalanceOperationCompletedListener 單元測試
 *
 * 測試策略：
 * 1. 使用 Mockito mock BalanceService 和 BalanceChangeEventProducer
 * 2. 純單元測試，不啟動 Spring Context
 * 3. 測試監聽器的雙重職責：快取失效 + MQ 通知
 * 4. 驗證條件邏輯（成功時清除快取，失敗時不清除）
 * 5. 測試錯誤處理路徑
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BalanceOperationCompletedListener Unit Tests")
class BalanceOperationCompletedListenerTest {

    @Mock
    private BalanceChangeProducer producer;

    @Mock
    private BalanceService balanceService;

    @InjectMocks
    private BalanceOperationCompletedListener listener;

    @Test
    @DisplayName("handleBalanceOperationCompleted - With successful operation - Evicts cache and sends MQ")
    void handleBalanceOperationCompleted_WithSuccessfulOperation_EvictsCacheAndSendsMQ() {
        // Given
        BalanceChange balanceChange = createSuccessfulBalanceChange();
        BalanceOperationCompletedEvent event = new BalanceOperationCompletedEvent(this, balanceChange);

        // When
        listener.handleBalanceOperationCompleted(event);

        // Then
        // 驗證快取被清除（因為是成功的操作）
        verify(balanceService, times(1)).evictBalanceCache("user_001");

        // 驗證 MQ 消息被發送
        verify(producer, times(1)).sendResult(
            eq(balanceChange),
            eq(true),
            isNull()
        );
    }

    @Test
    @DisplayName("handleBalanceOperationCompleted - With successful operation - Cache eviction happens before MQ send")
    void handleBalanceOperationCompleted_WithSuccessfulOperation_CacheEvictionBeforeMQSend() {
        // Given
        BalanceChange balanceChange = createSuccessfulBalanceChange();
        BalanceOperationCompletedEvent event = new BalanceOperationCompletedEvent(this, balanceChange);

        // When
        listener.handleBalanceOperationCompleted(event);

        // Then
        // 驗證執行順序：快取清除應該在 MQ 發送之前
        InOrder inOrder = inOrder(balanceService, producer);
        inOrder.verify(balanceService).evictBalanceCache("user_001");
        inOrder.verify(producer).sendResult(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("handleBalanceOperationCompleted - With failed operation - Only sends MQ, does not evict cache")
    void handleBalanceOperationCompleted_WithFailedOperation_OnlySendsMQ() {
        // Given
        BalanceChange balanceChange = createFailedBalanceChange();
        BalanceOperationCompletedEvent event = new BalanceOperationCompletedEvent(this, balanceChange);

        // When
        listener.handleBalanceOperationCompleted(event);

        // Then
        // 驗證快取未被清除（因為操作失敗，餘額未變更）
        verify(balanceService, never()).evictBalanceCache(anyString());

        // 驗證 MQ 消息仍然被發送（通知失敗）
        verify(producer, times(1)).sendResult(
            eq(balanceChange),
            eq(false),
            eq("Insufficient balance")
        );
    }

    @Test
    @DisplayName("handleBalanceOperationCompleted - When cache eviction fails - Still sends MQ notification")
    void handleBalanceOperationCompleted_WhenCacheEvictionFails_StillSendsMQ() {
        // Given
        BalanceChange balanceChange = createSuccessfulBalanceChange();
        BalanceOperationCompletedEvent event = new BalanceOperationCompletedEvent(this, balanceChange);

        // Mock: 快取清除失敗
        doThrow(new RuntimeException("Cache service unavailable"))
            .when(balanceService).evictBalanceCache(anyString());

        // When
        listener.handleBalanceOperationCompleted(event);

        // Then
        // 驗證快取清除被嘗試
        verify(balanceService, times(1)).evictBalanceCache("user_001");

        // 驗證即使快取清除失敗，MQ 消息仍然被發送
        verify(producer, times(1)).sendResult(
            eq(balanceChange),
            eq(true),
            isNull()
        );
    }

    @Test
    @DisplayName("handleBalanceOperationCompleted - When MQ send fails - Cache was already evicted")
    void handleBalanceOperationCompleted_WhenMQSendFails_CacheAlreadyEvicted() {
        // Given
        BalanceChange balanceChange = createSuccessfulBalanceChange();
        BalanceOperationCompletedEvent event = new BalanceOperationCompletedEvent(this, balanceChange);

        // Mock: MQ 發送失敗
        doThrow(new RuntimeException("MQ service unavailable"))
            .when(producer).sendResult(any(), anyBoolean(), any());

        // When & Then - 不應拋出異常（錯誤被捕獲並記錄）
        assertThatCode(() -> listener.handleBalanceOperationCompleted(event))
            .doesNotThrowAnyException();

        // 驗證快取在 MQ 發送失敗前已被清除
        verify(balanceService, times(1)).evictBalanceCache("user_001");

        // 驗證 MQ 發送被嘗試
        verify(producer, times(1)).sendResult(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("handleBalanceOperationCompleted - With correct userId from event - Evicts cache for correct user")
    void handleBalanceOperationCompleted_WithCorrectUserId_EvictsCacheForCorrectUser() {
        // Given
        String userId = "specific_user_123";
        BalanceChange balanceChange = BalanceChange.builder()
            .id(1L)
            .externalId(10001L)
            .type(BalanceChangeType.TRANSFER_OUT)
            .userId(userId)
            .amount(new BigDecimal("-100.00"))
            .balanceBefore(new BigDecimal("1000.00"))
            .balanceAfter(new BigDecimal("900.00"))
            .status(BalanceChangeStatus.COMPLETED)
            .createdAt(LocalDateTime.now())
            .completedAt(LocalDateTime.now())
            .build();

        BalanceOperationCompletedEvent event = new BalanceOperationCompletedEvent(this, balanceChange);

        // When
        listener.handleBalanceOperationCompleted(event);

        // Then
        // 驗證使用正確的 userId 清除快取
        verify(balanceService, times(1)).evictBalanceCache(userId);
        verify(balanceService, never()).evictBalanceCache(argThat(arg -> !arg.equals(userId)));
    }

    @Test
    @DisplayName("handleBalanceOperationCompleted - With multiple events - Each handled independently")
    void handleBalanceOperationCompleted_WithMultipleEvents_EachHandledIndependently() {
        // Given
        BalanceChange successChange = createSuccessfulBalanceChange();
        BalanceChange failedChange = createFailedBalanceChange();

        BalanceOperationCompletedEvent successEvent = new BalanceOperationCompletedEvent(this, successChange);
        BalanceOperationCompletedEvent failedEvent = new BalanceOperationCompletedEvent(this, failedChange);

        // When
        listener.handleBalanceOperationCompleted(successEvent);
        listener.handleBalanceOperationCompleted(failedEvent);

        // Then
        // 成功事件：快取被清除，MQ 被發送
        verify(balanceService, times(1)).evictBalanceCache("user_001");

        // 失敗事件：快取未清除（因為 userId 不同且失敗）
        verify(balanceService, never()).evictBalanceCache("user_002");

        // 兩個事件都發送 MQ
        verify(producer, times(2)).sendResult(any(), anyBoolean(), any());
    }

    // ==================== Helper Methods ====================

    /**
     * 建立成功的餘額變更記錄
     */
    private BalanceChange createSuccessfulBalanceChange() {
        return BalanceChange.builder()
            .id(1L)
            .externalId(10001L)
            .type(BalanceChangeType.TRANSFER_OUT)
            .userId("user_001")
            .amount(new BigDecimal("-100.00"))
            .balanceBefore(new BigDecimal("1000.00"))
            .balanceAfter(new BigDecimal("900.00"))
            .status(BalanceChangeStatus.COMPLETED)
            .createdAt(LocalDateTime.now())
            .completedAt(LocalDateTime.now())
            .build();
    }

    /**
     * 建立失敗的餘額變更記錄
     */
    private BalanceChange createFailedBalanceChange() {
        return BalanceChange.builder()
            .id(2L)
            .externalId(10002L)
            .type(BalanceChangeType.TRANSFER_OUT)
            .userId("user_002")
            .amount(new BigDecimal("-1500.00"))
            .balanceBefore(new BigDecimal("1000.00"))
            .status(BalanceChangeStatus.FAILED)
            .failureReason("Insufficient balance")
            .createdAt(LocalDateTime.now())
            .build();
    }
}
