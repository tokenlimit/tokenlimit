package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.QuotaRule;
import com.tokenlimit.server.repository.mapper.QuotaRuleMapper;
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

/**
 * 管理端：配额规则 CRUD（V5.0）.
 * <p>V5 规则模型：targetType+targetCode+model+limitType+limitValue+period+status。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/quota-rules")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN')")
public class QuotaRuleAdminController {

    private final QuotaRuleMapper quotaRuleMapper;

    public QuotaRuleAdminController(QuotaRuleMapper quotaRuleMapper) {
        this.quotaRuleMapper = quotaRuleMapper;
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
                .eq(StringUtils.hasText(targetType), QuotaRule::getTargetType, targetType)
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
        quotaRuleMapper.insert(rule);
        return Result.success(rule);
    }

    @PutMapping("/{id}")
    public Result<QuotaRule> update(@PathVariable Long id, @RequestBody QuotaRule rule) {
        require(id);
        rule.setId(id);
        quotaRuleMapper.updateById(rule);
        return Result.success(quotaRuleMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        require(id);
        quotaRuleMapper.deleteById(id);
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
        return Result.success();
    }

    private QuotaRule require(Long id) {
        QuotaRule rule = quotaRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "配额规则不存在");
        }
        return rule;
    }
}
