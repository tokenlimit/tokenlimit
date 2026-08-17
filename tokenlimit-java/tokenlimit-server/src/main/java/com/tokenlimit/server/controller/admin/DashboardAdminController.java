package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.common.api.Result;
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
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.security.SessionInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：Dashboard 概览统计（PRD V4.0）.
 * <p>PRD 11.2：TEAM_ADMIN 只统计本 Team 数据（Team Dashboard）；ADMIN 查看全局。</p>
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN')")
public class DashboardAdminController {

    private final QuotaRuleMapper quotaRuleMapper;
    private final UsageLogMapper usageLogMapper;
    private final TeamMapper teamMapper;
    private final UserMapper userMapper;
    private final ApiKeyMapper apiKeyMapper;

    public DashboardAdminController(QuotaRuleMapper quotaRuleMapper,
                                    UsageLogMapper usageLogMapper, TeamMapper teamMapper,
                                    UserMapper userMapper, ApiKeyMapper apiKeyMapper) {
        this.quotaRuleMapper = quotaRuleMapper;
        this.usageLogMapper = usageLogMapper;
        this.teamMapper = teamMapper;
        this.userMapper = userMapper;
        this.apiKeyMapper = apiKeyMapper;
    }

    /**
     * 统计概览：团队数 / 用户数 / Key 数 / 规则数 / 今日 Token / 今日调用数 /
     * 今日费用（SUM(cost) 计费快照）/ 今日缓存命中率 / 今日缓存节省金额.
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        String teamCode = teamScope();
        long teams = teamCode == null ? teamMapper.selectCount(null)
                : teamMapper.selectCount(new LambdaQueryWrapper<Team>().eq(Team::getTeamCode, teamCode));
        long rules = quotaRuleMapper.selectCount(ruleScope(teamCode));
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LambdaQueryWrapper<UsageLog> todayWrapper = new LambdaQueryWrapper<UsageLog>()
                .ge(UsageLog::getCreatedAt, dayStart)
                .eq(teamCode != null, UsageLog::getTeamCode, teamCode);
        List<UsageLog> todayLogs = usageLogMapper.selectList(todayWrapper);
        long todayTokens = todayLogs.stream()
                .mapToLong(l -> l.getTotalTokens() == null ? 0 : l.getTotalTokens())
                .sum();
        long todayCalls = todayLogs.size();

        long users = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(teamCode != null, User::getTeamCode, teamCode));
        long apiKeys = apiKeyMapper.selectCount(new LambdaQueryWrapper<ApiKey>()
                .eq(teamCode != null, ApiKey::getTeamCode, teamCode));

        // 计费快照聚合（V5.3）：今日费用 = SUM(cost)，历史不可变
        BigDecimal todayCost = todayLogs.stream()
                .map(l -> l.getCost() == null ? BigDecimal.ZERO : l.getCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 缓存指标（V5.4）：命中率 = SUM(cached_tokens) / SUM(prompt_tokens)
        // 节省金额 = SUM(cached × (输入单价 - 缓存读取单价) × 汇率)，基于快照计算，与当前价格无关
        long todayPromptTokens = todayLogs.stream()
                .mapToLong(l -> l.getPromptTokens() == null ? 0 : l.getPromptTokens())
                .sum();
        long todayCachedTokens = todayLogs.stream()
                .mapToLong(l -> l.getCachedTokens() == null ? 0 : l.getCachedTokens())
                .sum();
        double todayCacheHitRate = todayPromptTokens > 0
                ? Math.round(todayCachedTokens * 10000.0 / todayPromptTokens) / 100.0 : 0.0;
        BigDecimal todayCacheSaved = BigDecimal.ZERO;
        for (UsageLog l : todayLogs) {
            Long cached = l.getCachedTokens();
            BigDecimal readPrice = l.getCacheReadPriceSnapshot();
            BigDecimal inputPrice = l.getInputPriceSnapshot();
            if (cached == null || cached <= 0 || readPrice == null || inputPrice == null) {
                continue;
            }
            BigDecimal rate = l.getExchangeRateSnapshot() == null
                    ? BigDecimal.ONE : l.getExchangeRateSnapshot();
            todayCacheSaved = todayCacheSaved.add(
                    BigDecimal.valueOf(cached).multiply(inputPrice.subtract(readPrice)).multiply(rate));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalTeams", teams);
        data.put("totalQuotas", rules);
        data.put("totalUsers", users);
        data.put("totalApiKeys", apiKeys);
        data.put("todayTokens", todayTokens);
        data.put("todayCalls", todayCalls);
        data.put("todayCost", todayCost.setScale(6, RoundingMode.HALF_UP));
        data.put("todayCacheHitRate", todayCacheHitRate);
        data.put("todayCacheSavedCost", todayCacheSaved.setScale(6, RoundingMode.HALF_UP));
        return Result.success(data);
    }

    /**
     * 近 N 天 Token 消耗趋势.
     */
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "7") int days) {
        String teamCode = teamScope();
        List<Map<String, Object>> result = new ArrayList<>();
        List<UsageLog> logs = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                .ge(UsageLog::getCreatedAt, LocalDate.now().minusDays(days - 1L).atStartOfDay())
                .eq(teamCode != null, UsageLog::getTeamCode, teamCode));
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (UsageLog log : logs) {
            String day = log.getCreatedAt().toLocalDate().toString();
            byDay.merge(day, log.getTotalTokens() == null ? 0 : log.getTotalTokens(), Long::sum);
        }
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("value", byDay.getOrDefault(date.toString(), 0L));
            result.add(item);
        }
        return Result.success(result);
    }

    /**
     * 高消耗团队 Top N.
     */
    @GetMapping("/top-teams")
    public Result<List<Map<String, Object>>> topTeams(@RequestParam(defaultValue = "5") int topN) {
        String teamCode = teamScope();
        List<UsageLog> logs = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                .ge(UsageLog::getCreatedAt, LocalDate.now().minusDays(30).atStartOfDay())
                .eq(teamCode != null, UsageLog::getTeamCode, teamCode));
        Map<String, Long> byTeam = new LinkedHashMap<>();
        for (UsageLog log : logs) {
            byTeam.merge(log.getTeamCode(), log.getTotalTokens() == null ? 0 : log.getTotalTokens(), Long::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        byTeam.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .forEach(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("teamCode", e.getKey());
                    item.put("tokens", e.getValue());
                    item.put("calls", 0L);
                    item.put("cost", 0);
                    item.put("teamName", resolveTeamName(e.getKey()));
                    result.add(item);
                });
        return Result.success(result);
    }

    private String resolveTeamName(String teamCode) {
        Team team = teamMapper.selectOne(new LambdaQueryWrapper<Team>().eq(Team::getTeamCode, teamCode).last("limit 1"));
        return team == null ? teamCode : team.getTeamName();
    }

    /**
     * 当前角色可见的团队范围：TEAM_ADMIN 返回本团队编码，ADMIN 返回 null（全局）.
     */
    private String teamScope() {
        SessionInfo session = SecurityUtils.requireSession();
        return "TEAM_ADMIN".equals(session.getRole()) ? session.getTeamCode() : null;
    }

    /**
     * 配额规则范围查询条件：TEAM_ADMIN 只统计本团队规则 + 本团队用户的 USER 规则.
     */
    private LambdaQueryWrapper<QuotaRule> ruleScope(String teamCode) {
        if (teamCode == null) {
            return null;
        }
        List<String> userCodes = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .eq(User::getTeamCode, teamCode))
                .stream().map(User::getUserCode).toList();
        return new LambdaQueryWrapper<QuotaRule>()
                .and(w -> w.and(i -> i.eq(QuotaRule::getTargetType, "TEAM")
                                .eq(QuotaRule::getTargetCode, teamCode))
                        .or()
                        .and(i -> i.eq(QuotaRule::getTargetType, "USER")
                                .in(QuotaRule::getTargetCode, userCodes)));
    }
}
