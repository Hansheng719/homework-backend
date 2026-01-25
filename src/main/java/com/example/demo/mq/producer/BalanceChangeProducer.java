package com.example.demo.mq.producer;

import com.example.demo.entity.BalanceChange;
import com.example.demo.entity.BalanceChangeType;
import com.example.demo.mq.msg.BalanceChangeMsg;
import com.example.demo.mq.msg.BalanceChangeResultMsg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.example.demo.mq.constants.MQConstants.*;

/**
 * 餘額變更事件 Producer
 *
 * 職責：
 * 1. 發送扣款請求（debit request）
 * 2. 發送加帳請求（credit request）
 * 3. 發送處理結果（result）
 *
 * 錯誤處理：
 * - 使用同步發送（fail-fast 策略）
 * - 發送失敗時拋出異常，由上層決定如何處理
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BalanceChangeProducer {

    private final DefaultMQProducer producer;
    private final ObjectMapper objectMapper;

    /**
     * 發送扣款請求
     *
     * @param transferId 轉帳 ID（作為 externalId）
     * @param userId 要扣款的用戶 ID
     * @param amount 扣款金額（正數）
     */
    public void sendDebitRequest(Long transferId, String userId, BigDecimal amount) {
        BalanceChangeMsg msg = BalanceChangeMsg.builder()
                .externalId(transferId)
                .type(BalanceChangeType.TRANSFER_OUT)
                .userId(userId)
                .amount(amount.negate())  // 負數表示扣款
                .relatedId(transferId)
                .timestamp(System.currentTimeMillis())
                .build();

        sendMsg(msg, TOPIC_BALANCE_CHANGE_REQUESTS, userId);
        log.info("Sent debit request: transferId={}, userId={}, amount={}",
                transferId, userId, amount);
    }

    /**
     * 發送加帳請求
     *
     * @param transferId 轉帳 ID（作為 externalId）
     * @param userId 要加帳的用戶 ID
     * @param amount 加帳金額（正數）
     */
    public void sendCreditRequest(Long transferId, String userId, BigDecimal amount) {
        BalanceChangeMsg msg = BalanceChangeMsg.builder()
                .externalId(transferId)
                .type(BalanceChangeType.TRANSFER_IN)
                .userId(userId)
                .amount(amount)  // 正數表示加帳
                .relatedId(transferId)
                .timestamp(System.currentTimeMillis())
                .build();

        sendMsg(msg, TOPIC_BALANCE_CHANGE_REQUESTS, userId);
        log.info("Sent credit request: transferId={}, userId={}, amount={}",
                transferId, userId, amount);
    }

    /**
     * 發送處理結果
     *
     * @param balanceChange 餘額變更記錄
     * @param success 操作是否成功
     * @param failureReason 失敗原因（失敗時）
     */
    public void sendResult(BalanceChange balanceChange, boolean success, String failureReason) {
        BalanceChangeResultMsg msg = BalanceChangeResultMsg.builder()
                .externalId(balanceChange.getExternalId())
                .type(balanceChange.getType())
                .success(success)
                .userId(balanceChange.getUserId())
                .oldBalance(success ? balanceChange.getBalanceBefore() : null)
                .newBalance(success ? balanceChange.getBalanceAfter() : null)
                .failureReason(failureReason)
                .timestamp(System.currentTimeMillis())
                .build();

        sendMsg(msg, TOPIC_BALANCE_CHANGE_RESULTS, balanceChange.getUserId());
        log.info("Sent balance change result: externalId={}, type={}, success={}",
                balanceChange.getExternalId(), balanceChange.getType(), success);
    }

    /**
     * 發送事件到 RocketMQ
     *
     * @param event 事件對象
     * @param topic Topic 名稱
     * @param userId 用戶 ID（用於 sharding）
     */
    private void sendMsg(Object event, String topic, String userId) {
        try {
            String json = objectMapper.writeValueAsString(event);
            // 使用 userId 作為 message key 實現 sharding
            Message message = new Message(topic, null, userId, json.getBytes(StandardCharsets.UTF_8));
            message.putUserProperty("__SHARDINGKEY", userId);
            SendResult result = producer.send(message, (mqs, msg, arg) -> {
                String id = (String) arg;
                int index = Math.floorMod(id.hashCode(), mqs.size());
                return mqs.get(index);
            }, userId);

            log.debug("Sent MQ message: topic={}, userId={}, msgId={}", topic, userId, result.getMsgId());
        } catch (Exception e) {
            log.error("Failed to send MQ message: topic={}, userId={}", topic, userId, e);
            throw new RuntimeException("Failed to send MQ message", e);
        }
    }
}
