package com.tokenlimit.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.repository.mapper.ApiKeyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * API Key 异步指标更新服务.
 * <p>将 {@code last_used_at} 等轻量指标更新从网关主线程剥离，
 * 降低每次代理调用带来的同步 MySQL 写压力。</p>
 */
@Service
public class ApiKeyMetricsService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyMetricsService.class);

    private final ApiKeyMapper apiKeyMapper;

    public ApiKeyMetricsService(ApiKeyMapper apiKeyMapper) {
        this.apiKeyMapper = apiKeyMapper;
    }

    /**
     * 异步更新 API Key 最后使用时间.
     *
     * @param apiKeyId API Key 主键
     */
    @Async("asyncTaskExecutor")
    public void updateLastUsedAt(Long apiKeyId) {
        try {
            ApiKey update = new ApiKey();
            update.setId(apiKeyId);
            update.setLastUsedAt(LocalDateTime.now());
            apiKeyMapper.updateById(update);
        } catch (Exception e) {
            log.error("异步更新 API Key last_used_at 失败, apiKeyId={}", apiKeyId, e);
        }
    }
}
