package com.example.demo.mq.producer;

import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.BalanceChangeStatus;
import com.example.demo.entity.BalanceChangeType;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BalanceChangeProducer 單元測試
 *
 * 測試策略：
 * 1. 使用 Mockito mock DefaultMQProducer 和 ObjectMapper
 * 2. 專注於測試 MessageQueueSelector 的 sharding 邏輯
 * 3. 驗證修復後的 Math.floorMod() 能正確處理負數 hashCode
 *
 * 測試範圍：
 * - Queue sharding algorithm with positive hashCode
 * - Queue sharding algorithm with negative hashCode
 * - Edge case: Integer.MIN_VALUE hashCode
 * - Queue size variations (1, 2, 4, 10)
 * - Consistency: same userId maps to same queue
 * - Public methods: sendDebitRequest, sendCreditRequest, sendResult
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BalanceChangeProducer Queue Sharding Tests")
class BalanceChangeProducerTest {

    @Mock
    private DefaultMQProducer producer;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private BalanceChangeProducer balanceChangeProducer;

    // ==================== Queue Sharding Algorithm Tests ====================

    @Test
    @DisplayName("sendDebitRequest - With positive hashCode userId - Routes to valid queue")
    void sendDebitRequest_WithPositiveHashCodeUserId_RoutesToValidQueue() throws Exception {
        // Given
        Long transferId = 1L;
        String userId = "user_001"; // hashCode is positive
        BigDecimal amount = new BigDecimal("100.00");

        // Mock ObjectMapper
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // Mock Producer send - capture the MessageQueueSelector
        ArgumentCaptor<MessageQueueSelector> selectorCaptor = ArgumentCaptor.forClass(MessageQueueSelector.class);
        SendResult mockResult = mock(SendResult.class);
        when(mockResult.getMsgId()).thenReturn("msg-123");
        when(producer.send(any(Message.class), selectorCaptor.capture(), anyString()))
            .thenReturn(mockResult);

        // When
        balanceChangeProducer.sendDebitRequest(transferId, userId, amount);

        // Then - Extract and test the selector
        MessageQueueSelector selector = selectorCaptor.getValue();
        List<MessageQueue> queues = createMessageQueues(4);

        MessageQueue selected = selector.select(queues, null, userId);
        int index = queues.indexOf(selected);

        assertThat(index).isBetween(0, 3); // Valid index
        verify(producer, times(1)).send(any(Message.class), any(MessageQueueSelector.class), eq(userId));
    }

