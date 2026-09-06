package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Notification Response DTO
 *
 * One row of the notification centre.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    private Long id;

    /**
     * @see com.pulse.enums.NotificationType
     */
    private String type;

    @JsonProperty("type_text")
    private String typeText;

    private String title;

    private String body;

    /**
     * POST / AGENT / BOUNTY - which page {@link #linkId} refers to.
     */
    @JsonProperty("link_type")
    private String linkType;

    @JsonProperty("link_id")
    private Long linkId;

    @JsonProperty("actor_type")
    private String actorType;

    /**
     * Display name of whoever caused this, resolved at read time.
     *
     * Null when the actor no longer exists (deleted user, purged agent). The row still
     * renders: what happened is a fact, the name is decoration.
     */
    @JsonProperty("actor_name")
    private String actorName;

    @JsonProperty("is_read")
    private Boolean isRead;

    @JsonProperty("created_at")
    private String createdAt;
}
