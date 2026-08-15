package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.AuditLog;
import com.tokenlimit.server.repository.mapper.AuditLogMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：审计日志查询（PRD V2.0，eventType 事件模型）.
 */
@RestController
@RequestMapping("/api/v1/admin/audits")
@PreAuthorize("hasRole('ADMIN')")
public class AuditAdminController {

    private final AuditLogMapper auditLogMapper;

    public AuditAdminController(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @GetMapping
    public Result<PageResult<AuditLog>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(StringUtils.hasText(teamCode), AuditLog::getTeamCode, teamCode)
                .eq(StringUtils.hasText(eventType), AuditLog::getEventType, eventType)
                .eq(StringUtils.hasText(targetType), AuditLog::getTargetType, targetType)
                .like(StringUtils.hasText(operator), AuditLog::getOperator, operator)
                .eq(StringUtils.hasText(result), AuditLog::getResult, result)
                .ge(StringUtils.hasText(startTime), AuditLog::getCreatedAt, startTime)
                .le(StringUtils.hasText(endTime), AuditLog::getCreatedAt, endTime)
                .orderByDesc(AuditLog::getCreatedAt);
        Page<AuditLog> p = auditLogMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    @GetMapping("/{id}")
    public Result<AuditLog> get(@PathVariable Long id) {
        return Result.success(auditLogMapper.selectById(id));
    }
}
