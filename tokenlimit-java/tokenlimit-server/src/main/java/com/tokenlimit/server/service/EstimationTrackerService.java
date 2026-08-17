package com.tokenlimit.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.server.config.TokenLimitProperties;
import com.tokenlimit.server.entity.UsageLog;
import com.tokenlimit.server.repository.mapper.UsageLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * 预估偏差自学习（PRD 8.3 动态化）。
 *
 * <p>统计"jtokkit 预估值 → 供应商真实值"的比值 ratio = total / est（按模型维度）。
 * 首次使用时从 MySQL 读取该模型最近 {@value #SAMPLE_WINDOW} 条成功记录（usage_source=PROVIDER）
 * 生成初始均值，后续成功请求持续在线微调：前 {@value #SAMPLE_WINDOW} 条新样本为累计平均，
 * 之后保持 1/{@value #SAMPLE_WINDOW} 权重滑动平滑，均值趋于该模型稳态偏差。</p>
 *
 * <p>两个用途：</p>
 * <ul>
 *   <li><b>估算替代</b>：流式中断/供应商未返回 usage 时，用 est × avgRatio 估算真实消耗
 *       （配额扣减与计费都走调整值），比直接用预估值更接近真实；无统计时回退 1.0（与原行为一致）。</li>
 *   <li><b>动态异常检测</b>：样本充足（≥ estimation-min-samples）时，ratio 超过模型均值
 *       anomaly-ratio-factor 倍判异常；样本不足时用 anomaly-deviation-threshold 静态阈值兜底。</li>
 * </ul>
 *
 * <p>存储：Redis Hash（{prefix}:est:ratio，field=model，value=count|avgRatio），多实例共享；
 * 初始化用 SETNX 锁防并发（锁 TTL {@value #INIT_LOCK_TTL_SECONDS}s），失败仅告警不阻塞主流程。
 * Redis 故障时记录失败仅告警、读取回退默认值。极端样本限幅 [0.25, 4.0] 防污染。</p>
 */
@Service
public class EstimationTrackerService {

    private static final Logger log = LoggerFactory.getLogger(EstimationTrackerService.class);

    /** Redis Hash 后缀：{prefix}:est:ratio */
    private static final String HASH_SUFFIX = ":est:ratio";
    /** 初始化锁后缀：{prefix}:est:init:{model} */
    private static final String INIT_LOCK_SUFFIX = ":est:init:";
    /** 初始化锁 TTL（秒）：防多实例并发初始化 */
    private static final long INIT_LOCK_TTL_SECONDS = 30;
    /** 样本窗口：MySQL 初始化取最近 N 条；在线微调前 N 条为累计平均，之后固定 1/N 权重 */
    private static final long SAMPLE_WINDOW = 100;
    /** 无统计时的回退比值（直接用预估值） */
    private static final double DEFAULT_RATIO = 1.0;
    /** 极端样本限幅（真实/预估），防止异常样本污染均值 */
    private static final double RATIO_MIN = 0.25;
    private static final double RATIO_MAX = 4.0;

    private final StringRedisTemplate redisTemplate;
    private final TokenLimitProperties properties;
    private final UsageLogMapper usageLogMapper;

    public EstimationTrackerService(StringRedisTemplate redisTemplate, TokenLimitProperties properties,
                                    UsageLogMapper usageLogMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.usageLogMapper = usageLogMapper;
    }

    /**
     * 记录一次成功样本（供应商返回真实 usage 后调用）：按模型微调均值.
     */
    public void record(String model, long estTotal, long totalTokens) {
        if (!StringUtils.hasText(model) || estTotal <= 0 || totalTokens <= 0) {
            return;
        }
        ensureInitialized(model);
        double ratio = Math.max(RATIO_MIN, Math.min(RATIO_MAX, (double) totalTokens / estTotal));
        try {
            String key = hashKey();
            Object current = redisTemplate.opsForHash().get(key, model);
            if (current == null) {
                // 初始化未完成（并发/故障）：以本次样本作为首条，避免丢失学习机会
                redisTemplate.opsForHash().put(key, model, "1|" + ratio);
            } else {
                String[] parts = current.toString().split("\\|");
                long count = Long.parseLong(parts[0]);
                double avg = Double.parseDouble(parts[1]);
                // 自适应窗口平滑：前 SAMPLE_WINDOW 条累计平均（新样本权重 1/count），之后固定 1/SAMPLE_WINDOW
                long w = Math.min(count + 1, SAMPLE_WINDOW);
                double newAvg = avg + (ratio - avg) / w;
                redisTemplate.opsForHash().put(key, model, (count + 1) + "|" + newAvg);
            }
        } catch (Exception e) {
            log.warn("偏差样本记录失败, model={}: {}", model, e.getMessage());
        }
    }

    /**
     * 当前模型平均比值（真实/预估），无统计返回 1.0（直接用预估值）.
     */
    public double avgRatio(String model) {
        if (!StringUtils.hasText(model)) {
            return DEFAULT_RATIO;
        }
        ensureInitialized(model);
        try {
            Object value = redisTemplate.opsForHash().get(hashKey(), model);
            if (value != null) {
                return Double.parseDouble(value.toString().split("\\|")[1]);
            }
        } catch (Exception e) {
            log.warn("偏差均值读取失败, model={}: {}", model, e.getMessage());
        }
        return DEFAULT_RATIO;
    }

    /**
     * 用模型均值调整预估值：est × avgRatio（无统计时返回原值）.
     * <p>用于流式中断/无返回值的配额扣减与计费估算。</p>
     */
    public long adjust(String model, long estimate) {
        if (estimate <= 0) {
            return estimate;
        }
        return (long) Math.ceil(estimate * avgRatio(model));
    }

    /**
     * 异常检测：返回 null 表示正常，非 null 为异常详情.
     * <p>样本充足时动态判定（ratio &gt; 均值 × anomaly-ratio-factor）；样本不足时静态阈值兜底。</p>
     */
    public String anomalyDetail(String model, long estTotal, long totalTokens) {
        if (!StringUtils.hasText(model) || estTotal <= 0 || totalTokens <= 0) {
            return null;
        }
        ensureInitialized(model);
        double ratio = (double) totalTokens / estTotal;
        long count = sampleCount(model);
        if (count >= properties.getEstimationMinSamples()) {
            double avg = avgRatio(model);
            if (avg > 0 && ratio > avg * properties.getAnomalyRatioFactor()) {
                return String.format("预估 %d / 实际 %d，比值 %.2f 超过该模型均值 %.2f 的 %.0f 倍（样本 %d）",
                        estTotal, totalTokens, ratio, avg,
                        properties.getAnomalyRatioFactor(), count);
            }
        } else {
            double deviation = Math.abs((double) totalTokens - estTotal) / Math.max(totalTokens, estTotal);
            if (deviation > properties.getAnomalyDeviationThreshold()) {
                return String.format("预估 %d / 实际 %d，偏差 %.1f%% 超过静态阈值 %.0f%%（样本不足 %d，使用兜底）",
                        estTotal, totalTokens, deviation * 100,
                        properties.getAnomalyDeviationThreshold() * 100, properties.getEstimationMinSamples());
            }
        }
        return null;
    }

    /**
     * 惰性初始化：该模型无统计时，从 MySQL 读取最近 {@value #SAMPLE_WINDOW} 条成功记录生成初始均值.
     * <p>SETNX 锁防多实例并发初始化；初始化失败/无历史数据时保持无统计状态（回退默认值），下次再试。</p>
     */
    private void ensureInitialized(String model) {
        String key = hashKey();
        try {
            if (Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(key, model))) {
                return; // 已有统计，无需初始化
            }
        } catch (Exception e) {
            log.warn("偏差统计存在性检查失败, model={}: {}", model, e.getMessage());
            return;
        }
        String lock = properties.getRedisPrefix() + INIT_LOCK_SUFFIX + model;
        Boolean gotLock;
        try {
            gotLock = redisTemplate.opsForValue().setIfAbsent(lock, "1", Duration.ofSeconds(INIT_LOCK_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("偏差初始化锁获取失败, model={}: {}", model, e.getMessage());
            return;
        }
        if (!Boolean.TRUE.equals(gotLock)) {
            return; // 其他实例正在初始化，本次回退默认值
        }
        try {
            // double-check：等待锁期间其他实例可能已完成初始化，避免重复查询覆盖在线新样本
            if (Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(key, model))) {
                return;
            }
            List<UsageLog> history = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                    .select(UsageLog::getEstimatedTotalTokens, UsageLog::getTotalTokens)
                    .eq(UsageLog::getModel, model)
                    .eq(UsageLog::getUsageSource, "PROVIDER")
                    .eq(UsageLog::getStatus, "SUCCESS")
                    .gt(UsageLog::getEstimatedTotalTokens, 0)
                    .gt(UsageLog::getTotalTokens, 0)
                    .orderByDesc(UsageLog::getId)
                    .last("limit " + SAMPLE_WINDOW));
            long count = 0;
            double sum = 0;
            for (UsageLog usageLog : history) {
                long est = usageLog.getEstimatedTotalTokens();
                long total = usageLog.getTotalTokens();
                if (est <= 0 || total <= 0) {
                    continue;
                }
                sum += Math.max(RATIO_MIN, Math.min(RATIO_MAX, (double) total / est));
                count++;
            }
            if (count > 0) {
                redisTemplate.opsForHash().put(key, model, count + "|" + (sum / count));
                log.info("偏差均值初始化完成, model={}, 样本={}, avgRatio={}", model, count, sum / count);
            } else {
                // 无历史数据：写入占位符标记已初始化，避免每个请求重复查询 MySQL；
                // 后续 record() 以首条样本初始化（count=0 时新样本权重 1/1，均值=首条 ratio）
                redisTemplate.opsForHash().put(key, model, "0|" + DEFAULT_RATIO);
                log.info("偏差均值初始化完成（无历史样本）, model={}", model);
            }
        } catch (Exception e) {
            log.warn("偏差均值初始化失败, model={}: {}", model, e.getMessage());
        } finally {
            try {
                redisTemplate.delete(lock);
            } catch (Exception ignored) {
                // 锁释放失败：TTL 兜底过期
            }
        }
    }

    private long sampleCount(String model) {
        try {
            Object value = redisTemplate.opsForHash().get(hashKey(), model);
            if (value != null) {
                return Long.parseLong(value.toString().split("\\|")[0]);
            }
        } catch (Exception e) {
            log.warn("偏差样本数读取失败, model={}: {}", model, e.getMessage());
        }
        return 0;
    }

    private String hashKey() {
        return properties.getRedisPrefix() + HASH_SUFFIX;
    }
}
