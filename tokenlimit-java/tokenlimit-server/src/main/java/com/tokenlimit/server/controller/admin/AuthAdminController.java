package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.server.entity.AuditLog;
import com.tokenlimit.server.entity.User;
import com.tokenlimit.server.repository.mapper.AuditLogMapper;
import com.tokenlimit.server.repository.mapper.UserMapper;
import com.tokenlimit.server.security.JwtTokenProvider;
import com.tokenlimit.server.security.LoginAttemptService;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.security.SessionInfo;
import com.tokenlimit.server.util.PasswordHash;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端：登录鉴权（PRD V2.0）.
 * <p>登录账号为 tl_user.username，密码为 BCrypt 哈希（旧 SHA-256 首次登录自动升级）。
 * 首次登录（passwordChangedAt 为空）需强制修改密码；登录成功/失败均写审计日志。
 * 认证基于无状态 JWT（{@link JwtTokenProvider}）：登录签发令牌，不落 Redis 会话，
 * 天然支持多实例水平扩展；登录失败连续超限由 {@link LoginAttemptService} 触发
 * 临时锁定（Redis 计数，多实例共享）。</p>
 */
@RestController
@RequestMapping("/api/admin/auth")
public class AuthAdminController {

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginAttemptService loginAttemptService;

    public AuthAdminController(UserMapper userMapper, AuditLogMapper auditLogMapper,
                               JwtTokenProvider jwtTokenProvider, LoginAttemptService loginAttemptService) {
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.loginAttemptService = loginAttemptService;
    }

