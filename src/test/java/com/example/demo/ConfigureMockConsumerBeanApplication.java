package com.example.demo;

import com.example.demo.mq.consumer.BalanceChangeConsumer;
import com.example.demo.mq.consumer.BalanceChangeResultConsumer;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class ConfigureMockConsumerBeanApplication {


    @Bean
    public BalanceChangeConsumer balanceChangeConsumer() {
        return Mockito.mock(BalanceChangeConsumer.class);
    }

    @Bean
    public BalanceChangeResultConsumer balanceChangeResultConsumer() {
        return Mockito.mock(BalanceChangeResultConsumer.class);
    }
}
