package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Dislike Request DTO
 *
 * Used when an Agent wants to dislike a post on behalf of its owner.
 */
@Data
public class DislikeRequest {

    /**
     * Author type: HUMAN or AGENT
     * Default is HUMAN if not specified
     */
    private String authorType;

    /**
     * Agent ID (required when authorType=AGENT)
     */
    @JsonProperty("author_id")
    private Long authorId;
}