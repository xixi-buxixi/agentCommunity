package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.CommentCreateRequest;
import com.pulse.dto.request.PostCreateRequest;
import com.pulse.dto.response.CommentResponse;
import com.pulse.dto.response.PostResponse;
import com.pulse.entity.*;
import com.pulse.enums.AuthorType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.*;
import com.pulse.service.PostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Post Service Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final LikeMapper likeMapper;
    private final DislikeMapper dislikeMapper;
    private final PostViewMapper postViewMapper;
    private final UserMapper userMapper;
    private final AgentMapper agentMapper;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int MAX_REPLY_DEPTH = 3;

    @Override
    public Page<PostResponse> getPostList(Long userId, String authorType, boolean myAgents, String sortBy, String sortOrder, int page, int size) {
        Page<Post> pageParam = new Page<>(page, Math.min(size, 50));

        LambdaQueryWrapper<Post> queryWrapper = new LambdaQueryWrapper<>();

        // Filter by author type
        if (authorType != null && !authorType.isEmpty()) {
            queryWrapper.eq(Post::getAuthorType, authorType.toUpperCase());
        }

        // Filter by user's agents
        if (myAgents && userId != null) {
            // Get user's agent IDs
            LambdaQueryWrapper<Agent> agentQuery = new LambdaQueryWrapper<>();
            agentQuery.eq(Agent::getOwnerId, userId);
            agentQuery.select(Agent::getId);
            List<Long> agentIds = agentMapper.selectList(agentQuery).stream()
                    .map(Agent::getId)
                    .collect(Collectors.toList());

            if (!agentIds.isEmpty()) {
                queryWrapper.and(w -> w
                        .eq(Post::getAuthorType, AuthorType.HUMAN.getCode())
                        .eq(Post::getAuthorId, userId)
                        .or()
                        .eq(Post::getAuthorType, AuthorType.AGENT.getCode())
                        .in(Post::getAuthorId, agentIds)
                );
            } else {
                queryWrapper.eq(Post::getAuthorType, AuthorType.HUMAN.getCode())
                        .eq(Post::getAuthorId, userId);
            }
        }

        // @TableLogic on Post entity automatically filters deleted=1 records

        // Apply sorting
        applyPostSorting(queryWrapper, sortBy, sortOrder);

        Page<Post> postPage = postMapper.selectPage(pageParam, queryWrapper);
        List<Post> posts = postPage.getRecords();

        // ========== N+1 Query Optimization: Batch preload author info ==========

        // Collect all Human author IDs
        Set<Long> humanAuthorIds = posts.stream()
                .filter(p -> AuthorType.HUMAN.getCode().equalsIgnoreCase(p.getAuthorType()))
                .map(Post::getAuthorId)
                .collect(Collectors.toSet());

        // Collect all Agent author IDs
        Set<Long> agentAuthorIds = posts.stream()
                .filter(p -> AuthorType.AGENT.getCode().equalsIgnoreCase(p.getAuthorType()))
                .map(Post::getAuthorId)
                .collect(Collectors.toSet());

        // Batch load users (including human authors and agent owners)
        Map<Long, User> userCache = new HashMap<>();
        if (!humanAuthorIds.isEmpty()) {
            List<User> users = userMapper.selectBatchIds(humanAuthorIds);
            users.forEach(u -> userCache.put(u.getId(), u));
        }

        // Batch load agents
        Map<Long, Agent> agentCache = new HashMap<>();
        Set<Long> ownerIds = new HashSet<>();
        if (!agentAuthorIds.isEmpty()) {
            List<Agent> agents = agentMapper.selectBatchIds(agentAuthorIds);
            agents.forEach(a -> {
                agentCache.put(a.getId(), a);
                if (a.getOwnerId() != null) {
                    ownerIds.add(a.getOwnerId());
                }
            });

            // Batch load agent owners
            if (!ownerIds.isEmpty()) {
                List<User> owners = userMapper.selectBatchIds(ownerIds);
                owners.forEach(o -> userCache.put(o.getId(), o));
            }
        }

        // Batch check liked/disliked status for logged-in user
        Set<Long> likedPostIds = new HashSet<>();
        Set<Long> dislikedPostIds = new HashSet<>();
        if (userId != null && !posts.isEmpty()) {
            List<Long> postIds = posts.stream().map(Post::getId).collect(Collectors.toList());

            // Batch check likes
            LambdaQueryWrapper<Like> likeQuery = new LambdaQueryWrapper<>();
            likeQuery.eq(Like::getAuthorType, AuthorType.HUMAN.getCode())
                    .eq(Like::getAuthorId, userId)
                    .in(Like::getPostId, postIds);
            likeMapper.selectList(likeQuery).forEach(l -> likedPostIds.add(l.getPostId()));

            // Batch check dislikes
            LambdaQueryWrapper<Dislike> dislikeQuery = new LambdaQueryWrapper<>();
            dislikeQuery.eq(Dislike::getUserId, userId).in(Dislike::getPostId, postIds);
            dislikeMapper.selectList(dislikeQuery).forEach(d -> dislikedPostIds.add(d.getPostId()));
        }

        // ========== Build responses using cached data ==========

        List<PostResponse> responses = posts.stream()
                .map(post -> buildPostResponseCached(post, userId, userCache, agentCache, likedPostIds, dislikedPostIds))
                .collect(Collectors.toList());

        Page<PostResponse> responsePage = new Page<>(postPage.getCurrent(), postPage.getSize(), postPage.getTotal());
        responsePage.setRecords(responses);

        return responsePage;
    }

    /**
     * Apply sorting to post query
     * @param queryWrapper Query wrapper
     * @param sortBy Sort field: like_count, dislike_count, comment_count, view_count, created_at
     * @param sortOrder Sort order: asc, desc
     */
    private void applyPostSorting(LambdaQueryWrapper<Post> queryWrapper, String sortBy, String sortOrder) {
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);

        switch (sortBy.toLowerCase()) {
            case "like_count":
                if (isAsc) {
                    queryWrapper.orderByAsc(Post::getLikeCount);
                } else {
                    queryWrapper.orderByDesc(Post::getLikeCount);
                }
                break;
            case "dislike_count":
                if (isAsc) {
                    queryWrapper.orderByAsc(Post::getDislikeCount);
                } else {
                    queryWrapper.orderByDesc(Post::getDislikeCount);
                }
                break;
            case "comment_count":
                if (isAsc) {
                    queryWrapper.orderByAsc(Post::getCommentCount);
                } else {
                    queryWrapper.orderByDesc(Post::getCommentCount);
                }
                break;
            case "view_count":
                if (isAsc) {
                    queryWrapper.orderByAsc(Post::getViewCount);
                } else {
                    queryWrapper.orderByDesc(Post::getViewCount);
                }
                break;
            case "created_at":
            default:
                if (isAsc) {
                    queryWrapper.orderByAsc(Post::getCreatedAt);
                } else {
                    queryWrapper.orderByDesc(Post::getCreatedAt);
                }
                break;
        }
    }

    @Override
    public PostResponse getPostDetail(Long userId, Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        return buildPostResponse(post, userId);
    }

    @Override
    @Transactional
    public PostResponse createPost(Long userId, PostCreateRequest request) {
        // Validate content
        if ((request.getContent() == null || request.getContent().isBlank()) &&
            (request.getImageUrls() == null || request.getImageUrls().isEmpty())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "动态内容不能为空");
        }

        // Validate image count
        if (request.getImageUrls() != null && request.getImageUrls().size() > 4) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "最多上传4张图片");
        }

        Post post = new Post();
        post.setAuthorId(userId);
        post.setAuthorType(AuthorType.HUMAN.getCode());
        post.setContent(request.getContent());
        post.setImageUrls(request.getImageUrls());
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setIsSystemMessage(false);

        postMapper.insert(post);

        log.info("Post created: postId={}, authorId={}", post.getId(), userId);

        return buildPostResponse(post, userId);
    }

    @Override
    @Transactional
    public Map<String, Object> likePost(Long userId, Long postId) {
        // Check if post exists
        Post post = postMapper.selectById(postId);
        if (post == null || post.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        // Check if already liked
        LambdaQueryWrapper<Like> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Like::getAuthorType, AuthorType.HUMAN.getCode());
        queryWrapper.eq(Like::getAuthorId, userId);
        queryWrapper.eq(Like::getPostId, postId);
        if (likeMapper.selectCount(queryWrapper) > 0) {
            throw new BusinessException(ErrorCode.POST_ALREADY_LIKED);
        }

        // Like/dislike are mutually exclusive for the same author
        Dislike existingDislike = dislikeMapper.findByAuthorAndPost(
                AuthorType.HUMAN.getCode(), userId, postId);
        if (existingDislike != null) {
            dislikeMapper.deleteById(existingDislike.getId());
            postMapper.decrementDislikeCount(postId);
        }

        // Create like
        Like like = new Like();
        like.setUserId(userId);
        like.setAuthorType(AuthorType.HUMAN.getCode());
        like.setAuthorId(userId);
        like.setPostId(postId);
        likeMapper.insert(like);

        // Increment like count
        postMapper.incrementLikeCount(postId);

        log.info("Post liked: postId={}, userId={}", postId, userId);

        // Return updated like info
        Post updatedPost = postMapper.selectById(postId);
        return Map.of(
            "post_id", postId,
            "like_count", updatedPost.getLikeCount(),
            "dislike_count", updatedPost.getDislikeCount(),
            "is_liked", true,
            "is_disliked", false
        );
    }

    @Override
    @Transactional
    public Map<String, Object> unlikePost(Long userId, Long postId) {
        // Check if like exists
        LambdaQueryWrapper<Like> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Like::getAuthorType, AuthorType.HUMAN.getCode());
        queryWrapper.eq(Like::getAuthorId, userId);
        queryWrapper.eq(Like::getPostId, postId);
        Like like = likeMapper.selectOne(queryWrapper);

        if (like == null) {
            throw new BusinessException(ErrorCode.POST_NOT_LIKED);
        }

        // Delete like
        likeMapper.deleteById(like.getId());

        // Decrement like count
        postMapper.decrementLikeCount(postId);

        log.info("Post unliked: postId={}, userId={}", postId, userId);

        // Return updated like info
        Post updatedPost = postMapper.selectById(postId);
        return Map.of(
            "post_id", postId,
            "like_count", updatedPost.getLikeCount(),
            "dislike_count", updatedPost.getDislikeCount(),
            "is_liked", false,
            "is_disliked", false
        );
    }

    @Override
    @Transactional
    public Map<String, Object> dislikePost(Long userId, String authorType, Long authorId, Long postId) {
        // 1. Check if post exists
        Post post = postMapper.selectById(postId);
        if (post == null || post.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        // 2. Check if already disliked
        if (dislikeMapper.existsByAuthorAndPost(authorType, authorId, postId)) {
            throw new BusinessException(ErrorCode.POST_ALREADY_DISLIKED);
        }

        // 3. Check if already liked (mutual exclusion: remove like first if exists)
        Like existingLike = likeMapper.findByAuthorAndPost(authorType, authorId, postId);
        if (existingLike != null) {
            likeMapper.deleteById(existingLike.getId());
            postMapper.decrementLikeCount(postId);
            log.info("Removed existing like before dislike: postId={}, authorId={}", postId, authorId);
        }

        // 4. Create dislike record
        Dislike dislike = new Dislike();
        dislike.setUserId(userId);
        dislike.setAuthorType(authorType);
        dislike.setAuthorId(authorId);
        dislike.setPostId(postId);
        dislikeMapper.insert(dislike);

        // 5. Increment dislike count
        postMapper.incrementDislikeCount(postId);

        log.info("Post disliked: postId={}, authorType={}, authorId={}", postId, authorType, authorId);

        // 6. Return updated state
        Post updatedPost = postMapper.selectById(postId);
        return Map.of(
            "post_id", postId,
            "like_count", updatedPost.getLikeCount(),
            "dislike_count", updatedPost.getDislikeCount(),
            "is_liked", false,
            "is_disliked", true
        );
    }

    @Override
    @Transactional
    public Map<String, Object> undislikePost(Long userId, String authorType, Long authorId, Long postId) {
        // 1. Check if dislike exists
        Dislike dislike = dislikeMapper.findByAuthorAndPost(authorType, authorId, postId);
        if (dislike == null) {
            throw new BusinessException(ErrorCode.POST_NOT_DISLIKED);
        }

        // 2. Delete dislike record
        dislikeMapper.deleteById(dislike.getId());

        // 3. Decrement dislike count
        postMapper.decrementDislikeCount(postId);

        log.info("Post undisliked: postId={}, authorType={}, authorId={}", postId, authorType, authorId);

        // 4. Return updated state
        Post updatedPost = postMapper.selectById(postId);
        return Map.of(
            "post_id", postId,
            "like_count", updatedPost.getLikeCount(),
            "dislike_count", updatedPost.getDislikeCount(),
            "is_liked", false,
            "is_disliked", false
        );
    }

    @Override
    @Transactional
    public Map<String, Object> recordView(Long userId, String authorType, Long authorId, Long postId) {
        // 1. Check if post exists
        Post post = postMapper.selectById(postId);
        if (post == null || post.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        // 2. Check if already viewed
        PostView existingView = postViewMapper.findByAuthorAndPost(authorType, authorId, postId);
        boolean isFirstView = existingView == null;

        if (isFirstView) {
            // First view: create record + increment view count
            PostView view = new PostView();
            view.setUserId(userId);
            view.setAuthorType(authorType);
            view.setAuthorId(authorId);
            view.setPostId(postId);
            postViewMapper.insert(view);
            postMapper.incrementViewCount(postId);
            log.info("Post first view recorded: postId={}, authorType={}, authorId={}", postId, authorType, authorId);
        } else {
            // Repeat view: only update last viewed time
            postViewMapper.updateLastViewedAt(authorType, authorId, postId);
            log.debug("Post repeat view updated: postId={}, authorType={}, authorId={}", postId, authorType, authorId);
        }

        // 3. Return view info
        Post updatedPost = postMapper.selectById(postId);
        return Map.of(
            "post_id", postId,
            "view_count", updatedPost.getViewCount(),
            "is_first_view", isFirstView
        );
    }

    @Override
    public Page<CommentResponse> getComments(Long postId, int page, int size) {
        // Check if post exists
        Post post = postMapper.selectById(postId);
        if (post == null || post.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        Page<Comment> pageParam = new Page<>(page, Math.min(size, 50));

        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Comment::getPostId, postId);
        queryWrapper.isNull(Comment::getParentCommentId);
        queryWrapper.orderByAsc(Comment::getCreatedAt);

        Page<Comment> commentPage = commentMapper.selectPage(pageParam, queryWrapper);

        List<Comment> rootComments = commentPage.getRecords();
        List<Long> rootIds = rootComments.stream()
                .map(Comment::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<Comment> replies = rootIds.isEmpty()
                ? Collections.emptyList()
                : commentMapper.findRepliesByRootIds(rootIds);

        Map<Long, CommentResponse> responseById = new LinkedHashMap<>();
        rootComments.forEach(comment -> responseById.put(comment.getId(), buildCommentResponse(comment)));
        replies.forEach(comment -> responseById.put(comment.getId(), buildCommentResponse(comment)));

        replies.forEach(reply -> {
            CommentResponse replyResponse = responseById.get(reply.getId());
            CommentResponse parentResponse = responseById.get(reply.getParentCommentId());
            if (replyResponse != null && parentResponse != null) {
                parentResponse.getReplies().add(replyResponse);
            }
        });

        Page<CommentResponse> responsePage = new Page<>(commentPage.getCurrent(), commentPage.getSize(), commentPage.getTotal());
        List<CommentResponse> responses = rootComments.stream()
                .map(comment -> responseById.get(comment.getId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        responsePage.setRecords(responses);

        return responsePage;
    }

    @Override
    @Transactional
    public CommentResponse createComment(Long userId, Long postId, CommentCreateRequest request) {
        // Check if post exists
        Post post = postMapper.selectById(postId);
        if (post == null || post.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        // Check if post is system message (禁止评论系统消息)
        if (post.getIsSystemMessage() != null && post.getIsSystemMessage()) {
            throw new BusinessException(ErrorCode.SYSTEM_POST_NO_COMMENT);
        }

        User author = userMapper.selectById(userId);
        if (author == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        Long parentCommentId = request.getParentCommentId();
        Comment parentComment = null;
        Long rootCommentId = null;
        int replyDepth = 0;

        if (parentCommentId == null) {
            if (isPostOwnedByUser(post, userId)) {
                throw new BusinessException(ErrorCode.SELF_POST_DIRECT_COMMENT_FORBIDDEN);
            }
        } else {
            parentComment = commentMapper.selectById(parentCommentId);
            if (parentComment == null || Objects.equals(parentComment.getDeleted(), 1) || !Objects.equals(parentComment.getPostId(), postId)) {
                throw new BusinessException(ErrorCode.COMMENT_PARENT_NOT_FOUND);
            }
            if (AuthorType.HUMAN.getCode().equalsIgnoreCase(parentComment.getAuthorType())
                    && Objects.equals(parentComment.getAuthorId(), userId)) {
                throw new BusinessException(ErrorCode.SELF_COMMENT_REPLY_FORBIDDEN);
            }

            int parentDepth = parentComment.getReplyDepth() == null ? 0 : parentComment.getReplyDepth();
            replyDepth = parentDepth + 1;
            if (replyDepth > MAX_REPLY_DEPTH) {
                throw new BusinessException(ErrorCode.COMMENT_REPLY_DEPTH_EXCEEDED);
            }

            rootCommentId = parentComment.getRootCommentId() != null
                    ? parentComment.getRootCommentId()
                    : parentComment.getId();
        }

        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setAuthorId(userId);
        comment.setAuthorType(AuthorType.HUMAN.getCode());
        comment.setContent(request.getContent());
        comment.setParentCommentId(parentCommentId);
        comment.setRootCommentId(rootCommentId);
        comment.setReplyDepth(replyDepth);

        commentMapper.insert(comment);

        // Increment comment count
        postMapper.incrementCommentCount(postId);

        log.info("Comment created: commentId={}, postId={}, userId={}", comment.getId(), postId, userId);

        return buildCommentResponse(comment);
    }

    // ========== Helper Methods ==========

    private boolean isPostOwnedByUser(Post post, Long userId) {
        if (AuthorType.HUMAN.getCode().equalsIgnoreCase(post.getAuthorType())) {
            return Objects.equals(post.getAuthorId(), userId);
        }
        if (AuthorType.AGENT.getCode().equalsIgnoreCase(post.getAuthorType())) {
            Agent agent = agentMapper.selectById(post.getAuthorId());
            return agent != null && Objects.equals(agent.getOwnerId(), userId);
        }
        return false;
    }

    private PostResponse buildPostResponse(Post post, Long userId) {
        String authorName = null;
        String authorAvatar = null;
        String agentOwnerName = null;

        if (AuthorType.HUMAN.getCode().equalsIgnoreCase(post.getAuthorType())) {
            // Human author
            User user = userMapper.selectById(post.getAuthorId());
            if (user != null) {
                authorName = user.getUsername();
                authorAvatar = user.getAvatarUrl();
            }
        } else if (AuthorType.AGENT.getCode().equalsIgnoreCase(post.getAuthorType())) {
            // Agent author
            Agent agent = agentMapper.selectById(post.getAuthorId());
            if (agent != null) {
                authorName = agent.getName();
                authorAvatar = agent.getAvatarUrl();

                // Get owner name
                User owner = userMapper.selectById(agent.getOwnerId());
                if (owner != null) {
                    agentOwnerName = owner.getUsername();
                }
            }
        } else if (AuthorType.SYSTEM.getCode().equalsIgnoreCase(post.getAuthorType())) {
            // System message - use default values
            authorName = "SYSTEM";
        }

        // Check if user liked this post
        boolean isLiked = false;
        if (userId != null) {
            LambdaQueryWrapper<Like> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Like::getAuthorType, AuthorType.HUMAN.getCode());
            queryWrapper.eq(Like::getAuthorId, userId);
            queryWrapper.eq(Like::getPostId, post.getId());
            isLiked = likeMapper.selectCount(queryWrapper) > 0;
        }

        // Check if user disliked this post
        boolean isDisliked = false;
        if (userId != null) {
            isDisliked = dislikeMapper.existsByAuthorAndPost(AuthorType.HUMAN.getCode(), userId, post.getId());
        }

        return PostResponse.builder()
                .postId(post.getId())
                .authorId(post.getAuthorId())
                .authorType(post.getAuthorType())
                .authorName(authorName)
                .authorAvatar(authorAvatar)
                .agentOwnerName(agentOwnerName)
                .content(post.getContent())
                .imageUrls(post.getImageUrls())
                .likeCount(post.getLikeCount())
                .dislikeCount(post.getDislikeCount())
                .viewCount(post.getViewCount())
                .commentCount(post.getCommentCount())
                .isLiked(isLiked)
                .isDisliked(isDisliked)
                .isSystemMessage(post.getIsSystemMessage())
                .createdAt(post.getCreatedAt() != null ? post.getCreatedAt().format(DATE_FORMATTER) : null)
                .build();
    }

    /**
     * Build PostResponse using pre-loaded cached data (N+1 optimization).
     * Used by getPostList for batch processing.
     */
    private PostResponse buildPostResponseCached(
            Post post,
            Long userId,
            Map<Long, User> userCache,
            Map<Long, Agent> agentCache,
            Set<Long> likedPostIds,
            Set<Long> dislikedPostIds) {

        String authorName = null;
        String authorAvatar = null;
        String agentOwnerName = null;

        if (AuthorType.HUMAN.getCode().equalsIgnoreCase(post.getAuthorType())) {
            // Human author - get from cache
            User user = userCache.get(post.getAuthorId());
            if (user != null) {
                authorName = user.getUsername();
                authorAvatar = user.getAvatarUrl();
            }
        } else if (AuthorType.AGENT.getCode().equalsIgnoreCase(post.getAuthorType())) {
            // Agent author - get from cache
            Agent agent = agentCache.get(post.getAuthorId());
            if (agent != null) {
                authorName = agent.getName();
                authorAvatar = agent.getAvatarUrl();

                // Get owner name from cache
                if (agent.getOwnerId() != null) {
                    User owner = userCache.get(agent.getOwnerId());
                    if (owner != null) {
                        agentOwnerName = owner.getUsername();
                    }
                }
            }
        } else if (AuthorType.SYSTEM.getCode().equalsIgnoreCase(post.getAuthorType())) {
            // System message - use default values
            authorName = "SYSTEM";
        }

        // Check liked/disliked from pre-loaded sets
        boolean isLiked = userId != null && likedPostIds.contains(post.getId());
        boolean isDisliked = userId != null && dislikedPostIds.contains(post.getId());

        return PostResponse.builder()
                .postId(post.getId())
                .authorId(post.getAuthorId())
                .authorType(post.getAuthorType())
                .authorName(authorName)
                .authorAvatar(authorAvatar)
                .agentOwnerName(agentOwnerName)
                .content(post.getContent())
                .imageUrls(post.getImageUrls())
                .likeCount(post.getLikeCount())
                .dislikeCount(post.getDislikeCount())
                .viewCount(post.getViewCount())
                .commentCount(post.getCommentCount())
                .isLiked(isLiked)
                .isDisliked(isDisliked)
                .isSystemMessage(post.getIsSystemMessage())
                .createdAt(post.getCreatedAt() != null ? post.getCreatedAt().format(DATE_FORMATTER) : null)
                .build();
    }

    private CommentResponse buildCommentResponse(Comment comment) {
        String authorName = null;
        String authorAvatar = null;
        String agentOwnerName = null;

        if (AuthorType.HUMAN.getCode().equalsIgnoreCase(comment.getAuthorType())) {
            User user = userMapper.selectById(comment.getAuthorId());
            if (user != null) {
                authorName = user.getUsername();
                authorAvatar = user.getAvatarUrl();
            }
        } else if (AuthorType.AGENT.getCode().equalsIgnoreCase(comment.getAuthorType())) {
            Agent agent = agentMapper.selectById(comment.getAuthorId());
            if (agent != null) {
                authorName = agent.getName();
                authorAvatar = agent.getAvatarUrl();

                User owner = userMapper.selectById(agent.getOwnerId());
                if (owner != null) {
                    agentOwnerName = owner.getUsername();
                }
            }
        } else if (AuthorType.SYSTEM.getCode().equalsIgnoreCase(comment.getAuthorType())) {
            // System comment
            authorName = "SYSTEM";
        }

        return CommentResponse.builder()
                .commentId(comment.getId())
                .postId(comment.getPostId())
                .authorId(comment.getAuthorId())
                .authorType(comment.getAuthorType())
                .authorName(authorName)
                .authorAvatar(authorAvatar)
                .agentOwnerName(agentOwnerName)
                .parentCommentId(comment.getParentCommentId())
                .rootCommentId(comment.getRootCommentId())
                .replyDepth(comment.getReplyDepth() == null ? 0 : comment.getReplyDepth())
                .content(comment.getContent())
                .replies(new ArrayList<>())
                .createdAt(comment.getCreatedAt() != null ? comment.getCreatedAt().format(DATE_FORMATTER) : null)
                .build();
    }
}
