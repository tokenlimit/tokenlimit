package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.AuditLog;
import com.tokenlimit.server.entity.QuotaRule;
import com.tokenlimit.server.entity.User;
import com.tokenlimit.server.repository.mapper.AuditLogMapper;
import com.tokenlimit.server.repository.mapper.QuotaRuleMapper;
import com.tokenlimit.server.repository.mapper.UserMapper;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.security.SessionInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端：配额规则 CRUD（V5.0）.
 * <p>V5 规则模型：targetType+targetCode+model+limitType+limitValue+period+status。</p>
 * <p>PRD 11.2：TEAM_ADMIN 只能配置本 Team 配额规则（TEAM 规则 targetCode=本团队，USER 规则 targetCode 属本团队用户）。</p>
 */
@RestController
@RequestMapping("/api/admin/quota-rules")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN')")
public class QuotaRuleAdminController {

    private final QuotaRuleMapper quotaRuleMapper;
    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;

    public QuotaRuleAdminController(QuotaRuleMapper quotaRuleMapper, UserMapper userMapper,
                                    AuditLogMapper auditLogMapper) {
        this.quotaRuleMapper = quotaRuleMapper;
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @GetMapping
    public Result<PageResult<QuotaRule>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetCode,
            @RequestParam(required = false) String limitType,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<QuotaRule> wrapper = new LambdaQueryWrapper<QuotaRule>()
                // TEAM_ADMIN 强制归属过滤：本团队 TEAM 规则 + 本团队用户的 USER 规则（PRD 11.2）
                .and(w -> {
                    SessionInfo session = SecurityUtils.requireSession();
                    if ("TEAM_ADMIN".equals(session.getRole())) {
                        List<String> userCodes = userMapper.selectList(new LambdaQueryWrapper<User>()
                                        .eq(User::getTeamCode, session.getTeamCode()))
                                .stream().map(User::getUserCode).toList();
                        w.and(i -> i.eq(QuotaRule::getTargetType, "TEAM")
                                        .eq(QuotaRule::getTargetCode, session.getTeamCode()))
                                .or()
                                .and(i -> i.eq(QuotaRule::getTargetType, "USER")
                                        .in(QuotaRule::getTargetCode, userCodes));
                    } else {
                        w.eq(StringUtils.hasText(targetType), QuotaRule::getTargetType, targetType);
                    }
                })
                .eq(StringUtils.hasText(targetCode), QuotaRule::getTargetCode, targetCode)
                .eq(StringUtils.hasText(limitType), QuotaRule::getLimitType, limitType)
                .eq(StringUtils.hasText(period), QuotaRule::getPeriod, period)
                .eq(StringUtils.hasText(status), QuotaRule::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(QuotaRule::getTargetCode, keyword)
                        .or()
                        .like(QuotaRule::getModel, keyword)
                        .or()
                        .like(QuotaRule::getDescription, keyword))
                .orderByDesc(QuotaRule::getCreatedAt);
        Page<QuotaRule> p = quotaRuleMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    @GetMapping("/{id}")
    public Result<QuotaRule> get(@PathVariable Long id) {
        return Result.success(require(id));
    }

    @PostMapping
    public Result<QuotaRule> create(@Valid @RequestBody QuotaRule rule) {
        rule.setId(null);
        if (!StringUtils.hasText(rule.getStatus())) {
            rule.setStatus("ENABLED");
        }
        assertTeamOwned(rule);
        quotaRuleMapper.insert(rule);
        writeQuotaAudit(rule, "create");
        return Result.success(rule);
    }

    @PutMapping("/{id}")
    public Result<QuotaRule> update(@PathVariable Long id, @RequestBody QuotaRule rule) {
        require(id);
        rule.setId(id);
        assertTeamOwned(rule);
        quotaRuleMapper.updateById(rule);
        writeQuotaAudit(rule, "update");
        return Result.success(quotaRuleMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        QuotaRule rule = require(id);
        quotaRuleMapper.deleteById(id);
        writeQuotaAudit(rule, "delete");
        return Result.success();
    }

    /**
     * 启用 / 禁用规则（enabled=true → ENABLED，否则 DISABLED）.
     */
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam @NotBlank String enabled) {
        QuotaRule rule = require(id);
        rule.setStatus(Boolean.parseBoolean(enabled) ? "ENABLED" : "DISABLED");
        quotaRuleMapper.updateById(rule);
        writeQuotaAudit(rule, "status");
        return Result.success();
    }

    private QuotaRule require(Long id) {
        QuotaRule rule = quotaRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "配额规则不存在");
        }
        assertTeamOwned(rule);
        return rule;
    }

    /**
     * TEAM_ADMIN 归属校验：TEAM 规则 targetCode 必须为本团队，USER 规则 targetCode 必须为本团队用户（PRD 11.2）.
     */
    private void assertTeamOwned(QuotaRule rule) {
        SessionInfo session = SecurityUtils.requireSession();
        if (!"TEAM_ADMIN".equals(session.getRole())) {
            return;
        }
        boolean owned;
        if ("TEAM".equals(rule.getTargetType())) {
            owned = session.getTeamCode().equals(rule.getTargetCode());
        } else if ("USER".equals(rule.getTargetType())) {
            owned = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getTeamCode, session.getTeamCode())
                    .eq(User::getUserCode, rule.getTargetCode())) > 0;
        } else {
            owned = false;
        }
        if (!owned) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "无权操作其他团队的配额规则");
        }
    }

    /**
     * 配额变更审计（PRD 13.1：UPDATE_USER_QUOTA / UPDATE_TEAM_QUOTA，创建/更新/删除/状态变更均记录）.
     */
    private void writeQuotaAudit(QuotaRule rule, String action) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTeamCode("TEAM".equals(rule.getTargetType()) ? rule.getTargetCode()
                    : teamCodeOf(rule));
            auditLog.setUserCode("USER".equals(rule.getTargetType()) ? rule.getTargetCode() : null);
            auditLog.setOperator(currentOperator());
            auditLog.setEventType("USER".equals(rule.getTargetType())
                    ? "UPDATE_USER_QUOTA" : "UPDATE_TEAM_QUOTA");
            auditLog.setTargetType(rule.getTargetType());
            auditLog.setTargetCode(rule.getTargetCode());
            auditLog.setDetail("{\"action\":\"" + action + "\",\"limitType\":\""
                    + rule.getLimitType() + "\",\"limitValue\":\"" + rule.getLimitValue()
                    + "\",\"period\":\"" + rule.getPeriod() + "\",\"model\":\""
                    + (rule.getModel() == null ? "*" : rule.getModel()) + "\"}");
            auditLog.setResult("SUCCESS");
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 审计失败不影响主流程
        }
    }

    private String teamCodeOf(QuotaRule rule) {
        try {
            return SecurityUtils.requireSession().getTeamCode();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentOperator() {
        try {
            SessionInfo session = SecurityUtils.currentSession();
            return session != null && StringUtils.hasText(session.getUsername())
                    ? session.getUsername() : "console";
        } catch (Exception e) {
            return "console";
        }
    }
}
