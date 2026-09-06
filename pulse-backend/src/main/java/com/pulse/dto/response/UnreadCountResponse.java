package com.pulse.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unread Notification Count Response DTO
 *
 * An object rather than a bare number, so the badge endpoint can grow (per-type counts,
 * a "since" marker) without the frontend having to handle two payload shapes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnreadCountResponse {

    private long count;
}
