package com.tokenlimit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 审计日志实体.
 * eventType: LOGIN_SUCCESS / LOGIN_FAILED / CREATE_TEAM /
 *            CREATE_USER / DISABLE_USER / RESET_PASSWORD / CREATE_API_KEY /
 *            DISABLE_API_KEY / DELETE_API_KEY / UPDATE_USER_QUOTA / UPDATE_TEAM_QUOTA / QUOTA_BLOCK
 * result: SUCCESS / FAILED
 */
@TableName("tl_audit_log")
public class AuditLog extends BaseEntity {

    private String teamCode;
    private String userCode;
    private String apiKeyId;
    private String operator;
    private String eventType;
    private String targetType;
    private String targetCode;
    private String detail;
    private String result;
    private String traceId;

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

    public String getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(String apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetCode() {
        return targetCode;
    }

    public void setTargetCode(String targetCode) {
        this.targetCode = targetCode;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
