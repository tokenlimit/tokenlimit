package com.tokenlimit.server.controller;

import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.CheckResult;
import com.tokenlimit.common.dto.ReportResult;
import com.tokenlimit.server.dto.QuotaCheckRequest;
import com.tokenlimit.server.dto.UsageReportRequest;
import com.tokenlimit.server.service.QuotaService;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端接口：配额检查 / 用量上报（PRD V2.0）.
 * <p>鉴权统一使用 {@code Authorization: Bearer <access_key>:<secret>} 请求头，
 * access key 与 secret 双重校验（双向校验）。</p>
 */
@RestController
@RequestMapping("/api/v1/client")
public class ClientController {

    private final QuotaService quotaService;

    public ClientController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /**
     * 配额检查（客户端调用大模型前）.
     */
    @PostMapping("/quota/check")
    public Result<CheckResult> check(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody QuotaCheckRequest req) {
        Credential credential = extractCredential(authorization);
        CheckResult result = quotaService.check(credential.accessKey, credential.secret,
                req.getModel(), req.getEstimatedTokens());
        return Result.success(result);
    }

    /**
     * 用量上报（大模型调用完成后）.
     */
    @PostMapping("/usage/report")
    public Result<ReportResult> report(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody UsageReportRequest req) {
        Credential credential = extractCredential(authorization);
        ReportResult result = quotaService.report(
                req.getTraceId(),
                credential.accessKey,
                credential.secret,
                req.getModel(),
                req.getPromptTokens(),
                req.getCompletionTokens(),
                req.getTotalTokens(),
                req.getProvider(),
                req.getStatus(),
                req.getLatencyMs());
        return Result.success(result);
    }

    /**
     * 从 Authorization 头提取 Bearer token 并解析 access_key:secret.
     */
    public static Credential extractCredential(String authorization) {
        String token = extractBearerToken(authorization);
        if (!StringUtils.hasText(token)) {
            return new Credential(null, null);
        }
        int idx = token.indexOf(':');
        if (idx <= 0) {
            // 兼容旧格式：仅 access key（secret 为空，由服务端双向校验拒绝）
            return new Credential(token, null);
        }
        return new Credential(token.substring(0, idx), token.substring(idx + 1));
    }

    /**
     * 从 Authorization 头提取 Bearer token.
     */
    public static String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 客户端凭据：access key + secret.
     */
    public static class Credential {
        public final String accessKey;
        public final String secret;

        public Credential(String accessKey, String secret) {
            this.accessKey = accessKey;
            this.secret = secret;
        }
    }
}
