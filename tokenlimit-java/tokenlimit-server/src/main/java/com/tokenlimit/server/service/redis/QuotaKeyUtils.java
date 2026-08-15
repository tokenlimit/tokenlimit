package com.tokenlimit.server.service.redis;

import com.tokenlimit.common.enums.Period;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Redis Key 生成工具（V5.0）.
 *
 * <p>Key 结构：{prefix}:quota:used:{targetType}:{targetCode}:{limitType}:{period}:{timeKey}
 * <br>示例：tokenlimit:quota:used:team:team-rd:TOKEN:DAY:20260813
 * <br>V5 不再包含 model 维度；timeKey 即周期时间片（DAY 为 yyyyMMdd，MONTH 为 yyyyMM，TOTAL 为 total）。</p>
 */
public final class QuotaKeyUtils {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private QuotaKeyUtils() {
    }

    /**
     * 生成配额使用量 Key（V5 简单计数器）.
     *
     * @param prefix     前缀
     * @param targetType 目标类型（team / user）
     * @param targetCode 目标编码（teamCode / userCode）
     * @param limitType  额度类型（TOKEN / CALL）
     * @param period     周期
     * @param now        当前时间
     * @return Redis Key
     */
    public static String quotaKey(String prefix, String targetType, String targetCode,
                                  String limitType, Period period, LocalDateTime now) {
        String bucket = periodBucket(period, now);
        return prefix + ":quota:used:"
                + targetType.toLowerCase() + ":"
                + targetCode + ":"
                + limitType.toUpperCase() + ":"
                + period.name() + ":"
                + bucket;
    }

    /**
     * 生成当前周期时间标识.
     */
    public static String periodBucket(Period period, LocalDateTime now) {
        return switch (period) {
            case MINUTE -> now.format(MINUTE);
            case HOUR -> now.format(HOUR);
            case DAY -> now.format(DAY);
            case MONTH -> now.format(MONTH);
            case TOTAL -> "total";
        };
    }

    /**
     * 计算周期剩余过期时间（秒），用于设置 key TTL.
     */
    public static long periodTtlSeconds(Period period, LocalDateTime now) {
        return switch (period) {
            case MINUTE -> 60 - now.getSecond();
            case HOUR -> 3600 - now.getMinute() * 60 - now.getSecond();
            case DAY -> {
                long elapsed = now.getHour() * 3600L + now.getMinute() * 60L + now.getSecond();
                yield 86400L - elapsed;
            }
            case MONTH -> {
                long lastDay = now.toLocalDate().lengthOfMonth();
                yield (lastDay - now.getDayOfMonth() + 1) * 86400L
                        - (now.getHour() * 3600L + now.getMinute() * 60L + now.getSecond());
            }
            case TOTAL -> 365 * 86400L;
        };
    }
}
