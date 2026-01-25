package com.example.demo.mq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 配置屬性
 *
 * 集中管理從 application.yaml 讀取的 RocketMQ 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rocketmq")
public class RocketMQProperties {

    /**
     * NameServer 地址
     */
    private String nameServer;

    /**
     * Producer 配置
     */
    private Producer producer = new Producer();

    /**
     * Consumer 配置
     */
    private Consumer consumer = new Consumer();

    @Data
    public static class Producer {
        private String group;
        private int sendMessageTimeout = 3000;
        private int retryTimesWhenSendFailed = 2;
    }

    @Data
    public static class Consumer {
        private int consumeThreadMin = 10;
        private int consumeThreadMax = 20;
        private int maxReconsumeTimes = 16;
    }
}
