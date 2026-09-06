package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent Update Request DTO
 * All fields are optional for partial updates
 *
 * Accepts snake_case JSON fields from frontend to match API documentation.
 */
@Data
public class AgentUpdateRequest {

    @Size(min = 2, max = 50, message = "Agent名称长度为2-50字符")
    private String name;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    @Pattern(regexp = "^https?://.+", message = "API Base URL必须以http://或https://开头")
    @Size(max = 255, message = "API Base URL最大255字符")
    @JsonProperty("base_url")
    private String baseUrl;

    @Size(min = 10, max = 255, message = "API Key长度为10-255字符")
    @JsonProperty("api_key")
    private String apiKey;

    @Size(max = 80, message = "模型名称最大80字符")
    @JsonProperty("model_name")
    private String modelName;

    @Size(min = 10, max = 2000, message = "系统提示词长度为10-2000字符")
    @JsonProperty("system_prompt")
    private String systemPrompt;

    @Min(value = 1000, message = "Token上限最低1000")
    @Max(value = 100000000, message = "Token上限最高100000000")
    @JsonProperty("token_threshold")
    private Long tokenThreshold;

    @JsonProperty("is_unlimited")
    private Boolean isUnlimited;

    /**
     * Active hours, [start, end), 0-23.
     *
     * May wrap midnight (22 -> 6), which is how an owner gives an agent a night-owl
     * routine, and {@code start == end} means "active all day" - never "never active",
     * because an agent that can never wake up would look broken rather than quiet.
     *
     * Sending only one of the two bounds keeps the other as it is; both are always
     * persisted together.
     */
    @Min(value = 0, message = "活跃时段起点为0-23（起点与终点相同表示全天活跃）")
    @Max(value = 23, message = "活跃时段起点为0-23（起点与终点相同表示全天活跃）")
    @JsonProperty("wake_hours_start")
    private Integer wakeHoursStart;

    @Min(value = 0, message = "活跃时段终点为0-23（不含该小时；可跨零点，如22到6）")
    @Max(value = 23, message = "活跃时段终点为0-23（不含该小时；可跨零点，如22到6）")
    @JsonProperty("wake_hours_end")
    private Integer wakeHoursEnd;

    /**
     * Wake-ups per day, rhythm and interactions combined. This is the owner's cost
     * ceiling, so it is bounded on both ends: 0 would make the agent permanently silent
     * without saying so, and anything above hourly is not a routine.
     */
    @Min(value = 1, message = "每日唤醒预算为1-24")
    @Max(value = 24, message = "每日唤醒预算为1-24")
    @JsonProperty("daily_wake_budget")
    private Integer dailyWakeBudget;
}
