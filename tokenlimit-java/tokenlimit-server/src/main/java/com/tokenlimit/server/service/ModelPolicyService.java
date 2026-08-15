package com.tokenlimit.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.server.entity.ModelPrice;
import com.tokenlimit.server.entity.TeamModelPolicy;
import com.tokenlimit.server.repository.mapper.ModelPriceMapper;
import com.tokenlimit.server.repository.mapper.TeamModelPolicyMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型策略服务.
 * <p>封装 Team 模型白名单校验与可用模型列表查询逻辑，
 * 从 ProxyGatewayController 中剥离，降低 Controller 职责。</p>
 */
@Service
public class ModelPolicyService {

    private final TeamModelPolicyMapper teamModelPolicyMapper;
    private final ModelPriceMapper modelPriceMapper;
    private final ConfigCacheService configCacheService;

    public ModelPolicyService(TeamModelPolicyMapper teamModelPolicyMapper,
                              ModelPriceMapper modelPriceMapper,
                              ConfigCacheService configCacheService) {
        this.teamModelPolicyMapper = teamModelPolicyMapper;
        this.modelPriceMapper = modelPriceMapper;
        this.configCacheService = configCacheService;
    }

    /**
     * 校验模型是否在 Team 允许列表中.
     * <p>Team 未启用模型白名单时，所有模型均允许；启用时必须命中白名单。</p>
     *
     * @param teamCode Team 编码
     * @param model    模型名称
     * @throws BusinessException MODEL_NOT_ALLOWED 如果模型不在允许列表中
     */
    public void assertModelAllowed(String teamCode, String model) {
        List<TeamModelPolicy> policies = configCacheService.teamPolicies(teamCode);
        if (policies.isEmpty()) {
            return; // 未启用模型白名单
        }
        boolean allowed = policies.stream().anyMatch(p ->
                model.equals(p.getModel()) || "*".equals(p.getModel()));
        if (!allowed) {
            throw new BusinessException(ErrorCode.MODEL_NOT_ALLOWED);
        }
    }

    /**
     * 获取 Team 可用模型列表.
     * <p>Team 启用白名单时返回命中模型（* 展开为全部）；未启用时返回全部已启用模型。</p>
     *
     * @param teamCode Team 编码
     * @return 可用模型列表
     */
    public List<String> allowedModels(String teamCode) {
        List<TeamModelPolicy> policies = configCacheService.teamPolicies(teamCode);
        List<ModelPrice> prices = configCacheService.enabledModels();
        
        if (policies.isEmpty()) {
            List<String> all = new ArrayList<>();
            for (ModelPrice price : prices) {
                all.add(price.getModel());
            }
            return all;
        }
        
        Set<String> allModels = new LinkedHashSet<>();
        for (ModelPrice price : prices) {
            allModels.add(price.getModel());
        }
        
        Set<String> allowed = new LinkedHashSet<>();
        for (TeamModelPolicy policy : policies) {
            if ("*".equals(policy.getModel())) {
                allowed.addAll(allModels);
            } else if (StringUtils.hasText(policy.getModel())) {
                allowed.add(policy.getModel());
            }
        }
        return new ArrayList<>(allowed);
    }
}
