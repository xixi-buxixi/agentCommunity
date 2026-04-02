package com.pulse.exception;

import lombok.Getter;

/**
 * Error Code Enumeration
 *
 * Standard error codes for all API responses.
 */
@Getter
public enum ErrorCode {

    // ========== Auth Module (10000-10999) ==========
    USERNAME_EXISTS(10001, "用户名已存在"),
    EMAIL_EXISTS(10002, "邮箱已注册"),
    LOGIN_FAILED(10003, "用户名或密码错误"),
    TOKEN_EXPIRED(10004, "Token已过期"),
    TOKEN_INVALID(10005, "Token无效"),
    UNAUTHORIZED(10006, "未授权访问"),

    // ========== Agent Module (20000-20999) ==========
    AGENT_NAME_EXISTS(20001, "Agent名称已存在"),
    AGENT_NOT_FOUND(20002, "Agent不存在"),
    AGENT_NOT_OWNER(20003, "Agent不属于当前用户"),
    AGENT_DEAD(20004, "Agent已死机"),
    AGENT_CONFIRM_NAME_MISMATCH(20005, "确认名称不匹配"),
    AGENT_TOKEN_EXHAUSTED(20006, "Agent能量耗尽"),

    // ========== Post Module (30000-30999) ==========
    POST_NOT_FOUND(30001, "动态不存在"),
    POST_CONTENT_TOO_LONG(30002, "动态内容过长"),
    POST_IMAGE_LIMIT(30003, "图片数量超限"),
    POST_ALREADY_LIKED(30004, "已点赞"),
    POST_NOT_LIKED(30005, "未点赞"),
    POST_ALREADY_DISLIKED(30006, "已踩，不能重复踩"),
    POST_NOT_DISLIKED(30007, "未踩，无法取消"),

    // ========== Comment Module (40000-40999) ==========
    COMMENT_NOT_FOUND(40001, "评论不存在"),
    COMMENT_CONTENT_TOO_LONG(40002, "评论内容过长"),
    SYSTEM_POST_NO_COMMENT(40003, "系统消息禁止评论"),

    // ========== File Module (50000-50999) ==========
    FILE_SIZE_EXCEEDED(50001, "文件大小超限"),
    FILE_TYPE_INVALID(50002, "文件类型不支持"),

    // ========== System Errors (99999) ==========
    INVALID_PARAMETER(99900, "参数错误"),
    SYSTEM_ERROR(99999, "系统内部错误"),
    LLM_CALL_FAILED(99901, "LLM调用失败"),
    JSON_PARSE_ERROR(99902, "JSON解析失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}