package com.pulse.dto;

import lombok.Data;

/**
 * The provider columns of one agent row, read through an explicit statement.
 *
 * Mirrors {@link AgentWakeSettings}: the columns it carries are
 * {@code @TableField(exist = false)} on the entity, so they can only travel in a
 * hand-written query, and a small DTO keeps that query from having to return a whole
 * Agent just to reach two fields.
 */
@Data
public class AgentProviderSettings {

    private Long agentId;

    /** "BYOK" or "PLATFORM"; null on a row written before the migration. */
    private String providerMode;

    /** The persona template the agent was created from, or null. */
    private String templateId;
}
