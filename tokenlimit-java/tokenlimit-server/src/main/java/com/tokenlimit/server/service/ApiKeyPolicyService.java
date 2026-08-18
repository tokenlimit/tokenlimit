package com.tokenlimit.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.entity.ApiKeyPolicy;
import com.tokenlimit.server.repository.mapper.ApiKeyPolicyMapper;
import com.tokenlimit.server.repository.mapper.ApiKeyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * API Key 限额策略服务（V6.0 新增，User 自助风控）.
 * <p>支持 End User 自主设置 API Key 的：</p>
 * <ul>
 *   <li>单次请求最大 token 数</li>
 *   <li>小时限额（小时熔断）</li>
 *   <li>日限额</li>
 *   <li>冻结状态控制</li>
 * </ul>
 */
@Service
public class ApiKeyPolicyService extends ServiceImpl<ApiKeyPolicyMapper, ApiKeyPolicy> {

    private final ApiKeyMapper apiKeyMapper;

    public ApiKeyPolicyService(ApiKeyMapper apiKeyMapper) {
        this.apiKeyMapper = apiKeyMapper;
    }

    /**
     * 获取或创建 API Key 的策略记录.
     */
    public ApiKeyPolicy getOrCreatePolicy(String accessKey) {
        ApiKeyPolicy policy = getByAccessKey(accessKey);
        if (policy == null) {
            // 从 API Key 表同步基础信息
            ApiKey apiKey = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getAccessKey, accessKey));
            if (apiKey != null) {
                policy = new ApiKeyPolicy();
                policy.setAccessKey(accessKey);
                policy.setTeamCode(apiKey.getTeamCode());
                policy.setUserCode(apiKey.getUserCode());
                policy.setKeyId(apiKey.getKeyId());
                policy.setStatus("ENABLED");
                save(policy);
            }
        }
        return policy;
    }

    /**
     * 根据 accessKey 查询策略.
     */
    public ApiKeyPolicy getByAccessKey(String accessKey) {
        return getOne(new LambdaQueryWrapper<ApiKeyPolicy>()
            .eq(ApiKeyPolicy::getAccessKey, accessKey));
    }

    /**
     * 更新用户自定义策略（User 自助操作）.
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiKeyPolicy updateUserPolicy(String accessKey, Long maxTokensPerRequest,
                                          Long hourlyLimit, Long dailyLimit) {
        ApiKeyPolicy policy = getOrCreatePolicy(accessKey);
        policy.setMaxTokensPerRequest(maxTokensPerRequest);
        policy.setHourlyLimit(hourlyLimit);
        policy.setDailyLimit(dailyLimit);
        updateById(policy);
        return policy;
    }

    /**
     * 冻结/解冻 API Key（用户手动或系统自动）.
     */
    @Transactional(rollbackFor = Exception.class)
    public void freezeApiKey(String accessKey, boolean frozen, String reason) {
        ApiKeyPolicy policy = getOrCreatePolicy(accessKey);
        policy.setIsFrozen(frozen);
        policy.setFrozenReason(reason);
        updateById(policy);
    }

    /**
     * 重置小时用量（每小时自动调用）.
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetHourlyUsage(String accessKey) {
        ApiKeyPolicy policy = getOrCreatePolicy(accessKey);
        policy.setHourlyUsed(0L);
        policy.setHourlyResetAt(LocalDateTime.now().plusHours(1));
        updateById(policy);
    }

    /**
     * 重置日用量（每天自动调用）.
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetDailyUsage(String accessKey) {
        ApiKeyPolicy policy = getOrCreatePolicy(accessKey);
        policy.setDailyUsed(0L);
        policy.setDailyResetAt(LocalDateTime.now().plusDays(1));
        updateById(policy);
    }

    /**
     * 增加小时用量.
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrHourlyUsage(String accessKey, long tokens) {
        ApiKeyPolicy policy = getOrCreatePolicy(accessKey);
        Long currentUsed = Optional.ofNullable(policy.getHourlyUsed()).orElse(0L);
        policy.setHourlyUsed(currentUsed + tokens);
        if (policy.getHourlyResetAt() == null) {
            policy.setHourlyResetAt(LocalDateTime.now().plusHours(1));
        }
        updateById(policy);
    }

    /**
     * 增加日用量.
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrDailyUsage(String accessKey, long tokens) {
        ApiKeyPolicy policy = getOrCreatePolicy(accessKey);
        Long currentUsed = Optional.ofNullable(policy.getDailyUsed()).orElse(0L);
        policy.setDailyUsed(currentUsed + tokens);
        if (policy.getDailyResetAt() == null) {
            policy.setDailyResetAt(LocalDateTime.now().plusDays(1));
        }
        updateById(policy);
    }
}
