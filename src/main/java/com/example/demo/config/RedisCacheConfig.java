package com.example.demo.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

/**
 * Redis 快取配置
 *
 * 功能：
 * 1. 啟用 Spring Cache 框架 (@EnableCaching)
 * 2. 配置 Redis 作為快取後端
 * 3. 設定快取 TTL 為 300 秒（5 分鐘）
 * 4. 配置快取鍵格式：{cacheName}:{key}
 * 5. 使用 JDK 序列化（支援 BigDecimal）
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * 配置 Redis Cache Manager
     *
     * @param connectionFactory Redis 連線工廠
     * @return CacheManager
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 配置快取設定
        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            // 設定 TTL 為 300 秒（5 分鐘）
            .entryTtl(Duration.ofSeconds(300))
            // 配置快取鍵前綴：{cacheName}:
            .computePrefixWith(cacheName -> cacheName + ":");

        // 建立 RedisCacheManager
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(cacheConfig)
            .build();
    }
}
