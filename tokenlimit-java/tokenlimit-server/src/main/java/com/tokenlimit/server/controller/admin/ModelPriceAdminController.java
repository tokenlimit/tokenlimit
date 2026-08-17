package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.ModelPrice;
import com.tokenlimit.server.repository.mapper.ModelPriceMapper;
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
import java.time.LocalDateTime;

/**
 * 管理端：模型价格管理（对账中心 - 价格管理，PRD Phase 4）.
 */
@RestController
@RequestMapping("/api/admin/model-prices")
@PreAuthorize("hasRole('ADMIN')")
public class ModelPriceAdminController {

    private final ModelPriceMapper modelPriceMapper;

    public ModelPriceAdminController(ModelPriceMapper modelPriceMapper) {
        this.modelPriceMapper = modelPriceMapper;
    }

    /**
     * 分页查询模型价格.
     */
    @GetMapping
    public Result<PageResult<ModelPrice>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<ModelPrice> wrapper = new LambdaQueryWrapper<ModelPrice>()
                .eq(StringUtils.hasText(provider), ModelPrice::getProvider, provider)
                .like(StringUtils.hasText(model), ModelPrice::getModel, model)
                .eq(StringUtils.hasText(status), ModelPrice::getStatus, status)
                .orderByAsc(ModelPrice::getProvider, ModelPrice::getModel);
        Page<ModelPrice> p = modelPriceMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    /**
     * 查询单个模型价格.
     */
    @GetMapping("/{id}")
    public Result<ModelPrice> get(@PathVariable Long id) {
        return Result.success(require(id));
    }

    /**
     * 新建模型价格.
     */
    @PostMapping
    public Result<ModelPrice> create(@Valid @RequestBody ModelPrice modelPrice) {
        if (!StringUtils.hasText(modelPrice.getProvider()) || !StringUtils.hasText(modelPrice.getModel())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "供应商与模型不能为空");
        }
        if (modelPriceMapper.selectCount(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getProvider, modelPrice.getProvider())
                .eq(ModelPrice::getModel, modelPrice.getModel())) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "该供应商/模型价格已存在");
        }
        modelPrice.setId(null);
        if (modelPrice.getInputPricePerToken() == null) {
            modelPrice.setInputPricePerToken(BigDecimal.ZERO);
        }
        if (modelPrice.getOutputPricePerToken() == null) {
            modelPrice.setOutputPricePerToken(BigDecimal.ZERO);
        }
        if (!StringUtils.hasText(modelPrice.getCurrency())) {
            modelPrice.setCurrency("CNY");
        }
        if (!StringUtils.hasText(modelPrice.getStatus())) {
            modelPrice.setStatus("ENABLED");
        }
        modelPrice.setEffectiveAt(LocalDateTime.now());
        modelPrice.setCreatedBy("console");
        modelPriceMapper.insert(modelPrice);
        return Result.success(modelPriceMapper.selectById(modelPrice.getId()));
    }

    /**
     * 更新模型价格（改价只影响新调用：usage_log 计费快照已固化历史费用）.
     */
    @PutMapping("/{id}")
    public Result<ModelPrice> update(@PathVariable Long id, @RequestBody ModelPrice modelPrice) {
        require(id);
        modelPrice.setId(id);
        // 改价即生效：刷新 effective_at 记录本次改价时间
        modelPrice.setEffectiveAt(LocalDateTime.now());
        modelPriceMapper.updateById(modelPrice);
        return Result.success(modelPriceMapper.selectById(id));
    }

    /**
     * 删除模型价格.
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        require(id);
        modelPriceMapper.deleteById(id);
        return Result.success();
    }

    /**
     * 启用/停用.
     */
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam @NotBlank String status) {
        ModelPrice modelPrice = require(id);
        modelPrice.setStatus(status);
        modelPriceMapper.updateById(modelPrice);
        return Result.success();
    }

    private ModelPrice require(Long id) {
        ModelPrice modelPrice = modelPriceMapper.selectById(id);
        if (modelPrice == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "模型价格不存在");
        }
        return modelPrice;
    }
}
