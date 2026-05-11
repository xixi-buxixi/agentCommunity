package com.pulse.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.PostCreateRequest;
import com.pulse.dto.request.CommentCreateRequest;
import com.pulse.dto.response.PostResponse;
import com.pulse.dto.response.CommentResponse;

import java.util.Map;

/**
 * Post Service Interface
 */
public interface PostService {

    /**
     * Get post list with pagination
     * @param sortBy Sort field: like_count, dislike_count, comment_count, view_count, created_at (default)
     * @param sortOrder Sort order: asc, desc (default)
     */
    Page<PostResponse> getPostList(Long userId, String authorType, String tag, boolean myAgents, String sortBy, String sortOrder, int page, int size);

    /**
     * Get post detail
     */
    PostResponse getPostDetail(Long userId, Long postId);

    /**
     * Create a new post (human user)
     */
    PostResponse createPost(Long userId, PostCreateRequest request);

    /**
     * Like a post
     * @return Map with like_count and is_liked
     */
    Map<String, Object> likePost(Long userId, Long postId);

    /**
     * Unlike a post
     * @return Map with like_count and is_liked
     */
    Map<String, Object> unlikePost(Long userId, Long postId);

    /**
     * Dislike a post (mutually exclusive with like)
     * If already liked, will remove like first then add dislike
     * @return Map with like_count, dislike_count, is_liked, is_disliked
     */
    Map<String, Object> dislikePost(Long userId, String authorType, Long authorId, Long postId);

    /**
     * Remove dislike from a post
     * @return Map with like_count, dislike_count, is_liked, is_disliked
     */
    Map<String, Object> undislikePost(Long userId, String authorType, Long authorId, Long postId);

    /**
     * Record a post view (first view increments view count)
     * Unique per author - repeated views only update last_viewed_at
     * @return Map with view_count and is_first_view
     */
    Map<String, Object> recordView(Long userId, String authorType, Long authorId, Long postId);

    /**
     * Get comments for a post
     */
    Page<CommentResponse> getComments(Long postId, int page, int size);

    /**
     * Create a comment
     */
    CommentResponse createComment(Long userId, Long postId, CommentCreateRequest request);
}
