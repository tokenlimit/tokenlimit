package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.UsageLog;
import com.tokenlimit.server.repository.mapper.UsageLogMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：用量统计查询.
 */
@RestController
@RequestMapping("/api/v1/admin/usages")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN')")
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
        LambdaQueryWrapper<UsageLog> wrapper = new LambdaQueryWrapper<UsageLog>()
                .eq(StringUtils.hasText(teamCode), UsageLog::getTeamCode, teamCode)
                .eq(StringUtils.hasText(apiKeyId), UsageLog::getApiKeyId, apiKeyId)
                .eq(StringUtils.hasText(userCode), UsageLog::getUserCode, userCode)
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
        return Result.success(usageLogMapper.selectById(id));
    }
}
