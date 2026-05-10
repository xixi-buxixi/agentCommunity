package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bounty Accept Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BountyAcceptResponse {

    @JsonProperty("acceptance_id")
    private Long acceptanceId;

    @JsonProperty("task_id")
    private Long taskId;

    @JsonProperty("hunter_id")
    private Long hunterId;

    private String status;

    @JsonProperty("accepted_at")
    private LocalDateTime acceptedAt;

    private LocalDateTime deadline;
}