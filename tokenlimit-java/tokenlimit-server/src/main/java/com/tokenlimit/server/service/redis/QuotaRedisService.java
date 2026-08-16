package com.tokenlimit.server.service.redis;

import com.tokenlimit.common.enums.Period;
import com.tokenlimit.server.config.TokenLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis 的配额服务（V5.1）.
 * <p>双模式（{@code tokenlimit.quota-check-mode}）：</p>
 * <ul>
 *   <li><b>PREDUCT（默认，严格）</b>：check 阶段用 Lua 原子预扣（used + pre + est &gt; limit 拒绝），
 *       report 阶段回滚预扣、累加真实用量。used 与 pre 分离，used 与 MySQL 聚合一致。</li>
 *   <li><b>CHECK_ONLY（宽松）</b>：check 只读 used 判断（used ≥ limit 拒绝），不扣减，
 *       并发下最后几次请求可能同时放行（超卖），report 阶段直接累加。</li>
 * </ul>
 * Redis 是实时缓存，MySQL 是事实来源（先写 MySQL，再更新 Redis）。
 * 预扣残留（check 后未 report）随周期 key TTL 自动清理。</p>
 */
@Service
public class QuotaRedisService {

    private static final Logger log = LoggerFactory.getLogger(QuotaRedisService.class);

    /** Lua 原子预扣脚本（check 阶段）：used + pre + delta &gt; limit 拒绝，否则 pre += delta */
    private static final DefaultRedisScript<Long> PRE_DEDUCT_SCRIPT = new DefaultRedisScript<>(
            loadScript("lua/quota_deduct.lua"), Long.class);

    /** Lua 结算脚本（report 阶段）：回滚预扣（pre -= rollback）+ 累加真实用量（used += actual） */
    private static final DefaultRedisScript<Long> ADJUST_SCRIPT = new DefaultRedisScript<>(
            loadScript("lua/quota_adjust.lua"), Long.class);

    /**
     * 读取 classpath 下的 Lua 脚本内容（UTF-8，规避平台默认编码差异）.
     */
    private static String loadScript(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Lua script: " + path, e);
        }
    }

    private final StringRedisTemplate redisTemplate;
    private final TokenLimitProperties properties;

    public QuotaRedisService(StringRedisTemplate redisTemplate, TokenLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 读取当前已用量（used：已完成调用的真实用量）.
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
     * 读取当前预扣量（pre：进行中请求的预扣总和，PREDUCT 模式）.
     * <p>用于计算剩余额度（limit - used - pre）展示；check 阶段的判定在 Lua 脚本内原子完成。</p>
     */
    public long readPreUsed(String targetType, String targetCode, String limitType,
                            Period period, LocalDateTime now) {
        String key = QuotaKeyUtils.preQuotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        String pre = redisTemplate.opsForValue().get(key);
        return pre == null ? 0 : Long.parseLong(pre);
    }

    /**
     * 累加用量（report 阶段 CHECK_ONLY 模式，调用后生效）.
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

    // ==================== 预扣减（PREDUCT 模式） ====================

    /**
     * 原子预扣（check 阶段，PREDUCT 模式）.
     * <p>Lua 脚本内完成 检查 + 预扣：used + pre + delta &gt; limit 返回 0（拒绝，本次不扣），
     * 否则 pre += delta（预扣成功）。单条规则一次调用，天然防并发超卖。</p>
     *
     * @return 1=预扣成功 / 0=超限拒绝 / 2=上限配置异常（limit ≤ 0）
     */
    public int preDeduct(String targetType, String targetCode, String limitType,
                         Period period, LocalDateTime now, long amount, long limit) {
        String usedKey = QuotaKeyUtils.quotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        String preKey = QuotaKeyUtils.preQuotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        try {
            Long result = redisTemplate.execute(PRE_DEDUCT_SCRIPT,
                    List.of(usedKey, preKey),
                    String.valueOf(amount), String.valueOf(limit),
                    String.valueOf(QuotaKeyUtils.periodTtlSeconds(period, now)));
            return result == null ? 2 : result.intValue();
        } catch (Exception e) {
            log.warn("Redis 配额预扣失败, rule={}:{}:{}:{}, amount={}: {}",
                    targetType, targetCode, limitType, period, amount, e.getMessage());
            // Redis 异常时按配置降级：放行（不预扣，report 阶段直接真实累加）或抛出（拒绝）
            if (properties.isRedisFallbackEnabled()) {
                return 1;
            }
            throw e;
        }
    }

    /**
     * 结算（report 阶段，PREDUCT 模式）：回滚预扣 + 累加真实用量.
     * <p>Lua 脚本内完成：pre -= rollback（减到 0 删 key），used += actual（首次创建设置周期 TTL）。</p>
     *
     * @param rollbackAmount 预扣回滚量（与 check 阶段预扣量一致；0 表示未预扣）
     * @param actualAmount   真实扣减量（厂商返回真实 token 数 / 调用次数 1；0 表示无用量）
     * @return 累加后的 used 值
     */
    public long adjust(String targetType, String targetCode, String limitType,
                       Period period, LocalDateTime now, long rollbackAmount, long actualAmount) {
        String usedKey = QuotaKeyUtils.quotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        String preKey = QuotaKeyUtils.preQuotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        try {
            Long used = redisTemplate.execute(ADJUST_SCRIPT,
                    List.of(usedKey, preKey),
                    String.valueOf(rollbackAmount), String.valueOf(actualAmount),
                    String.valueOf(QuotaKeyUtils.periodTtlSeconds(period, now)));
            return used == null ? 0 : used;
        } catch (Exception e) {
            log.error("Redis 配额结算失败 rule={}:{}:{}:{}, rollback={}, actual={}: {}",
                    targetType, targetCode, limitType, period, rollbackAmount, actualAmount, e.getMessage());
            // Redis 故障时不中断流程：MySQL 已持久化，Redis 可后续从 MySQL 恢复
            return 0;
        }
    }

    /**
     * 回滚预扣（check 阶段多规则预扣部分失败时补偿；或 report 前异常兜底）.
     * <p>非原子补偿操作，pre 减到 0 时删除 key。</p>
     */
    public void rollbackPre(String targetType, String targetCode, String limitType,
                            Period period, LocalDateTime now, long amount) {
        if (amount <= 0) {
            return;
        }
        try {
            String preKey = QuotaKeyUtils.preQuotaKey(properties.getRedisPrefix(), targetType, targetCode,
                    limitType, period, now);
            Long pre = redisTemplate.opsForValue().increment(preKey, -amount);
            if (pre != null && pre <= 0) {
                redisTemplate.delete(preKey);
            }
        } catch (Exception e) {
            log.warn("Redis 回滚预扣失败, rule={}:{}:{}:{}, amount={}",
                    targetType, targetCode, limitType, period, amount, e);
        }
    }

    /**
     * 根据规则信息构建配额 key（供上层复用）.
     */
    public String quotaKey(String targetType, String targetCode, String limitType,
                           Period period, LocalDateTime now) {
        return QuotaKeyUtils.quotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
    }

    // ==================== check 上下文（traceId 关联 check/report） ====================

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
