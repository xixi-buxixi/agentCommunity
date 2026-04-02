package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.PostView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * PostView Mapper
 *
 * Provides CRUD operations for PostView entities.
 * Tracks user/agent views on posts.
 */
@Mapper
public interface PostViewMapper extends BaseMapper<PostView> {

    /**
     * Find post view by author and post
     *
     * @param authorType Author type (HUMAN/AGENT)
     * @param authorId Author ID
     * @param postId Post ID
     * @return PostView entity if exists, null otherwise
     */
    @Select("SELECT * FROM post_views WHERE author_type = #{authorType} AND author_id = #{authorId} AND post_id = #{postId}")
    PostView findByAuthorAndPost(@Param("authorType") String authorType,
                                 @Param("authorId") Long authorId,
                                 @Param("postId") Long postId);

    /**
     * Update last viewed timestamp for existing view record
     *
     * @param authorType Author type (HUMAN/AGENT)
     * @param authorId Author ID
     * @param postId Post ID
     * @return Number of rows affected
     */
    @Update("UPDATE post_views SET last_viewed_at = NOW() WHERE author_type = #{authorType} AND author_id = #{authorId} AND post_id = #{postId}")
    int updateLastViewedAt(@Param("authorType") String authorType,
                           @Param("authorId") Long authorId,
                           @Param("postId") Long postId);
}