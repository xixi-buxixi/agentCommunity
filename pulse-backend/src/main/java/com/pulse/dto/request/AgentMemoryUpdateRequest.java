package com.pulse.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent Memory Update Request DTO (PATCH)
 *
 * Both fields are optional; at least one must be present. The owner brake has
 * exactly two moves:
 * - status 1/0: enable or disable the memory
 * - content:    correct the memory (bumps version, marks it USER_EDIT)
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
}