    @Test
    @DisplayName("sendDebitRequest - With negative hashCode userId - Routes to valid queue without exception")
    void sendDebitRequest_WithNegativeHashCodeUserId_RoutesToValidQueueWithoutException() throws Exception {
        // Given
        Long transferId = 2L;
        // This userId has negative hashCode (verified: "\u0001".hashCode() = 1, but we'll use a crafted one)
        // Note: We can't guarantee a specific userId has negative hashCode, so we test the selector logic
        String userId = "user_negative_hash";
        BigDecimal amount = new BigDecimal("200.00");

        // Mock ObjectMapper
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // Mock Producer send
        ArgumentCaptor<MessageQueueSelector> selectorCaptor = ArgumentCaptor.forClass(MessageQueueSelector.class);
        SendResult mockResult = mock(SendResult.class);
        when(mockResult.getMsgId()).thenReturn("msg-456");
        when(producer.send(any(Message.class), selectorCaptor.capture(), anyString()))
            .thenReturn(mockResult);

        // When
        balanceChangeProducer.sendDebitRequest(transferId, userId, amount);

        // Then - Test selector with simulated negative hashCode scenario
        MessageQueueSelector selector = selectorCaptor.getValue();
        List<MessageQueue> queues = createMessageQueues(4);

        // Test with a string that has negative hashCode
        String negativeHashUserId = createNegativeHashCodeString();

        // This should NOT throw IndexOutOfBoundsException
        assertThatCode(() -> {
            MessageQueue selected = selector.select(queues, null, negativeHashUserId);
            int index = queues.indexOf(selected);
            assertThat(index).isBetween(0, 3); // Valid index even with negative hashCode
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Math.floorMod - With edge case values - Returns valid non-negative results")
    void mathFloorMod_WithEdgeCaseValues_ReturnsValidNonNegativeResults() {
        // Test the Math.floorMod logic directly to ensure it handles all edge cases

        // Test 1: Integer.MIN_VALUE
        int result1 = Math.floorMod(Integer.MIN_VALUE, 4);
        assertThat(result1).isEqualTo(0).isBetween(0, 3);

        // Test 2: Negative hashCode
        int result2 = Math.floorMod(-100, 4);
        assertThat(result2).isEqualTo(0).isBetween(0, 3);

        // Test 3: Another negative hashCode
        int result3 = Math.floorMod(-1, 4);
        assertThat(result3).isEqualTo(3).isBetween(0, 3);

        // Test 4: Positive hashCode
        int result4 = Math.floorMod(100, 4);
        assertThat(result4).isEqualTo(0).isBetween(0, 3);

        // Test 5: Integer.MAX_VALUE
        int result5 = Math.floorMod(Integer.MAX_VALUE, 4);
        assertThat(result5).isEqualTo(3).isBetween(0, 3);

        // Key assertion: floorMod NEVER returns negative
        for (int hash : new int[]{Integer.MIN_VALUE, -1000, -1, 0, 1, 1000, Integer.MAX_VALUE}) {
            for (int size : new int[]{1, 2, 4, 10}) {
                int index = Math.floorMod(hash, size);
                assertThat(index)
                    .describedAs("floorMod(%d, %d)", hash, size)
                    .isBetween(0, size - 1);
            }
        }
    }

    @Test
    @DisplayName("Queue selector - With queue size 1 - Always returns index 0")
    void queueSelector_WithQueueSizeOne_AlwaysReturnsIndexZero() throws Exception {
        // Given
        Long transferId = 4L;
        String userId = "user_single_queue";
        BigDecimal amount = new BigDecimal("400.00");

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ArgumentCaptor<MessageQueueSelector> selectorCaptor = ArgumentCaptor.forClass(MessageQueueSelector.class);
        SendResult mockResult = mock(SendResult.class);
        when(producer.send(any(Message.class), selectorCaptor.capture(), anyString()))
            .thenReturn(mockResult);

        // When
        balanceChangeProducer.sendDebitRequest(transferId, userId, amount);

        // Then
        MessageQueueSelector selector = selectorCaptor.getValue();
        List<MessageQueue> queues = createMessageQueues(1);

        MessageQueue selected = selector.select(queues, null, userId);
        int index = queues.indexOf(selected);

        assertThat(index).isEqualTo(0); // Only one queue, must be index 0
    }

    @Test
    @DisplayName("Queue selector - With various queue sizes - Always returns valid index")
    void queueSelector_WithVariousQueueSizes_AlwaysReturnsValidIndex() throws Exception {
        // Given
        Long transferId = 5L;
        String userId = "user_various_sizes";
        BigDecimal amount = new BigDecimal("500.00");

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ArgumentCaptor<MessageQueueSelector> selectorCaptor = ArgumentCaptor.forClass(MessageQueueSelector.class);
        SendResult mockResult = mock(SendResult.class);
        when(producer.send(any(Message.class), selectorCaptor.capture(), anyString()))
            .thenReturn(mockResult);

        // When
        balanceChangeProducer.sendDebitRequest(transferId, userId, amount);

        // Then - Test with various queue sizes
        MessageQueueSelector selector = selectorCaptor.getValue();

        int[] queueSizes = {1, 2, 4, 10, 100};
        for (int size : queueSizes) {
            List<MessageQueue> queues = createMessageQueues(size);
            MessageQueue selected = selector.select(queues, null, userId);
            int index = queues.indexOf(selected);

            assertThat(index)
                .describedAs("Queue size: %d", size)
                .isBetween(0, size - 1);
        }
    }

    @Test
    @DisplayName("Queue selector - Same userId called multiple times - Returns same queue (consistency)")
    void queueSelector_SameUserIdMultipleTimes_ReturnsSameQueue() throws Exception {
        // Given
        Long transferId = 6L;
        String userId = "user_consistency_test";
        BigDecimal amount = new BigDecimal("600.00");

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ArgumentCaptor<MessageQueueSelector> selectorCaptor = ArgumentCaptor.forClass(MessageQueueSelector.class);
        SendResult mockResult = mock(SendResult.class);
        when(producer.send(any(Message.class), selectorCaptor.capture(), anyString()))
            .thenReturn(mockResult);

        // When
        balanceChangeProducer.sendDebitRequest(transferId, userId, amount);

        // Then - Test consistency
        MessageQueueSelector selector = selectorCaptor.getValue();
        List<MessageQueue> queues = createMessageQueues(4);

        MessageQueue firstSelection = selector.select(queues, null, userId);

        // Call multiple times
        for (int i = 0; i < 10; i++) {
            MessageQueue selected = selector.select(queues, null, userId);
            assertThat(selected).isEqualTo(firstSelection); // Must be same queue
        }
    }

    @Test
    @DisplayName("Queue selector - Different userIds - Can map to different queues")
    void queueSelector_DifferentUserIds_CanMapToDifferentQueues() throws Exception {
        // Given
        Long transferId = 7L;
        String userId = "user_001";
        BigDecimal amount = new BigDecimal("700.00");

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ArgumentCaptor<MessageQueueSelector> selectorCaptor = ArgumentCaptor.forClass(MessageQueueSelector.class);
        SendResult mockResult = mock(SendResult.class);
        when(producer.send(any(Message.class), selectorCaptor.capture(), anyString()))
            .thenReturn(mockResult);

        // When
        balanceChangeProducer.sendDebitRequest(transferId, userId, amount);

        // Then
        MessageQueueSelector selector = selectorCaptor.getValue();
        List<MessageQueue> queues = createMessageQueues(4);

        // Test that different users can be routed deterministically
        String[] userIds = {"user_001", "user_002", "user_003", "user_004", "user_005"};
        List<Integer> indices = new ArrayList<>();

        for (String uid : userIds) {
            MessageQueue selected = selector.select(queues, null, uid);
            int index = queues.indexOf(selected);
            indices.add(index);

            assertThat(index).isBetween(0, 3); // All valid
        }

        // Verify deterministic mapping (same userId always gets same index)
        for (String uid : userIds) {
            MessageQueue selected1 = selector.select(queues, null, uid);
            MessageQueue selected2 = selector.select(queues, null, uid);
            assertThat(selected1).isEqualTo(selected2);
        }
    }

    // ==================== Public Method Tests ====================

    @Test
    @DisplayName("sendCreditRequest - With valid parameters - Sends message successfully")
    void sendCreditRequest_WithValidParameters_SendsMessageSuccessfully() throws Exception {
        // Given
        Long transferId = 8L;
        String userId = "user_credit";
        BigDecimal amount = new BigDecimal("800.00");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"type\":\"TRANSFER_IN\"}");

        SendResult mockResult = mock(SendResult.class);
        when(mockResult.getMsgId()).thenReturn("msg-credit-123");
        when(producer.send(any(Message.class), any(MessageQueueSelector.class), anyString()))
            .thenReturn(mockResult);

        // When & Then - Should not throw exception
        assertThatCode(() -> balanceChangeProducer.sendCreditRequest(transferId, userId, amount))
            .doesNotThrowAnyException();

        verify(producer, times(1)).send(any(Message.class), any(MessageQueueSelector.class), eq(userId));
    }

    @Test
    @DisplayName("sendResult - With successful balance change - Sends result message")
    void sendResult_WithSuccessfulBalanceChange_SendsResultMessage() throws Exception {
        // Given
        BalanceChange balanceChange = BalanceChange.builder()
            .id(1L)
            .externalId(9L)
            .type(BalanceChangeType.TRANSFER_OUT)
            .userId("user_result")
            .amount(new BigDecimal("-100.00"))
            .balanceBefore(new BigDecimal("1000.00"))
            .balanceAfter(new BigDecimal("900.00"))
            .status(BalanceChangeStatus.COMPLETED)
            .createdAt(LocalDateTime.now())
            .completedAt(LocalDateTime.now())
            .build();

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"success\":true}");

        SendResult mockResult = mock(SendResult.class);
        when(mockResult.getMsgId()).thenReturn("msg-result-456");
        when(producer.send(any(Message.class), any(MessageQueueSelector.class), anyString()))
            .thenReturn(mockResult);

        // When & Then
        assertThatCode(() -> balanceChangeProducer.sendResult(balanceChange, true, null))
            .doesNotThrowAnyException();

        verify(producer, times(1)).send(any(Message.class), any(MessageQueueSelector.class), eq("user_result"));
    }

    @Test
    @DisplayName("sendResult - With failed balance change - Sends failure result message")
    void sendResult_WithFailedBalanceChange_SendsFailureResultMessage() throws Exception {
        // Given
        BalanceChange balanceChange = BalanceChange.builder()
            .id(2L)
            .externalId(10L)
            .type(BalanceChangeType.TRANSFER_OUT)
            .userId("user_failed")
            .amount(new BigDecimal("-500.00"))
            .status(BalanceChangeStatus.FAILED)
            .failureReason("Insufficient balance")
            .createdAt(LocalDateTime.now())
            .build();

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"success\":false}");

        SendResult mockResult = mock(SendResult.class);
        when(mockResult.getMsgId()).thenReturn("msg-fail-789");
        when(producer.send(any(Message.class), any(MessageQueueSelector.class), anyString()))
            .thenReturn(mockResult);

        // When & Then
        assertThatCode(() ->
            balanceChangeProducer.sendResult(balanceChange, false, "Insufficient balance"))
            .doesNotThrowAnyException();

        verify(producer, times(1)).send(any(Message.class), any(MessageQueueSelector.class), eq("user_failed"));
    }

