package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.UsageLog;
import com.tokenlimit.server.repository.mapper.UsageLogMapper;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.security.SessionInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.List;

/**
 * 管理端：用量统计查询.
 * <p>ADMIN/TEAM_ADMIN 查看全部；USER 仅查看自己的用量（自动按 userCode 过滤）。</p>
 */
@RestController
@RequestMapping("/api/admin/usages")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN', 'USER')")
public class UsageAdminController {

    private final UsageLogMapper usageLogMapper;

    public UsageAdminController(UsageLogMapper usageLogMapper) {
        this.usageLogMapper = usageLogMapper;
    }

    @GetMapping
    public Result<PageResult<UsageLog>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) String apiKeyId,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        SessionInfo session = SecurityUtils.requireSession();
        boolean isUser = "USER".equals(session.getRole());

        LambdaQueryWrapper<UsageLog> wrapper = new LambdaQueryWrapper<UsageLog>()
                // USER 角色强制过滤为自己的用量
                .eq(isUser, UsageLog::getUserCode, isUser ? session.getUserCode() : userCode)
                // TEAM_ADMIN 强制过滤为本团队的用量
                .eq("TEAM_ADMIN".equals(session.getRole()), UsageLog::getTeamCode, session.getTeamCode())
                // ADMIN 可按 teamCode/userCode 筛选
                .eq(!isUser && StringUtils.hasText(teamCode), UsageLog::getTeamCode, teamCode)
                .eq(!isUser && StringUtils.hasText(apiKeyId), UsageLog::getApiKeyId, apiKeyId)
                .eq(!isUser && StringUtils.hasText(userCode), UsageLog::getUserCode, userCode)
                .eq(StringUtils.hasText(model), UsageLog::getModel, model)
                .eq(StringUtils.hasText(status), UsageLog::getStatus, status)
                .ge(StringUtils.hasText(startTime), UsageLog::getCreatedAt, startTime)
                .le(StringUtils.hasText(endTime), UsageLog::getCreatedAt, endTime)
                .orderByDesc(UsageLog::getCreatedAt);
        Page<UsageLog> p = usageLogMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    @GetMapping("/{id}")
    public Result<UsageLog> get(@PathVariable Long id) {
        UsageLog log = usageLogMapper.selectById(id);
        // USER 只能查看自己的用量
        SessionInfo session = SecurityUtils.requireSession();
        if ("USER".equals(session.getRole()) && log != null
                && !session.getUserCode().equals(log.getUserCode())) {
            return Result.success(null);
        }
        return Result.success(log);
    }

    /**
     * 导出调用明细 CSV
     */
    @GetMapping("/export-detail")
    public ResponseEntity<byte[]> exportUsageDetail(
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        
        SessionInfo session = SecurityUtils.requireSession();
        boolean isUser = "USER".equals(session.getRole());
        boolean isTeamAdmin = "TEAM_ADMIN".equals(session.getRole());

        // 构建查询条件
        LambdaQueryWrapper<UsageLog> wrapper = new LambdaQueryWrapper<UsageLog>()
                .eq(isUser, UsageLog::getUserCode, isUser ? session.getUserCode() : userCode)
                .eq(isTeamAdmin, UsageLog::getTeamCode, isTeamAdmin ? session.getTeamCode() : teamCode)
                .eq(!isUser && !isTeamAdmin && StringUtils.hasText(teamCode), UsageLog::getTeamCode, teamCode)
                .eq(!isUser && !isTeamAdmin && StringUtils.hasText(userCode), UsageLog::getUserCode, userCode)
                .eq(StringUtils.hasText(model), UsageLog::getModel, model)
                .eq(StringUtils.hasText(providerCode), UsageLog::getProviderCode, providerCode)
                .ge(StringUtils.hasText(startTime), UsageLog::getCreatedAt, startTime)
                .le(StringUtils.hasText(endTime), UsageLog::getCreatedAt, endTime)
                .orderByDesc(UsageLog::getCreatedAt);

        // 限制最大导出量防止 OOM（超过 1 万条建议异步任务）
        List<UsageLog> records = usageLogMapper.selectList(wrapper);
        if (records.size() > 10000) {
            records = records.subList(0, 10000);
        }

        // 生成 CSV 内容
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
            // CSV 表头
            writer.write("\uFEFF"); // BOM for Excel UTF-8 recognition
            writer.write("请求时间，TraceID，用户，团队，模型，输入 Token，输出 Token，总 Token，预估耗时 (ms),实际耗时 (ms),状态码，Provider\n");
            
            DecimalFormat df = new DecimalFormat("#.##");
            for (UsageLog log : records) {
                writer.write(String.format("%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%s,%s\n",
                        log.getCreatedAt() != null ? log.getCreatedAt() : "",
                        log.getTraceId() != null ? log.getTraceId() : "",
                        log.getUserCode() != null ? log.getUserCode() : "",
                        log.getTeamCode() != null ? log.getTeamCode() : "",
                        log.getModel() != null ? log.getModel() : "",
                        log.getInputTokens() != null ? log.getInputTokens() : 0,
                        log.getOutputTokens() != null ? log.getOutputTokens() : 0,
                        log.getTotalTokens() != null ? log.getTotalTokens() : 0,
                        log.getEstimatedLatencyMs() != null ? log.getEstimatedLatencyMs() : 0,
                        log.getActualLatencyMs() != null ? log.getActualLatencyMs() : 0,
                        log.getStatus() != null ? log.getStatus() : "",
                        log.getProviderCode() != null ? log.getProviderCode() : ""
                ));
            }
        } catch (Exception e) {
            throw new RuntimeException("导出 CSV 失败", e);
        }

        String filename = URLEncoder.encode("调用明细_" + System.currentTimeMillis() + ".csv", StandardCharsets.UTF_8).replace("+", "%20");
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(baos.toByteArray());
    }

    /**
     * 导出成本报表 CSV（按团队/个人汇总）
     */
    @GetMapping("/export-cost-report")
    public ResponseEntity<byte[]> exportCostReport(
            @RequestParam(defaultValue = "team") String dimension, // team | user
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        
        SessionInfo session = SecurityUtils.requireSession();
        boolean isUser = "USER".equals(session.getRole());
        boolean isTeamAdmin = "TEAM_ADMIN".equals(session.getRole());

        // 注意：实际生产中应使用 SQL GROUP BY 聚合查询，这里简化为内存聚合演示
        LambdaQueryWrapper<UsageLog> wrapper = new LambdaQueryWrapper<UsageLog>()
                .eq(isUser, UsageLog::getUserCode, isUser ? session.getUserCode() : null)
                .eq(isTeamAdmin, UsageLog::getTeamCode, isTeamAdmin ? session.getTeamCode() : null)
                .ge(StringUtils.hasText(startTime), UsageLog::getCreatedAt, startTime)
                .le(StringUtils.hasText(endTime), UsageLog::getCreatedAt, endTime);

        List<UsageLog> records = usageLogMapper.selectList(wrapper);
        
        // 简单聚合（实际应使用 SQL GROUP BY）
        // 这里仅为示例，实际实现需要关联 ModelPrice 表计算金额
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
            writer.write("\uFEFF");
            if ("team".equals(dimension)) {
                writer.write("日期，团队，模型，调用次数，总 Token，总金额 (元)\n");
                // TODO: 实际实现需要 GROUP BY DATE(created_at), team_code, model 并关联价格表计算金额
                writer.write("# 注：此功能需要关联模型价格表计算金额，V5.0 版本暂为示例框架\n");
            } else {
                writer.write("日期，用户，团队，模型，调用次数，总 Token，总金额 (元)\n");
                // TODO: 实际实现需要 GROUP BY DATE(created_at), user_code, team_code, model
                writer.write("# 注：此功能需要关联模型价格表计算金额，V5.0 版本暂为示例框架\n");
            }
        } catch (Exception e) {
            throw new RuntimeException("导出 CSV 失败", e);
        }

        String filename = URLEncoder.encode("成本报表_" + dimension + "_" + System.currentTimeMillis() + ".csv", StandardCharsets.UTF_8).replace("+", "%20");
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(baos.toByteArray());
    }
}
