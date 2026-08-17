package com.tokenlimit.server.service.redis;

import com.tokenlimit.common.enums.Period;
import com.tokenlimit.server.config.TokenLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 基于 Redis 的配额余额/预扣服务（V5.2）.
 * <p>双 key 模型（均缓存 Long 值，key 含 targetCode 即 userId/teamId）：</p>
 * <ul>
 *   <li><b>balance</b>：真实余额 = 配额上限 - 真实用量。真实用量来自 MySQL 用量聚合
 *       （首次访问时计算写入缓存，report 阶段原子扣减保持实时），周期 TTL 滚动重建。</li>
 *   <li><b>pre</b>：进行中请求的预扣总量。check 阶段按预估量原子 INCRBY（凭空写入），
 *       report 阶段 DECRBY 回滚。仅需一个原子操作控制，无需 Lua。</li>
 * </ul>
 * <p>预计算开关开启时判定：balance - pre &gt; 0 放行；并发下存在极小窗口超支 1 次调用（可接受）。</p>
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

    // ==================== balance（真实余额，来自 MySQL 聚合） ====================

    /**
     * 读取当前真实余额（balance = limit - used，used 来自 MySQL 聚合）.
     * <p>key 不存在返回 -1，由调用方触发惰性初始化（从 MySQL 重算写入）。</p>
     */
    public long readBalance(String targetType, String targetCode, String limitType,
                            Period period, LocalDateTime now) {
        String key = QuotaKeyUtils.balanceKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        String balance = redisTemplate.opsForValue().get(key);
        return balance == null ? -1 : Long.parseLong(balance);
    }

    /**
     * 惰性初始化余额：key 不存在时写入（limit - MySQL 聚合用量），存在则忽略.
     * <p>缓存维护（无缓存就去数据库加）：</p>
     * <ul>
     *   <li>key 缺失（首次访问/周期滚动/缓存丢失）→ SETNX 写入初始值；</li>
     *   <li>key 为负值（并发超支残留/异常扣减）→ 以 MySQL 聚合值覆盖重建，恢复正确余额；</li>
     *   <li>key 为正常非负值 → 保持不动（缓存优先，避免覆盖并发的实时扣减）。</li>
     * </ul>
     * <p>TTL 为周期剩余时间，周期滚动后 key 自动过期，下次访问重新从 MySQL 聚合。</p>
     */
    public void initBalanceIfAbsent(String targetType, String targetCode, String limitType,
                                    Period period, LocalDateTime now, long initialValue) {
        String key = QuotaKeyUtils.balanceKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        try {
            String current = redisTemplate.opsForValue().get(key);
            if (current == null) {
                Boolean set = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(initialValue));
                if (Boolean.TRUE.equals(set)) {
                    redisTemplate.expire(key, Duration.ofSeconds(QuotaKeyUtils.periodTtlSeconds(period, now)));
                }
            } else if (Long.parseLong(current) < 0) {
                // 负值残留（并发超支/异常扣减）：以 MySQL 聚合为准覆盖重建
                redisTemplate.opsForValue().set(key, String.valueOf(initialValue),
                        Duration.ofSeconds(QuotaKeyUtils.periodTtlSeconds(period, now)));
                log.info("Redis 余额负值重建, rule={}:{}:{}:{}, old={}, new={}",
                        targetType, targetCode, limitType, period, current, initialValue);
            }
        } catch (Exception e) {
            log.warn("Redis 初始化余额失败, rule={}:{}:{}:{}, value={}: {}",
                    targetType, targetCode, limitType, period, initialValue, e.getMessage());
        }
    }

    /**
     * 原子增减余额（report 阶段：balance -= 真实用量；delta 传负值）.
     * <p>INCRBY 后若为首次创建，设置周期 TTL。调用方需保证 key 已存在（未初始化时先走 {@link #initBalanceIfAbsent}）。</p>
     *
     * @return 增减后的余额
     */
    public long addBalance(String targetType, String targetCode, String limitType,
                           Period period, LocalDateTime now, long delta) {
        String key = QuotaKeyUtils.balanceKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        try {
            Long balance = redisTemplate.opsForValue().increment(key, delta);
            if (balance != null && balance == delta) {
                redisTemplate.expire(key, Duration.ofSeconds(QuotaKeyUtils.periodTtlSeconds(period, now)));
            }
            return balance == null ? 0 : balance;
        } catch (Exception e) {
            log.error("Redis 余额扣减失败 rule={}:{}:{}:{}, delta={}: {}",
                    targetType, targetCode, limitType, period, delta, e.getMessage());
            // Redis 故障时不中断流程：MySQL 已持久化，余额可从 MySQL 重新聚合恢复
            return 0;
        }
    }

    // ==================== pre（预扣值，原子增减） ====================

    /**
     * 读取当前预扣值（进行中请求的预估总量）.
     */
    public long readPre(String targetType, String targetCode, String limitType,
                        Period period, LocalDateTime now) {
        String key = QuotaKeyUtils.preQuotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        String pre = redisTemplate.opsForValue().get(key);
        return pre == null ? 0 : Long.parseLong(pre);
    }

    /**
     * 原子预扣（check 阶段，预计算开关开启时）.
     * <p>本次请求预估量凭空 INCRBY 写入；首次创建时设置周期 TTL（周期滚动自动清零）。</p>
     *
     * @return 预扣后的 pre 值
     */
    public long addPre(String targetType, String targetCode, String limitType,
                       Period period, LocalDateTime now, long amount) {
        if (amount <= 0) {
            return readPre(targetType, targetCode, limitType, period, now);
        }
        String key = QuotaKeyUtils.preQuotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        try {
            Long pre = redisTemplate.opsForValue().increment(key, amount);
            if (pre != null && pre == amount) {
                redisTemplate.expire(key, Duration.ofSeconds(QuotaKeyUtils.periodTtlSeconds(period, now)));
            }
            return pre == null ? 0 : pre;
        } catch (Exception e) {
            log.warn("Redis 预扣失败, rule={}:{}:{}:{}, amount={}: {}",
                    targetType, targetCode, limitType, period, amount, e.getMessage());
            if (properties.isRedisFallbackEnabled()) {
                return readPre(targetType, targetCode, limitType, period, now);
            }
            throw e;
        }
    }

    /**
     * 回滚预扣（report 阶段：pre -= 预扣量；减到 0 删除 key）.
     * <p>非原子补偿无需担心：pre 是本请求凭空写入的值，减到 0 即等价于从未预扣。</p>
     */
    public void rollbackPre(String targetType, String targetCode, String limitType,
                            Period period, LocalDateTime now, long amount) {
        if (amount <= 0) {
            return;
        }
        String key = QuotaKeyUtils.preQuotaKey(properties.getRedisPrefix(), targetType, targetCode,
                limitType, period, now);
        try {
            Long pre = redisTemplate.opsForValue().increment(key, -amount);
            if (pre != null && pre <= 0) {
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.warn("Redis 回滚预扣失败, rule={}:{}:{}:{}, amount={}: {}",
                    targetType, targetCode, limitType, period, amount, e.getMessage());
        }
    }

    // ==================== check 上下文（traceId 关联 check/report） ====================

    /**
     * 保存 check 上下文：check 放行时记录 traceId → 上下文信息，report 时读取.
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
