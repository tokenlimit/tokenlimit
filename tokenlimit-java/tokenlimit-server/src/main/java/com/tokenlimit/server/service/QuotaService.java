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
 * 配额核心服务（PRD V5.0）.
 *
 * <p>V5 采用<b>简单计数器模型</b>：</p>
 * <ul>
 *   <li>check：只读 Redis used，判定 used ≥ limit 则拦截；<b>不做预估预扣/冻结</b>。</li>
 *   <li>report：写 usage_log（含预估 + 真实值 + usage_source + 异常检测），再对 Redis used 累加真实值。</li>
 * </ul>
 *
 * <p>配额写入顺序：先写 MySQL（事实来源），后更新 Redis（实时缓存）。</p>
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

    public QuotaService(QuotaRuleMapper quotaRuleMapper, UsageLogMapper usageLogMapper,
                        ApiKeyMapper apiKeyMapper, TeamMapper teamMapper, UserMapper userMapper,
                        QuotaRedisService quotaRedisService, TokenLimitProperties properties) {
        this.quotaRuleMapper = quotaRuleMapper;
        this.usageLogMapper = usageLogMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.teamMapper = teamMapper;
        this.userMapper = userMapper;
        this.quotaRedisService = quotaRedisService;
        this.properties = properties;
    }

    /**
     * 配额检查（调用大模型前）.
     * <p>V5：读取当前 used，used ≥ limit 直接拦截；不预扣、不冻结。</p>
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

        // 第一步：Team 配额检查（简单计数器，只读）
        List<QuotaRule> teamRules = resolveRules(TargetType.TEAM, teamCode, model);
        String deniedTeam = checkAll(teamRules, now);
        if (deniedTeam != null) {
            return CheckResult.denied(ErrorCode.TEAM_QUOTA_EXCEEDED.name(), deniedTeam);
        }

        // 第二步：按 User.quotaMode 决定抵扣来源
        String quotaMode = StringUtils.hasText(user.getQuotaMode()) ? user.getQuotaMode() : "PERSONAL_FIRST_THEN_TEAM";
        String consumeFrom;
        List<QuotaRule> userRules = resolveRules(TargetType.USER, userCode, model);

        switch (quotaMode) {
            case "PERSONAL_ONLY" -> {
                String deniedUser = checkAll(userRules, now);
                if (deniedUser != null) {
                    return CheckResult.denied(ErrorCode.USER_QUOTA_EXCEEDED.name(), deniedUser);
                }
                consumeFrom = "PERSONAL";
            }
            case "TEAM_ONLY" -> consumeFrom = "TEAM";
            default -> {
                // PERSONAL_FIRST_THEN_TEAM：个人额度足够走个人，否则团队兜底（团队已检查）
                String deniedUser = checkAll(userRules, now);
                consumeFrom = deniedUser == null ? "PERSONAL" : "TEAM";
            }
        }

        // 生成 traceId 并保存 check 上下文（仅关联信息，不预扣）
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
     * <p>V5：先写 usage_log（事实来源），再累加 Redis used（实时缓存）；
     * usage_source 区分 PROVIDER / ESTIMATED，并对预估偏差做异常检测。</p>
     */
    @Transactional
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
        usageLogMapper.insert(usageLog);

        // 后更新 Redis（简单计数器：累加实际用量）
        LocalDateTime now = LocalDateTime.now();
        if (statTokens > 0) {
            List<QuotaRule> rules = parseRules(rulesInfo);
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
        // secret 双向校验
        if (!SecretUtils.verifySecret(secret, apiKey.getSecretHash())) {
            throw new BusinessException(ErrorCode.INVALID_API_KEY);
        }
        if (!StringUtils.hasText(apiKey.getUserCode())) {
            throw new BusinessException(ErrorCode.INVALID_API_KEY);
        }
        // 更新最后使用时间
        ApiKey update = new ApiKey();
        update.setId(apiKey.getId());
        update.setLastUsedAt(LocalDateTime.now());
        apiKeyMapper.updateById(update);
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
     * 简单计数器检查：任一规则 used ≥ limit 即视为超限，返回拒绝说明；全部通过返回 null.
     */
    private String checkAll(List<QuotaRule> rules, LocalDateTime now) {
        if (rules.isEmpty()) {
            return null;
        }
        for (QuotaRule rule : rules) {
            Period period = Period.valueOf(rule.getPeriod());
            long limit = rule.getLimitValue().longValue();
            long used = quotaRedisService.readUsed(
                    rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
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
            quotaRedisService.addUsed(rule.getTargetType(), rule.getTargetCode(),
                    rule.getLimitType(), period, now, amount);
        }
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
        sb.append('|').append(rulesSb);
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
            long limit = rule.getLimitValue().longValue();
            minRemain = Math.min(minRemain, Math.max(limit - used, 0));
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
