package com.tokenlimit.server.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 本地缓存（Caffeine 短 TTL）.
 * <p>对网关高频读取且低变更的配置数据（模型价格、Team 模型白名单）做本地缓存，
 * 显著降低 MySQL 压力。默认 TTL 60s：管理端修改配置后最长 1 分钟生效，可接受。</p>
 * <p>注意：含密钥/敏感信息的凭证类数据<b>不</b>走缓存，避免泄露风险。</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .recordStats());
        return manager;
    }
}
