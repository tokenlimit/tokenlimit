package com.tokenlimit.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.dto.CheckResult;
import com.tokenlimit.common.dto.ReportResult;
import com.tokenlimit.common.enums.LimitType;
import com.tokenlimit.common.enums.Period;
import com.tokenlimit.common.enums.TargetType;
import com.tokenlimit.common.enums.UsageStatus;
import com.tokenlimit.server.config.TokenLimitProperties;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.entity.QuotaRule;
import com.tokenlimit.server.entity.Team;
import com.tokenlimit.server.entity.UsageLog;
import com.tokenlimit.server.entity.User;
import com.tokenlimit.server.repository.mapper.ApiKeyMapper;
import com.tokenlimit.server.repository.mapper.QuotaRuleMapper;
import com.tokenlimit.server.repository.mapper.TeamMapper;
import com.tokenlimit.server.repository.mapper.UsageLogMapper;
import com.tokenlimit.server.repository.mapper.UserMapper;
import com.tokenlimit.server.service.quota.QuotaCheckContext;
import com.tokenlimit.server.service.quota.QuotaDenied;
import com.tokenlimit.server.service.quota.QuotaInterceptor;
import com.tokenlimit.server.service.quota.QuotaUsageAggregator;
import com.tokenlimit.server.service.redis.QuotaRedisService;
import com.tokenlimit.server.util.SecretUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 配额核心服务（PRD V5.2）.
 *
 * <p><b>责任链拦截</b>（{@code tokenlimit.quota-chain} 可配置顺序/裁剪，任一环节拒绝即拦截）：</p>
 * <ul>
 *   <li>{@code team-balance}：Team 余额拦截（TOTAL 周期长期规则）</li>
 *   <li>{@code user-balance}：个人余额拦截（TOTAL 周期长期规则，并确定抵扣来源 consumeFrom）</li>
 *   <li>{@code usage-period}：周期用量拦截（MONTH/WEEK/DAY/HOUR/MINUTE/YEAR 规则，含"每次请求" REQUEST_COUNT 限次）</li>
 * </ul>
 *
 * <p><b>预计算开关</b>（{@code tokenlimit.quota-precompute-enabled}）：</p>
 * <ul>
 *   <li><b>true（默认）</b>：调用大模型前按 jtokkit 预估量原子预扣（真实余额 - 预扣值 &gt; 0 才放行），
 *       调用结束后回滚预扣、按厂商返回真实 token 扣减余额；并发下存在极小窗口超支 1 次调用（可接受）。</li>
 *   <li><b>false</b>：仅判断余额不预扣，并发下最后几次请求可能同时放行（超卖）。</li>
 * </ul>
 *
 * <p><b>Redis 双 key</b>（均缓存 Long 值，key 含 targetCode 即 userId/teamId）：</p>
 * <ul>
 *   <li><b>balance</b>：真实余额 = 配额上限 - 真实用量（来自 MySQL usage_log 聚合，首次访问时写入缓存，
 *       report 阶段原子扣减保持实时），周期 TTL 滚动重建。</li>
 *   <li><b>pre</b>：进行中请求的预扣总量（本次请求预估量凭空写入，原子 INCRBY/DECRBY 控制，无需 Lua）。</li>
 * </ul>
 *
 * <p>余额变更发生在调用大模型结束（写 usage_log 后）：先写 MySQL（事实来源），后更新 Redis（实时缓存）。
 * 预扣残留（check 后未 report）随周期 key TTL 自动清理。</p>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final QuotaRuleMapper quotaRuleMapper;
    private final UsageLogMapper usageLogMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final TeamMapper teamMapper;
    private final UserMapper userMapper;
    private final QuotaRedisService quotaRedisService;
    private final TokenLimitProperties properties;
    private final UsageLogAsyncService usageLogAsyncService;
    private final ApiKeyMetricsService apiKeyMetricsService;
    private final QuotaUsageAggregator quotaUsageAggregator;
    private final PriceCalculatorService priceCalculatorService;
    /** 配额拦截责任链（按 tokenlimit.quota-chain 配置顺序执行） */
    private final LinkedHashMap<String, QuotaInterceptor> chain;

    public QuotaService(QuotaRuleMapper quotaRuleMapper, UsageLogMapper usageLogMapper,
                        ApiKeyMapper apiKeyMapper, TeamMapper teamMapper, UserMapper userMapper,
                        QuotaRedisService quotaRedisService, TokenLimitProperties properties,
                        UsageLogAsyncService usageLogAsyncService,
                        ApiKeyMetricsService apiKeyMetricsService,
                        QuotaUsageAggregator quotaUsageAggregator,
                        PriceCalculatorService priceCalculatorService,
                        List<QuotaInterceptor> interceptors) {
        this.quotaRuleMapper = quotaRuleMapper;
        this.usageLogMapper = usageLogMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.teamMapper = teamMapper;
        this.userMapper = userMapper;
        this.quotaRedisService = quotaRedisService;
        this.properties = properties;
        this.usageLogAsyncService = usageLogAsyncService;
        this.apiKeyMetricsService = apiKeyMetricsService;
        this.quotaUsageAggregator = quotaUsageAggregator;
        this.priceCalculatorService = priceCalculatorService;
        Map<String, QuotaInterceptor> byName = new LinkedHashMap<>();
        for (QuotaInterceptor interceptor : interceptors) {
            byName.put(interceptor.name(), interceptor);
        }
        this.chain = new LinkedHashMap<>();
        for (String name : properties.getQuotaChain()) {
            QuotaInterceptor interceptor = byName.get(name);
            if (interceptor != null) {
                chain.put(name, interceptor);
            }
        }
    }

    /**
     * 认证 API Key（网关入口）：双向校验 accessKey + secret，返回有效 ApiKey.
     * <p>供 ProxyGateway 在配额检查前获取 Team / User 上下文（模型策略校验、凭证解析）。</p>
     *
     * @throws BusinessException INVALID_API_KEY / API_KEY_DISABLED / API_KEY_EXPIRED
     */
    public ApiKey authenticate(String accessKey, String secret) {
        return resolveApiKey(accessKey, secret);
    }

    /**
     * 配额检查（调用大模型前）.
     * <p>V5.2 责任链拦截：按 {@code tokenlimit.quota-chain} 顺序执行拦截器，任一拒绝即返回；
     * 全部通过后（预计算开关开启时）对适用规则按 jtokkit 预估量原子预扣。</p>
     *
     * @param accessKey               access key
     * @param secret                  secret（双向校验）
     * @param model                   模型
     * @param estimatedPromptTokens   预估 prompt tokens（jtokkit）
     * @param estimatedCompletionTokens 预估 completion tokens（jtokkit）
     * @param estimatedTotalTokens    预估总 tokens
     */
    public CheckResult check(String accessKey, String secret, String model,
                             long estimatedPromptTokens, long estimatedCompletionTokens,
                             long estimatedTotalTokens) {
        ApiKey apiKey = resolveApiKey(accessKey, secret);
        if (estimatedTotalTokens > properties.getMaxEstimatedTokens()) {
            return CheckResult.denied("TOKEN_LIMIT_EXCEEDED",
                    "预估 token 超过单次上限 " + properties.getMaxEstimatedTokens());
        }

        String teamCode = apiKey.getTeamCode();
        String userCode = apiKey.getUserCode();

        // 校验 Team / User 状态（ENABLED / DISABLED）
        Team team = teamMapper.selectOne(new LambdaQueryWrapper<Team>()
                .eq(Team::getTeamCode, teamCode));
        if (team == null || !"ENABLED".equals(team.getStatus())) {
            return CheckResult.denied("TEAM_DISABLED", "所属团队已被禁用");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getTeamCode, teamCode)
                .eq(User::getUserCode, userCode));
        if (user == null || !"ENABLED".equals(user.getStatus())) {
            return CheckResult.denied("USER_DISABLED", "所属用户已被禁用");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean precompute = properties.isQuotaPrecomputeEnabled();

        // 解析 Team / User 配额规则（含全模型规则与指定模型规则，仅 ENABLED）
        List<QuotaRule> teamRules = resolveRules(TargetType.TEAM, teamCode, model);
        List<QuotaRule> userRules = resolveRules(TargetType.USER, userCode, model);
        String quotaMode = StringUtils.hasText(user.getQuotaMode()) ? user.getQuotaMode() : "PERSONAL_FIRST_THEN_TEAM";
        QuotaCheckContext ctx = new QuotaCheckContext(apiKey, quotaMode, teamRules, userRules,
                estimatedTotalTokens, precompute, now);

        // 责任链拦截：任一环节拒绝即返回（顺序由 tokenlimit.quota-chain 配置）
        for (QuotaInterceptor interceptor : chain.values()) {
            QuotaDenied denied = interceptor.check(ctx);
            if (denied != null) {
                return CheckResult.denied(denied.reason(), denied.message());
            }
        }

        // 统一预扣（预计算开关开启时）：对适用规则按预估量原子 INCRBY
        if (precompute) {
            preDeductApplied(ctx);
        }

        String consumeFrom = StringUtils.hasText(ctx.getConsumeFrom()) ? ctx.getConsumeFrom() : "TEAM";

        // 生成 traceId 并保存 check 上下文（含预计算标记，report 时按标记结算）
        String traceId = genTraceId();
        List<QuotaRule> appliedRules = mergeRules(userRules, teamRules);
        quotaRedisService.saveCheckContext(traceId,
                buildContext(teamCode, apiKey.getKeyId(), userCode, model,
                        estimatedPromptTokens, estimatedCompletionTokens, estimatedTotalTokens,
                        consumeFrom, appliedRules, precompute));

        CheckResult result = CheckResult.allowed(traceId, consumeFrom,
                readMinRemain(appliedRules, now));
        result.setEstimatedPromptTokens(estimatedPromptTokens);
        result.setEstimatedCompletionTokens(estimatedCompletionTokens);
        result.setEstimatedTotalTokens(estimatedTotalTokens);
        return result;
    }

    /**
     * 用量上报（大模型调用完成后）.
     * <p>V5.2：异步写 usage_log（事实来源，余额变更发生在此刻），再更新 Redis：
     * 预计算开启时先回滚预扣（与 check 预扣量一致），再按真实 token 原子扣减余额；
     * 关闭时仅扣减余额。usage_source 区分 PROVIDER / ESTIMATED，并对预估偏差做异常检测。</p>
     */
    public ReportResult report(String traceId, String accessKey, String secret, String model,
                               long promptTokens, long completionTokens, long totalTokens,
                               long cachedTokens, long cacheWriteTokens,
                               String provider, String status, Long latencyMs,
                               long estimatedPromptTokens, long estimatedCompletionTokens,
                               long estimatedTotalTokens) {
        ApiKey apiKey = resolveApiKey(accessKey, secret);
        String context = quotaRedisService.getCheckContext(traceId);
        if (!StringUtils.hasText(context)) {
            throw new BusinessException(ErrorCode.TRACE_NOT_FOUND);
        }
        String[] parts = context.split("\\|");
        // parts: team|apiKeyId|user|model|estPrompt|estCompletion|estTotal|consumeFrom|rules|precomputed
        String teamCode = parts[0];
        String apiKeyId = parts[1];
        if (!StringUtils.hasText(apiKeyId) || !apiKeyId.equals(apiKey.getKeyId())) {
            throw new BusinessException(ErrorCode.INVALID_API_KEY);
        }
        String contextUser = parts[2];
        long estPrompt = parts.length > 4 ? Long.parseLong(parts[4]) : 0;
        long estCompletion = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
        long estTotal = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
        String consumeFrom = parts.length > 7 ? parts[7] : "TEAM";
        String rulesInfo = parts.length > 8 ? parts[8] : "";
        // 预计算标记（parts[9]）：PRECOMPUTE=check 已预扣需回滚 / CHECK_ONLY=未预扣；
        // 旧上下文（V5.1 PREDUCT）按 PRECOMPUTE 处理（pre key 结构相同，回滚兼容）；V5.0 无此字段不回滚
        boolean precomputed = parts.length > 9 && !"CHECK_ONLY".equalsIgnoreCase(parts[9]);

        String usageStatus = StringUtils.hasText(status) ? status : UsageStatus.SUCCESS.name();
        boolean interrupted = UsageStatus.INTERRUPTED.name().equals(usageStatus);

        // usage_source 判定（PRD 8.4）
        //  - 正常完成 + 供应商返回 usage → PROVIDER
        //  - 流式中断 → ESTIMATED / INTERRUPTED
        //  - 供应商未返回 usage → ESTIMATED
        //  - 调用报错 → ESTIMATED / ERROR
        boolean providerUsage = !interrupted && totalTokens > 0;
        String usageSource = providerUsage ? "PROVIDER" : "ESTIMATED";

        // 配额统计值：PROVIDER 用真实值，ESTIMATED 用预估值
        long statTokens = providerUsage ? totalTokens
                : Math.max(estTotal, estimatedTotalTokens);

        // 异常检测：仅对 PROVIDER 且存在预估值时，偏差超过阈值标记 anomaly
        boolean anomaly = false;
        String anomalyDetail = null;
        if (providerUsage && estTotal > 0) {
            long max = Math.max(totalTokens, estTotal);
            if (max > 0) {
                double deviation = Math.abs((double) totalTokens - estTotal) / max;
                if (deviation > properties.getAnomalyDeviationThreshold()) {
                    anomaly = true;
                    anomalyDetail = String.format("预估 %d / 实际 %d，偏差 %.1f%% 超过阈值 %.0f%%",
                            estTotal, totalTokens, deviation * 100,
                            properties.getAnomalyDeviationThreshold() * 100);
                }
            }
        }

        // 写入 usage_log（先写 MySQL，余额变更发生在此刻）
        UsageLog usageLog = new UsageLog();
        usageLog.setTraceId(traceId);
        usageLog.setTeamCode(teamCode);
        usageLog.setApiKeyId(apiKeyId);
        usageLog.setUserCode(StringUtils.hasText(contextUser) ? contextUser : null);
        usageLog.setModel(model);
        usageLog.setProvider(StringUtils.hasText(provider) ? provider : null);
        usageLog.setEstimatedPromptTokens(Math.max(estPrompt, estimatedPromptTokens));
        usageLog.setEstimatedCompletionTokens(Math.max(estCompletion, estimatedCompletionTokens));
        usageLog.setEstimatedTotalTokens(Math.max(estTotal, estimatedTotalTokens));
        usageLog.setPromptTokens(promptTokens);
        usageLog.setCompletionTokens(completionTokens);
        usageLog.setTotalTokens(totalTokens);
        // 计费快照（V5.3）：动态读取价格表计算费用，单价/汇率固化到 usage_log，历史费用不可变
        // 缓存计费（V5.4）：PROVIDER 时按厂商返回的缓存 token 计算，ESTIMATED 时缓存按 0
        long billPrompt = providerUsage ? promptTokens : Math.max(estPrompt, estimatedPromptTokens);
        long billCompletion = providerUsage ? completionTokens : Math.max(estCompletion, estimatedCompletionTokens);
        PriceCalculatorService.CostResult billing = priceCalculatorService.calculateCost(
                StringUtils.hasText(provider) ? provider : null, model, billPrompt, billCompletion,
                providerUsage ? cachedTokens : 0, providerUsage ? cacheWriteTokens : 0);
        usageLog.setCost(billing.costBase());
        usageLog.setCostOriginal(billing.costOriginal());
        usageLog.setCurrency(billing.currency());
        usageLog.setInputPriceSnapshot(billing.inputPricePerToken());
        usageLog.setOutputPriceSnapshot(billing.outputPricePerToken());
        usageLog.setExchangeRateSnapshot(billing.exchangeRate());
        usageLog.setBaseCurrency(billing.baseCurrency());
        usageLog.setCachedTokens(providerUsage ? cachedTokens : 0);
        usageLog.setCacheWriteTokens(providerUsage ? cacheWriteTokens : 0);
        usageLog.setCacheReadPriceSnapshot(billing.cacheReadPricePerToken());
        usageLog.setCacheWritePriceSnapshot(billing.cacheWritePricePerToken());
        usageLog.setConsumeFrom(StringUtils.hasText(consumeFrom) ? consumeFrom : "TEAM");
        usageLog.setUsageSource(usageSource);
        usageLog.setStatus(usageStatus);
        usageLog.setAnomalyDetected(anomaly);
        usageLog.setAnomalyDetail(anomalyDetail);
        // 异步写入 usage_log（避免阻塞网关请求线程）
        usageLogAsyncService.saveUsageLog(usageLog);

        // 后更新 Redis：先回滚预扣（若预扣过），再按真实用量扣减余额
        LocalDateTime now = LocalDateTime.now();
        List<QuotaRule> rules = parseRules(rulesInfo);
        settle(rules, now, statTokens, estTotal, consumeFrom, precomputed);

        quotaRedisService.deleteCheckContext(traceId);

        long remain = readMinRemain(rulesInfo, now);
        return ReportResult.success(remain, usageLog.getId());
    }

    /**
     * 双向校验：根据 access key + secret 解析有效 API Key（PRD V5 错误码）.
     */
    private ApiKey resolveApiKey(String accessKey, String secret) {
        if (!StringUtils.hasText(accessKey) || !StringUtils.hasText(secret)) {
            throw new BusinessException(ErrorCode.INVALID_API_KEY);
        }
        ApiKey apiKey = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getAccessKey, accessKey.trim())
                .last("limit 1"));
        if (apiKey == null) {
            throw new BusinessException(ErrorCode.INVALID_API_KEY);
        }
        // 到期自动失效
        if (apiKey.getExpireAt() != null && !apiKey.getExpireAt().isAfter(LocalDateTime.now())) {
            if ("ENABLED".equals(apiKey.getStatus())) {
                ApiKey update = new ApiKey();
                update.setId(apiKey.getId());
                update.setStatus("EXPIRED");
                apiKeyMapper.updateById(update);
                log.info("API key auto expired, id={}, accessKey={}", apiKey.getId(), apiKey.getAccessKey());
            }
            throw new BusinessException(ErrorCode.API_KEY_EXPIRED);
        }
        if (!"ENABLED".equals(apiKey.getStatus())) {
            throw new BusinessException(ErrorCode.API_KEY_DISABLED);
        }
        // secret 双向校验（HMAC-SHA256 + 服务端 pepper）
        if (!SecretUtils.verifySecret(secret, apiKey.getSecretHash(), properties.getHashPepper())) {
            throw new BusinessException(ErrorCode.INVALID_API_KEY);
        }
        if (!StringUtils.hasText(apiKey.getUserCode())) {
            throw new BusinessException(ErrorCode.INVALID_API_KEY);
        }
        // 异步更新最后使用时间（降低 MySQL 写压力）
        apiKeyMetricsService.updateLastUsedAt(apiKey.getId());
        return apiKey;
    }

    /**
     * 解析对象适用的所有配额规则（含全模型规则与指定模型规则，仅 ENABLED）.
     */
    private List<QuotaRule> resolveRules(TargetType targetType, String targetCode, String model) {
        LambdaQueryWrapper<QuotaRule> wrapper = new LambdaQueryWrapper<QuotaRule>()
                .eq(QuotaRule::getTargetType, targetType.name())
                .eq(QuotaRule::getTargetCode, targetCode)
                .eq(QuotaRule::getStatus, "ENABLED")
                .and(w -> w.isNull(QuotaRule::getModel).or()
                        .eq(QuotaRule::getModel, "*").or()
                        .eq(QuotaRule::getModel, model));
        return quotaRuleMapper.selectList(wrapper);
    }

    /**
     * 统一预扣（check 放行后，预计算开关开启时）：对适用规则按预估量原子 INCRBY.
     * <p>预扣量 = jtokkit 预估总 token（REQUEST_COUNT 规则为 1）；consumeFrom=TEAM 时跳过个人规则
     * （团队兜底，个人额度不动，与结算语义一致）。预扣值凭空写入 pre key（周期 TTL），report 时回滚。</p>
     */
    private void preDeductApplied(QuotaCheckContext ctx) {
        String consumeFrom = ctx.getConsumeFrom();
        for (QuotaRule rule : ctx.getTeamRules()) {
            preDeductOne(rule, ctx, consumeFrom);
        }
        if ("PERSONAL".equals(consumeFrom)) {
            for (QuotaRule rule : ctx.getUserRules()) {
                preDeductOne(rule, ctx, consumeFrom);
            }
        }
    }

    private void preDeductOne(QuotaRule rule, QuotaCheckContext ctx, String consumeFrom) {
        boolean userRule = "USER".equalsIgnoreCase(rule.getTargetType());
        if (userRule && "TEAM".equals(consumeFrom)) {
            return; // 团队兜底：个人额度不动
        }
        long amount = amountFor(rule, ctx.getEstTotal());
        Period period = Period.valueOf(rule.getPeriod());
        // 原子 INCRBY 控制预扣值；Redis 异常时 addPre 内部已按 redis-fallback-enabled 降级
        quotaRedisService.addPre(rule.getTargetType(), rule.getTargetCode(),
                rule.getLimitType(), period, ctx.getNow(), amount);
    }

    /**
     * 结算（report 阶段）：先回滚预扣（预计算开启时），再按真实用量原子扣减余额.
     * <p>consumeFrom=PERSONAL 时同时结算 Team 与 User；=TEAM 时仅结算 Team（团队兜底，个人额度不动）。</p>
     * <p>余额扣减前惰性初始化 balance（key 缺失/负值时从 MySQL 聚合重建），保证扣减落盘；
     * Redis 异常只记录日志不中断流程（MySQL 已持久化，余额可从 MySQL 重新聚合恢复）。</p>
     */
    private void settle(List<QuotaRule> rules, LocalDateTime now, long statTokens,
                        long estTotal, String consumeFrom, boolean precomputed) {
        for (QuotaRule rule : rules) {
            boolean userRule = "USER".equalsIgnoreCase(rule.getTargetType());
            // consumeFrom=TEAM 时仅结算团队规则（团队兜底，个人额度不动）
            if (userRule && "TEAM".equals(consumeFrom)) {
                continue;
            }
            boolean requestCount = LimitType.REQUEST_COUNT.name().equals(rule.getLimitType());
            long rollback = requestCount ? 1 : estTotal;
            long actual = requestCount ? 1 : statTokens;
            Period period = Period.valueOf(rule.getPeriod());
            String targetType = rule.getTargetType();
            String targetCode = rule.getTargetCode();
            String limitType = rule.getLimitType();

            // 1) 回滚预扣（与 check 预扣量一致）
            if (precomputed) {
                quotaRedisService.rollbackPre(targetType, targetCode, limitType, period, now, rollback);
            }

            // 2) 真实扣减余额（balance = limit - used，扣减发生在调用结束）
            if (actual <= 0) {
                continue;
            }
            try {
                long balance = quotaRedisService.readBalance(targetType, targetCode, limitType, period, now);
                if (balance < 0) {
                    long init = rule.getLimitValue().longValue() - quotaUsageAggregator.aggregateUsed(
                            targetType, targetCode, limitType, period, now);
                    quotaRedisService.initBalanceIfAbsent(targetType, targetCode, limitType, period, now, init);
                }
                quotaRedisService.addBalance(targetType, targetCode, limitType, period, now, -actual);
            } catch (Exception e) {
                log.error("Redis 余额扣减失败 rule={}:{}, amount={}",
                        targetType, targetCode, actual, e);
                // Redis 故障时不中断流程，MySQL 已持久化，余额可从 MySQL 重新聚合恢复
            }
        }
    }

    /**
     * 本次规则扣减量：REQUEST_COUNT 规则为 1，其余为预估总 token.
     */
    private long amountFor(QuotaRule rule, long estTotal) {
        return LimitType.REQUEST_COUNT.name().equals(rule.getLimitType()) ? 1 : estTotal;
    }

    private String buildContext(String teamCode, String apiKeyId, String userCode, String model,
                                long estPrompt, long estCompletion, long estTotal,
                                String consumeFrom, List<QuotaRule> rules, boolean precompute) {
        StringBuilder sb = new StringBuilder();
        sb.append(teamCode).append('|')
                .append(StringUtils.hasText(apiKeyId) ? apiKeyId : "").append('|')
                .append(StringUtils.hasText(userCode) ? userCode : "").append('|')
                .append(model == null ? "" : model).append('|')
                .append(estPrompt).append('|')
                .append(estCompletion).append('|')
                .append(estTotal).append('|')
                .append(consumeFrom);
        StringBuilder rulesSb = new StringBuilder();
        for (QuotaRule rule : rules) {
            if (rulesSb.length() > 0) {
                rulesSb.append(',');
            }
            rulesSb.append(rule.getTargetType()).append(':')
                    .append(rule.getTargetCode()).append(':')
                    .append(rule.getLimitType()).append(':')
                    .append(rule.getPeriod()).append(':')
                    .append(rule.getLimitValue().longValue());
        }
        sb.append('|').append(rulesSb)
                .append('|').append(precompute ? "PRECOMPUTE" : "CHECK_ONLY");
        return sb.toString();
    }

    private List<QuotaRule> mergeRules(List<QuotaRule> userRules, List<QuotaRule> teamRules) {
        List<QuotaRule> merged = new ArrayList<>(userRules);
        merged.addAll(teamRules);
        return merged;
    }

    private List<QuotaRule> parseRules(String rulesInfo) {
        List<QuotaRule> rules = new ArrayList<>();
        if (!StringUtils.hasText(rulesInfo)) {
            return rules;
        }
        for (String ruleStr : rulesInfo.split(",")) {
            if (ruleStr.isBlank()) {
                continue;
            }
            String[] f = ruleStr.split(":");
            QuotaRule rule = new QuotaRule();
            rule.setTargetType(f[0]);
            rule.setTargetCode(f[1]);
            rule.setLimitType(f[2]);
            rule.setPeriod(f[3]);
            rule.setLimitValue(Long.parseLong(f[4]));
            rules.add(rule);
        }
        return rules;
    }

    /**
     * 读取全部适用规则的最小剩余额度（balance - pre），用于 check/report 响应展示.
     * <p>Redis 缓存缺失/负值时从 MySQL 聚合重建；Redis 故障时返回 -1（未知），不中断主流程。</p>
     */
    private long readMinRemain(List<QuotaRule> rules, LocalDateTime now) {
        if (rules.isEmpty()) {
            return -1;
        }
        long minRemain = Long.MAX_VALUE;
        for (QuotaRule rule : rules) {
            Period period = Period.valueOf(rule.getPeriod());
            long limit = rule.getLimitValue().longValue();
            try {
                long balance = quotaRedisService.readBalance(
                        rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
                if (balance < 0) {
                    balance = limit - quotaUsageAggregator.aggregateUsed(
                            rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
                }
                long pre = quotaRedisService.readPre(
                        rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
                // 剩余额度 = balance - pre（预计算关闭时 pre 恒为 0）
                minRemain = Math.min(minRemain, Math.max(balance - pre, 0));
            } catch (Exception e) {
                log.warn("Redis 剩余额度读取失败, rule={}:{}", rule.getTargetType(), rule.getTargetCode(), e);
                return -1;
            }
        }
        return minRemain == Long.MAX_VALUE ? -1 : minRemain;
    }

    private long readMinRemain(String rulesInfo, LocalDateTime now) {
        if (!StringUtils.hasText(rulesInfo)) {
            return -1;
        }
        return readMinRemain(parseRules(rulesInfo), now);
    }

    private String genTraceId() {
        return "trace-" + UUID.randomUUID().toString().replace("-", "");
    }
}
