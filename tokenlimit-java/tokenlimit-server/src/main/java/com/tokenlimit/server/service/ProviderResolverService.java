package com.tokenlimit.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.server.entity.ProviderCredential;
import com.tokenlimit.server.entity.TeamModelPolicy;
import com.tokenlimit.server.repository.mapper.ProviderCredentialMapper;
import com.tokenlimit.server.repository.mapper.TeamModelPolicyMapper;
import com.tokenlimit.server.util.CredentialCryptoUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Provider 凭证解析服务（PRD V4.0）.
 * <p>查找优先级：Team 专属策略凭证 → Team 专属凭证 → GLOBAL 凭证 → PROVIDER_NOT_FOUND。</p>
 */
@Service
public class ProviderResolverService {

    private final TeamModelPolicyMapper teamModelPolicyMapper;
    private final ProviderCredentialMapper providerCredentialMapper;

    public ProviderResolverService(TeamModelPolicyMapper teamModelPolicyMapper,
                                   ProviderCredentialMapper providerCredentialMapper) {
        this.teamModelPolicyMapper = teamModelPolicyMapper;
        this.providerCredentialMapper = providerCredentialMapper;
    }

    /**
     * 解析团队使用某模型应转发的上游凭证.
     *
     * @param teamCode 团队编码
     * @param model    模型
     * @return 已解密的上游凭证
     */
    public ResolvedCredential resolve(String teamCode, String model) {
        // 1. 团队模型策略：模型精确匹配优先，其次全模型策略
        TeamModelPolicy policy = teamModelPolicyMapper.selectOne(new LambdaQueryWrapper<TeamModelPolicy>()
                .eq(TeamModelPolicy::getTeamCode, teamCode)
                .eq(TeamModelPolicy::getEnabled, true)
                .eq(TeamModelPolicy::getModel, model)
                .last("limit 1"));
        if (policy == null) {
            policy = teamModelPolicyMapper.selectOne(new LambdaQueryWrapper<TeamModelPolicy>()
                    .eq(TeamModelPolicy::getTeamCode, teamCode)
                    .eq(TeamModelPolicy::getEnabled, true)
                    .eq(TeamModelPolicy::getModel, "*")
                    .last("limit 1"));
        }
        if (policy != null) {
            ProviderCredential credential = findActive(policy.getCredentialCode());
            if (credential != null) {
                return toResolved(credential);
            }
        }

        // 2. 团队专属凭证（模型匹配，无则不限模型）
        ProviderCredential teamCredential = providerCredentialMapper.selectOne(new LambdaQueryWrapper<ProviderCredential>()
                .eq(ProviderCredential::getScopeType, "TEAM")
                .eq(ProviderCredential::getTeamCode, teamCode)
                .eq(ProviderCredential::getStatus, "ENABLED")
                .and(w -> w.eq(ProviderCredential::getModel, model)
                        .or().isNull(ProviderCredential::getModel))
                .orderByDesc(ProviderCredential::getUpdatedAt)
                .last("limit 1"));
        if (teamCredential != null) {
            return toResolved(teamCredential);
        }

        // 3. GLOBAL 全局凭证
        ProviderCredential globalCredential = providerCredentialMapper.selectOne(new LambdaQueryWrapper<ProviderCredential>()
                .eq(ProviderCredential::getScopeType, "GLOBAL")
                .eq(ProviderCredential::getStatus, "ENABLED")
                .and(w -> w.eq(ProviderCredential::getModel, model)
                        .or().isNull(ProviderCredential::getModel))
                .orderByDesc(ProviderCredential::getUpdatedAt)
                .last("limit 1"));
        if (globalCredential != null) {
            return toResolved(globalCredential);
        }

        throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(),
                "PROVIDER_NOT_FOUND: 团队 " + teamCode + " 未配置模型 " + model + " 的上游凭证");
    }

    private ProviderCredential findActive(String credentialCode) {
        if (!StringUtils.hasText(credentialCode)) {
            return null;
        }
        ProviderCredential credential = providerCredentialMapper.selectOne(
                new LambdaQueryWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getCredentialCode, credentialCode)
                        .last("limit 1"));
        if (credential == null || !"ENABLED".equals(credential.getStatus())) {
            return null;
        }
        return credential;
    }

    private ResolvedCredential toResolved(ProviderCredential credential) {
        ResolvedCredential resolved = new ResolvedCredential();
        resolved.setProvider(credential.getProvider());
        resolved.setProviderName(credential.getProviderName());
        resolved.setApiBaseUrl(credential.getApiBaseUrl());
        resolved.setApiKey(CredentialCryptoUtils.decrypt(credential.getApiKeyEnc()));
        return resolved;
    }

    /**
     * 已解密的解析结果（仅内存传递，不落库）.
     */
    public static class ResolvedCredential {
        private String provider;
        private String providerName;
        private String apiBaseUrl;
        private String apiKey;

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
    }
}
