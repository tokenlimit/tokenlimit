package com.tokenlimit.server.controller;

import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.CheckResult;
import com.tokenlimit.common.dto.ReportResult;
import com.tokenlimit.server.dto.QuotaCheckRequest;
import com.tokenlimit.server.dto.UsageReportRequest;
import com.tokenlimit.server.service.QuotaService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端配额接口（PRD V5.0）.
 * <p>鉴权：{@code Authorization: Bearer <access_key>:<secret>}（双向校验）。</p>
 */
@RestController
@RequestMapping("/api/v1/client")
public class ClientController {

    private final QuotaService quotaService;

    public ClientController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /**
     * 调用前检查（V5：只读 used，不预扣）.
     */
    @PostMapping("/check")
    public Result<CheckResult> check(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody QuotaCheckRequest req) {
        String[] cred = parseCredential(authorization);
        long estPrompt = nvl(req.getEstimatedPromptTokens());
        long estCompletion = nvl(req.getEstimatedCompletionTokens());
        long estTotal = nvl(req.getEstimatedTotalTokens());
        if (estTotal == 0) {
            estTotal = nvl(req.getEstimatedTokens());
        }
        CheckResult result = quotaService.check(cred[0], cred[1], req.getModel(),
                estPrompt, estCompletion, estTotal);
        return Result.success(result);
    }

    /**
     * 调用后上报（V5：写 usage_log 并累加简单计数器）.
     */
    @PostMapping("/report")
    public Result<ReportResult> report(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody UsageReportRequest req) {
        String[] cred = parseCredential(authorization);
        ReportResult result = quotaService.report(
                req.getTraceId(), cred[0], cred[1], req.getModel(),
                req.getPromptTokens(), req.getCompletionTokens(), req.getTotalTokens(),
                req.getProvider(), req.getStatus(), req.getLatencyMs(),
                nvl(req.getEstimatedPromptTokens()),
                nvl(req.getEstimatedCompletionTokens()),
                nvl(req.getEstimatedTotalTokens()));
        return Result.success(result);
    }

    private String[] parseCredential(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            int idx = token.indexOf(':');
            if (idx > 0) {
                return new String[]{token.substring(0, idx), token.substring(idx + 1)};
            }
        }
        return new String[]{"", ""};
    }

    private long nvl(Long value) {
        return value == null ? 0 : value;
    }
}
