package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.ProviderCredential;
import com.tokenlimit.server.entity.Team;
import com.tokenlimit.server.entity.TeamModelPolicy;
import com.tokenlimit.server.entity.User;
import com.tokenlimit.server.enums.LlmProvider;
import com.tokenlimit.server.repository.mapper.ProviderCredentialMapper;
import com.tokenlimit.server.repository.mapper.TeamModelPolicyMapper;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.util.CredentialCryptoUtils;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端：Provider 凭证管理（PRD V4.0）.
 * <p>真实 Key 加密存储、永不回显；scopeType: GLOBAL / TEAM。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/providers")
@PreAuthorize("hasRole('ADMIN')")
public class ProviderAdminController {

    private final ProviderCredentialMapper providerCredentialMapper;
    private final TeamModelPolicyMapper teamModelPolicyMapper;

    public ProviderAdminController(ProviderCredentialMapper providerCredentialMapper,
                                   TeamModelPolicyMapper teamModelPolicyMapper) {
        this.providerCredentialMapper = providerCredentialMapper;
        this.teamModelPolicyMapper = teamModelPolicyMapper;
    }

    @GetMapping
    public Result<PageResult<ProviderCredential>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<ProviderCredential> wrapper = new LambdaQueryWrapper<ProviderCredential>()
                .eq(StringUtils.hasText(provider), ProviderCredential::getProvider, provider)
                .eq(StringUtils.hasText(scopeType), ProviderCredential::getScopeType, scopeType)
                .eq(StringUtils.hasText(teamCode), ProviderCredential::getTeamCode, teamCode)
                .eq(StringUtils.hasText(status), ProviderCredential::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(ProviderCredential::getCredentialCode, keyword)
                        .or().like(ProviderCredential::getCredentialName, keyword)
                        .or().like(ProviderCredential::getProviderName, keyword))
                .orderByDesc(ProviderCredential::getCreatedAt);
        Page<ProviderCredential> p = providerCredentialMapper.selectPage(new Page<>(page, size), wrapper);
        p.getRecords().forEach(c -> c.setApiKeyEnc(null));
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    @PostMapping
    public Result<Map<String, Object>> create(@org.springframework.validation.annotation.Validated @RequestBody CreateCredentialRequest req) {
        if (!"GLOBAL".equals(req.getScopeType()) && !"TEAM".equals(req.getScopeType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "scopeType 仅支持 GLOBAL/TEAM");
        }
        if ("TEAM".equals(req.getScopeType()) && !StringUtils.hasText(req.getTeamCode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "TEAM 作用域必须指定 teamCode");
        }
        if (providerCredentialMapper.selectCount(new LambdaQueryWrapper<ProviderCredential>()
                .eq(ProviderCredential::getCredentialCode, req.getCredentialCode())) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "凭证编码已存在");
        }
        ProviderCredential credential = new ProviderCredential();
        credential.setCredentialCode(StringUtils.hasText(req.getCredentialCode())
                ? req.getCredentialCode().trim()
                : "pcred-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        credential.setProvider(req.getProvider());
        credential.setProviderName(req.getProviderName());
        credential.setCredentialName(req.getCredentialName());
        credential.setScopeType(req.getScopeType());
        credential.setTeamCode("TEAM".equals(req.getScopeType()) ? req.getTeamCode() : null);
        credential.setApiBaseUrl(req.getApiBaseUrl());
        credential.setApiKeyEnc(CredentialCryptoUtils.encrypt(req.getApiKey()));
        credential.setModel(StringUtils.hasText(req.getModel()) ? req.getModel() : null);
        credential.setStatus(StringUtils.hasText(req.getStatus()) ? req.getStatus() : "ENABLED");
        credential.setRemark(req.getRemark());
        credential.setCreatedBy(currentOperator());
        providerCredentialMapper.insert(credential);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("credentialCode", credential.getCredentialCode());
        data.put("credentialName", credential.getCredentialName());
        data.put("apiKey", req.getApiKey());
        return Result.success(data);
    }

