package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.AuditLog;
import com.tokenlimit.server.entity.User;
import com.tokenlimit.server.repository.mapper.AuditLogMapper;
import com.tokenlimit.server.repository.mapper.UserMapper;
import com.tokenlimit.server.security.SecurityUtils;
import com.tokenlimit.server.security.SessionInfo;
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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 管理端：用户 CRUD（PRD V2.0）.
 * <p>用户可配置登录账号（username，全局唯一）与初始密码；禁用用户后其 API Key 将不可调用。</p>
 * <p>PRD 4.5 / 11.2：TEAM_ADMIN 只能管理本 Team 下的 User（list/create 强制 teamCode，其他操作校验归属）。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_ADMIN')")
public class UserAdminController {

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;

    public UserAdminController(UserMapper userMapper, AuditLogMapper auditLogMapper) {
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @GetMapping
    public Result<PageResult<User>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) String quotaMode,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        // TEAM_ADMIN 强制过滤为本团队用户（PRD 11.2），请求参数 teamCode 无效
        SessionInfo session = SecurityUtils.requireSession();
        if ("TEAM_ADMIN".equals(session.getRole())) {
            teamCode = session.getTeamCode();
        }
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(StringUtils.hasText(teamCode), User::getTeamCode, teamCode)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(User::getUserCode, keyword)
                        .or()
                        .like(User::getUserName, keyword)
                        .or()
                        .like(User::getUsername, keyword))
                .eq(StringUtils.hasText(userType), User::getUserType, userType)
                .eq(StringUtils.hasText(quotaMode), User::getQuotaMode, quotaMode)
                .eq(StringUtils.hasText(role), User::getRole, role)
                .eq(StringUtils.hasText(status), User::getStatus, status)
                .orderByDesc(User::getCreatedAt);
        Page<User> p = userMapper.selectPage(new Page<>(page, size), wrapper);
        // 敏感字段脱敏
        p.getRecords().forEach(u -> u.setPasswordHash(null));
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    @GetMapping("/{id}")
    public Result<User> get(@PathVariable Long id) {
        User user = require(id);
        user.setPasswordHash(null);
        return Result.success(user);
    }

    @PostMapping
    public Result<User> create(@Valid @RequestBody CreateUserRequest req) {
        // TEAM_ADMIN 只能在本 Team 下创建用户（PRD 11.2），忽略请求中的 teamCode
        SessionInfo session = SecurityUtils.requireSession();
        if ("TEAM_ADMIN".equals(session.getRole())) {
            req.setTeamCode(session.getTeamCode());
        }
        if (StringUtils.hasText(req.getTeamCode())
                && StringUtils.hasText(req.getUserCode())
                && userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getTeamCode, req.getTeamCode())
                .eq(User::getUserCode, req.getUserCode())) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "用户编码在团队内已存在");
        }
        if (StringUtils.hasText(req.getUsername()) && userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsername())) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "登录账号已存在");
        }
        User user = new User();
        user.setTeamCode(req.getTeamCode());
        user.setUserCode(req.getUserCode());
        user.setUserName(req.getUserName());
        user.setUserType(StringUtils.hasText(req.getUserType()) ? req.getUserType() : "EMPLOYEE");
        user.setQuotaMode(StringUtils.hasText(req.getQuotaMode())
                ? req.getQuotaMode() : "PERSONAL_FIRST_THEN_TEAM");
        user.setRole(StringUtils.hasText(req.getRole()) ? req.getRole() : "USER");
        user.setUsername(req.getUsername());
        if (StringUtils.hasText(req.getPassword())) {
            user.setPasswordHash(AuthAdminController.hashPassword(req.getPassword()));
            user.setPasswordChangedAt(null); // 首次登录强制改密
        }
        user.setLoginEnabled(req.getLoginEnabled() == null || req.getLoginEnabled());
        user.setStatus("ENABLED");
        userMapper.insert(user);

        writeAudit(user.getTeamCode(), user.getUserCode(),
                "CREATE_USER", "USER", user.getUserCode(), null, "SUCCESS");
        user.setPasswordHash(null);
        return Result.success(user);
    }

    @PutMapping("/{id}")
    public Result<User> update(@PathVariable Long id, @RequestBody User user) {
        require(id);
        user.setId(id);
        user.setUserCode(null); // 不允许修改编码
        user.setTeamCode(null); // 不允许修改团队归属（防跨团队迁移越权）
        user.setPasswordHash(null); // 不允许直接改密码哈希
        userMapper.updateById(user);
        User updated = userMapper.selectById(id);
        updated.setPasswordHash(null);
        return Result.success(updated);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        User user = require(id);
        userMapper.deleteById(id);
        writeAudit(user.getTeamCode(), user.getUserCode(),
                "DISABLE_USER", "USER", user.getUserCode(), "{\"action\":\"delete\"}", "SUCCESS");
        return Result.success();
    }

    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam @NotBlank String status) {
        User user = require(id);
        user.setStatus(status);
        userMapper.updateById(user);
        writeAudit(user.getTeamCode(), user.getUserCode(),
                "DISABLE_USER", "USER", user.getUserCode(),
                "{\"status\":\"" + status + "\"}", "SUCCESS");
        return Result.success();
    }

    /**
     * 重置密码（返回初始密码，用户下次登录强制改密）.
     */
    @PostMapping("/{id}/reset-password")
    public Result<Map<String, String>> resetPassword(@PathVariable Long id) {
        User user = require(id);
        String newPassword = genTempPassword();
        User update = new User();
        update.setId(user.getId());
        update.setPasswordHash(AuthAdminController.hashPassword(newPassword));
        update.setPasswordChangedAt(null);
        userMapper.updateById(update);
        writeAudit(user.getTeamCode(), user.getUserCode(),
                "RESET_PASSWORD", "USER", user.getUserCode(), null, "SUCCESS");
        return Result.success(Map.of("username", StringUtils.hasText(user.getUsername())
                ? user.getUsername() : user.getUserCode(), "password", newPassword));
    }

    private User require(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "用户不存在");
        }
        // TEAM_ADMIN 只能操作本 Team 用户（PRD 11.2），防止跨团队越权
        SessionInfo session = SecurityUtils.requireSession();
        if ("TEAM_ADMIN".equals(session.getRole())
                && !session.getTeamCode().equals(user.getTeamCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "无权操作其他团队的用户");
        }
        return user;
    }

    private String genTempPassword() {
        return "Tl@" + (int) (Math.random() * 9000 + 1000) + (int) (Math.random() * 900 + 100);
    }

    private void writeAudit(String teamCode, String userCode,
                            String eventType, String targetType, String targetCode,
                            String detail, String result) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTeamCode(teamCode);
            auditLog.setUserCode(userCode);
            auditLog.setOperator(currentOperator());
            auditLog.setEventType(eventType);
            auditLog.setTargetType(targetType);
            auditLog.setTargetCode(targetCode);
            auditLog.setDetail(detail);
            auditLog.setResult(result);
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

    /**
     * 创建用户请求体（避免直接绑定实体导致密码哈希泄漏）.
     */
    public static class CreateUserRequest {
        @NotBlank(message = "teamCode 不能为空")
        private String teamCode;
        @NotBlank(message = "userCode 不能为空")
        private String userCode;
        @NotBlank(message = "userName 不能为空")
        private String userName;
        private String userType;
        private String quotaMode;
        private String role;
        private String username;
        private String password;
        private Boolean loginEnabled;

        public String getTeamCode() {
            return teamCode;
        }

        public void setTeamCode(String teamCode) {
            this.teamCode = teamCode;
        }

        public String getUserCode() {
            return userCode;
        }

        public void setUserCode(String userCode) {
            this.userCode = userCode;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getUserType() {
            return userType;
        }

        public void setUserType(String userType) {
            this.userType = userType;
        }

        public String getQuotaMode() {
            return quotaMode;
        }

        public void setQuotaMode(String quotaMode) {
            this.quotaMode = quotaMode;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

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

        public Boolean getLoginEnabled() {
            return loginEnabled;
        }

        public void setLoginEnabled(Boolean loginEnabled) {
            this.loginEnabled = loginEnabled;
        }
    }
}
