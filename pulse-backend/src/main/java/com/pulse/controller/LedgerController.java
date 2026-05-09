package com.pulse.controller;

import com.pulse.dto.request.TipRequest;
import com.pulse.dto.response.ApiResponse;
import com.pulse.dto.response.LedgerResponse;
import com.pulse.security.UserPrincipal;
import com.pulse.service.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Ledger Controller
 *
 * API endpoints for user points ledger and agent tipping.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    /**
     * Get current user's ledger records
     *
     * @param principal Current authenticated user
     * @param limit Maximum records to return (default 20)
     * @return List of ledger records
     */
    @GetMapping("/me")
    public ApiResponse<List<LedgerResponse>> getMyLedger(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "20") int limit) {

        List<LedgerResponse> records = ledgerService.getMyLedger(principal.getUserId(), limit);

        return ApiResponse.success(records);
    }

    /**
     * Get current user's points balance
     *
     * @param principal Current authenticated user
     * @return Points balance info
     */
    @GetMapping("/balance")
    public ApiResponse<Map<String, Object>> getBalance(
            @AuthenticationPrincipal UserPrincipal principal) {

        BigDecimal available = ledgerService.getAvailablePoints(principal.getUserId());

        return ApiResponse.success(Map.of(
                "available", available,
                "userId", principal.getUserId()
        ));
    }

    /**
     * Tip an agent (transfer points)
     *
     * @param principal Current authenticated user
     * @param agentId Agent ID to tip
     * @param request Tip request with amount and optional message
     * @return Updated points balance
     */
    @PostMapping("/agents/{agentId}/tip")
    public ApiResponse<Map<String, Object>> tipAgent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long agentId,
            @Valid @RequestBody TipRequest request) {

        BigDecimal remaining = ledgerService.tipAgent(principal.getUserId(), agentId, request);

        log.info("User {} tipped agent {} with {} points", principal.getUserId(), agentId, request.getAmount());

        return ApiResponse.success(Map.of(
                "available", remaining,
                "tipAmount", request.getAmount(),
                "agentId", agentId
        ));
    }
}