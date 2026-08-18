package com.tokenlimit.server.controller;

import com.tokenlimit.common.api.Result;
import com.tokenlimit.server.entity.ApiKeyPolicy;
import com.tokenlimit.server.security.SessionInfo;
import com.tokenlimit.server.service.ApiKeyPolicyService;
import org.springframework.web.bind.annotation.*;

/**
 * 用户自助风控策略控制器（V6.0 新增）.
 * <p>End User 可自主设置 API Key 的日限额、小时限额、单次请求限额。</p>
 */
@RestController
@RequestMapping("/api/admin/user-policy")
public class UserPolicyController {

    private final ApiKeyPolicyService apiKeyPolicyService;

    public UserPolicyController(ApiKeyPolicyService apiKeyPolicyService) {
        this.apiKeyPolicyService = apiKeyPolicyService;
    }

    /**
     * 获取当前用户的 API Key 策略列表.
     */
    @GetMapping("/my-policies")
    public Result<Object> getMyPolicies(SessionInfo sessionInfo) {
        // TODO: 根据 sessionInfo.getUserCode() 查询该用户所有 API Key 的策略
        return Result.success(apiKeyPolicyService.list());
    }

    /**
     * 获取指定 API Key 的策略详情.
     */
    @GetMapping("/{accessKey}/policy")
    public Result<ApiKeyPolicy> getPolicy(@PathVariable String accessKey, SessionInfo sessionInfo) {
        // TODO: 校验权限（只能查看自己的 API Key）
        ApiKeyPolicy policy = apiKeyPolicyService.getOrCreatePolicy(accessKey);
        return Result.success(policy);
    }

    /**
     * 更新用户自定义策略（User 自助操作）.
     */
    @PutMapping("/{accessKey}/policy")
    public Result<ApiKeyPolicy> updatePolicy(
            @PathVariable String accessKey,
            @RequestBody UpdatePolicyRequest request,
            SessionInfo sessionInfo) {
        // TODO: 校验权限（只能修改自己的 API Key）
        ApiKeyPolicy policy = apiKeyPolicyService.updateUserPolicy(
            accessKey,
            request.getMaxTokensPerRequest(),
            request.getHourlyLimit(),
            request.getDailyLimit()
        );
        return Result.success(policy);
    }

    /**
     * 冻结/解冻 API Key（用户手动操作）.
     */
    @PostMapping("/{accessKey}/freeze")
    public Result<Void> freezeApiKey(
            @PathVariable String accessKey,
            @RequestBody FreezeRequest request,
            SessionInfo sessionInfo) {
        // TODO: 校验权限
        apiKeyPolicyService.freezeApiKey(accessKey, request.isFrozen(), request.getReason());
        return Result.success();
    }

    /**
     * 重置小时用量（系统定时调用或用户手动触发）.
     */
    @PostMapping("/{accessKey}/reset-hourly")
    public Result<Void> resetHourlyUsage(@PathVariable String accessKey, SessionInfo sessionInfo) {
        apiKeyPolicyService.resetHourlyUsage(accessKey);
        return Result.success();
    }

    /**
     * 重置日用量（系统定时调用或用户手动触发）.
     */
    @PostMapping("/{accessKey}/reset-daily")
    public Result<Void> resetDailyUsage(@PathVariable String accessKey, SessionInfo sessionInfo) {
        apiKeyPolicyService.resetDailyUsage(accessKey);
        return Result.success();
    }

    // ==================== DTO ====================

    public static class UpdatePolicyRequest {
        private Long maxTokensPerRequest;
        private Long hourlyLimit;
        private Long dailyLimit;

        public Long getMaxTokensPerRequest() { return maxTokensPerRequest; }
        public void setMaxTokensPerRequest(Long maxTokensPerRequest) { this.maxTokensPerRequest = maxTokensPerRequest; }
        public Long getHourlyLimit() { return hourlyLimit; }
        public void setHourlyLimit(Long hourlyLimit) { this.hourlyLimit = hourlyLimit; }
        public Long getDailyLimit() { return dailyLimit; }
        public void setDailyLimit(Long dailyLimit) { this.dailyLimit = dailyLimit; }
    }

    public static class FreezeRequest {
        private boolean frozen;
        private String reason;

        public boolean isFrozen() { return frozen; }
        public void setFrozen(boolean frozen) { this.frozen = frozen; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
