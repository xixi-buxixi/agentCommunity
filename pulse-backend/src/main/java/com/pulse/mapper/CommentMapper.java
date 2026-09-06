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
     * How many times this agent has already answered one specific comment.
     *
     * The post-level guard ("one comment per post") is deliberately NOT the rule for
     * targeted replies: a thread with three questions in it should get three answers.
     * What must stay bounded is answering the SAME comment over and over, which is the
     * shape a loop takes once agents can reply to each other's comments.
     *
     * @param agentId         Agent ID
     * @param parentCommentId The comment being answered
     * @return Number of live replies this agent has written under that comment
     */
    @Select("SELECT COUNT(*) FROM comments WHERE author_id = #{agentId} AND author_type = 'AGENT' "
            + "AND parent_comment_id = #{parentCommentId} AND deleted = 0")
    int countAgentRepliesToComment(@Param("agentId") Long agentId,
                                   @Param("parentCommentId") Long parentCommentId);

    /**
     * The most recent comments under one post, newest first.
     *
     * Rendered as child lines of the post's context block so an agent can answer a
     * specific person instead of shouting at the post. Newest-first because the tail of
     * a discussion is what an answer has to engage with; the caller reverses them back
     * into reading order.
     *
     * @param postId Post the comments hang under
     * @param limit  Hard ceiling - the context budget, not paging, decides this number
     * @return Live comments, newest first
     */
    @Select("SELECT * FROM comments WHERE post_id = #{postId} AND deleted = 0 "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<Comment> findRecentCommentsByPost(@Param("postId") Long postId, @Param("limit") int limit);

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
     * The distinct AGENT authors that have commented under one post.
     *
     * Half of the mention candidate set: an "@name" may only wake an agent that is
     * already part of this conversation (see AgentMentionService for why the set is
     * bounded at all). LIMIT is a hard ceiling rather than paging - a thread with more
     * agents than this is already far past the point where one more mention matters,
     * and an unbounded IN list would be built from user-driven data.
     *
     * @param postId Post the comment tree belongs to
     * @param limit  Maximum number of distinct agents to return
     * @return distinct agent ids, lowest first so the set is deterministic
     */
    @Select("SELECT DISTINCT author_id FROM comments "
            + "WHERE post_id = #{postId} AND author_type = 'AGENT' AND deleted = 0 "
            + "ORDER BY author_id ASC LIMIT #{limit}")
    List<Long> findAgentAuthorIdsByPost(@Param("postId") Long postId, @Param("limit") int limit);

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
