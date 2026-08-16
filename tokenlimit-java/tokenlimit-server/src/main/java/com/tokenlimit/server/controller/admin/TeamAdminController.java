package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tokenlimit.common.api.BusinessException;
import com.tokenlimit.common.api.ErrorCode;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.common.dto.PageResult;
import com.tokenlimit.server.entity.AuditLog;
import com.tokenlimit.server.entity.Team;
import com.tokenlimit.server.repository.mapper.AuditLogMapper;
import com.tokenlimit.server.repository.mapper.TeamMapper;
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

/**
 * 管理端：团队 CRUD（核心预算池 / 成本中心 / 账号边界 / 密钥边界）.
 * <p>PRD 4.5 / 11.1：团队管理（创建 / 编辑 / 禁用）仅 ADMIN；TEAM_ADMIN 只能管理本 Team 数据，无团队 CRUD 权限。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/teams")
@PreAuthorize("hasRole('ADMIN')")
public class TeamAdminController {

    private final TeamMapper teamMapper;
    private final AuditLogMapper auditLogMapper;

    public TeamAdminController(TeamMapper teamMapper, AuditLogMapper auditLogMapper) {
        this.teamMapper = teamMapper;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 分页查询团队.
     */
    @GetMapping
    public Result<PageResult<Team>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String teamType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<Team> wrapper = new LambdaQueryWrapper<Team>()
                .eq(StringUtils.hasText(teamType), Team::getTeamType, teamType)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Team::getTeamCode, keyword)
                        .or()
                        .like(Team::getTeamName, keyword))
                .eq(StringUtils.hasText(status), Team::getStatus, status)
                .orderByDesc(Team::getCreatedAt);
        Page<Team> p = teamMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(new PageResult<>(page, size, p.getTotal(), p.getRecords()));
    }

    @GetMapping("/{id}")
    public Result<Team> get(@PathVariable Long id) {
        return Result.success(require(id));
    }

    @PostMapping
    public Result<Team> create(@Valid @RequestBody Team team) {
        if (StringUtils.hasText(team.getTeamCode())
                && teamMapper.selectCount(new LambdaQueryWrapper<Team>()
                .eq(Team::getTeamCode, team.getTeamCode())) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "团队编码已存在");
        }
        team.setId(null);
        if (!StringUtils.hasText(team.getStatus())) {
            team.setStatus("ENABLED");
        }
        teamMapper.insert(team);
        writeAudit(team.getTeamCode(), "CREATE_TEAM", team.getTeamCode(), null, "SUCCESS");
        return Result.success(team);
    }

    @PutMapping("/{id}")
    public Result<Team> update(@PathVariable Long id, @RequestBody Team team) {
        require(id);
        team.setId(id);
        teamMapper.updateById(team);
        writeAudit(team.getTeamCode(), "UPDATE_TEAM", team.getTeamCode(),
                "{\"teamName\":\"" + (team.getTeamName() == null ? "" : team.getTeamName()) + "\"}", "SUCCESS");
        return Result.success(teamMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Team team = require(id);
        teamMapper.deleteById(id);
        writeAudit(team.getTeamCode(), "UPDATE_TEAM", team.getTeamCode(),
                "{\"action\":\"delete\"}", "SUCCESS");
        return Result.success();
    }

    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam @NotBlank String status) {
        Team team = require(id);
        team.setStatus(status);
        teamMapper.updateById(team);
        writeAudit(team.getTeamCode(), "DISABLED".equals(status) ? "DISABLE_TEAM" : "UPDATE_TEAM",
                team.getTeamCode(), "{\"status\":\"" + status + "\"}", "SUCCESS");
        return Result.success();
    }

    private Team require(Long id) {
        Team team = teamMapper.selectById(id);
        if (team == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "团队不存在");
        }
        return team;
    }

    /**
     * 写审计日志（PRD 13.1：CREATE_TEAM / UPDATE_TEAM / DISABLE_TEAM）.
     */
    private void writeAudit(String teamCode, String eventType, String targetCode,
                            String detail, String result) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTeamCode(teamCode);
            auditLog.setOperator(currentOperator());
            auditLog.setEventType(eventType);
            auditLog.setTargetType("TEAM");
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
}
