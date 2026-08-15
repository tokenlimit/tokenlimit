package com.tokenlimit.common.enums;

/**
 * 配额目标类型.
 * <p>MVP 阶段仅支持 TEAM / USER 两级配额（PRD V4.0），已废除 NAMESPACE。</p>
 */
public enum TargetType {
    /** 团队（团队 / 部门 / 应用 / 项目 / 客户 / 成本中心） */
    TEAM,
    /** 用户（员工 / 终端客户 / 机器人账号 / 服务 / 系统） */
    USER
}
