package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.UsageLog;
import com.tokenlimit.server.repository.mapper.UsageLogMapper;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.security.SessionInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：用量统计查询.
 * <p>ADMIN/TEAM_ADMIN 查看全部；USER 仅查看自己的用量（自动按 userCode 过滤）。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/usages")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN', 'USER')")
public class UsageAdminController {

    private final UsageLogMapper usageLogMapper;

    public UsageAdminController(UsageLogMapper usageLogMapper) {
        this.usageLogMapper = usageLogMapper;
    }

    @GetMapping
    public Result<PageResult<UsageLog>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) String apiKeyId,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        SessionInfo session = SecurityUtils.requireSession();
        boolean isUser = "USER".equals(session.getRole());

        LambdaQueryWrapper<UsageLog> wrapper = new LambdaQueryWrapper<UsageLog>()
                // USER 角色强制过滤为自己的用量
                .eq(isUser, UsageLog::getUserCode, isUser ? session.getUserCode() : userCode)
                // TEAM_ADMIN 强制过滤为本团队的用量
                .eq("TEAM_ADMIN".equals(session.getRole()), UsageLog::getTeamCode, session.getTeamCode())
                // ADMIN 可按 teamCode/userCode 筛选
                .eq(!isUser && StringUtils.hasText(teamCode), UsageLog::getTeamCode, teamCode)
                .eq(!isUser && StringUtils.hasText(apiKeyId), UsageLog::getApiKeyId, apiKeyId)
                .eq(!isUser && StringUtils.hasText(userCode), UsageLog::getUserCode, userCode)
                .eq(StringUtils.hasText(model), UsageLog::getModel, model)
                .eq(StringUtils.hasText(status), UsageLog::getStatus, status)
                .ge(StringUtils.hasText(startTime), UsageLog::getCreatedAt, startTime)
                .le(StringUtils.hasText(endTime), UsageLog::getCreatedAt, endTime)
                .orderByDesc(UsageLog::getCreatedAt);
        Page<UsageLog> p = usageLogMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    @GetMapping("/{id}")
    public Result<UsageLog> get(@PathVariable Long id) {
        UsageLog log = usageLogMapper.selectById(id);
        // USER 只能查看自己的用量
        SessionInfo session = SecurityUtils.requireSession();
        if ("USER".equals(session.getRole()) && log != null
                && !session.getUserCode().equals(log.getUserCode())) {
            return Result.success(null);
        }
        return Result.success(log);
    }
}
