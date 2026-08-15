package com.tokenlimit.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
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
import com.tokenlimit.server.service.AuthSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人中心（PRD V4.0）.
 * <p>按登录用户返回其个人额度 / 用量 / 账单 / 流水 / API Key。</p>
 */
@RestController
@RequestMapping("/api/v1/my")
public class MyController {

    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final QuotaRuleMapper quotaRuleMapper;
    private final UsageLogMapper usageLogMapper;

    public MyController(UserMapper userMapper, TeamMapper teamMapper, ApiKeyMapper apiKeyMapper,
                        QuotaRuleMapper quotaRuleMapper, UsageLogMapper usageLogMapper) {
        this.userMapper = userMapper;
        this.teamMapper = teamMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.quotaRuleMapper = quotaRuleMapper;
        this.usageLogMapper = usageLogMapper;
    }

    /**
     * 我的概览.
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        User user = requireUser();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamCode", user.getTeamCode());
        data.put("userCode", user.getUserCode());
        data.put("userName", user.getUserName());
        data.put("quotaMode", user.getQuotaMode());

        Team team = teamMapper.selectOne(new LambdaQueryWrapper<Team>()
                .eq(Team::getTeamCode, user.getTeamCode()));
        data.put("teamName", team != null ? team.getTeamName() : null);

        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        // 今日/本月用量
        Long todayTokens = sumTokens(user, dayStart, LocalDateTime.now());
        Long monthTokens = sumTokens(user, monthStart, LocalDateTime.now());
        Long todayCalls = countCalls(user, dayStart, LocalDateTime.now());
        Long monthCostSum = sumCost(user, monthStart, LocalDateTime.now());

        data.put("todayTokens", todayTokens == null ? 0L : todayTokens);
        data.put("todayCalls", todayCalls == null ? 0L : todayCalls);
        data.put("monthTokens", monthTokens == null ? 0L : monthTokens);
        data.put("monthCost", monthCostSum == null ? 0L : monthCostSum);

        // 个人配额
        List<QuotaRule> userRules = quotaRuleMapper.selectList(new LambdaQueryWrapper<QuotaRule>()
                .eq(QuotaRule::getTargetType, "USER")
                .eq(QuotaRule::getTargetCode, user.getUserCode())
                .eq(QuotaRule::getStatus, "ENABLED"));
        long personalQuota = 0;
        for (QuotaRule rule : userRules) {
            if ("TOKEN".equals(rule.getLimitType())) {
                personalQuota += rule.getLimitValue().longValue();
            }
        }
        data.put("personalQuota", personalQuota);
        data.put("personalUsed", 0L);

        // 团队配额
        List<QuotaRule> teamRules = quotaRuleMapper.selectList(new LambdaQueryWrapper<QuotaRule>()
                .eq(QuotaRule::getTargetType, "TEAM")
                .eq(QuotaRule::getTargetCode, user.getTeamCode())
                .eq(QuotaRule::getStatus, "ENABLED"));
        long teamQuota = 0;
        for (QuotaRule rule : teamRules) {
            if ("TOKEN".equals(rule.getLimitType())) {
                teamQuota += rule.getLimitValue().longValue();
            }
        }
        data.put("teamQuota", teamQuota);
        data.put("teamUsed", monthTokens == null ? 0L : monthTokens);
        return Result.success(data);
    }

    /**
     * 我的额度明细.
     */
    @GetMapping("/quota")
    public Result<List<Map<String, Object>>> quota() {
        User user = requireUser();
        List<Map<String, Object>> items = new ArrayList<>();
        List<QuotaRule> rules = quotaRuleMapper.selectList(new LambdaQueryWrapper<QuotaRule>()
                .and(w -> w.and(x -> x.eq(QuotaRule::getTargetType, "USER")
                                .eq(QuotaRule::getTargetCode, user.getUserCode()))
                        .or(y -> y.eq(QuotaRule::getTargetType, "TEAM")
                                .eq(QuotaRule::getTargetCode, user.getTeamCode())))
                .eq(QuotaRule::getStatus, "ENABLED"));
        for (QuotaRule rule : rules) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("targetType", rule.getTargetType());
            item.put("targetCode", rule.getTargetCode());
            item.put("model", rule.getModel());
            item.put("limitType", rule.getLimitType());
            item.put("limitValue", rule.getLimitValue());
            item.put("period", rule.getPeriod());
            item.put("used", 0L);
            item.put("remain", rule.getLimitValue());
            items.add(item);
        }
        return Result.success(items);
    }

    /**
     * 我的用量.
     */
    @GetMapping("/usage")
    public Result<PageResult<UsageLog>> usage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        User user = requireUser();
        LambdaQueryWrapper<UsageLog> wrapper = new LambdaQueryWrapper<UsageLog>()
                .eq(UsageLog::getUserCode, user.getUserCode())
                .ge(startTime != null && !startTime.isBlank(), UsageLog::getCreatedAt, startTime)
                .le(endTime != null && !endTime.isBlank(), UsageLog::getCreatedAt, endTime)
                .orderByDesc(UsageLog::getCreatedAt);
        Page<UsageLog> p = usageLogMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    /**
     * 我的流水.
     */
    @GetMapping("/transactions")
    public Result<PageResult<UsageLog>> transactions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        User user = requireUser();
        LambdaQueryWrapper<UsageLog> wrapper = new LambdaQueryWrapper<UsageLog>()
                .eq(UsageLog::getUserCode, user.getUserCode())
                .orderByDesc(UsageLog::getCreatedAt);
        Page<UsageLog> p = usageLogMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    /**
     * 我的账单（按天聚合）.
     */
    @GetMapping("/bills")
    public Result<List<Map<String, Object>>> bills() {
        User user = requireUser();
        List<Map<String, Object>> bills = new ArrayList<>();
        List<UsageLog> logs = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                .eq(UsageLog::getUserCode, user.getUserCode())
                .orderByDesc(UsageLog::getCreatedAt));
        Map<String, long[]> byDay = new LinkedHashMap<>();
        for (UsageLog log : logs) {
            if (log.getCreatedAt() == null) {
                continue;
            }
            String day = log.getCreatedAt().toLocalDate().toString();
            long[] agg = byDay.computeIfAbsent(day, k -> new long[2]);
            agg[0] += log.getTotalTokens() == null ? 0 : log.getTotalTokens();
            agg[1] += 1;
        }
        byDay.forEach((day, agg) -> {
            Map<String, Object> bill = new LinkedHashMap<>();
            bill.put("period", day);
            bill.put("totalTokens", agg[0]);
            bill.put("callCount", agg[1]);
            bill.put("totalCost", 0);
            bills.add(bill);
        });
        return Result.success(bills);
    }

    /**
     * 我的 API Key.
     */
    @GetMapping("/api-keys")
    public Result<PageResult<ApiKey>> apiKeys(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        User user = requireUser();
        LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getUserCode, user.getUserCode())
                .orderByDesc(ApiKey::getCreatedAt);
        Page<ApiKey> p = apiKeyMapper.selectPage(new Page<>(page, size), wrapper);
        p.getRecords().forEach(k -> k.setSecretHash(null));
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    private User requireUser() {
        AuthSession.SessionInfo session = SecurityUtils.requireSession();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, session.getUsername())
                .last("limit 1"));
        if (user == null) {
            throw new com.tokenlimit.common.api.BusinessException(
                    com.tokenlimit.common.api.ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private Long sumTokens(User user, LocalDateTime start, LocalDateTime end) {
        List<UsageLog> logs = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                .select(UsageLog::getId, UsageLog::getTotalTokens)
                .eq(UsageLog::getUserCode, user.getUserCode())
                .ge(UsageLog::getCreatedAt, start)
                .le(UsageLog::getCreatedAt, end));
        return logs.stream()
                .map(l -> l.getTotalTokens() == null ? 0L : l.getTotalTokens())
                .reduce(0L, Long::sum);
    }

    private Long countCalls(User user, LocalDateTime start, LocalDateTime end) {
        return usageLogMapper.selectCount(new LambdaQueryWrapper<UsageLog>()
                .eq(UsageLog::getUserCode, user.getUserCode())
                .ge(UsageLog::getCreatedAt, start)
                .le(UsageLog::getCreatedAt, end));
    }

    private Long sumCost(User user, LocalDateTime start, LocalDateTime end) {
        return 0L; // MVP 阶段不计算费用
    }
}
