package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.AuditLog;
import com.tokenlimit.server.entity.ReconcileItem;
import com.tokenlimit.server.entity.ReconcileTask;
import com.tokenlimit.server.repository.mapper.AuditLogMapper;
import com.tokenlimit.server.repository.mapper.ReconcileItemMapper;
import com.tokenlimit.server.repository.mapper.ReconcileTaskMapper;
import com.tokenlimit.server.service.ReconcileService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理端：对账任务管理（对账中心，PRD Phase 4）.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciles")
@PreAuthorize("hasRole('ADMIN')")
public class ReconcileAdminController {

    private final ReconcileTaskMapper taskMapper;
    private final ReconcileItemMapper itemMapper;
    private final ReconcileService reconcileService;
    private final AuditLogMapper auditLogMapper;

    public ReconcileAdminController(ReconcileTaskMapper taskMapper,
                                    ReconcileItemMapper itemMapper,
                                    ReconcileService reconcileService,
                                    AuditLogMapper auditLogMapper) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.reconcileService = reconcileService;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 分页查询对账任务.
     */
    @GetMapping
    public Result<PageResult<ReconcileTask>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<ReconcileTask> wrapper = new LambdaQueryWrapper<ReconcileTask>()
                .eq(billDate != null, ReconcileTask::getBillDate, billDate)
                .eq(StringUtils.hasText(provider), ReconcileTask::getProvider, provider)
                .eq(StringUtils.hasText(status), ReconcileTask::getStatus, status)
                .orderByDesc(ReconcileTask::getCreatedAt);
        Page<ReconcileTask> p = taskMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    /**
     * 对账中心统计卡片（PRD V3 页面）：
     * 本月对账任务 / 发现差异 / 待处理争议 / 平均差异率.
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        YearMonth currentMonth = YearMonth.now();
        LocalDate monthStart = currentMonth.atDay(1);
        LocalDate monthEnd = currentMonth.atEndOfMonth();

        long monthTasks = taskMapper.selectCount(new LambdaQueryWrapper<ReconcileTask>()
                .ge(ReconcileTask::getBillDate, monthStart)
                .le(ReconcileTask::getBillDate, monthEnd));

        long diffItems = itemMapper.selectCount(new LambdaQueryWrapper<ReconcileItem>()
                .eq(ReconcileItem::getStatus, "DIFFERENCE"));

        long disputeItems = itemMapper.selectCount(new LambdaQueryWrapper<ReconcileItem>()
                .eq(ReconcileItem::getStatus, "DISPUTED"));

        // 平均差异率：取最近 COMPLETED 任务的 avg_diff_rate 平均
        Page<ReconcileTask> completedPage = taskMapper.selectPage(new Page<>(1, 100),
                new LambdaQueryWrapper<ReconcileTask>()
                        .eq(ReconcileTask::getStatus, "COMPLETED")
                        .orderByDesc(ReconcileTask::getExecutedAt));
        BigDecimal avgRate = BigDecimal.ZERO;
        if (!completedPage.getRecords().isEmpty()) {
            BigDecimal sum = completedPage.getRecords().stream()
                    .map(t -> t.getAvgDiffRate() == null ? BigDecimal.ZERO : t.getAvgDiffRate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            avgRate = sum.divide(BigDecimal.valueOf(completedPage.getRecords().size()), 4,
                    java.math.RoundingMode.HALF_UP);
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("monthTasks", monthTasks);
        stats.put("diffItems", diffItems);
        stats.put("disputeItems", disputeItems);
        stats.put("avgDiffRate", avgRate);
        return Result.success(stats);
    }

    /**
     * 查询对账任务详情.
     */
    @GetMapping("/{id}")
    public Result<ReconcileTask> get(@PathVariable Long id) {
        return Result.success(requireTask(id));
    }

    /**
     * 创建对账任务.
     */
    @PostMapping
    public Result<ReconcileTask> create(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam String provider,
            @RequestParam(required = false) String remark) {
        return Result.success(reconcileService.createTask(billDate, provider, remark));
    }

    /**
     * 执行对账（对比我方用量与供应商账单）.
     */
    @PostMapping("/{id}/execute")
    public Result<ReconcileTask> execute(@PathVariable Long id) {
        ReconcileTask task = reconcileService.execute(id);
        writeAudit(task, "EXECUTE_RECONCILE", "执行对账任务 " + task.getTaskCode(), "SUCCESS");
        return Result.success(task);
    }

    /**
     * 分页查询对账明细分.
     */
    @GetMapping("/{id}/items")
    public Result<PageResult<ReconcileItem>> items(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String model) {
        requireTask(id);
        LambdaQueryWrapper<ReconcileItem> wrapper = new LambdaQueryWrapper<ReconcileItem>()
                .eq(ReconcileItem::getTaskId, id)
                .eq(StringUtils.hasText(status), ReconcileItem::getStatus, status)
                .like(StringUtils.hasText(model), ReconcileItem::getModel, model)
                .orderByDesc(ReconcileItem::getTokenDiffRate);
        Page<ReconcileItem> p = itemMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    /**
     * 明细状态流转（发起争议 / 处理完成）.
     * 合法流转：DIFFERENCE/PENDING -> DISPUTED（发起争议）；DISPUTED -> CONSISTENT（争议解决，标记一致）
     */
    @PutMapping("/items/{id}/status")
    public Result<ReconcileItem> changeItemStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam(required = false) String remark) {
        ReconcileItem item = itemMapper.selectById(id);
        if (item == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "对账明细不存在");
        }
        if (!("DISPUTED".equals(status) || "CONSISTENT".equals(status) || "DIFFERENCE".equals(status))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "不支持的状态：" + status);
        }
        item.setStatus(status);
        if (StringUtils.hasText(remark)) {
            item.setRemark(remark);
        }
        itemMapper.updateById(item);

        // 同步任务统计（争议数 / 差异数）
        ReconcileTask task = requireTask(item.getTaskId());
        task.setDisputeItems((int) (long) itemMapper.selectCount(
                new LambdaQueryWrapper<ReconcileItem>()
                        .eq(ReconcileItem::getTaskId, task.getId())
                        .eq(ReconcileItem::getStatus, "DISPUTED")));
        task.setDiffItems((int) (long) itemMapper.selectCount(
                new LambdaQueryWrapper<ReconcileItem>()
                        .eq(ReconcileItem::getTaskId, task.getId())
                        .eq(ReconcileItem::getStatus, "DIFFERENCE")));
        taskMapper.updateById(task);
        return Result.success(itemMapper.selectById(id));
    }

    /**
     * 删除对账任务（级联删除明细）.
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ReconcileTask task = requireTask(id);
        itemMapper.delete(new LambdaQueryWrapper<ReconcileItem>().eq(ReconcileItem::getTaskId, id));
        taskMapper.deleteById(id);
        writeAudit(task, "DELETE_RECONCILE", "删除对账任务 " + task.getTaskCode(), "SUCCESS");
        return Result.success();
    }

    private ReconcileTask requireTask(Long id) {
        ReconcileTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "对账任务不存在");
        }
        return task;
    }

    private void writeAudit(ReconcileTask task, String eventType, String detail, String result) {
        try {
            AuditLog auditLog = new AuditLog();

            auditLog.setTeamCode(null);
            auditLog.setUserCode(null);
            auditLog.setOperator("console");
            auditLog.setEventType(eventType);
            auditLog.setTargetType("RECONCILE");
            auditLog.setTargetCode(task == null ? null : String.valueOf(task.getId()));
            auditLog.setDetail(detail);
            auditLog.setResult(result);
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 审计失败不影响主流程
        }
    }
}
