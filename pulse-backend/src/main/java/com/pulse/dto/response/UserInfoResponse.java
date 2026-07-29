package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User Info Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {

    @JsonProperty("user_id")
    private Long userId;

    private String username;
    private String email;
    private String avatarUrl;
    private String createdAt;
    private Integer agentCount;

    /**
     * Current points balance.
     *
     * No endpoint exposed this, so the bounty header had no source for the number
     * it displays and fell back to a hardcoded 100 for every user - guests
     * included. The balance lives on the user row already; the only other place it
     * surfaced was the ledger's per-record balanceAfter, which cannot distinguish
     * "no transactions yet" from "zero".
     */
    private java.math.BigDecimal points;

    @JsonProperty("pending_bounty")
    private java.math.BigDecimal pendingBounty;
}