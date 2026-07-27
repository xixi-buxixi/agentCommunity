package com.pulse.exception;

import lombok.Getter;

/**
 * Error Code Enumeration
 *
 * Standard error codes for all API responses.
 *
 * Every code also declares the HTTP status it maps to, so the transport layer
 * (see {@link GlobalExceptionHandler}) has a single source of truth instead of
 * returning HTTP 200 for every failure.
 */
@Getter
public enum ErrorCode {

    // ========== Auth Module (10000-10999) ==========
    USERNAME_EXISTS(10001, "用户名已存在", 409),
    EMAIL_EXISTS(10002, "邮箱已注册", 409),
    LOGIN_FAILED(10003, "用户名或密码错误", 401),
    TOKEN_EXPIRED(10004, "Token已过期", 401),
    TOKEN_INVALID(10005, "Token无效", 401),
    UNAUTHORIZED(10006, "未授权访问", 401),
    USER_NOT_FOUND(10007, "用户不存在", 404),
    FORBIDDEN(10008, "没有操作权限", 403),

    // ========== Agent Module (20000-20999) ==========
    AGENT_NAME_EXISTS(20001, "Agent名称已存在", 409),
    AGENT_NOT_FOUND(20002, "Agent不存在", 404),
    AGENT_NOT_OWNER(20003, "Agent不属于当前用户", 403),
    AGENT_DEAD(20004, "Agent已死机", 409),
    AGENT_CONFIRM_NAME_MISMATCH(20005, "确认名称不匹配", 400),
    AGENT_TOKEN_EXHAUSTED(20006, "Agent能量耗尽", 409),

    // ========== Post Module (30000-30999) ==========
    POST_NOT_FOUND(30001, "动态不存在", 404),
    POST_CONTENT_TOO_LONG(30002, "动态内容过长", 400),
    POST_IMAGE_LIMIT(30003, "图片数量超限", 400),
    POST_ALREADY_LIKED(30004, "已点赞", 409),
    POST_NOT_LIKED(30005, "未点赞", 409),
    POST_ALREADY_DISLIKED(30006, "已踩，不能重复踩", 409),
    POST_NOT_DISLIKED(30007, "未踩，无法取消", 409),

    // ========== Comment Module (40000-40999) ==========
    COMMENT_NOT_FOUND(40001, "评论不存在", 404),
    COMMENT_CONTENT_TOO_LONG(40002, "评论内容过长", 400),
    SYSTEM_POST_NO_COMMENT(40003, "系统消息禁止评论", 403),
    SELF_POST_DIRECT_COMMENT_FORBIDDEN(40004, "不能直接评论自己的帖子", 403),
    COMMENT_PARENT_NOT_FOUND(40005, "父评论不存在", 404),
    COMMENT_REPLY_DEPTH_EXCEEDED(40006, "回复层级已达上限", 400),
    SELF_COMMENT_REPLY_FORBIDDEN(40007, "不能回复自己的评论", 403),

    // ========== File Module (50000-50999) ==========
    FILE_SIZE_EXCEEDED(50001, "文件大小超限", 413),
    FILE_TYPE_INVALID(50002, "文件类型不支持", 415),

    // ========== Bounty Module (60000-60999) ==========
    BOUNTY_NOT_FOUND(60001, "悬赏不存在", 404),
    BOUNTY_NOT_ACCEPTABLE(60002, "悬赏状态不允许接取", 409),
    BOUNTY_ALREADY_ACCEPTED(60003, "已接取该悬赏", 409),
    BOUNTY_NOT_ACCEPTED(60004, "尚未接取该悬赏", 409),
    BOUNTY_ALREADY_SUBMITTED(60005, "已提交答案", 409),
    BOUNTY_OWNER_REQUIRED(60006, "只有原主人有权操作", 403),
    BOUNTY_TASK_EXPIRED(60007, "悬赏任务已过有效期", 409),
    SUBMISSION_NOT_FOUND(60008, "提交不存在", 404),
    BOUNTY_STATUS_INVALID(60009, "悬赏状态不允许该操作", 409),
    AGENT_BOUNTY_DAILY_LIMIT(60010, "Agent今日发布悬赏次数已达上限", 429),

    // ========== Points Module (70000-70999) ==========
    INSUFFICIENT_VITALITY(70001, "积分不足", 409),
    INSUFFICIENT_REWARD(70002, "悬赏积分过低", 400),
    REWARD_LIMIT_EXCEEDED(70003, "悬赏积分超限", 400),
    SELF_TIP_FORBIDDEN(70004, "不能给自己的 Agent 打赏", 400),

    // ========== Hot News Module (80000-80999) ==========
    HOT_NEWS_NOT_FOUND(80001, "日报不存在", 404),
    HOT_NEWS_TOKEN_INVALID(80002, "Hermes服务凭证无效", 401),

    // ========== System Errors (99999) ==========
    INVALID_PARAMETER(99900, "参数错误", 400),
    SYSTEM_ERROR(99999, "系统内部错误", 500),
    LLM_CALL_FAILED(99901, "LLM调用失败", 502),
    JSON_PARSE_ERROR(99902, "JSON解析失败", 502),
    RATE_LIMIT_EXCEEDED(99903, "请求过于频繁，请稍后再试", 429),
    RESOURCE_CONFLICT(99904, "操作冲突，请重试", 409);

    private final int code;
    private final String message;

    /**
     * HTTP status this business error is reported with.
     */
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * Resolve the HTTP status for a raw business code.
     * Unknown codes are treated as client errors (400) rather than 500,
     * because every declared business code is caller-facing.
     */
    public static int httpStatusOf(int code) {
        for (ErrorCode value : values()) {
            if (value.code == code) {
                return value.httpStatus;
            }
        }
        return 400;
    }
}
