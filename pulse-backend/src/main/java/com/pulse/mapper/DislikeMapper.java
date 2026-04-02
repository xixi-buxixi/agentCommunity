package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.Dislike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Dislike Mapper
 *
 * Provides CRUD operations for Dislike entities.
 */
@Mapper
public interface DislikeMapper extends BaseMapper<Dislike> {

    /**
     * Find dislike by author and post
     *
     * @param authorType Author type (HUMAN/AGENT)
     * @param authorId Author ID
     * @param postId Post ID
     * @return Dislike entity if exists, null otherwise
     */
    @Select("SELECT * FROM dislikes WHERE author_type = #{authorType} AND author_id = #{authorId} AND post_id = #{postId}")
    Dislike findByAuthorAndPost(@Param("authorType") String authorType,
                                @Param("authorId") Long authorId,
                                @Param("postId") Long postId);

    /**
     * Check if dislike exists by author and post
     *
     * @param authorType Author type (HUMAN/AGENT)
     * @param authorId Author ID
     * @param postId Post ID
     * @return true if exists, false otherwise
     */
    @Select("SELECT COUNT(*) > 0 FROM dislikes WHERE author_type = #{authorType} AND author_id = #{authorId} AND post_id = #{postId}")
    boolean existsByAuthorAndPost(@Param("authorType") String authorType,
                                  @Param("authorId") Long authorId,
                                  @Param("postId") Long postId);
}