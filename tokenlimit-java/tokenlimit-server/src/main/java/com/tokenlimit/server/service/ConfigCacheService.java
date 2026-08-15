package com.tokenlimit.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.server.entity.ModelPrice;
import com.tokenlimit.server.entity.TeamModelPolicy;
import com.tokenlimit.server.repository.mapper.ModelPriceMapper;
import com.tokenlimit.server.repository.mapper.TeamModelPolicyMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 网关配置缓存服务（Caffeine 短 TTL，见 {@code CacheConfig}）.
 * <p>承载网关高频读取且低变更的配置：模型价格全量列表、Team 模型白名单。
 * 管理端修改后最长 1 分钟生效；凭证类数据（含密钥）不走缓存。</p>
 */
@Service
public class ConfigCacheService {

    private final ModelPriceMapper modelPriceMapper;
    private final TeamModelPolicyMapper teamModelPolicyMapper;

    public ConfigCacheService(ModelPriceMapper modelPriceMapper, TeamModelPolicyMapper teamModelPolicyMapper) {
        this.modelPriceMapper = modelPriceMapper;
        this.teamModelPolicyMapper = teamModelPolicyMapper;
    }

    /**
     * 全部已启用模型价格（网关 /v1/models 与模型校验高频使用）.
     */
    @Cacheable(cacheNames = "modelPrices", key = "'all'")
    public List<ModelPrice> enabledModels() {
        return modelPriceMapper.selectList(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getStatus, "ENABLED"));
    }

    /**
     * Team 已启用的模型白名单（空集合表示未启用白名单）.
     */
    @Cacheable(cacheNames = "teamPolicies", key = "#teamCode")
    public List<TeamModelPolicy> teamPolicies(String teamCode) {
        return teamModelPolicyMapper.selectList(new LambdaQueryWrapper<TeamModelPolicy>()
                .eq(TeamModelPolicy::getTeamCode, teamCode)
                .eq(TeamModelPolicy::getEnabled, true));
    }
}
