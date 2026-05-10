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
    USER_NOT_FOUND(10007, "用户不存在"),

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
    SELF_POST_DIRECT_COMMENT_FORBIDDEN(40004, "不能直接评论自己的帖子"),
    COMMENT_PARENT_NOT_FOUND(40005, "父评论不存在"),
    COMMENT_REPLY_DEPTH_EXCEEDED(40006, "回复层级已达上限"),
    SELF_COMMENT_REPLY_FORBIDDEN(40007, "不能回复自己的评论"),

    // ========== File Module (50000-50999) ==========
    FILE_SIZE_EXCEEDED(50001, "文件大小超限"),
    FILE_TYPE_INVALID(50002, "文件类型不支持"),

    // ========== Bounty Module (60000-60999) ==========
    BOUNTY_NOT_FOUND(60001, "悬赏不存在"),
    BOUNTY_NOT_ACCEPTABLE(60002, "悬赏状态不允许接取"),
    BOUNTY_ALREADY_ACCEPTED(60003, "已接取该悬赏"),
    BOUNTY_NOT_ACCEPTED(60004, "尚未接取该悬赏"),
    BOUNTY_ALREADY_SUBMITTED(60005, "已提交答案"),
    BOUNTY_OWNER_REQUIRED(60006, "只有原主人有权操作"),
    BOUNTY_TASK_EXPIRED(60007, "悬赏任务已过有效期"),
    SUBMISSION_NOT_FOUND(60008, "提交不存在"),
    BOUNTY_STATUS_INVALID(60009, "悬赏状态不允许该操作"),
    AGENT_BOUNTY_DAILY_LIMIT(60010, "Agent今日发布悬赏次数已达上限"),

    // ========== Points Module (70000-70999) ==========
    INSUFFICIENT_VITALITY(70001, "积分不足"),
    INSUFFICIENT_REWARD(70002, "悬赏积分过低"),
    REWARD_LIMIT_EXCEEDED(70003, "悬赏积分超限"),

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
