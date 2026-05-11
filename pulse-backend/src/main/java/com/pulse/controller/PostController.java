package com.pulse.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.CommentCreateRequest;
import com.pulse.dto.request.DislikeRequest;
import com.pulse.dto.request.PostCreateRequest;
import com.pulse.dto.request.ViewRequest;
import com.pulse.dto.response.ApiResponse;
import com.pulse.dto.response.CommentResponse;
import com.pulse.dto.response.PostResponse;
import com.pulse.entity.Agent;
import com.pulse.enums.AuthorType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.security.UserPrincipal;
import com.pulse.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Post Controller
 *
 * Handles community square operations: posts, comments, likes, dislikes, views.
 */
@Tag(name = "Post", description = "社区广场接口")
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final AgentMapper agentMapper;

    /**
     * Get post list with pagination
     */
    @Operation(summary = "获取动态列表")
    @GetMapping
    public ApiResponse<Page<PostResponse>> getPostList(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "author_type", required = false) String authorType,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "my_agents", required = false, defaultValue = "false") boolean myAgents,
            @RequestParam(value = "sort_by", required = false, defaultValue = "created_at") String sortBy,
            @RequestParam(value = "sort_order", required = false, defaultValue = "desc") String sortOrder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long userId = principal != null ? principal.getUserId() : null;
        Page<PostResponse> result = postService.getPostList(userId, authorType, tag, myAgents, sortBy, sortOrder, page, size);
        return ApiResponse.success(result);
    }

    /**
     * Get post detail
     */
    @Operation(summary = "获取动态详情")
    @GetMapping("/{postId}")
    public ApiResponse<PostResponse> getPostDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long postId) {

        Long userId = principal != null ? principal.getUserId() : null;
        PostResponse response = postService.getPostDetail(userId, postId);
        return ApiResponse.success(response);
    }

    /**
     * Create a new post (human user)
     */
    @Operation(summary = "发布动态")
    @PostMapping
    public ApiResponse<PostResponse> createPost(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody PostCreateRequest request) {

        PostResponse response = postService.createPost(principal.getUserId(), request);
        return ApiResponse.success(response);
    }

    /**
     * Like a post
     */
    @Operation(summary = "点赞动态")
    @PostMapping("/{postId}/like")
    public ApiResponse<Map<String, Object>> likePost(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long postId) {

        Map<String, Object> result = postService.likePost(principal.getUserId(), postId);
        return ApiResponse.success(result);
    }

    /**
     * Unlike a post
     */
    @Operation(summary = "取消点赞")
    @DeleteMapping("/{postId}/like")
    public ApiResponse<Map<String, Object>> unlikePost(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long postId) {

        Map<String, Object> result = postService.unlikePost(principal.getUserId(), postId);
        return ApiResponse.success(result);
    }

    /**
     * Get comments for a post
     */
    @Operation(summary = "获取评论列表")
    @GetMapping("/{postId}/comments")
    public ApiResponse<Page<CommentResponse>> getComments(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<CommentResponse> result = postService.getComments(postId, page, size);
        return ApiResponse.success(result);
    }

    /**
     * Create a comment
     */
    @Operation(summary = "发表评论")
    @PostMapping("/{postId}/comments")
    public ApiResponse<CommentResponse> createComment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long postId,
            @Valid @RequestBody CommentCreateRequest request) {

        CommentResponse response = postService.createComment(principal.getUserId(), postId, request);
        return ApiResponse.success(response);
    }

    /**
     * Dislike a post
     * Human users can dislike directly; Agents can dislike on behalf of their owner.
     */
    @Operation(summary = "踩动态")
    @PostMapping("/{postId}/dislike")
    public ApiResponse<Map<String, Object>> dislikePost(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long postId,
            @RequestBody(required = false) DislikeRequest request) {

        // Default to human user action
        String authorType = AuthorType.HUMAN.getCode();
        Long authorId = principal.getUserId();

        // If request specifies Agent action, validate ownership
        if (request != null && AuthorType.AGENT.getCode().equals(request.getAuthorType())) {
            Agent agent = agentMapper.selectById(request.getAuthorId());
            if (agent == null || !agent.getOwnerId().equals(principal.getUserId())) {
                throw new BusinessException(ErrorCode.AGENT_NOT_OWNER);
            }
            authorType = request.getAuthorType();
            authorId = request.getAuthorId();
        }

        Map<String, Object> result = postService.dislikePost(principal.getUserId(), authorType, authorId, postId);
        return ApiResponse.success(result);
    }

    /**
     * Remove dislike from a post
     */
    @Operation(summary = "取消踩")
    @DeleteMapping("/{postId}/dislike")
    public ApiResponse<Map<String, Object>> undislikePost(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long postId,
            @RequestBody(required = false) DislikeRequest request) {

        // Default to human user action
        String authorType = AuthorType.HUMAN.getCode();
        Long authorId = principal.getUserId();

        // If request specifies Agent action, validate ownership
        if (request != null && AuthorType.AGENT.getCode().equals(request.getAuthorType())) {
            Agent agent = agentMapper.selectById(request.getAuthorId());
            if (agent == null || !agent.getOwnerId().equals(principal.getUserId())) {
                throw new BusinessException(ErrorCode.AGENT_NOT_OWNER);
            }
            authorType = request.getAuthorType();
            authorId = request.getAuthorId();
        }

        Map<String, Object> result = postService.undislikePost(principal.getUserId(), authorType, authorId, postId);
        return ApiResponse.success(result);
    }

    /**
     * Record a post view
     * First view increments count; subsequent views only update timestamp.
     */
    @Operation(summary = "记录浏览")
    @PostMapping("/{postId}/view")
    public ApiResponse<Map<String, Object>> recordView(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long postId,
            @RequestBody(required = false) ViewRequest request) {

        // Default to human user action
        String authorType = AuthorType.HUMAN.getCode();
        Long authorId = principal.getUserId();

        // If request specifies Agent action, validate ownership
        if (request != null && AuthorType.AGENT.getCode().equals(request.getAuthorType())) {
            Agent agent = agentMapper.selectById(request.getAuthorId());
            if (agent == null || !agent.getOwnerId().equals(principal.getUserId())) {
                throw new BusinessException(ErrorCode.AGENT_NOT_OWNER);
            }
            authorType = request.getAuthorType();
            authorId = request.getAuthorId();
        }

        Map<String, Object> result = postService.recordView(principal.getUserId(), authorType, authorId, postId);
        return ApiResponse.success(result);
    }
}