    @PutMapping("/{credentialCode}")
    public Result<ProviderCredential> update(@PathVariable String credentialCode,
                                             @RequestBody CreateCredentialRequest req) {
        ProviderCredential credential = providerCredentialMapper.selectOne(
                new LambdaQueryWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getCredentialCode, credentialCode));
        if (credential == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (StringUtils.hasText(req.getProvider())) {
            credential.setProvider(req.getProvider());
        }
        if (StringUtils.hasText(req.getProviderName())) {
            credential.setProviderName(req.getProviderName());
        }
        if (StringUtils.hasText(req.getCredentialName())) {
            credential.setCredentialName(req.getCredentialName());
        }
        if ("TEAM".equals(req.getScopeType()) && StringUtils.hasText(req.getTeamCode())) {
            credential.setScopeType("TEAM");
            credential.setTeamCode(req.getTeamCode());
        } else if ("GLOBAL".equals(req.getScopeType())) {
            credential.setScopeType("GLOBAL");
            credential.setTeamCode(null);
        }
        if (req.getApiBaseUrl() != null) {
            credential.setApiBaseUrl(req.getApiBaseUrl());
        }
        if (StringUtils.hasText(req.getApiKey())) {
            credential.setApiKeyEnc(CredentialCryptoUtils.encrypt(req.getApiKey()));
        }
        if (req.getModel() != null) {
            credential.setModel(StringUtils.hasText(req.getModel()) ? req.getModel() : null);
        }
        if (StringUtils.hasText(req.getStatus())) {
            credential.setStatus(req.getStatus());
        }
        if (req.getRemark() != null) {
            credential.setRemark(req.getRemark());
        }
        providerCredentialMapper.updateById(credential);
        credential.setApiKeyEnc(null);
        return Result.success(credential);
    }

    @PostMapping("/{credentialCode}/toggle")
    public Result<ProviderCredential> toggle(@PathVariable String credentialCode) {
        ProviderCredential credential = providerCredentialMapper.selectOne(
                new LambdaQueryWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getCredentialCode, credentialCode));
        if (credential == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        credential.setStatus("ENABLED".equals(credential.getStatus()) ? "DISABLED" : "ENABLED");
        providerCredentialMapper.updateById(credential);
        credential.setApiKeyEnc(null);
        return Result.success(credential);
    }

    @DeleteMapping("/{credentialCode}")
    public Result<Void> delete(@PathVariable String credentialCode) {
        if (teamModelPolicyMapper.selectCount(new LambdaQueryWrapper<TeamModelPolicy>()
                .eq(TeamModelPolicy::getCredentialCode, credentialCode)
                .eq(TeamModelPolicy::getEnabled, true)) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "该凭证正被模型策略引用，请先解除引用");
        }
        providerCredentialMapper.delete(new LambdaQueryWrapper<ProviderCredential>()
                .eq(ProviderCredential::getCredentialCode, credentialCode));
        return Result.success(null);
    }

    /**
     * 内置供应商模板下拉（PRD V5.0 §9.7）.
     * <p>返回已知大模型厂商的 OpenAI 兼容 Base URL 模板，控制台选择后自动填充；自定义厂商不在此列表。</p>
     */
    @GetMapping("/templates")
    public Result<List<Map<String, Object>>> templates() {
        return Result.success(LlmProvider.templates().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", p.getCode());
            m.put("providerName", p.getDisplayName());
            m.put("baseUrl", p.getDefaultBaseUrl());
            m.put("openAiCompatible", p.isOpenAiCompatible());
            m.put("requiresEndpoint", p.isRequiresEndpoint());
            m.put("directPassthrough", p.isDirectPassthrough());
            return m;
        }).collect(java.util.stream.Collectors.toList()));
    }

    /**
     * 供应商下拉.
     */
    @GetMapping("/providers")
    public Result<List<Map<String, String>>> providers() {
        List<ProviderCredential> all = providerCredentialMapper.selectList(
                new LambdaQueryWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getStatus, "ENABLED")
                        .orderByAsc(ProviderCredential::getProvider));
        return Result.success(all.stream().distinct().map(c -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("provider", c.getProvider());
            m.put("providerName", c.getProviderName());
            return m;
        }).collect(java.util.stream.Collectors.toList()));
    }

    private String currentOperator() {
        try {
            return SecurityUtils.requireSession().getUsername();
        } catch (Exception e) {
            return "system";
        }
    }

    /**
     * 创建/更新凭证请求体.
     */
    public static class CreateCredentialRequest {
        private String credentialCode;
        @NotBlank(message = "provider 不能为空")
        private String provider;
        private String providerName;
        @NotBlank(message = "credentialName 不能为空")
        private String credentialName;
        @NotBlank(message = "scopeType 不能为空")
        private String scopeType;
        private String teamCode;
        private String apiBaseUrl;
        @NotBlank(message = "apiKey 不能为空")
        private String apiKey;
        private String model;
        private String status;
        private String remark;

        public String getCredentialCode() {
            return credentialCode;
        }

        public void setCredentialCode(String credentialCode) {
            this.credentialCode = credentialCode;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getProviderName() {
            return providerName;
        }

        public void setProviderName(String providerName) {
            this.providerName = providerName;
        }

        public String getCredentialName() {
            return credentialName;
        }

        public void setCredentialName(String credentialName) {
            this.credentialName = credentialName;
        }

        public String getScopeType() {
            return scopeType;
        }

        public void setScopeType(String scopeType) {
            this.scopeType = scopeType;
        }

        public String getTeamCode() {
            return teamCode;
        }

        public void setTeamCode(String teamCode) {
            this.teamCode = teamCode;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }
}
