package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.ProviderCredential;
import com.tokenlimit.server.entity.TeamModelPolicy;
import com.tokenlimit.server.repository.mapper.ProviderCredentialMapper;
import com.tokenlimit.server.repository.mapper.TeamModelPolicyMapper;
import com.tokenlimit.server.security.SecurityUtils;
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
 * 管理端：团队模型策略管理（PRD V4.0）.
 * <p>team + model 决定使用哪个 Provider 凭证转发；查找优先级 Team 专属 → GLOBAL。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/model-policies")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN')")
public class TeamModelPolicyAdminController {

    private final TeamModelPolicyMapper teamModelPolicyMapper;
    private final ProviderCredentialMapper providerCredentialMapper;

    public TeamModelPolicyAdminController(TeamModelPolicyMapper teamModelPolicyMapper,
                                          ProviderCredentialMapper providerCredentialMapper) {
        this.teamModelPolicyMapper = teamModelPolicyMapper;
        this.providerCredentialMapper = providerCredentialMapper;
    }

    @GetMapping
    public Result<PageResult<TeamModelPolicy>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<TeamModelPolicy> wrapper = new LambdaQueryWrapper<TeamModelPolicy>()
                .eq(StringUtils.hasText(teamCode), TeamModelPolicy::getTeamCode, teamCode)
                .eq(StringUtils.hasText(model), TeamModelPolicy::getModel, model)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(TeamModelPolicy::getTeamCode, keyword)
                        .or().like(TeamModelPolicy::getCredentialCode, keyword))
                .orderByDesc(TeamModelPolicy::getCreatedAt);
        Page<TeamModelPolicy> p = teamModelPolicyMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    @PostMapping
    public Result<TeamModelPolicy> create(@RequestBody TeamModelPolicy policy) {
        if (!StringUtils.hasText(policy.getTeamCode()) || !StringUtils.hasText(policy.getCredentialCode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "teamCode 与 credentialCode 必填");
        }
        // 校验凭证存在且启用
        ProviderCredential credential = providerCredentialMapper.selectOne(
                new LambdaQueryWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getCredentialCode, policy.getCredentialCode()));
        if (credential == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "凭证不存在");
        }
        if (!"ENABLED".equals(credential.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "凭证未启用");
        }
        if (teamModelPolicyMapper.selectCount(new LambdaQueryWrapper<TeamModelPolicy>()
                .eq(TeamModelPolicy::getTeamCode, policy.getTeamCode())
                .eq(TeamModelPolicy::getModel, policy.getModel() == null ? "" : policy.getModel())) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "该团队模型策略已存在");
        }
        if (policy.getEnabled() == null) {
            policy.setEnabled(true);
        }
        policy.setCreatedBy(SecurityUtils.requireSession().getUsername());
        teamModelPolicyMapper.insert(policy);
        return Result.success(policy);
    }

    @PutMapping("/{id}")
    public Result<TeamModelPolicy> update(@PathVariable Long id, @RequestBody TeamModelPolicy policy) {
        TeamModelPolicy existing = teamModelPolicyMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (StringUtils.hasText(policy.getCredentialCode())) {
            existing.setCredentialCode(policy.getCredentialCode());
        }
        if (policy.getEnabled() != null) {
            existing.setEnabled(policy.getEnabled());
        }
        if (policy.getRemark() != null) {
            existing.setRemark(policy.getRemark());
        }
        teamModelPolicyMapper.updateById(existing);
        return Result.success(existing);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        teamModelPolicyMapper.deleteById(id);
        return Result.success(null);
    }

    /**
     * 模型策略下拉：返回可用的凭证列表.
     */
    @GetMapping("/credentials")
    public Result<List<ProviderCredential>> credentials(@RequestParam(required = false) String teamCode) {
        LambdaQueryWrapper<ProviderCredential> wrapper = new LambdaQueryWrapper<ProviderCredential>()
                .eq(ProviderCredential::getStatus, "ENABLED")
                .and(w -> w.eq(ProviderCredential::getScopeType, "GLOBAL")
                        .or(x -> x.eq(ProviderCredential::getScopeType, "TEAM")
                                .eq(StringUtils.hasText(teamCode), ProviderCredential::getTeamCode, teamCode)))
                .orderByAsc(ProviderCredential::getScopeType);
        List<ProviderCredential> list = providerCredentialMapper.selectList(wrapper);
        list.forEach(c -> c.setApiKeyEnc(null));
        return Result.success(list);
    }
}
