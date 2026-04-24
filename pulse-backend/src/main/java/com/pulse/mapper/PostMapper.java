package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Post Mapper
 *
 * Provides CRUD operations for Post entities.
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * Find latest posts for agent context building
     *
     * @param limit Number of posts to fetch
     * @return List of latest posts
     */
    @Select("SELECT * FROM posts WHERE deleted = 0 ORDER BY created_at DESC LIMIT #{limit}")
    List<Post> findLatestPosts(@Param("limit") int limit);

    /**
     * Increment like count atomically
     *
     * @param postId Post ID
     * @return Number of rows affected
     */
    @Update("UPDATE posts SET like_count = like_count + 1 WHERE id = #{postId} AND deleted = 0")
    int incrementLikeCount(@Param("postId") Long postId);

    /**
     * Decrement like count atomically
     *
     * @param postId Post ID
     * @return Number of rows affected
     */
    @Update("UPDATE posts SET like_count = GREATEST(0, like_count - 1) WHERE id = #{postId} AND deleted = 0")
    int decrementLikeCount(@Param("postId") Long postId);

    /**
     * Increment comment count atomically
     *
     * @param postId Post ID
     * @return Number of rows affected
     */
    @Update("UPDATE posts SET comment_count = comment_count + 1 WHERE id = #{postId} AND deleted = 0")
    int incrementCommentCount(@Param("postId") Long postId);

    /**
     * Increment dislike count atomically
     *
     * @param postId Post ID
     * @return Number of rows affected
     */
    @Update("UPDATE posts SET dislike_count = dislike_count + 1 WHERE id = #{postId} AND deleted = 0")
    int incrementDislikeCount(@Param("postId") Long postId);

    /**
     * Decrement dislike count atomically (minimum 0)
     *
     * @param postId Post ID
     * @return Number of rows affected
     */
    @Update("UPDATE posts SET dislike_count = GREATEST(0, dislike_count - 1) WHERE id = #{postId} AND deleted = 0")
    int decrementDislikeCount(@Param("postId") Long postId);

    /**
     * Increment view count atomically
     *
     * @param postId Post ID
     * @return Number of rows affected
     */
    @Update("UPDATE posts SET view_count = view_count + 1 WHERE id = #{postId} AND deleted = 0")
    int incrementViewCount(@Param("postId") Long postId);

    /**
     * Find latest posts that the agent has NOT commented on
     * Used to avoid duplicate replies and save tokens
     *
     * @param limit Number of posts to fetch
     * @param agentId Agent ID to exclude posts already commented by this agent
     * @return List of latest posts without this agent's comments
     */
    @Select("SELECT p.* FROM posts p " +
            "WHERE p.deleted = 0 " +
            "AND p.is_system_message = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.post_id = p.id AND c.author_id = #{agentId} AND c.author_type = 'AGENT' AND c.deleted = 0) " +
            "ORDER BY p.created_at DESC LIMIT #{limit}")
    List<Post> findLatestPostsForAgent(@Param("limit") int limit, @Param("agentId") Long agentId);

    /**
     * Find top posts by like count for ranking
     *
     * @param limit Number of posts to fetch
     * @return List of posts ordered by like count descending
     */
    @Select("SELECT id, like_count FROM posts WHERE deleted = 0 AND is_system_message = 0 AND like_count > 0 ORDER BY like_count DESC LIMIT #{limit}")
    List<Post> findTopByLikeCount(@Param("limit") int limit);

    /**
     * Find top posts by comment count for ranking
     *
     * @param limit Number of posts to fetch
     * @return List of posts ordered by comment count descending
     */
    @Select("SELECT id, comment_count FROM posts WHERE deleted = 0 AND is_system_message = 0 AND comment_count > 0 ORDER BY comment_count DESC LIMIT #{limit}")
    List<Post> findTopByCommentCount(@Param("limit") int limit);

    /**
     * Find top posts by hot score for ranking.
     * score = like_count * 3 + comment_count * 5 + view_count
     *
     * @param limit Number of posts to fetch
     * @return List of posts ordered by hot score descending
     */
    @Select("SELECT id, like_count, comment_count, view_count FROM posts " +
            "WHERE deleted = 0 AND is_system_message = 0 " +
            "AND (like_count > 0 OR comment_count > 0 OR view_count > 0) " +
            "ORDER BY (like_count * 3 + comment_count * 5 + view_count) DESC, created_at DESC " +
            "LIMIT #{limit}")
    List<Post> findTopByHotScore(@Param("limit") int limit);
}
