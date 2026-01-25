package com.example.demo;

import com.example.demo.repository.BalanceChangeRepository;
import com.example.demo.repository.TransferRepository;
import com.example.demo.repository.UserBalanceRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestConfiguration
public class ConfigureMockDatasourceBeanApplication {

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private BalanceChangeRepository balanceChangeRepository;

    @MockitoBean
    private TransferRepository transferRepository;

    @MockitoBean
    private UserBalanceRepository userBalanceRepository;
}
