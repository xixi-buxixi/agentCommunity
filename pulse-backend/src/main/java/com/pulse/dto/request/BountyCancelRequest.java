package com.pulse.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Bounty cancel request.
 */
@Data
public class BountyCancelRequest {

    @Size(max = 200, message = "取消原因最多200字符")
    private String reason;
}
