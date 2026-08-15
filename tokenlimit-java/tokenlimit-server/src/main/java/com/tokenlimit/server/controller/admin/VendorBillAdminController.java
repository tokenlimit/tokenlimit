package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.AuditLog;
import com.tokenlimit.server.entity.VendorBill;
import com.tokenlimit.server.repository.mapper.AuditLogMapper;
import com.tokenlimit.server.repository.mapper.VendorBillMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 管理端：供应商账单管理（对账中心 - 账单导入）.
 */
@RestController
@RequestMapping("/api/v1/admin/vendor-bills")
@PreAuthorize("hasRole('ADMIN')")
public class VendorBillAdminController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VendorBillAdminController.class);

    private final VendorBillMapper vendorBillMapper;
    private final AuditLogMapper auditLogMapper;

    public VendorBillAdminController(VendorBillMapper vendorBillMapper, AuditLogMapper auditLogMapper) {
        this.vendorBillMapper = vendorBillMapper;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 分页查询供应商账单.
     */
    @GetMapping
    public Result<PageResult<VendorBill>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) LocalDate billDate,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<VendorBill> wrapper = new LambdaQueryWrapper<VendorBill>()
                .eq(billDate != null, VendorBill::getBillDate, billDate)
                .eq(StringUtils.hasText(provider), VendorBill::getProvider, provider)
                .like(StringUtils.hasText(model), VendorBill::getModel, model)
                .eq(StringUtils.hasText(status), VendorBill::getStatus, status)
                .orderByDesc(VendorBill::getBillDate);
        Page<VendorBill> p = vendorBillMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    /**
     * 查询单个供应商账单.
     */
    @GetMapping("/{id}")
    public Result<VendorBill> get(@PathVariable Long id) {
        return Result.success(require(id));
    }

    /**
     * 新建供应商账单.
     */
    @PostMapping
    public Result<VendorBill> create(@Valid @RequestBody VendorBill vendorBill) {
        validate(vendorBill);
        vendorBill.setId(null);
        if (vendorBill.getProviderTokens() == null) {
            vendorBill.setProviderTokens(0L);
        }
        if (vendorBill.getProviderCost() == null) {
            vendorBill.setProviderCost(BigDecimal.ZERO);
        }
        if (!StringUtils.hasText(vendorBill.getCurrency())) {
            vendorBill.setCurrency("CNY");
        }
        if (!StringUtils.hasText(vendorBill.getStatus())) {
            vendorBill.setStatus("ENABLED");
        }
        vendorBillMapper.insert(vendorBill);
        writeAudit("CREATE_VENDOR_BILL", vendorBill, null);
        return Result.success(vendorBillMapper.selectById(vendorBill.getId()));
    }

    /**
     * 批量导入供应商账单（账单导入）.
     */
    @PostMapping("/batch")
    public Result<Integer> batchCreate(@RequestBody List<VendorBill> bills) {
        if (bills == null || bills.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "账单列表不能为空");
        }
        int count = 0;
        for (VendorBill bill : bills) {
            try {
                validate(bill);
                bill.setId(null);
                if (bill.getProviderTokens() == null) {
                    bill.setProviderTokens(0L);
                }
                if (bill.getProviderCost() == null) {
                    bill.setProviderCost(BigDecimal.ZERO);
                }
                if (!StringUtils.hasText(bill.getCurrency())) {
                    bill.setCurrency("CNY");
                }
                if (!StringUtils.hasText(bill.getStatus())) {
                    bill.setStatus("ENABLED");
                }
                vendorBillMapper.insert(bill);
                count++;
            } catch (Exception e) {
                log.warn("批量导入跳过异常记录: {}", e.getMessage());
            }
        }
        writeAudit("IMPORT_VENDOR_BILL", null, "导入 " + count + " 条供应商账单");
        return Result.success(count);
    }

    /**
     * 更新供应商账单.
     */
    @PutMapping("/{id}")
    public Result<VendorBill> update(@PathVariable Long id, @RequestBody VendorBill vendorBill) {
        require(id);
        validate(vendorBill);
        vendorBill.setId(id);
        vendorBillMapper.updateById(vendorBill);
        return Result.success(vendorBillMapper.selectById(id));
    }

    /**
     * 删除供应商账单.
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        VendorBill bill = require(id);
        vendorBillMapper.deleteById(id);
        writeAudit("DELETE_VENDOR_BILL", bill, null);
        return Result.success();
    }

    private void validate(VendorBill vendorBill) {
        if (vendorBill.getBillDate() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "账单日期不能为空");
        }
        if (!StringUtils.hasText(vendorBill.getProvider()) || !StringUtils.hasText(vendorBill.getModel())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "供应商与模型不能为空");
        }
    }

    private VendorBill require(Long id) {
        VendorBill vendorBill = vendorBillMapper.selectById(id);
        if (vendorBill == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "供应商账单不存在");
        }
        return vendorBill;
    }

    private void writeAudit(String eventType, VendorBill bill, String detail) {
        try {
            AuditLog auditLog = new AuditLog();

            auditLog.setTeamCode(null);
            auditLog.setUserCode(null);
            auditLog.setOperator("console");
            auditLog.setEventType(eventType);
            auditLog.setTargetType("VENDOR_BILL");
            auditLog.setTargetCode(bill == null ? null : bill.getBillDate() + "/" + bill.getProvider());
            auditLog.setDetail(detail);
            auditLog.setResult("SUCCESS");
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.warn("写入账单审计日志失败: {}", e.getMessage());
        }
    }

}
