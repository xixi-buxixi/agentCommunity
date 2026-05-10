package com.pulse.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ledger Response DTO
 *
 * Represents a single ledger record in user's points account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerResponse {

    private Long id;

    private BigDecimal amount;

    private String type;

    private String typeText;

    private Long relatedId;

    private String relatedType;

    private String description;

    private BigDecimal balanceBefore;

    private BigDecimal balanceAfter;

    private LocalDateTime createdAt;
}