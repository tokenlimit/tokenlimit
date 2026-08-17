package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.config.TokenLimitProperties;
import com.tokenlimit.server.entity.ApiKey;
import com.tokenlimit.server.entity.AuditLog;
import com.tokenlimit.server.entity.User;
import com.tokenlimit.server.repository.mapper.ApiKeyMapper;
import com.tokenlimit.server.repository.mapper.AuditLogMapper;
import com.tokenlimit.server.repository.mapper.UserMapper;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.security.SessionInfo;
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

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端：API Key 管理（PRD V5.0）.
 * <p>ADMIN 管理全部；TEAM_ADMIN 仅管理本团队（PRD 11.2）；USER 仅管理自己的 API Key（自动按 userCode 过滤）。</p>
 * <p>API Key 强绑定 team/user；access_key 全局唯一（格式 tl_ak_ + 32 位 base62）；
 * secret 明文仅创建/重置时返回一次，库中仅存哈希。</p>
 */
@RestController
@RequestMapping("/api/admin/api-keys")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN', 'USER')")
public class ApiKeyAdminController {

    private final ApiKeyMapper apiKeyMapper;
    private final UserMapper userMapper;
    private final TokenLimitProperties properties;
    private final AuditLogMapper auditLogMapper;

    /** accessKey 随机段长度：32 位 base62 ≈ 190 bit 熵（对齐 GitHub PAT 36 位 / OpenAI 40+ 位的大厂策略） */
    private static final int ACCESS_KEY_RANDOM_LEN = 32;

    /** base62 字符集（大小写字母 + 数字，无冒号等拼接分隔符冲突） */
    private static final char[] BASE62_CHARS =
            ("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz").toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    public ApiKeyAdminController(ApiKeyMapper apiKeyMapper, UserMapper userMapper,
                                 TokenLimitProperties properties, AuditLogMapper auditLogMapper) {
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
        this.properties = properties;
        this.auditLogMapper = auditLogMapper;
    }

    @GetMapping
    public Result<PageResult<ApiKey>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        SessionInfo session = SecurityUtils.requireSession();
        boolean isUser = "USER".equals(session.getRole());

        LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<ApiKey>()
                // USER 角色强制过滤为自己的 API Key
                .eq(isUser, ApiKey::getUserCode, isUser ? session.getUserCode() : userCode)
                // TEAM_ADMIN 强制过滤为本团队的 API Key
                .eq("TEAM_ADMIN".equals(session.getRole()), ApiKey::getTeamCode, session.getTeamCode())
                // ADMIN 可按 teamCode/userCode 筛选
                .eq(!isUser && StringUtils.hasText(teamCode), ApiKey::getTeamCode, teamCode)
                .eq(!isUser && StringUtils.hasText(userCode), ApiKey::getUserCode, userCode)
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
        ApiKey apiKey = require(id);
        assertKeyOwned(apiKey);
        return Result.success(apiKey);
    }

    /**
     * 创建 API Key：生成 access_key + secret（secret 仅返回一次，库中仅存哈希）.
     * <p>USER 角色只能为自己创建；TEAM_ADMIN 只能为本 Team 用户创建（PRD 10.2）；ADMIN 可为任意用户创建。</p>
     */
    @PostMapping
    public Result<Map<String, Object>> create(@Valid @RequestBody ApiKey apiKey) {
        SessionInfo session = SecurityUtils.requireSession();
        boolean isUser = "USER".equals(session.getRole());

        // USER 角色强制绑定为自己
        if (isUser) {
            apiKey.setTeamCode(session.getTeamCode());
            apiKey.setUserCode(session.getUserCode());
        }
        // TEAM_ADMIN 强制绑定为本团队（PRD 10.2：只能创建本 Team 下 User 的 API Key）
        if ("TEAM_ADMIN".equals(session.getRole())) {
            apiKey.setTeamCode(session.getTeamCode());
        }

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
        // USER 角色只能为自己创建
        if (isUser && !session.getUserCode().equals(user.getUserCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "只能为自己创建 API Key");
        }
        // TEAM_ADMIN 只能为本团队用户创建（防跨团队越权）
        if ("TEAM_ADMIN".equals(session.getRole())
                && !session.getTeamCode().equals(user.getTeamCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "只能为本团队用户创建 API Key");
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
        writeAudit(apiKey, "CREATE_API_KEY", "{\"keyName\":\"" + apiKey.getKeyName() + "\"}");

        Map<String, Object> data = new HashMap<>();
        data.put("apiKey", apiKey);
        data.put("secret", secret); // 仅此一次
        return Result.success(data);
    }

    @PutMapping("/{id}")
    public Result<ApiKey> update(@PathVariable Long id, @RequestBody ApiKey apiKey) {
        ApiKey existed = require(id);
        assertKeyOwned(existed);
        apiKey.setId(id);
        apiKey.setAccessKey(null); // 不允许修改 accessKey
        apiKey.setSecretHash(null);
        apiKey.setTeamCode(null); // 不允许修改归属（防跨团队迁移越权）
        apiKey.setUserCode(null);
        apiKeyMapper.updateById(apiKey);
        return Result.success(apiKeyMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ApiKey apiKey = require(id);
        assertKeyOwned(apiKey);
        apiKeyMapper.deleteById(id);
        writeAudit(apiKey, "DELETE_API_KEY", null);
        return Result.success();
    }

    /**
     * 重置 API Key 密钥：secret 仅返回一次.
     */
    @PostMapping("/{id}/reset-secret")
    public Result<Map<String, Object>> resetSecret(@PathVariable Long id) {
        ApiKey apiKey = require(id);
        assertKeyOwned(apiKey);
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
        assertKeyOwned(apiKey);
        apiKey.setStatus(status);
        apiKeyMapper.updateById(apiKey);
        if (!"ENABLED".equals(status)) {
            writeAudit(apiKey, "DISABLE_API_KEY", "{\"status\":\"" + status + "\"}");
        }
        return Result.success();
    }

    private ApiKey require(Long id) {
        ApiKey apiKey = apiKeyMapper.selectById(id);
        if (apiKey == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "API Key 不存在");
        }
        return apiKey;
    }

    /**
     * 归属校验（PRD 10.2 / 11.2）：USER 只能操作自己的 Key，TEAM_ADMIN 只能操作本团队 Key.
     */
    private void assertKeyOwned(ApiKey apiKey) {
        SessionInfo session = SecurityUtils.requireSession();
        if ("USER".equals(session.getRole())
                && !session.getUserCode().equals(apiKey.getUserCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "只能操作自己的 API Key");
        }
        if ("TEAM_ADMIN".equals(session.getRole())
                && !session.getTeamCode().equals(apiKey.getTeamCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "只能操作本团队的 API Key");
        }
    }

    /**
     * 写审计日志（PRD 13.1：CREATE_API_KEY / DISABLE_API_KEY / DELETE_API_KEY）.
     */
    private void writeAudit(ApiKey apiKey, String eventType, String detail) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTeamCode(apiKey.getTeamCode());
            auditLog.setUserCode(apiKey.getUserCode());
            auditLog.setApiKeyId(apiKey.getKeyId());
            auditLog.setOperator(currentOperator());
            auditLog.setEventType(eventType);
            auditLog.setTargetType("API_KEY");
            auditLog.setTargetCode(apiKey.getKeyId());
            auditLog.setDetail(detail);
            auditLog.setResult("SUCCESS");
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 审计失败不影响主流程
        }
    }

    private String currentOperator() {
        try {
            SessionInfo session = SecurityUtils.currentSession();
            return session != null && StringUtils.hasText(session.getUsername())
                    ? session.getUsername() : "console";
        } catch (Exception e) {
            return "console";
        }
    }

    private String genKeyId() {
        return "key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 生成 accessKey：{@code tl_ak_} + 32 位 base62 随机段（≈190 bit 熵）.
     * <p>对齐 GitHub / OpenAI / 阿里云等大厂凭证策略：语义化前缀 + CSPRNG 高熵随机段；
     * 唯一性由随机熵 + 创建时查库重试 + 数据库唯一索引三重保障。</p>
     */
    private String genAccessKey() {
        return "tl_ak_" + randomBase62(ACCESS_KEY_RANDOM_LEN);
    }

    /**
     * 生成指定长度的 base62 随机串（SecureRandom，nextInt 无模偏差）.
     */
    private static String randomBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62_CHARS[RANDOM.nextInt(BASE62_CHARS.length)]);
        }
        return sb.toString();
    }

    private String genSecret() {
        return "sk_tl_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * secret 哈希：HMAC-SHA256 + 服务端 pepper（见 SecretUtils）.
     */
    private String hashSecret(String secret) {
        return SecretUtils.hashSecret(secret, properties.getHashPepper());
    }
}
