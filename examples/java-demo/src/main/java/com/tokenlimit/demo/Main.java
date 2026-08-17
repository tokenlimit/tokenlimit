package com.tokenlimit.demo;

import com.tokenlimit.client.TokenLimitClient;
import com.tokenlimit.client.TokenLimitConfig;
import com.tokenlimit.client.TokenLimitException;
import com.tokenlimit.common.dto.CheckResult;
import com.tokenlimit.common.dto.ReportResult;

/**
 * Java Client 使用示例：check -> 调用大模型 -> report（PRD V2.0）.
 *
 * <p>运行前需先启动 tokenlimit-server 并初始化 MySQL/Redis，且已在管理端创建
 * Namespace / Team / User / API Key（access_key 形如 tl_&lt;ns&gt;_ak_xxx）。</p>
 */
public class Main {

    public static void main(String[] args) {
        // 1. 创建客户端（Bearer <access_key>:<secret> 双向校验）
        TokenLimitClient client = new TokenLimitClient(
                TokenLimitConfig.builder("http://127.0.0.1:8080")
                        .apiKey("tl_prod_ak_zhangsan_demo") // 管理端创建的 API Key access key
                        .secret("your-secret-here")         // 创建/重置 API Key 时返回的 secret
                        .build());

        // 2. 配额检查（调用大模型前），凭 api_key 解析命名空间/团队/用户
        try {
            CheckResult checkResult = client.check("gpt-4o", 1000);
            System.out.println("check.allowed  = " + checkResult.isAllowed());
            System.out.println("check.traceId  = " + checkResult.getTraceId());
            System.out.println("check.remain   = " + checkResult.getRemainTokens());

            if (!checkResult.isAllowed()) {
                System.out.println("配额被拦截，原因: " + checkResult.getReason()
                        + "，" + checkResult.getMessage());
                return;
            }

            // 3. 调用真实大模型（此处用模拟数据）
            long promptTokens = 800;
            long completionTokens = 180;
            long totalTokens = promptTokens + completionTokens;

            // 4. 用量上报（调用完成后）
            try {
                ReportResult reportResult = client.report(
                        checkResult.getTraceId(), "gpt-4o", "OPENAI",
                        promptTokens, completionTokens, totalTokens, "SUCCESS", 1250L);
                System.out.println("report.success = " + reportResult.isSuccess());
                System.out.println("report.logId   = " + reportResult.getLogId());
            } catch (TokenLimitException e) {
                System.err.println("上报失败: code=" + e.getCode() + ", msg=" + e.getMessage());
            }
        } catch (TokenLimitException e) {
            System.err.println("配额检查失败: code=" + e.getCode() + ", msg=" + e.getMessage());
        }
    }
}
