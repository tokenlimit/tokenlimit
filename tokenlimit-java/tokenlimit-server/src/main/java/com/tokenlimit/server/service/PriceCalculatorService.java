package com.tokenlimit.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.server.entity.ModelPrice;
import com.tokenlimit.server.entity.Setting;
import com.tokenlimit.server.repository.mapper.ModelPriceMapper;
import com.tokenlimit.server.repository.mapper.SettingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 价格计算引擎（V5.3 计费快照 / V5.4 缓存计费 / V5.5 峰谷定价）.
 * <p>动态读取 tl_model_price 价格表计算单次调用费用，并固化为计费快照：</p>
 * <ul>
 *   <li>价格单位：每 1 个 Token 的单价（数据库中已折算，计算时无需除以 1000000）；</li>
 *   <li>公式：cost = 正常输入 × 输入单价 + 缓存命中 × 缓存读取单价 + 缓存写入 × 缓存写入单价 + 输出 × 输出单价；</li>
 *   <li>多币种：按 tl_setting 全局汇率（usd_to_cny_rate 等）折算到企业本位币（base_currency）；</li>
 *   <li>未配置价格：按 0 费用处理（允许调用但无法统计成本，控制台应告警）；</li>
 *   <li>缓存单价未配置：缓存 token 按正常输入单价计费（等价无折扣）。</li>
 *   <li>V5.5 峰谷定价：根据请求时间判断峰谷时段，最终单价 = 基础单价 × 峰谷系数。</li>
 * </ul>
 * <p>调用方将计算结果连同单价/汇率/峰谷系数一起写入 usage_log 快照字段，历史费用永久固化；
 * 后续修改价格/汇率/峰谷规则只影响新调用，报表必须基于快照 SUM 聚合。</p>
 */
@Service
public class PriceCalculatorService {

    private static final Logger log = LoggerFactory.getLogger(PriceCalculatorService.class);

    private final ModelPriceMapper modelPriceMapper;
    private final SettingMapper settingMapper;

    public PriceCalculatorService(ModelPriceMapper modelPriceMapper, SettingMapper settingMapper) {
        this.modelPriceMapper = modelPriceMapper;
        this.settingMapper = settingMapper;
    }

    /**
     * 单次调用费用计算结果（计费快照原始数据）.
     *
     * @param currency            模型原始计价币种（USD/CNY）
     * @param inputPricePerToken  调用时输入单价（每 Token）
     * @param outputPricePerToken 调用时输出单价（每 Token）
     * @param exchangeRate        调用时汇率（原始币种→本位币）
     * @param baseCurrency        企业本位币
     * @param costOriginal        原始币种费用
     * @param costBase            本位币费用（核心扣费/报表字段）
     * @param cacheReadPricePerToken  调用时缓存读取单价（每 Token，未配置为 null）
     * @param cacheWritePricePerToken 调用时缓存写入单价（每 Token，未配置为 null）
     * @param priceMultiplier     调用时的峰谷价格系数（V5.5）
     */
    public record CostResult(String currency, BigDecimal inputPricePerToken,
                             BigDecimal outputPricePerToken, BigDecimal exchangeRate,
                             String baseCurrency, BigDecimal costOriginal, BigDecimal costBase,
                             BigDecimal cacheReadPricePerToken, BigDecimal cacheWritePricePerToken,
                             BigDecimal priceMultiplier) {
    }

