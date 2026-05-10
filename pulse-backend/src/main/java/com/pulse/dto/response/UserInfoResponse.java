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
}