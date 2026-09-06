package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent Create Request DTO
 *
 * Accepts snake_case JSON fields from frontend to match API documentation.
 */
@Data
public class AgentCreateRequest {

    @NotBlank(message = "Agent名称不能为空")
    @Size(min = 2, max = 50, message = "Agent名称长度为2-50字符")
    private String name;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    /**
     * Which credentials this agent runs on: "BYOK" (default) or "PLATFORM".
     *
     * Optional and case-insensitive; absent means BYOK, which is what every request sent
     * before this field existed meant. An unrecognised value is a parameter error rather
     * than a silent fall back to BYOK - a client that meant PLATFORM and typoed it must
     * not end up with an agent billed to somebody else's key.
     *
     * Fixed at creation. The update endpoint has no counterpart: see AgentUpdateRequest.
     */
    @Size(max = 16, message = "provider_mode 取值为 BYOK 或 PLATFORM")
    @JsonProperty("provider_mode")
    private String providerMode;

    /**
     * Optional id of the built-in persona template this agent was created from, for
     * display and analytics. The template's prompt still has to be sent in system_prompt:
     * templates are a starting point the owner may edit, so the server never substitutes
     * the stored text for what was actually submitted.
     */
    @Size(max = 64, message = "template_id 最大64字符")
    @JsonProperty("template_id")
    private String templateId;

    // The three credential fields below are NOT annotated @NotBlank any more, and that is
    // the whole shape of this feature at the request layer. Whether they are required
    // depends on provider_mode - required for BYOK, forbidden-in-practice for PLATFORM -
    // and a bean-validation annotation cannot express a rule about another field. The
    // check moved to AgentServiceImpl, which rejects a BYOK request missing any of them
    // with the same INVALID_PARAMETER the annotations produced. The format constraints
    // stay here: @Pattern and @Size both skip a null value, so they still guard exactly
    // what they used to guard whenever a value IS present.

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

    @NotBlank(message = "系统提示词不能为空")
    @Size(min = 10, max = 2000, message = "系统提示词长度为10-2000字符")
    @JsonProperty("system_prompt")
    private String systemPrompt;

    @Min(value = 1000, message = "Token上限最低1000")
    @Max(value = 100000000, message = "Token上限最高100000000")
    @JsonProperty("token_threshold")
    private Long tokenThreshold = 500000L;

    @JsonProperty("is_unlimited")
    private Boolean isUnlimited = false;

    /**
     * Active hours, [start, end), 0-23 - the same three optional fields as
     * AgentUpdateRequest, with the same bounds and the same wrap-around semantics
     * (22 -> 6 is a night-owl routine, {@code start == end} is "active all day").
     *
     * They are accepted at creation so the wizard's rhythm step lands in the same
     * transaction as the agent itself. Before that, the wizard had to follow the create
     * with an update, and a dropped connection between the two left the owner with an
     * agent quietly running on random default hours they never chose.
     *
     * Omitting them keeps the seeded random rhythm, which is what every request written
     * before this field existed meant. On a deployment without the wake-queue columns
     * they are ignored rather than refused: an unavailable rhythm must not be able to
     * fail the creation itself.
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
     * Wake-ups per day, rhythm and interactions combined. Bounded on both ends for the
     * same reason as on the update request: 0 would make the agent permanently silent
     * without saying so, and anything above hourly is not a routine.
     */
    @Min(value = 1, message = "每日唤醒预算为1-24")
    @Max(value = 24, message = "每日唤醒预算为1-24")
    @JsonProperty("daily_wake_budget")
    private Integer dailyWakeBudget;
}
