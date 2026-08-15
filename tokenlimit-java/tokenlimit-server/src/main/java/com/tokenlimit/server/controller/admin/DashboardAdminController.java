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
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 管理端：Dashboard 概览统计（PRD V4.0）.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
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
     * 统计概览：团队数 / 用户数 / Key 数 / 规则数 / 今日 Token / 今日调用数.
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        long teams = teamMapper.selectCount(null);
        long rules = quotaRuleMapper.selectCount(null);
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LambdaQueryWrapper<UsageLog> todayWrapper = new LambdaQueryWrapper<UsageLog>()
                .ge(UsageLog::getCreatedAt, dayStart);
        List<UsageLog> todayLogs = usageLogMapper.selectList(todayWrapper);
        long todayTokens = todayLogs.stream()
                .mapToLong(l -> l.getTotalTokens() == null ? 0 : l.getTotalTokens())
                .sum();
        long todayCalls = todayLogs.size();

        long users = userMapper.selectCount(null);
        long apiKeys = apiKeyMapper.selectCount(null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalTeams", teams);
        data.put("totalQuotas", rules);
        data.put("totalUsers", users);
        data.put("totalApiKeys", apiKeys);
        data.put("todayTokens", todayTokens);
        data.put("todayCalls", todayCalls);
        data.put("todayCost", 0);
        return Result.success(data);
    }

    /**
     * 近 N 天 Token 消耗趋势.
     */
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "7") int days) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<UsageLog> logs = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                .ge(UsageLog::getCreatedAt, LocalDate.now().minusDays(days - 1L).atStartOfDay()));
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
        List<UsageLog> logs = usageLogMapper.selectList(new LambdaQueryWrapper<UsageLog>()
                .ge(UsageLog::getCreatedAt, LocalDate.now().minusDays(30).atStartOfDay()));
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
}
