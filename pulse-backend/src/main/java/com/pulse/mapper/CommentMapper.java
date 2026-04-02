package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}