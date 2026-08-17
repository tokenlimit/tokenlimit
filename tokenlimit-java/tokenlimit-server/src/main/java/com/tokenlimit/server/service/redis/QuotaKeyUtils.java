package com.tokenlimit.server.service.redis;

import com.tokenlimit.common.enums.Period;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Redis Key 生成工具（V5.2）.
 *
 * <p>Key 结构：{prefix}:quota:{balance|pre}:{targetType}:{targetCode}:{limitType}:{period}:{timeKey}
 * <br>示例：tokenlimit:quota:balance:team:team-rd:TOKEN:DAY:20260813
 * <br>V5.2 双 key：balance 存真实余额（limit - used，used 来自 MySQL 用量聚合，Redis 仅缓存），
 * pre 存进行中请求的预扣总量（本次请求计算得出，原子 INCRBY/DECRBY）。
 * <br>timeKey 即周期时间片（MINUTE 为 yyyyMMddHHmm，HOUR 为 yyyyMMddHH，DAY 为 yyyyMMdd，WEEK 为 ISO 周 yyyy'W'ww，MONTH 为 yyyyMM，YEAR 为 yyyy，TOTAL 为 total）。</p>
 */
public final class QuotaKeyUtils {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter WEEK = DateTimeFormatter.ofPattern("yyyy'W'ww");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");

    private QuotaKeyUtils() {
    }

    /**
     * 生成配额余额 Key（balance：limit - used 的真实余额，used 来自 MySQL 用量聚合）.
     *
     * @param prefix     前缀
     * @param targetType 目标类型（team / user）
     * @param targetCode 目标编码（teamCode / userCode）
     * @param limitType  额度类型（TOKEN / COST / REQUEST_COUNT）
     * @param period     周期
     * @param now        当前时间
     * @return Redis Key
     */
    public static String balanceKey(String prefix, String targetType, String targetCode,
                                    String limitType, Period period, LocalDateTime now) {
        return key(prefix, "balance", targetType, targetCode, limitType, period, now);
    }

    /**
     * 生成配额预扣 Key（pre：进行中请求的预扣总量）.
     */
    public static String preQuotaKey(String prefix, String targetType, String targetCode,
                                     String limitType, Period period, LocalDateTime now) {
        return key(prefix, "pre", targetType, targetCode, limitType, period, now);
    }

    private static String key(String prefix, String segment, String targetType, String targetCode,
                              String limitType, Period period, LocalDateTime now) {
        String bucket = periodBucket(period, now);
        return prefix + ":quota:" + segment + ":"
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
            case WEEK -> now.format(WEEK);
            case MONTH -> now.format(MONTH);
            case YEAR -> now.format(YEAR);
            case TOTAL -> "total";
        };
    }

    /**
     * 计算周期起点（用于 MySQL 用量聚合：统计该周期内的真实用量）.
     * <p>WEEK 以周一 00:00 为起点（ISO 8601）。</p>
     */
    public static LocalDateTime periodStart(Period period, LocalDateTime now) {
        return switch (period) {
            case MINUTE -> now.withSecond(0).withNano(0);
            case HOUR -> now.withMinute(0).withSecond(0).withNano(0);
            case DAY -> now.toLocalDate().atStartOfDay();
            case WEEK -> now.toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay();
            case MONTH -> now.toLocalDate().withDayOfMonth(1).atStartOfDay();
            case YEAR -> now.toLocalDate().withDayOfYear(1).atStartOfDay();
            case TOTAL -> LocalDateTime.of(1970, 1, 1, 0, 0);
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
            case WEEK -> {
                LocalDateTime nextMonday = now.toLocalDate()
                        .with(DayOfWeek.MONDAY).plusWeeks(1).atStartOfDay();
                yield Duration.between(now, nextMonday).getSeconds();
            }
            case MONTH -> {
                long lastDay = now.toLocalDate().lengthOfMonth();
                yield (lastDay - now.getDayOfMonth() + 1) * 86400L
                        - (now.getHour() * 3600L + now.getMinute() * 60L + now.getSecond());
            }
            case YEAR -> {
                LocalDateTime nextYear = now.toLocalDate()
                        .withDayOfYear(1).plusYears(1).atStartOfDay();
                yield Duration.between(now, nextYear).getSeconds();
            }
            case TOTAL -> 365 * 86400L;
        };
    }
}