    /**
     * 登录：从 tl_user 表校验 username + 密码哈希.
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        // 登录防爆破：Redis 计数锁定
        if (loginAttemptService.isLocked(req.getUsername())) {
            writeAudit(null, null, req.getUsername(), "LOGIN_FAILED", "USER",
                    req.getUsername(), "{\"reason\":\"登录已锁定\"}", "FAILED", null);
            throw new BusinessException(ErrorCode.UNAUTHORIZED.getCode(),
                    "登录失败次数过多，请 " + (loginAttemptService.getLockSeconds() / 60) + " 分钟后重试");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsername())
                .last("limit 1"));
        boolean passwordOk = user != null && user.getPasswordHash() != null
                && PasswordHash.matches(req.getPassword(), user.getPasswordHash());
        if (!passwordOk || user.getLoginEnabled() == null || !user.getLoginEnabled()) {
            loginAttemptService.recordFailure(req.getUsername());
            writeAudit(user == null ? null : user.getTeamCode(),
                    user == null ? null : user.getUserCode(),
                    req.getUsername(), "LOGIN_FAILED", "USER",
                    user == null ? req.getUsername() : user.getUserCode(),
                    "{\"reason\":\"用户名或密码错误\"}", "FAILED", null);
            throw new BusinessException(ErrorCode.UNAUTHORIZED.getCode(), "用户名或密码错误");
        }
        if (!"ENABLED".equals(user.getStatus())) {
            loginAttemptService.recordFailure(req.getUsername());
            writeAudit(user.getTeamCode(), user.getUserCode(),
                    req.getUsername(), "LOGIN_FAILED", "USER", user.getUserCode(),
                    "{\"reason\":\"用户已禁用\"}", "FAILED", null);
            throw new BusinessException(ErrorCode.UNAUTHORIZED.getCode(), "用户已被禁用");
        }

        // 登录成功：清除失败计数、更新最后登录时间
        loginAttemptService.reset(req.getUsername());
        User update = new User();
        update.setId(user.getId());
        update.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(update);

        // 旧版 SHA-256 哈希登录成功 → 静默升级为 BCrypt
        if (PasswordHash.isLegacy(user.getPasswordHash())) {
            User upgrade = new User();
            upgrade.setId(user.getId());
            upgrade.setPasswordHash(PasswordHash.hash(req.getPassword()));
            userMapper.updateById(upgrade);
        }

        boolean mustChangePassword = user.getPasswordChangedAt() == null;

        // 签发无状态 JWT（身份快照固化进 claims，避免后续请求查库，亦不依赖 Redis）
        SessionInfo session = new SessionInfo(user, mustChangePassword);
        session.setRole(resolveRole(user));
        String token = jwtTokenProvider.generate(session);

        writeAudit(user.getTeamCode(), user.getUserCode(),
                user.getUsername(), "LOGIN_SUCCESS", "USER", user.getUserCode(), null, "SUCCESS", null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("username", user.getUsername());
        data.put("userName", user.getUserName());
        data.put("role", resolveRole(user));
        data.put("teamCode", user.getTeamCode());
        data.put("userCode", user.getUserCode());
        data.put("mustChangePassword", mustChangePassword);
        return Result.success(data);
    }

    /**
     * 获取当前登录用户信息（直接从会话读取，不查库）.
     */
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        SessionInfo session = SecurityUtils.requireSession();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", session.getUsername());
        data.put("userName", session.getUserName());
        data.put("role", session.getRole());
        data.put("teamCode", session.getTeamCode());
        data.put("userCode", session.getUserCode());
        data.put("mustChangePassword", session.isMustChangePassword());
        return Result.success(data);
    }

    /**
     * 修改密码（首次登录强制改密）.
     * <p>JWT 无状态，改密后旧令牌无法即时吊销：本接口直接重新签发一份
     * {@code mustChangePassword=false} 的新 JWT 返回给前端替换，使强制改密流程即时生效；
     * 其它设备持有的旧令牌最长存活至过期（可通过缩短 {@code expire-seconds} 收敛风险）。</p>
     */
    @PostMapping("/change-password")
    public Result<Map<String, Object>> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        SessionInfo session = SecurityUtils.requireSession();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, session.getUsername())
                .last("limit 1"));
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (user.getPasswordHash() == null
                || !PasswordHash.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "原密码错误");
        }
        if (req.getNewPassword().length() < 8) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "新密码长度不能少于 8 位");
        }
        User update = new User();
        update.setId(user.getId());
        update.setPasswordHash(PasswordHash.hash(req.getNewPassword()));
        update.setPasswordChangedAt(LocalDateTime.now());
        userMapper.updateById(update);

        // 改密后重新签发 JWT：mustChangePassword=false，返回前端替换本地令牌
        SessionInfo newSession = new SessionInfo(user, false);
        newSession.setRole(session.getRole());
        newSession.setLoginAt(session.getLoginAt());
        String newToken = jwtTokenProvider.generate(newSession);

        writeAudit(user.getTeamCode(), user.getUserCode(),
                user.getUsername(), "RESET_PASSWORD", "USER", user.getUserCode(), null, "SUCCESS", null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", newToken);
        data.put("username", user.getUsername());
        data.put("userName", user.getUserName());
        data.put("role", session.getRole());
        data.put("teamCode", user.getTeamCode());
        data.put("userCode", user.getUserCode());
        data.put("mustChangePassword", false);
        return Result.success(data);
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        // JWT 无状态：无需服务端会话销毁，前端删除本地令牌即可完成登出。
        // 保留端点以兼容前端调用链与审计需求。
        writeAudit(SecurityUtils.requireSession().getTeamCode(),
                SecurityUtils.requireSession().getUserCode(),
                SecurityUtils.requireSession().getUsername(),
                "LOGOUT", "USER", SecurityUtils.requireSession().getUserCode(), null, "SUCCESS", null);
        return Result.success();
    }

    /**
     * 登录请求体.
     */
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class ChangePasswordRequest {
        @NotBlank(message = "原密码不能为空")
        private String oldPassword;
        @NotBlank(message = "新密码不能为空")
        private String newPassword;

        public String getOldPassword() {
            return oldPassword;
        }

        public void setOldPassword(String oldPassword) {
            this.oldPassword = oldPassword;
        }

        public String getNewPassword() {
            return newPassword;
        }

        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
    }

    /**
     * 当前用户角色（superadmin 初始化账号固定为 ADMIN；否则取 tl_user.role）.
     */
    private String resolveRole(User user) {
        if ("superadmin".equals(user.getUsername())) {
            return "ADMIN";
        }
        return StringUtils.hasText(user.getRole()) ? user.getRole() : "USER";
    }

    private void writeAudit(String teamCode, String userCode,
                            String operator, String eventType, String targetType,
                            String targetCode, String detail, String result, String traceId) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTeamCode(teamCode);
            auditLog.setUserCode(userCode);
            auditLog.setOperator(operator);
            auditLog.setEventType(eventType);
            auditLog.setTargetType(targetType);
            auditLog.setTargetCode(targetCode);
            auditLog.setDetail(detail);
            auditLog.setResult(result);
            auditLog.setTraceId(traceId);
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 审计失败不影响主流程
        }
    }

    /**
     * 从 Authorization 头提取 Bearer token（供 UserAdminController 等复用）.
     */
    public static String extractBearerToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    public static String hashPassword(String password) {
        return PasswordHash.hash(password);
    }
}
