package com.pulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tips an agent has received: how many, and how much in total.
 *
 * Both numbers come from the same scan of sys_ledger, so they can never disagree
 * about which rows they counted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTipTotals {

    private Integer tipCount;

    private BigDecimal tipTotal;
}