    /**
     * 计算单次调用费用（含多币种换算、峰谷定价，保留 6 位小数）.
     * <p>未配置价格/未启用时按 0 计费（策略 A）；汇率缺失时按 1:1 兜底并记录日志。</p>
     * <p>缓存计费（V5.4）：输入成本 = (prompt - cached - write) × 输入单价 + cached × 缓存读取单价
     * + write × 缓存写入单价；未配置缓存单价时按正常输入单价计费（等价无折扣）。</p>
     * <p>峰谷定价（V5.5）：最终单价 = 基础单价 × 峰谷系数，系数固化为快照。</p>
     *
     * @param provider         供应商（如 deepseek/openai）
     * @param model            模型（如 deepseek-chat）
     * @param promptTokens     本次调用输入 token（真实值，缺失时用预估值）
     * @param completionTokens 本次调用输出 token（真实值，缺失时用预估值）
     * @param cachedTokens     缓存命中 token（OpenAI cached_tokens / DeepSeek prompt_cache_hit_tokens / Anthropic cache_read_input_tokens）
     * @param cacheWriteTokens 缓存写入 token（Anthropic cache_creation_input_tokens）
     * @param requestTime      请求发生时间（用于峰谷判断）
     */
    public CostResult calculateCost(String provider, String model, long promptTokens, long completionTokens,
                                    long cachedTokens, long cacheWriteTokens, LocalDateTime requestTime) {
        ModelPrice price = getPrice(provider, model);
        if (price == null) {
            return zeroResult();
        }
        
        // V5.5 峰谷定价：判断当前时间是否为低谷时段
        BigDecimal multiplier = calculatePriceMultiplier(price, requestTime);
        
        BigDecimal inputPrice = price.getInputPricePerToken() == null
                ? BigDecimal.ZERO : price.getInputPricePerToken();
        BigDecimal outputPrice = price.getOutputPricePerToken() == null
                ? BigDecimal.ZERO : price.getOutputPricePerToken();
        // 缓存单价未配置时按正常输入单价兜底（等价无折扣）
        BigDecimal cacheReadPrice = price.getCacheReadPricePerToken() == null
                ? inputPrice : price.getCacheReadPricePerToken();
        BigDecimal cacheWritePrice = price.getCacheWritePricePerToken() == null
                ? inputPrice : price.getCacheWritePricePerToken();

        // V5.5 应用峰谷系数：最终单价 = 基础单价 × 峰谷系数
        BigDecimal finalInputPrice = inputPrice.multiply(multiplier);
        BigDecimal finalOutputPrice = outputPrice.multiply(multiplier);
        BigDecimal finalCacheReadPrice = cacheReadPrice.multiply(multiplier);
        BigDecimal finalCacheWritePrice = cacheWritePrice.multiply(multiplier);

        // 防御：缓存 token 不允许为负数，且不超过输入总量（write 后再截断 read）
        long cached = Math.min(Math.max(cachedTokens, 0), promptTokens);
        long write = Math.min(Math.max(cacheWriteTokens, 0), promptTokens - cached);
        long normalInput = Math.max(promptTokens - cached - write, 0);

        // 原始币种费用：正常输入 × 输入单价 + 缓存命中 × 缓存读取单价 + 缓存写入 × 缓存写入单价 + 输出 × 输出单价
        BigDecimal inputCost = BigDecimal.valueOf(normalInput).multiply(finalInputPrice)
                .add(BigDecimal.valueOf(cached).multiply(finalCacheReadPrice))
                .add(BigDecimal.valueOf(write).multiply(finalCacheWritePrice));
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens).multiply(finalOutputPrice);
        BigDecimal costOriginal = inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);

        // 折算到企业本位币（同币种汇率 1）
        String baseCurrency = baseCurrency();
        BigDecimal exchangeRate = exchangeRate(price.getCurrency(), baseCurrency);
        BigDecimal costBase = costOriginal.multiply(exchangeRate).setScale(6, RoundingMode.HALF_UP);

        return new CostResult(price.getCurrency(), finalInputPrice, finalOutputPrice, exchangeRate,
                baseCurrency, costOriginal, costBase,
                price.getCacheReadPricePerToken() != null ? finalCacheReadPrice : null,
                price.getCacheWritePricePerToken() != null ? finalCacheWritePrice : null,
                multiplier);
    }
    
    /**
     * 计算峰谷价格系数（V5.5）.
     * <p>根据请求时间和模型配置的峰谷规则，返回对应的价格系数。</p>
     *
     * @param price 模型价格配置
     * @param requestTime 请求发生时间
     * @return 价格系数（高峰返回 peakMultiplier，低谷返回 offPeakMultiplier）
     */
    public BigDecimal calculatePriceMultiplier(ModelPrice price, LocalDateTime requestTime) {
        // 默认固定定价，系数为 1.0
        if (price.getPricingType() == null || !"PEAK_OFF_PEAK".equals(price.getPricingType())) {
            return BigDecimal.ONE;
        }
        
        LocalTime start = price.getOffPeakStart();
        LocalTime end = price.getOffPeakEnd();
        BigDecimal peakMultiplier = price.getPeakMultiplier() != null 
                ? price.getPeakMultiplier() : BigDecimal.ONE;
        BigDecimal offPeakMultiplier = price.getOffPeakMultiplier() != null 
                ? price.getOffPeakMultiplier() : BigDecimal.ONE;
        
        if (start == null || end == null) {
            return peakMultiplier;
        }
        
        LocalTime requestTimeOnly = requestTime.toLocalTime();
        boolean isOffPeak;
        
        // 情况 1: 低谷时段不跨天 (例如 01:00 - 06:00)
        if (start.isBefore(end)) {
            isOffPeak = !requestTimeOnly.isBefore(start) && requestTimeOnly.isBefore(end);
        }
        // 情况 2: 低谷时段跨天 (例如 22:00 - 次日 08:00)
        else {
            isOffPeak = !requestTimeOnly.isBefore(start) || requestTimeOnly.isBefore(end);
        }
        
        return isOffPeak ? offPeakMultiplier : peakMultiplier;
    }

    /**
     * 查询当前生效价格（ENABLED，provider + model 精确匹配）.
     */
    private ModelPrice getPrice(String provider, String model) {
        if (provider == null || model == null) {
            return null;
        }
        return modelPriceMapper.selectOne(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getProvider, provider)
                .eq(ModelPrice::getModel, model)
                .eq(ModelPrice::getStatus, "ENABLED")
                .last("limit 1"));
    }

    /**
     * 未配置价格的兜底结果：0 费用（策略 A，允许调用）.
     */
    private CostResult zeroResult() {
        String baseCurrency = baseCurrency();
        return new CostResult("CNY", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                baseCurrency, BigDecimal.ZERO, BigDecimal.ZERO, null, null, BigDecimal.ONE);
    }

    /**
     * 企业本位币（tl_setting.base_currency，默认 CNY）.
     */
    private String baseCurrency() {
        String v = settingValue("base_currency");
        return v == null || v.isBlank() ? "CNY" : v;
    }

    /**
     * 汇率（原始币种→本位币）：同币种为 1；其余读 tl_setting 的
     * {@code {currency}_to_{baseCurrency}_rate}（如 usd_to_cny_rate）；未配置按 1:1 兜底.
     */
    private BigDecimal exchangeRate(String currency, String baseCurrency) {
        if (currency == null || currency.equalsIgnoreCase(baseCurrency)) {
            return BigDecimal.ONE;
        }
        String key = currency.toLowerCase() + "_to_" + baseCurrency.toLowerCase() + "_rate";
        String v = settingValue(key);
        if (v == null || v.isBlank()) {
            log.warn("汇率未配置 {}，按 1:1 兜底计费", key);
            return BigDecimal.ONE;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            log.warn("汇率配置非法 {}={}，按 1:1 兜底计费", key, v);
            return BigDecimal.ONE;
        }
    }

    private String settingValue(String key) {
        Setting setting = settingMapper.selectOne(new LambdaQueryWrapper<Setting>()
                .eq(Setting::getSettingKey, key)
                .last("limit 1"));
        return setting == null ? null : setting.getSettingValue();
    }
}
