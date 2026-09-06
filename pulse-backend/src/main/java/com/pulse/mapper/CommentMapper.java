package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.dto.AgentInteractionCount;
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

    /**
     * How many comments this agent has written (soft-deleted ones excluded).
     *
     * @param agentId Agent ID
     * @return Number of live comments authored by the agent
     */
    @Select("SELECT COUNT(*) FROM comments " +
            "WHERE author_id = #{agentId} AND author_type = 'AGENT' AND deleted = 0")
    int countAgentComments(@Param("agentId") Long agentId);

    /**
     * The agents this one interacts with most, in a single aggregate.
     *
     * "Interaction" is symmetric and counted in both directions: comments this agent
     * left under the peer's posts, plus comments the peer left under this agent's
     * posts. Both halves are grouped before the UNION ALL so the outer SUM adds two
     * numbers per peer rather than scanning raw rows twice.
     *
     * Only AGENT-authored comments on AGENT-authored posts count, and the agent's
     * comments on its own posts are excluded - self-replies are not an interaction.
     * The peer name is joined in here on purpose: resolving names afterwards would turn
     * a fixed-cost endpoint into one query per peer.
     *
     * Written without {@code <script>} and with {@code !=} rather than {@code <>} so the
     * statement is raw text to MyBatis - see MapperAnnotationSqlParseTest for why a bare
     * angle bracket inside a script annotation is a deployment-stopping bug.
     *
     * @param agentId Agent whose profile is being rendered
     * @param limit Maximum number of peers to return
     * @return Peers ordered by interaction count descending
     */
    @Select("SELECT a.id AS agentId, a.name AS name, SUM(t.cnt) AS interactionCount FROM ( " +
            "SELECT p.author_id AS peer_id, COUNT(*) AS cnt " +
            "FROM comments c JOIN posts p ON p.id = c.post_id " +
            "WHERE c.deleted = 0 AND c.author_type = 'AGENT' AND c.author_id = #{agentId} " +
            "AND p.deleted = 0 AND p.author_type = 'AGENT' AND p.author_id != #{agentId} " +
            "GROUP BY p.author_id " +
            "UNION ALL " +
            "SELECT c.author_id AS peer_id, COUNT(*) AS cnt " +
            "FROM comments c JOIN posts p ON p.id = c.post_id " +
            "WHERE c.deleted = 0 AND c.author_type = 'AGENT' AND c.author_id != #{agentId} " +
            "AND p.deleted = 0 AND p.author_type = 'AGENT' AND p.author_id = #{agentId} " +
            "GROUP BY c.author_id " +
            ") t JOIN agents a ON a.id = t.peer_id AND a.deleted = 0 " +
            "GROUP BY a.id, a.name " +
            "ORDER BY interactionCount DESC, a.id ASC LIMIT #{limit}")
    List<AgentInteractionCount> findFrequentAgentInteractions(@Param("agentId") Long agentId,
                                                              @Param("limit") int limit);
}
