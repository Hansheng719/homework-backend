package com.example.demo.mq.consumer;

import com.example.demo.facade.BalanceFacade;
import com.example.demo.mq.config.RocketMQProperties;
import com.example.demo.mq.msg.BalanceChangeMsg;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static com.example.demo.mq.constants.MQConstants.*;

/**
 * 餘額變更消費者
 * <p>
 * 職責：
 * 1. 監聽 balance-change-requests topic
 * 2. 委派給 BalanceFacade 處理餘額變更請求
 * 3. BalanceFacade 協調 BalanceService 執行扣款/加帳操作
 * 4. BalanceService 會發布結果事件，由 BalanceChangeResultListener 發送 MQ
 * <p>
 * 錯誤處理策略：
 * - 用戶不存在（UserNotFoundException）：
 * 抛出異常，Consumer 捕獲後記錄錯誤，不重試（重試也無法成功）
 * - 餘額不足（InsufficientBalanceException）：
 * 在 BalanceService 內部處理，返回 FAILED BalanceChange
 * - 系統異常（資料庫錯誤、網路錯誤等）：
 * 返回 RECONSUME_LATER（觸發 RocketMQ 重試，最多 16 次）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BalanceChangeConsumer {

    private final BalanceFacade balanceFacade;
    private final ObjectMapper objectMapper;
    private final RocketMQProperties rocketMQProperties;

    /**
     * 啟動 Consumer
     */
    @PostConstruct
    public void start() throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(GROUP_BALANCE_SERVICE);
        consumer.setNamesrvAddr(rocketMQProperties.getNameServer());
        consumer.subscribe(TOPIC_BALANCE_CHANGE_REQUESTS, "*");
        consumer.setConsumeThreadMin(CONSUMER_THREAD_MIN);
        consumer.setConsumeThreadMax(CONSUMER_THREAD_MAX);
        consumer.setMaxReconsumeTimes(MAX_RECONSUME_TIMES);

        consumer.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                try {
                    String json = new String(msg.getBody(), StandardCharsets.UTF_8);
                    BalanceChangeMsg event = objectMapper.readValue(json, BalanceChangeMsg.class);
                    balanceFacade.handleBalanceChangeRequest(event);
                } catch (Exception e) {
                    log.error("Failed to process message: msgId={}", msg.getMsgId(), e);
                    return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                }
            }
            return ConsumeOrderlyStatus.SUCCESS;
        });

        consumer.start();
        log.info("BalanceChangeConsumer started: group={}, topic={}", GROUP_BALANCE_SERVICE, TOPIC_BALANCE_CHANGE_REQUESTS);
    }
}
