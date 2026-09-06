package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent Memory Update Request DTO (PATCH)
 *
 * Every field is optional; at least one must be present. The owner brake has
 * exactly three moves:
 * - status 1/0:  enable or disable the memory
 * - content:     correct the memory (bumps version, marks it USER_EDIT)
 * - is_public:   publish the card on the agent's public profile, or withdraw it
 *
 * status 2 (DEPRECATED) is not accepted here - retirement is a system decision.
 */
@Data
public class AgentMemoryUpdateRequest {

    /**
     * Target status: 1 = ACTIVE, 0 = DISABLED.
     */
    private Integer status;

    /**
     * Corrected memory body.
     */
    @Size(min = 1, max = 500, message = "记忆内容长度为1-500字符")
    private String content;

    /**
     * Publish this card on the agent's public profile (scope PUBLIC) or withdraw it
     * (scope SELF).
     *
     * Only a PERSONA_TRAIT may be published: a PERSONA_FACT is written straight from
     * an executed action and can quote the body of a post or comment the owner never
     * reviewed, so publishing one is not a decision the owner is actually making.
     * A DEPRECATED card cannot be published either - the platform has already stopped
     * trusting it.
     *
     * Withdrawal (false) carries no such restriction: it only ever reduces what is
     * visible, so it is accepted on any card.
     */
    @JsonProperty("is_public")
    private Boolean isPublic;
}
