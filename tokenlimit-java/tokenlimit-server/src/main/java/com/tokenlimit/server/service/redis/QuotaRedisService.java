package com.tokenlimit.server.service.redis;

import com.tokenlimit.common.enums.Period;
import com.tokenlimit.server.config.TokenLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;

/**
 * 基于 Redis 的简单计数器配额服务（V5.0）.
 * <p>V5 不再使用 Lua 预扣减脚本：
 * <ul>
 *   <li>check 阶段：只读 used（GET），不做任何扣减/冻结。</li>
 *   <li>report 阶段：used += actual_tokens（INCRBY），并设置周期 TTL。</li>
 * </ul>
 * Redis 是实时缓存，MySQL 是事实来源（先写 MySQL，再更新 Redis）。</p>
 */
@Service
public class QuotaRedisService {

    private static final Logger log = LoggerFactory.getLogger(QuotaRedisService.class);

    private final StringRedisTemplate redisTemplate;
    private final TokenLimitProperties properties;

    public QuotaRedisService(StringRedisTemplate redisTemplate, TokenLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 读取当前已用量（V5 简单计数器，无预扣）。
     *
     * @param targetType team / user
     * @param targetCode 目标编码
     * @param limitType  TOKEN / CALL
     * @param period     周期
     * @param now        当前时间
     * @return 已用量
     */
    public long readUsed(String targetType, String targetCode, String limitType,
                         Period period, LocalDateTime now) {
        String key = QuotaKeyUtils.quotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        return readUsedByKey(key);
    }

    /**
     * 读取指定 key 的已用量.
     */
    public long readUsedByKey(String key) {
        String used = redisTemplate.opsForValue().get(key);
        return used == null ? 0 : Long.parseLong(used);
    }

    /**
     * 累加用量（report 阶段，调用后生效）.
     * <p>INCRBY 后若 key 为首次创建，设置周期 TTL 避免陈旧数据堆积。</p>
     *
     * @param targetType team / user
     * @param targetCode 目标编码
     * @param limitType  TOKEN / CALL
     * @param period     周期
     * @param now        当前时间
     * @param amount     累加量（实际 token 数或调用次数 1）
     * @return 累加后的用量
     */
    public long addUsed(String targetType, String targetCode, String limitType,
                        Period period, LocalDateTime now, long amount) {
        String key = QuotaKeyUtils.quotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        return addUsedByKey(key, period, now, amount);
    }

    /**
     * 对指定 key 累加用量.
     */
    public long addUsedByKey(String key, Period period, LocalDateTime now, long amount) {
        Long used = redisTemplate.opsForValue().increment(key, amount);
        if (used != null && used == amount) {
            // 首次创建，设置周期 TTL
            redisTemplate.expire(key, Duration.ofSeconds(QuotaKeyUtils.periodTtlSeconds(period, now)));
        }
        return used == null ? 0 : used;
    }

    /**
     * 根据规则信息构建配额 key（供上层复用）.
     */
    public String quotaKey(String targetType, String targetCode, String limitType,
                           Period period, LocalDateTime now) {
        return QuotaKeyUtils.quotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
    }

    // ==================== check 上下文（V5：traceId 关联，不做预扣） ====================

    /**
     * 保存 check 上下文：check 放行时记录 traceId → 上下文信息，report 时读取.
     * <p>仅用于 traceId 关联（teamCode/userCode/consumeFrom/预估值/规则），不涉及任何扣减。</p>
     * <p>Redis 异常时记录日志但不中断流程（check 已放行，report 时若上下文缺失会失败）。</p>
     *
     * @param traceId 追踪 ID
     * @param context 上下文（管道符分隔）
     */
    public void saveCheckContext(String traceId, String context) {
        try {
            String key = checkContextKey(traceId);
            redisTemplate.opsForValue().set(key, context,
                    Duration.ofSeconds(properties.getCheckContextTtlSeconds()));
        } catch (Exception e) {
            log.error("Redis 保存 check 上下文失败, traceId={}", traceId, e);
            // Redis 故障时不中断流程，report 时会因上下文缺失而失败
        }
    }

    /**
     * 读取 check 上下文.
     * <p>Redis 异常时返回 null，导致 report 失败（TRACE_NOT_FOUND）。</p>
     */
    public String getCheckContext(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(checkContextKey(traceId));
        } catch (Exception e) {
            log.error("Redis 读取 check 上下文失败, traceId={}", traceId, e);
            return null; // Redis 故障时返回 null，report 会失败
        }
    }

    /**
     * 删除 check 上下文（report 完成后清理）.
     * <p>Redis 异常时记录日志但不影响流程。</p>
     */
    public void deleteCheckContext(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            try {
                redisTemplate.delete(checkContextKey(traceId));
            } catch (Exception e) {
                log.error("Redis 删除 check 上下文失败, traceId={}", traceId, e);
                // 清理失败不影响主流程
            }
        }
    }

    private String checkContextKey(String traceId) {
        return properties.getRedisPrefix() + ":check:" + traceId;
    }
}
