package com.tokenlimit.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.server.entity.AuditLog;
import com.tokenlimit.server.entity.ReconcileItem;
import com.tokenlimit.server.entity.ReconcileTask;
import com.tokenlimit.server.entity.UsageLog;
import com.tokenlimit.server.entity.VendorBill;
import com.tokenlimit.server.repository.mapper.AuditLogMapper;
import com.tokenlimit.server.repository.mapper.ReconcileItemMapper;
import com.tokenlimit.server.repository.mapper.ReconcileTaskMapper;
import com.tokenlimit.server.repository.mapper.UsageLogMapper;
import com.tokenlimit.server.repository.mapper.VendorBillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 对账服务：价格管理支撑的成本核算 + 供应商账单对比 + 差异分析（PRD Phase 4）.
 *
 * <p>执行流程：
 * <ol>
 *   <li>从 tl_usage_log 按 (provider, model) 聚合我方用量（SUCCESS 且当日）</li>
 *   <li>与 tl_vendor_bill 供应商账单对比，计算 tokens/成本差异与差异率</li>
 *   <li>差异率超过阈值（默认 3%）判定为 DIFFERENCE，否则 CONSISTENT</li>
 *   <li>汇总任务统计：明细总数、差异数、平均差异率</li>
 * </ol>
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    /** 差异率阈值：超过视为差异（PRD：>3%） */
    private static final BigDecimal MAX_DIFF_RATE = new BigDecimal("0.03");

    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReconcileTaskMapper taskMapper;
    private final ReconcileItemMapper itemMapper;
    private final VendorBillMapper vendorBillMapper;
    private final UsageLogMapper usageLogMapper;
    private final AuditLogMapper auditLogMapper;

    public ReconcileService(ReconcileTaskMapper taskMapper,
                            ReconcileItemMapper itemMapper,
                            VendorBillMapper vendorBillMapper,
                            UsageLogMapper usageLogMapper,
                            AuditLogMapper auditLogMapper) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.vendorBillMapper = vendorBillMapper;
        this.usageLogMapper = usageLogMapper;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 创建对账任务（PENDING）.
     */
    public ReconcileTask createTask(LocalDate billDate, String provider, String remark) {
        if (billDate == null || !org.springframework.util.StringUtils.hasText(provider)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "账单日期与供应商不能为空");
        }
        ReconcileTask task = new ReconcileTask();
        task.setTaskCode(generateTaskCode(billDate, provider));
        task.setBillDate(billDate);
        task.setProvider(provider.trim());
        task.setStatus("PENDING");
        task.setTotalItems(0);
        task.setDiffItems(0);
        task.setDisputeItems(0);
        task.setAvgDiffRate(BigDecimal.ZERO);
        task.setRemark(remark);
        task.setCreatedBy("console");
        taskMapper.insert(task);
        return task;
    }

    /**
     * 执行对账：聚合我方用量并与供应商账单对比生成明细.
     */
    @Transactional(rollbackFor = Exception.class)
    public ReconcileTask execute(Long taskId) {
        ReconcileTask task = requireTask(taskId);
        if (!"PENDING".equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "任务已执行过，不能重复执行");
        }
        task.setStatus("RUNNING");
        taskMapper.updateById(task);
        try {
            LocalDate billDate = task.getBillDate();
            String provider = task.getProvider();

            // 1. 我方用量聚合：(provider, model) -> ourTokens / ourCost
            Map<String, OurUsage> ourMap = aggregateOurUsage(billDate, provider);
            // 2. 供应商账单
            List<VendorBill> bills = vendorBillMapper.selectList(
                    new LambdaQueryWrapper<VendorBill>()
                            .eq(VendorBill::getBillDate, billDate)
                            .eq(VendorBill::getProvider, provider));
            Map<String, VendorBill> vendorMap = bills.stream()
                    .collect(Collectors.toMap(VendorBill::getModel, b -> b, (a, b) -> a));

            // 3. 合并所有模型维度
            List<String> models = new ArrayList<>(ourMap.keySet());
            for (String model : vendorMap.keySet()) {
                if (!ourMap.containsKey(model)) {
                    models.add(model);
                }
            }

            int totalItems = 0;
            int diffItems = 0;
            BigDecimal rateSum = BigDecimal.ZERO;
            List<ReconcileItem> items = new ArrayList<>();

            for (String model : models) {
                OurUsage our = ourMap.get(model);
                VendorBill vendor = vendorMap.get(model);

                long ourTokens = our == null ? 0L : our.tokens;
                long vendorTokens = vendor == null ? 0L : vendor.getProviderTokens();
                BigDecimal ourCost = our == null ? BigDecimal.ZERO : our.cost;
                BigDecimal vendorCost = vendor == null ? BigDecimal.ZERO : vendor.getProviderCost();

                long tokenDiff = vendorTokens - ourTokens;
                BigDecimal costDiff = vendorCost.subtract(ourCost);
                BigDecimal tokenRate = diffRate(tokenDiff, vendorTokens);
                BigDecimal costRate = diffRate(costDiff, vendorCost);
                BigDecimal maxRate = tokenRate.max(costRate);
                boolean difference = maxRate.compareTo(MAX_DIFF_RATE) > 0;

                ReconcileItem item = new ReconcileItem();
                item.setTaskId(task.getId());
                item.setBillDate(billDate);
                item.setProvider(provider);
                item.setModel(model);
                item.setOurTokens(ourTokens);
                item.setProviderTokens(vendorTokens);
                item.setTokenDiff(tokenDiff);
                item.setTokenDiffRate(tokenRate);
                item.setOurCost(ourCost);
                item.setProviderCost(vendorCost);
                item.setCostDiff(costDiff);
                item.setCostDiffRate(costRate);
                item.setStatus(difference ? "DIFFERENCE" : "CONSISTENT");
                items.add(item);

                totalItems++;
                if (difference) {
                    diffItems++;
                }
                rateSum = rateSum.add(tokenRate);
            }

            // 4. 批量写入明细
            if (!items.isEmpty()) {
                for (ReconcileItem item : items) {
                    itemMapper.insert(item);
                }
            }

            // 5. 更新任务统计
            task.setTotalItems(totalItems);
            task.setDiffItems(diffItems);
            task.setDisputeItems((int) itemMapper.selectCount(
                    new LambdaQueryWrapper<ReconcileItem>()
                            .eq(ReconcileItem::getTaskId, task.getId())
                            .eq(ReconcileItem::getStatus, "DISPUTED")));
            task.setAvgDiffRate(totalItems == 0 ? BigDecimal.ZERO
                    : rateSum.divide(BigDecimal.valueOf(totalItems), 4, RoundingMode.HALF_UP));
            task.setStatus("COMPLETED");
            task.setExecutedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            writeAudit(task.getId(), provider, billDate, "EXECUTE_RECONCILE",
                    "完成对账：" + totalItems + " 条明细，" + diffItems + " 条差异", "SUCCESS");
            return task;
        } catch (Exception e) {
            log.error("对账执行失败, taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setExecutedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            writeAudit(task.getId(), task.getProvider(), task.getBillDate(), "EXECUTE_RECONCILE",
                    "对账执行失败：" + e.getMessage(), "FAILED");
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "对账执行失败：" + e.getMessage());
        }
    }

    /**
     * 我方用量聚合（查询 usage_log 按 model 分组）.
     */
    private Map<String, OurUsage> aggregateOurUsage(LocalDate billDate, String provider) {
        QueryWrapper<UsageLog> wrapper = new QueryWrapper<>();
        wrapper.select("model",
                        "COALESCE(SUM(total_tokens), 0) AS ourTokens",
                        "COALESCE(SUM(cost), 0) AS ourCost")
                .eq("provider", provider)
                .eq("status", "SUCCESS")
                .ge("created_at", billDate.atStartOfDay())
                .lt("created_at", billDate.plusDays(1).atStartOfDay())
                .groupBy("model");
        List<Map<String, Object>> rows = usageLogMapper.selectMaps(wrapper);
        return rows.stream().collect(Collectors.toMap(
                r -> String.valueOf(r.get("model")),
                r -> new OurUsage(
                        r.get("ourTokens") == null ? 0L : ((Number) r.get("ourTokens")).longValue(),
                        r.get("ourCost") == null ? BigDecimal.ZERO : new BigDecimal(r.get("ourCost").toString())),
                (a, b) -> a));
    }

    /**
     * 差异率：|diff| / base（base 为 0 时按 diff 是否非零判定）.
     */
    private BigDecimal diffRate(long diff, long base) {
        if (base <= 0) {
            return diff == 0 ? BigDecimal.ZERO : BigDecimal.ONE;
        }
        return BigDecimal.valueOf(Math.abs(diff))
                .divide(BigDecimal.valueOf(base), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal diffRate(BigDecimal diff, BigDecimal base) {
        if (base == null || base.compareTo(BigDecimal.ZERO) <= 0) {
            return diff == null || diff.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO : BigDecimal.ONE;
        }
        return diff.abs().divide(base, 4, RoundingMode.HALF_UP);
    }

    private String generateTaskCode(LocalDate billDate, String provider) {
        return "RC-" + provider.trim().toUpperCase() + "-" + billDate.format(CODE_DATE)
                + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private ReconcileTask requireTask(Long taskId) {
        ReconcileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "对账任务不存在");
        }
        return task;
    }

    private void writeAudit(Long taskId, String provider, LocalDate billDate,
                            String eventType, String detail, String result) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTeamCode(null);
            auditLog.setUserCode(null);
            auditLog.setOperator("console");
            auditLog.setEventType(eventType);
            auditLog.setTargetType("RECONCILE");
            auditLog.setTargetCode(taskId == null ? null : String.valueOf(taskId));
            auditLog.setDetail(detail);
            auditLog.setResult(result);
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 审计失败不影响主流程
            log.warn("写入对账审计日志失败: {}", e.getMessage());
        }
    }

    /** 我方聚合结果（model 维度） */
    private static class OurUsage {
        private final long tokens;
        private final BigDecimal cost;

        OurUsage(long tokens, BigDecimal cost) {
            this.tokens = tokens;
            this.cost = cost;
        }
    }
}
