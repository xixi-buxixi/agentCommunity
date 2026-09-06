package com.pulse.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.response.ApiResponse;
import com.pulse.dto.response.NotificationResponse;
import com.pulse.dto.response.PageResponse;
import com.pulse.dto.response.UnreadCountResponse;
import com.pulse.security.UserPrincipal;
import com.pulse.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification Controller
 *
 * Every endpoint here reads or writes the CALLER's own notifications only. The
 * recipient id never comes from the request - it is taken from the authenticated
 * principal and pushed down into the SQL predicate, so there is no request shape that
 * can address somebody else's inbox.
 *
 * No SecurityConfig entry is needed: /api/v1/notifications/** falls through to
 * {@code anyRequest().authenticated()}, which is what these endpoints require.
 */
@Tag(name = "Notification", description = "Notification centre APIs")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * List My Notifications (paged, newest first)
     */
    @Operation(summary = "List my notifications", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> getNotifications(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<NotificationResponse> notifications =
                notificationService.getNotifications(principal.getUserId(), unreadOnly, page, size);
        return ApiResponse.success(PageResponse.from(notifications));
    }

    /**
     * Unread Count (badge)
     */
    @Operation(summary = "Unread notification count", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> getUnreadCount(
            @AuthenticationPrincipal UserPrincipal principal) {
        long count = notificationService.getUnreadCount(principal.getUserId());
        return ApiResponse.success(UnreadCountResponse.builder().count(count).build());
    }

    /**
     * Mark One Notification Read
     */
    @Operation(summary = "Mark one notification read", security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id) {
        notificationService.markRead(principal.getUserId(), id);
        return ApiResponse.success("已标记为已读", null);
    }

    /**
     * Mark Every Notification Read
     */
    @Operation(summary = "Mark all notifications read", security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/read-all")
    public ApiResponse<UnreadCountResponse> markAllRead(
            @AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markAllRead(principal.getUserId());
        // The badge is what the caller is about to redraw, and it is now zero by
        // definition - returning the flipped count instead would make the frontend
        // subtract rather than assign.
        return ApiResponse.success("已全部标记为已读",
                UnreadCountResponse.builder().count(0).build());
    }
}
