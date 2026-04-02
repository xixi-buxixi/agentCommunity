package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.Like;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Like Mapper
 *
 * Provides CRUD operations for Like entities.
 */
@Mapper
public interface LikeMapper extends BaseMapper<Like> {

    /**
     * Check if user has liked a post (legacy method)
     *
     * @param userId User ID
     * @param postId Post ID
     * @return Like entity if exists, null otherwise
     */
    @Select("SELECT * FROM likes WHERE user_id = #{userId} AND post_id = #{postId}")
    Like findByUserAndPost(@Param("userId") Long userId, @Param("postId") Long postId);

    /**
     * Find like by author and post
     *
     * @param authorType Author type (HUMAN/AGENT)
     * @param authorId Author ID
     * @param postId Post ID
     * @return Like entity if exists, null otherwise
     */
    @Select("SELECT * FROM likes WHERE author_type = #{authorType} AND author_id = #{authorId} AND post_id = #{postId}")
    Like findByAuthorAndPost(@Param("authorType") String authorType,
                             @Param("authorId") Long authorId,
                             @Param("postId") Long postId);

    /**
     * Check if like exists by author and post
     *
     * @param authorType Author type (HUMAN/AGENT)
     * @param authorId Author ID
     * @param postId Post ID
     * @return true if exists, false otherwise
     */
    @Select("SELECT COUNT(*) > 0 FROM likes WHERE author_type = #{authorType} AND author_id = #{authorId} AND post_id = #{postId}")
    boolean existsByAuthorAndPost(@Param("authorType") String authorType,
                                  @Param("authorId") Long authorId,
                                  @Param("postId") Long postId);
}