package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.entity.User;
import com.tokenlimit.server.repository.mapper.ApiKeyMapper;
import com.tokenlimit.server.repository.mapper.UserMapper;
import com.tokenlimit.server.util.SecretUtils;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端：API Key 管理（PRD V4.0）.
 * <p>API Key 强绑定 team/user；access_key 全局唯一（格式 tl_ak_xxx）；
 * secret 明文仅创建/重置时返回一次，库中仅存哈希。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/api-keys")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN')")
public class ApiKeyAdminController {

    private final ApiKeyMapper apiKeyMapper;
    private final UserMapper userMapper;

    public ApiKeyAdminController(ApiKeyMapper apiKeyMapper, UserMapper userMapper) {
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
    }

    @GetMapping
    public Result<PageResult<ApiKey>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<ApiKey>()
                .eq(StringUtils.hasText(teamCode), ApiKey::getTeamCode, teamCode)
                .eq(StringUtils.hasText(userCode), ApiKey::getUserCode, userCode)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(ApiKey::getAccessKey, keyword)
                        .or()
                        .like(ApiKey::getKeyName, keyword)
                        .or()
                        .like(ApiKey::getKeyId, keyword))
                .eq(StringUtils.hasText(status), ApiKey::getStatus, status)
                .orderByDesc(ApiKey::getCreatedAt);
        Page<ApiKey> p = apiKeyMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    @GetMapping("/{id}")
    public Result<ApiKey> get(@PathVariable Long id) {
        return Result.success(require(id));
    }

    /**
     * 创建 API Key：生成 access_key + secret（secret 仅返回一次，库中仅存哈希）.
     */
    @PostMapping
    public Result<Map<String, Object>> create(@Valid @RequestBody ApiKey apiKey) {
        if (!StringUtils.hasText(apiKey.getTeamCode())
                || !StringUtils.hasText(apiKey.getUserCode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "team/user 均必填");
        }
        if (!StringUtils.hasText(apiKey.getKeyName())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "keyName 必填");
        }
        // 校验用户存在且归属该 team
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getTeamCode, apiKey.getTeamCode())
                .eq(User::getUserCode, apiKey.getUserCode()));
        if (user == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "绑定的用户不存在");
        }
        if (user.getStatus() != null && "DISABLED".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "绑定的用户已被禁用");
        }

        String accessKey = genAccessKey();
        String secret = genSecret();
        String keyId = genKeyId();
        while (apiKeyMapper.selectCount(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getAccessKey, accessKey)) > 0) {
            accessKey = genAccessKey();
        }
        apiKey.setId(null);
        apiKey.setAccessKey(accessKey);
        apiKey.setSecretHash(hashSecret(secret));
        apiKey.setKeyId(keyId);
        apiKey.setStatus(StringUtils.hasText(apiKey.getStatus()) ? apiKey.getStatus() : "ENABLED");
        apiKeyMapper.insert(apiKey);

        Map<String, Object> data = new HashMap<>();
        data.put("apiKey", apiKey);
        data.put("secret", secret); // 仅此一次
        return Result.success(data);
    }

    @PutMapping("/{id}")
    public Result<ApiKey> update(@PathVariable Long id, @RequestBody ApiKey apiKey) {
        require(id);
        apiKey.setId(id);
        apiKey.setAccessKey(null); // 不允许修改 accessKey
        apiKey.setSecretHash(null);
        apiKeyMapper.updateById(apiKey);
        return Result.success(apiKeyMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        require(id);
        apiKeyMapper.deleteById(id);
        return Result.success();
    }

    /**
     * 重置 API Key 密钥：secret 仅返回一次.
     */
    @PostMapping("/{id}/reset-secret")
    public Result<Map<String, Object>> resetSecret(@PathVariable Long id) {
        ApiKey apiKey = require(id);
        String secret = genSecret();
        apiKey.setSecretHash(hashSecret(secret));
        apiKeyMapper.updateById(apiKey);
        Map<String, Object> data = new HashMap<>();
        data.put("accessKey", apiKey.getAccessKey());
        data.put("secret", secret);
        return Result.success(data);
    }

    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam @NotBlank String status) {
        ApiKey apiKey = require(id);
        apiKey.setStatus(status);
        apiKeyMapper.updateById(apiKey);
        return Result.success();
    }

    private ApiKey require(Long id) {
        ApiKey apiKey = apiKeyMapper.selectById(id);
        if (apiKey == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "API Key 不存在");
        }
        return apiKey;
    }

    private String genKeyId() {
        return "key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String genAccessKey() {
        return "tl_ak_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String genSecret() {
        return "sk_tl_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * MVP 阶段使用简单 SHA-256 哈希；生产可替换为 bcrypt.
     */
    public static String hashSecret(String secret) {
        return SecretUtils.hashSecret(secret);
    }
}
