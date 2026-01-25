package com.example.demo.mq.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * RocketMQ 配置類
 *
 * 配置 RocketMQ Producer 和 JSON 序列化器
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class RocketMQConfig {

    private final RocketMQProperties properties;

    /**
     * 配置 RocketMQ Producer
     *
     * @return DefaultMQProducer 實例
     * @throws MQClientException 如果初始化失敗
     */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer producer() throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducer().getGroup());
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setSendMsgTimeout(properties.getProducer().getSendMessageTimeout());
        producer.setRetryTimesWhenSendFailed(properties.getProducer().getRetryTimesWhenSendFailed());
        log.info("RocketMQ Producer initialized: nameServer={}, group={}",
                properties.getNameServer(), properties.getProducer().getGroup());
        return producer;
    }

    /**
     * 配置 ObjectMapper 用於 JSON 序列化
     *
     * @return ObjectMapper 實例
     */
    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }
}
