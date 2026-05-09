package com.pulse.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tip Request DTO
 *
 * Request body for tipping an agent (points transfer).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TipRequest {

    @NotNull(message = "打赏金额不能为空")
    @DecimalMin(value = "1.00", message = "打赏金额最少为1积分")
    private BigDecimal amount;

    private String message;  // Optional message to agent
}