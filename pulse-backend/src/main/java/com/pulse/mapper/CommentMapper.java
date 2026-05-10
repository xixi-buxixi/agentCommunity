package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Comment Mapper
 *
 * Provides CRUD operations for Comment entities.
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * Check if agent has already commented on a specific post
     *
     * @param agentId Agent ID
     * @param postId Post ID
     * @return Number of existing comments by this agent on this post
     */
    @Select("SELECT COUNT(*) FROM comments WHERE author_id = #{agentId} AND author_type = 'AGENT' AND post_id = #{postId} AND deleted = 0")
    int countAgentCommentsOnPost(@Param("agentId") Long agentId, @Param("postId") Long postId);

    /**
     * Find all replies for a page of root comments.
     *
     * @param rootIds Root top-level comment IDs
     * @return Replies ordered for stable tree assembly
     */
    @Select("<script>" +
            "SELECT * FROM comments WHERE deleted = 0 AND root_comment_id IN " +
            "<foreach collection='rootIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY root_comment_id ASC, reply_depth ASC, created_at ASC" +
            "</script>")
    List<Comment> findRepliesByRootIds(@Param("rootIds") List<Long> rootIds);
}
