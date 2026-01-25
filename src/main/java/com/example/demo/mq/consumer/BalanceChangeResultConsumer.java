package com.example.demo.mq.consumer;

import com.example.demo.facade.TransferFacade;
import com.example.demo.mq.config.RocketMQProperties;
import com.example.demo.mq.msg.BalanceChangeResultMsg;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static com.example.demo.mq.constants.MQConstants.*;

/**
 * 餘額變更結果消費者
 *
 * 職責：
 * 1. 監聽 balance-change-results topic
 * 2. 委派給 TransferFacade 處理餘額變更結果
 *
 * 狀態流轉邏輯：
 * - Debit 成功 → Facade 處理 → TransferService 更新狀態 → 事件驅動發送 credit MQ
 * - Debit 失敗 → Facade 處理 → TransferService 更新為 DEBIT_FAILED
 * - Credit 成功 → Facade 處理 → TransferService 更新為 COMPLETED
 * - Credit 失敗 → Facade 拋出異常觸發重試（理論上不應發生）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BalanceChangeResultConsumer {

    private final TransferFacade transferFacade;
    private final ObjectMapper objectMapper;
    private final RocketMQProperties rocketMQProperties;

    /**
     * 啟動 Consumer
     */
    @PostConstruct
    public void start() throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(GROUP_TRANSFER_SERVICE);
        consumer.setNamesrvAddr(rocketMQProperties.getNameServer());
        consumer.subscribe(TOPIC_BALANCE_CHANGE_RESULTS, "*");
        consumer.setConsumeThreadMin(CONSUMER_THREAD_MIN);
        consumer.setConsumeThreadMax(CONSUMER_THREAD_MAX);
        consumer.setMaxReconsumeTimes(MAX_RECONSUME_TIMES);

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                try {
                    String json = new String(msg.getBody(), StandardCharsets.UTF_8);
                    BalanceChangeResultMsg event = objectMapper.readValue(json, BalanceChangeResultMsg.class);
                    transferFacade.handleBalanceChangeResult(event);
                } catch (Exception e) {
                    log.error("Failed to process msgId={}", msg.getMsgId(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
        log.info("BalanceChangeResultConsumer started: group={}, topic={}", GROUP_TRANSFER_SERVICE, TOPIC_BALANCE_CHANGE_RESULTS);
    }
}
