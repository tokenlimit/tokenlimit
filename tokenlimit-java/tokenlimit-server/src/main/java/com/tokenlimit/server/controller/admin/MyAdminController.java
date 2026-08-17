package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.common.enums.Period;
import com.tokenlimit.server.entity.QuotaRule;
import com.tokenlimit.server.entity.Team;
import com.tokenlimit.server.entity.UsageLog;
import com.tokenlimit.server.entity.User;
import com.tokenlimit.server.repository.mapper.QuotaRuleMapper;
import com.tokenlimit.server.repository.mapper.TeamMapper;
import com.tokenlimit.server.repository.mapper.UsageLogMapper;
import com.tokenlimit.server.repository.mapper.UserMapper;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.security.SessionInfo;
import com.tokenlimit.server.service.quota.QuotaUsageAggregator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
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
 * 管理端：当前登录用户视角的"我的"数据（PRD V5.0 控制台）.
 * <p>所有接口均从 {@link SecurityUtils#requireSession()} 取当前会话的 userCode/teamCode，
 * 不接受他人编码参数，防止越权查看他人数据。</p>
 * <p>配额口径复用 {@link QuotaUsageAggregator}（与责任链拦截器一致：
 * used = MySQL 聚合真实用量，remain = limit - used）；报表费用基于计费快照 SUM(cost)。</p>
 */
@RestController
@RequestMapping("/api/admin/my")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN', 'USER')")
public class MyAdminController {

    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final QuotaRuleMapper quotaRuleMapper;
    private final UsageLogMapper usageLogMapper;
    private final QuotaUsageAggregator quotaUsageAggregator;

    public MyAdminController(UserMapper userMapper, TeamMapper teamMapper,
                             QuotaRuleMapper quotaRuleMapper, UsageLogMapper usageLogMapper,
                             QuotaUsageAggregator quotaUsageAggregator) {
        this.userMapper = userMapper;
        this.teamMapper = teamMapper;
        this.quotaRuleMapper = quotaRuleMapper;
        this.usageLogMapper = usageLogMapper;
        this.quotaUsageAggregator = quotaUsageAggregator;
    }

    /**
     * 我的概览：身份信息、个人/团队配额（TOTAL 长期规则口径）、今日与本月用量统计.
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        SessionInfo session = SecurityUtils.requireSession();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserCode, session.getUserCode())
                .last("limit 1"));
        Team team = StringUtils.hasText(session.getTeamCode())
                ? teamMapper.selectOne(new LambdaQueryWrapper<Team>()
                .eq(Team::getTeamCode, session.getTeamCode()).last("limit 1"))
                : null;
        LocalDateTime now = LocalDateTime.now();

        // 个人/团队长期额度：TOTAL 周期 TOKEN 规则（V5.2 user-balance / team-balance 口径）
        long personalQuota = quotaLimit("USER", session.getUserCode());
        long teamQuota = StringUtils.hasText(session.getTeamCode())
                ? quotaLimit("TEAM", session.getTeamCode()) : 0L;
        long personalUsed = quotaUsageAggregator.aggregateUsed(
                "USER", session.getUserCode(), "TOKEN", Period.TOTAL, now);
        long teamUsed = StringUtils.hasText(session.getTeamCode())
                ? quotaUsageAggregator.aggregateUsed(
                "TEAM", session.getTeamCode(), "TOKEN", Period.TOTAL, now) : 0L;

        // 今日/本月统计（仅当前用户）
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        List<UsageLog> todayLogs = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                .eq(UsageLog::getUserCode, session.getUserCode())
                .ge(UsageLog::getCreatedAt, dayStart));
        List<UsageLog> monthLogs = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                .eq(UsageLog::getUserCode, session.getUserCode())
                .ge(UsageLog::getCreatedAt, monthStart));
        long todayTokens = todayLogs.stream()
                .mapToLong(l -> l.getTotalTokens() == null ? 0 : l.getTotalTokens()).sum();
        long monthTokens = monthLogs.stream()
                .mapToLong(l -> l.getTotalTokens() == null ? 0 : l.getTotalTokens()).sum();
        BigDecimal monthCost = monthLogs.stream()
                .map(l -> l.getCost() == null ? BigDecimal.ZERO : l.getCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamCode", session.getTeamCode());
        data.put("teamName", team == null ? null : team.getTeamName());
        data.put("userCode", session.getUserCode());
        data.put("userName", user == null ? session.getUserName() : user.getUserName());
        data.put("quotaMode", user == null ? null : user.getQuotaMode());
        data.put("personalQuota", personalQuota);
        data.put("personalUsed", personalUsed);
        data.put("teamQuota", teamQuota);
        data.put("teamUsed", teamUsed);
        data.put("todayTokens", todayTokens);
        data.put("todayCalls", todayLogs.size());
        data.put("monthTokens", monthTokens);
        data.put("monthCost", monthCost.setScale(2, RoundingMode.HALF_UP));
        return Result.success(data);
    }

    /**
     * 我的额度：个人与团队维度的配额规则明细（每条规则独立聚合 used/remain）.
     */
    @GetMapping("/quota")
    public Result<List<Map<String, Object>>> quota() {
        SessionInfo session = SecurityUtils.requireSession();
        LambdaQueryWrapper<QuotaRule> wrapper = new LambdaQueryWrapper<QuotaRule>()
                .and(w -> {
                    w.eq(QuotaRule::getTargetType, "USER")
                            .eq(QuotaRule::getTargetCode, session.getUserCode());
                    if (StringUtils.hasText(session.getTeamCode())) {
                        w.or().eq(QuotaRule::getTargetType, "TEAM")
                                .eq(QuotaRule::getTargetCode, session.getTeamCode());
                    }
                })
                .eq(QuotaRule::getStatus, "ENABLED")
                .orderByDesc(QuotaRule::getCreatedAt);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> result = new ArrayList<>();
        for (QuotaRule rule : quotaRuleMapper.selectList(wrapper)) {
            Period period;
            try {
                period = Period.valueOf(rule.getPeriod());
            } catch (Exception e) {
                continue; // 未知周期规则跳过
            }
            long used = quotaUsageAggregator.aggregateUsed(
                    rule.getTargetType(), rule.getTargetCode(), rule.getLimitType(), period, now);
            long limit = rule.getLimitValue() == null ? 0 : rule.getLimitValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("targetType", rule.getTargetType());
            item.put("targetCode", rule.getTargetCode());
            item.put("model", rule.getModel());
            item.put("limitType", rule.getLimitType());
            item.put("limitValue", limit);
            item.put("period", rule.getPeriod());
            item.put("used", used);
            item.put("remain", Math.max(limit - used, 0));
            result.add(item);
        }
        return Result.success(result);
    }

    /**
     * 我的流水：当前用户用量日志分页（traceId/模型/Token/费用/来源/状态）.
     */
    @GetMapping("/transactions")
    public Result<PageResult<Map<String, Object>>> transactions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        SessionInfo session = SecurityUtils.requireSession();
        Page<UsageLog> p = usageLogMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<UsageLog>()
                        .eq(UsageLog::getUserCode, session.getUserCode())
                        .orderByDesc(UsageLog::getCreatedAt));
        List<Map<String, Object>> records = p.getRecords().stream().map(l -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", l.getId());
            item.put("traceId", l.getTraceId());
            item.put("model", l.getModel());
            item.put("totalTokens", l.getTotalTokens());
            item.put("cost", l.getCost());
            item.put("consumeFrom", l.getConsumeFrom());
            item.put("status", l.getStatus());
            item.put("createdAt", l.getCreatedAt());
            return item;
        }).toList();
        return Result.success(new PageResult<>(page, size, p.getTotal(), records));
    }

    /**
     * 我的账单：按日聚合的用量与费用（近 90 天，账期倒序）.
     */
    @GetMapping("/bills")
    public Result<List<Map<String, Object>>> bills() {
        SessionInfo session = SecurityUtils.requireSession();
        List<UsageLog> logs = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                .eq(UsageLog::getUserCode, session.getUserCode())
                .ge(UsageLog::getCreatedAt, LocalDate.now().minusDays(89).atStartOfDay())
                .orderByDesc(UsageLog::getCreatedAt));
        // LinkedHashMap 保持按创建时间倒序的插入序（账期倒序）
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (UsageLog log : logs) {
            String day = log.getCreatedAt().toLocalDate().toString();
            Map<String, Object> item = byDay.computeIfAbsent(day, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("period", k);
                m.put("callCount", 0L);
                m.put("totalTokens", 0L);
                m.put("totalCost", BigDecimal.ZERO);
                return m;
            });
            item.put("callCount", (Long) item.get("callCount") + 1);
            item.put("totalTokens", (Long) item.get("totalTokens")
                    + (log.getTotalTokens() == null ? 0 : log.getTotalTokens()));
            item.put("totalCost", ((BigDecimal) item.get("totalCost"))
                    .add(log.getCost() == null ? BigDecimal.ZERO : log.getCost()));
        }
        byDay.values().forEach(m ->
                m.put("totalCost", ((BigDecimal) m.get("totalCost")).setScale(4, RoundingMode.HALF_UP)));
        return Result.success(new ArrayList<>(byDay.values()));
    }

    /**
     * 长期配额上限：TOTAL 周期 TOKEN 类型规则 limitValue 之和（未配置返回 0）.
     */
    private long quotaLimit(String targetType, String targetCode) {
        return quotaRuleMapper.selectList(new LambdaQueryWrapper<QuotaRule>()
                        .eq(QuotaRule::getTargetType, targetType)
                        .eq(QuotaRule::getTargetCode, targetCode)
                        .eq(QuotaRule::getPeriod, "TOTAL")
                        .eq(QuotaRule::getLimitType, "TOKEN")
                        .eq(QuotaRule::getStatus, "ENABLED"))
                .stream().mapToLong(r -> r.getLimitValue() == null ? 0 : r.getLimitValue())
                .sum();
    }
}
