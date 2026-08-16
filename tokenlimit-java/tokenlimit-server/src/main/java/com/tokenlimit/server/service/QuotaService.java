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
 * 配额核心服务（PRD V5.1）.
 *
 * <p>双模式（{@code tokenlimit.quota-check-mode}）：</p>
 * <ul>
 *   <li><b>PREDUCT（默认，严格）</b>：check 阶段逐条规则 Lua 原子预扣（used + pre + est &gt; limit 拦截），
 *       防并发超卖；report 阶段回滚预扣、累加真实用量（jtokkit 预估 → 厂商真实 token）。</li>
 *   <li><b>CHECK_ONLY（宽松）</b>：check 只读 Redis used 判定（used ≥ limit 拦截），不预扣；
 *       并发下最后几次请求可能同时放行（超卖），report 阶段直接累加。</li>
 * </ul>
 *
 * <p>配额写入顺序：先写 MySQL（事实来源），后更新 Redis（实时缓存）。
 * Redis 预扣残留（check 后未 report）随周期 key TTL 自动清理。</p>
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

    public QuotaService(QuotaRuleMapper quotaRuleMapper, UsageLogMapper usageLogMapper,
                        ApiKeyMapper apiKeyMapper, TeamMapper teamMapper, UserMapper userMapper,
                        QuotaRedisService quotaRedisService, TokenLimitProperties properties,
                        UsageLogAsyncService usageLogAsyncService,
                        ApiKeyMetricsService apiKeyMetricsService) {
        this.quotaRuleMapper = quotaRuleMapper;
        this.usageLogMapper = usageLogMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.teamMapper = teamMapper;
        this.userMapper = userMapper;
        this.quotaRedisService = quotaRedisService;
        this.properties = properties;
        this.usageLogAsyncService = usageLogAsyncService;
        this.apiKeyMetricsService = apiKeyMetricsService;
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
     * <p>V5.1 双模式：</p>
     * <ul>
     *   <li>PREDUCT：先判断剩余额度（limit - used - pre）&gt; 0，再按 jtokkit 预估量原子预扣；
     *       任一规则预扣后剩余 &lt; 0 即拦截，并回滚已预扣规则。</li>
     *   <li>CHECK_ONLY：只读 used 判断（剩余 &lt; 0 拦截），不扣减；并发下最后一次请求可能漏拦。</li>
     * </ul>
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
        boolean preduct = isPreductMode();

        // 第一步：Team 配额（PREDUCT 预扣 / CHECK_ONLY 只读检查）
        List<QuotaRule> teamRules = resolveRules(TargetType.TEAM, teamCode, model);
        String deniedTeam = preduct ? preDeductRules(teamRules, now, estimatedTotalTokens)
                : checkAll(teamRules, now);
        if (deniedTeam != null) {
            return CheckResult.denied(ErrorCode.TEAM_QUOTA_EXCEEDED.name(), deniedTeam);
        }

        // 第二步：按 User.quotaMode 决定抵扣来源
        String quotaMode = StringUtils.hasText(user.getQuotaMode()) ? user.getQuotaMode() : "PERSONAL_FIRST_THEN_TEAM";
        String consumeFrom;
        List<QuotaRule> userRules = resolveRules(TargetType.USER, userCode, model);

        switch (quotaMode) {
            case "PERSONAL_ONLY" -> {
                String deniedUser = preduct ? preDeductRules(userRules, now, estimatedTotalTokens)
                        : checkAll(userRules, now);
                if (deniedUser != null) {
                    return CheckResult.denied(ErrorCode.USER_QUOTA_EXCEEDED.name(), deniedUser);
                }
                consumeFrom = "PERSONAL";
            }
            case "TEAM_ONLY" -> consumeFrom = "TEAM";
            default -> {
                // PERSONAL_FIRST_THEN_TEAM：个人额度足够走个人，否则团队兜底（团队已检查/预扣）
                String deniedUser = preduct ? preDeductRules(userRules, now, estimatedTotalTokens)
                        : checkAll(userRules, now);
                consumeFrom = deniedUser == null ? "PERSONAL" : "TEAM";
            }
        }

        // 生成 traceId 并保存 check 上下文（含检查模式，report 时按模式结算）
        String traceId = genTraceId();
        List<QuotaRule> appliedRules = mergeRules(userRules, teamRules);
        quotaRedisService.saveCheckContext(traceId,
                buildContext(teamCode, apiKey.getKeyId(), userCode, model,
                        estimatedPromptTokens, estimatedCompletionTokens, estimatedTotalTokens,
                        consumeFrom, appliedRules));

        CheckResult result = CheckResult.allowed(traceId, consumeFrom,
                readMinRemain(appliedRules, now));
        result.setEstimatedPromptTokens(estimatedPromptTokens);
        result.setEstimatedCompletionTokens(estimatedCompletionTokens);
        result.setEstimatedTotalTokens(estimatedTotalTokens);
        return result;
    }

    /**
     * 用量上报（大模型调用完成后）.
     * <p>V5.1：异步写 usage_log（事实来源），再按检查模式更新 Redis：
     * PREDUCT 回滚预扣 + 累加真实用量（厂商返回真实 token 数）；CHECK_ONLY 仅累加。
     * usage_source 区分 PROVIDER / ESTIMATED，并对预估偏差做异常检测。</p>
     */
    public ReportResult report(String traceId, String accessKey, String secret, String model,
                               long promptTokens, long completionTokens, long totalTokens,
                               String provider, String status, Long latencyMs,
                               long estimatedPromptTokens, long estimatedCompletionTokens,
                               long estimatedTotalTokens) {
        ApiKey apiKey = resolveApiKey(accessKey, secret);
        String context = quotaRedisService.getCheckContext(traceId);
        if (!StringUtils.hasText(context)) {
            throw new BusinessException(ErrorCode.TRACE_NOT_FOUND);
        }
        String[] parts = context.split("\\|");
        // parts: team|apiKeyId|user|model|estPrompt|estCompletion|estTotal|consumeFrom|rules
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
        // 检查模式（parts[9]，旧上下文无此字段时按 CHECK_ONLY 处理，避免误回滚）
        String checkMode = parts.length > 9 ? parts[9] : "CHECK_ONLY";

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

        // 写入 usage_log（先写 MySQL）
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
        usageLog.setCost(0L); // MVP 阶段不计算费用
        usageLog.setConsumeFrom(StringUtils.hasText(consumeFrom) ? consumeFrom : "TEAM");
        usageLog.setUsageSource(usageSource);
        usageLog.setStatus(usageStatus);
        usageLog.setAnomalyDetected(anomaly);
        usageLog.setAnomalyDetail(anomalyDetail);
        // 异步写入 usage_log（避免阻塞网关请求线程）
        usageLogAsyncService.saveUsageLog(usageLog);

        // 后更新 Redis（PREDUCT：回滚预扣 + 累加真实用量；CHECK_ONLY：仅累加）
        LocalDateTime now = LocalDateTime.now();
        List<QuotaRule> rules = parseRules(rulesInfo);
        if ("PREDUCT".equalsIgnoreCase(checkMode)) {
            // 无条件结算：即使无真实用量（statTokens=0，如鉴权失败/流中断）也要回滚预扣，避免预扣残留
            settlePreduct(rules, now, statTokens, estTotal, consumeFrom);
        } else if (statTokens > 0) {
            accumulate(rules, now, statTokens, consumeFrom);
        }

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
     * CHECK_ONLY 模式检查：任一规则 used ≥ limit 即视为超限，返回拒绝说明；全部通过返回 null.
     * <p>Redis 异常时根据 {@code tokenlimit.redis-fallback-enabled} 配置决定降级策略：
     * 启用时放行（可用性优先），禁用时抛出异常（一致性优先）。</p>
     */
    private String checkAll(List<QuotaRule> rules, LocalDateTime now) {
        if (rules.isEmpty()) {
            return null;
        }
        for (QuotaRule rule : rules) {
            Period period = Period.valueOf(rule.getPeriod());
            long limit = rule.getLimitValue().longValue();
            long used;
            try {
                used = quotaRedisService.readUsed(
                        rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
            } catch (Exception e) {
                log.warn("Redis 配额读取失败，降级策略: {}",
                        properties.isRedisFallbackEnabled() ? "放行" : "拒绝", e);
                if (properties.isRedisFallbackEnabled()) {
                    continue; // Redis 故障时放行（可用性优先）
                }
                throw e; // Redis 故障时拒绝（一致性优先）
            }
            if (used >= limit) {
                return "配额超限: " + rule.getTargetType() + "/" + rule.getTargetCode()
                        + " " + period + " 已用 " + used + " 上限 " + limit;
            }
        }
        return null;
    }

    /**
     * report 阶段累加：按规则 limitType 累加（TOKEN 累加 token 数，REQUEST_COUNT 累加 1）.
     * <p>consumeFrom=PERSONAL 时同时累加 Team 与 User；=TEAM 时仅累加 Team。</p>
     * <p>Redis 异常时记录日志但不中断流程（MySQL 是事实来源，Redis 可后续恢复）。</p>
     */
    private void accumulate(List<QuotaRule> rules, LocalDateTime now,
                            long statTokens, String consumeFrom) {
        for (QuotaRule rule : rules) {
            boolean userRule = "USER".equalsIgnoreCase(rule.getTargetType());
            // consumeFrom=TEAM 时仅累加团队规则（团队兜底，个人额度不动）
            if (userRule && "TEAM".equals(consumeFrom)) {
                continue;
            }
            long amount = LimitType.REQUEST_COUNT.name().equals(rule.getLimitType()) ? 1 : statTokens;
            Period period = Period.valueOf(rule.getPeriod());
            try {
                quotaRedisService.addUsed(rule.getTargetType(), rule.getTargetCode(),
                        rule.getLimitType(), period, now, amount);
            } catch (Exception e) {
                log.error("Redis 配额累加失败 rule={}:{}, amount={}",
                        rule.getTargetType(), rule.getTargetCode(), amount, e);
                // Redis 故障时不中断流程，MySQL 已持久化，Redis 可后续从 MySQL 恢复
            }
        }
    }

    /**
     * PREDUCT 模式预扣（check 阶段）：逐条规则 Lua 原子预扣（used + pre + est &gt; limit 拒绝）.
     * <p>预扣量 = jtokkit 预估总 token（REQUEST_COUNT 规则为 1）。任一规则拒绝时回滚已预扣规则；
     * limit ≤ 0 视为配置异常，不预扣也不拦截。全部成功返回 null。</p>
     */
    private String preDeductRules(List<QuotaRule> rules, LocalDateTime now, long estTotal) {
        if (rules.isEmpty()) {
            return null;
        }
        List<QuotaRule> deducted = new ArrayList<>();
        for (QuotaRule rule : rules) {
            long amount = LimitType.REQUEST_COUNT.name().equals(rule.getLimitType()) ? 1 : estTotal;
            Period period = Period.valueOf(rule.getPeriod());
            int result = quotaRedisService.preDeduct(rule.getTargetType(), rule.getTargetCode(),
                    rule.getLimitType(), period, now, amount, rule.getLimitValue().longValue());
            if (result == 0) {
                // 预扣后剩余 < 0：回滚已预扣规则，返回拒绝
                rollbackPreRules(deducted, now, estTotal);
                return "配额超限: " + rule.getTargetType() + "/" + rule.getTargetCode()
                        + " " + period + " 已用+预扣 超过上限 " + rule.getLimitValue().longValue();
            }
            if (result == 2) {
                continue; // limit ≤ 0 配置异常：不预扣、不拦截
            }
            deducted.add(rule);
        }
        return null;
    }

    /**
     * 回滚已预扣规则（check 拒绝 / PERSONAL_FIRST_THEN_TEAM 转团队兜底时补偿）.
     */
    private void rollbackPreRules(List<QuotaRule> rules, LocalDateTime now, long estTotal) {
        for (QuotaRule rule : rules) {
            long amount = LimitType.REQUEST_COUNT.name().equals(rule.getLimitType()) ? 1 : estTotal;
            Period period = Period.valueOf(rule.getPeriod());
            quotaRedisService.rollbackPre(rule.getTargetType(), rule.getTargetCode(),
                    rule.getLimitType(), period, now, amount);
        }
    }

    /**
     * PREDUCT 模式结算（report 阶段）：回滚预扣（与 check 预扣量一致）+ 累加真实用量.
     * <p>consumeFrom=PERSONAL 时同时结算 Team 与 User；=TEAM 时仅结算 Team（与 {@link #accumulate} 语义一致）。</p>
     */
    private void settlePreduct(List<QuotaRule> rules, LocalDateTime now,
                               long statTokens, long estTotal, String consumeFrom) {
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
            quotaRedisService.adjust(rule.getTargetType(), rule.getTargetCode(),
                    rule.getLimitType(), period, now, rollback, actual);
        }
    }

    private boolean isPreductMode() {
        return "PREDUCT".equalsIgnoreCase(properties.getQuotaCheckMode());
    }

    private String buildContext(String teamCode, String apiKeyId, String userCode, String model,
                                long estPrompt, long estCompletion, long estTotal,
                                String consumeFrom, List<QuotaRule> rules) {
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
                .append('|').append(isPreductMode() ? "PREDUCT" : "CHECK_ONLY");
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

    private long readMinRemain(List<QuotaRule> rules, LocalDateTime now) {
        if (rules.isEmpty()) {
            return -1;
        }
        long minRemain = Long.MAX_VALUE;
        for (QuotaRule rule : rules) {
            Period period = Period.valueOf(rule.getPeriod());
            long used = quotaRedisService.readUsed(
                    rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
            long pre = quotaRedisService.readPreUsed(
                    rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
            long limit = rule.getLimitValue().longValue();
            // 剩余额度 = limit - used - pre（PREDUCT 模式 pre 为进行中预扣；CHECK_ONLY 模式 pre 恒为 0）
            minRemain = Math.min(minRemain, Math.max(limit - used - pre, 0));
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
