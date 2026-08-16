package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.entity.Team;
import com.tokenlimit.server.entity.User;
import com.tokenlimit.server.repository.mapper.ApiKeyMapper;
import com.tokenlimit.server.repository.mapper.TeamMapper;
import com.tokenlimit.server.repository.mapper.UserMapper;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.security.SessionInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：下拉选项元数据（PRD V4.0）.
 * <p>已废除 Namespace，仅保留 Team / User / API Key 元数据。</p>
 * <p>PRD 11.2：TEAM_ADMIN 下拉数据强制限缩到本 Team（团队仅自身，用户/Key 仅本团队）。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/meta")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN')")
public class MetaAdminController {

    private final TeamMapper teamMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final UserMapper userMapper;

    public MetaAdminController(TeamMapper teamMapper,
                               ApiKeyMapper apiKeyMapper, UserMapper userMapper) {
        this.teamMapper = teamMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
    }

    /**
     * 团队下拉.
     */
    @GetMapping("/teams")
    public Result<List<Team>> teams() {
        // TEAM_ADMIN 只能看到自己的团队（PRD 11.2）
        SessionInfo session = SecurityUtils.requireSession();
        String teamCode = "TEAM_ADMIN".equals(session.getRole()) ? session.getTeamCode() : null;
        return Result.success(teamMapper.selectList(new LambdaQueryWrapper<Team>()
                .eq(Team::getStatus, "ENABLED")
                .eq(teamCode != null, Team::getTeamCode, teamCode)
                .orderByDesc(Team::getCreatedAt)));
    }

    /**
     * API Key 下拉（可按团队 / 用户过滤）.
     */
    @GetMapping("/api-keys")
    public Result<List<ApiKey>> apiKeys(@RequestParam(required = false) String teamCode,
                                        @RequestParam(required = false) String userCode) {
        // TEAM_ADMIN 强制过滤为本团队（PRD 11.2）
        SessionInfo session = SecurityUtils.requireSession();
        if ("TEAM_ADMIN".equals(session.getRole())) {
            teamCode = session.getTeamCode();
        }
        return Result.success(apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .eq(teamCode != null && !teamCode.isBlank(), ApiKey::getTeamCode, teamCode)
                .eq(userCode != null && !userCode.isBlank(), ApiKey::getUserCode, userCode)
                .eq(ApiKey::getStatus, "ENABLED")
                .orderByDesc(ApiKey::getCreatedAt)));
    }

    /**
     * 用户下拉（可按团队过滤）.
     */
    @GetMapping("/users")
    public Result<List<User>> users(@RequestParam(required = false) String teamCode) {
        // TEAM_ADMIN 强制过滤为本团队（PRD 11.2）
        SessionInfo session = SecurityUtils.requireSession();
        if ("TEAM_ADMIN".equals(session.getRole())) {
            teamCode = session.getTeamCode();
        }
        return Result.success(userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(teamCode != null && !teamCode.isBlank(), User::getTeamCode, teamCode)
                .eq(User::getStatus, "ENABLED")
                .orderByDesc(User::getCreatedAt)));
    }

    /**
     * 汇总元数据（一次获取所有下拉项）.
     */
    @GetMapping("/all")
    public Result<Map<String, Object>> all() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teams", teams().getData());
        data.put("apiKeys", apiKeys(null, null).getData());
        data.put("users", users(null).getData());
        data.put("targetTypes", List.of("TEAM", "USER"));
        data.put("teamTypes", List.of("TEAM", "DEPARTMENT", "APPLICATION", "PROJECT", "CUSTOMER", "COST_CENTER"));
        data.put("userTypes", List.of("EMPLOYEE", "END_CUSTOMER", "BOT", "SERVICE", "SYSTEM"));
        data.put("quotaModes", List.of("PERSONAL_ONLY", "TEAM_ONLY", "PERSONAL_FIRST_THEN_TEAM"));
        data.put("roles", List.of("USER", "TEAM_ADMIN", "ADMIN"));
        data.put("apiKeyStatuses", List.of("ENABLED", "DISABLED", "EXPIRED", "REVOKED"));
        data.put("limitTypes", List.of("TOKEN", "COST", "REQUEST_COUNT", "RPM", "TPM"));
        data.put("periods", List.of("MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "YEAR", "TOTAL"));
        // PRD 13.1：16 种审计事件全量下发
        data.put("auditEventTypes", List.of(
                "LOGIN_SUCCESS", "LOGIN_FAILED", "CREATE_TEAM", "UPDATE_TEAM", "DISABLE_TEAM",
                "CREATE_USER", "DISABLE_USER", "RESET_PASSWORD", "CREATE_API_KEY",
                "DISABLE_API_KEY", "DELETE_API_KEY", "UPDATE_USER_QUOTA", "UPDATE_TEAM_QUOTA",
                "UPDATE_PROVIDER_CREDENTIAL", "QUOTA_BLOCK", "USAGE_ANOMALY"));
        return Result.success(data);
    }
}