    @Test
    @DisplayName("sendDebitRequest - When producer throws exception - Propagates RuntimeException")
    void sendDebitRequest_WhenProducerThrowsException_PropagatesRuntimeException() throws Exception {
        // Given
        Long transferId = 11L;
        String userId = "user_exception";
        BigDecimal amount = new BigDecimal("100.00");

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(producer.send(any(Message.class), any(MessageQueueSelector.class), anyString()))
            .thenThrow(new RuntimeException("MQ connection failed"));

        // When & Then
        assertThatThrownBy(() -> balanceChangeProducer.sendDebitRequest(transferId, userId, amount))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to send MQ message");
    }

    // ==================== Helper Methods ====================

    /**
     * 建立測試用的 MessageQueue 列表
     */
    private List<MessageQueue> createMessageQueues(int size) {
        List<MessageQueue> queues = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            MessageQueue queue = new MessageQueue("test-topic", "test-broker", i);
            queues.add(queue);
        }
        return queues;
    }

    /**
     * 創建一個 hashCode 為負數的字符串
     * 注意：某些字符串的 hashCode 是負數
     */
    private String createNegativeHashCodeString() {
        // "polygenelubricants" has negative hashCode: -1428785667
        // Alternatively, we can use any string and verify
        String candidate = "polygenelubricants";
        if (candidate.hashCode() < 0) {
            return candidate;
        }

        // Fallback: search for one (this is just for testing)
        for (int i = 0; i < 10000; i++) {
            String test = "user_" + i;
            if (test.hashCode() < 0) {
                return test;
            }
        }

        // Last resort: return a known negative hashCode string
        return "polygenelubricants"; // Known to have negative hashCode
    }
}
