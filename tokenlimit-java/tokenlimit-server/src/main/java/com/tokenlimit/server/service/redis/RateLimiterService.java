package com.tokenlimit.server.service.redis;

import com.tokenlimit.server.config.TokenLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 接口级限流（Redis Lua 固定窗口，多实例共享）.
 * <p>对 OpenAI Compatible 网关按 API Key 限流，防止单 Key 打爆上游 Provider。
 * 固定窗口实现：{@code INCR + PEXPIRE} 原子执行；窗口内计数超过 {@code limit} 即拒绝。
 * Redis 故障时放行（限流失效但不阻断业务），由 {@code tokenlimit.rate-limit.enabled} 控制开关。</p>
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private static final String FIXED_WINDOW_LUA =
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then " +
            "  redis.call('PEXPIRE', KEYS[1], ARGV[1]) " +
            "end " +
            "return current";

    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT =
            new DefaultRedisScript<>(FIXED_WINDOW_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final TokenLimitProperties properties;

    public RateLimiterService(StringRedisTemplate redisTemplate, TokenLimitProperties properties) {
        this.redis = redisTemplate;
        this.properties = properties;
    }

    /**
     * 尝试获取一次调用配额.
     *
     * @param key         限流维度 key（如 API Key id），由调用方保证业务语义
     * @param limit       窗口内允许的最大请求数
     * @param windowMillis 窗口时长（毫秒）
     * @return true=放行；false=拒绝；Redis 异常时降级放行
     */
    public boolean tryAcquire(String key, int limit, long windowMillis) {
        try {
            Long count = redis.execute(FIXED_WINDOW_SCRIPT, List.of(rateKey(key)), windowMillis);
            return count == null || count <= limit;
        } catch (Exception e) {
            // 限流依赖 Redis：故障时降级放行，可用性优先
            log.warn("限流判断失败，降级放行 key={}", key);
            return true;
        }
    }

    /**
     * 网关是否启用限流（配置开关）.
     */
    public boolean isEnabled() {
        return properties.getRateLimit().isEnabled();
    }

    private String rateKey(String key) {
        return properties.getRedisPrefix() + ":ratelimit:" + key;
    }
}
