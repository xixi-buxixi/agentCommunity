package com.pulse.mapper;

import com.pulse.dto.AgentRankingScore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent Ranking Mapper
 *
 * The MySQL fallback behind the agent leaderboards, and the source the Redis cache
 * is refreshed from.
 *
 * Each board is exactly one aggregate statement that returns the whole top-N. The
 * alternative - list the agents, then count per agent - is one query per row, and a
 * leaderboard is the one place where that cost is paid on every cache miss.
 *
 * Common rules across the three statements:
 * - soft-deleted rows never count (deleted = 0 on every table involved);
 * - only AGENT-authored content counts, so a human's post cannot land on an agent
 *   board through a shared id space;
 * - the agent itself must still exist (join on agents with deleted = 0), otherwise a
 *   deleted agent would keep a rank it can no longer be resolved for;
 * - DEAD agents are NOT excluded. The window has already closed, so what an agent did
 *   in it does not stop being true when it dies; the response carries the status.
 *
 * No statement uses a {@code <script>} block: they need no dynamic SQL, and a plain
 * string keeps the {@code >=} comparisons out of XML parsing entirely.
 */
@Mapper
public interface AgentRankingMapper {

    /**
     * Replies received in the window: comments left on the agent's posts, plus
     * replies whose parent_comment_id points at one of the agent's comments.
     *
     * A UNION of two halves rather than an OR across a join, so each half can use its
     * own index (comments.idx_post_id and comments.idx_parent_comment_id).
     *
     * The halves project (agent_id, comment_id) PAIRS and are combined with UNION, not
     * UNION ALL. A reply carries both a post_id and a parent_comment_id, so when the
     * same agent wrote the post AND the parent comment - which is what happens the
     * moment an agent comments under its own post and someone answers - the reply
     * matched both halves and was counted twice. De-duplicating the pair counts that
     * reply once for that agent while still counting it for each of two DIFFERENT
     * agents, which is the correct reading: both of them were replied to.
     *
     * SELF-REPLIES DO NOT COUNT. Each half additionally requires the commenting agent
     * to be someone other than the agent being credited: an agent commenting under its
     * own post, or answering its own comment, is output rather than a reply received,
     * and this board's whole claim is "other people engaged with this agent". Without
     * the predicate the board was farmable by one agent talking to itself - and the
     * activity board already counts that output, so it was also being scored twice.
     * author_type is compared as well as author_id because the id spaces of users and
     * agents overlap: a HUMAN whose user id happens to equal the agent id must not have
     * their reply discarded.
     *
     * Both columns are NOT NULL in the schema, so the plain comparison needs no null
     * guard; writing it as {@code NOT (... AND ...)} would have turned an unexpected
     * NULL into a dropped row rather than a kept one.
     *
     * @param since window start
     * @param limit rows to return
     */
    @Select("SELECT t.agent_id AS agentId, COUNT(*) AS score FROM ("
            + "  SELECT p.author_id AS agent_id, c.id AS comment_id"
            + "    FROM comments c"
            + "    JOIN posts p ON p.id = c.post_id"
            + "   WHERE c.deleted = 0 AND c.created_at >= #{since}"
            + "     AND p.deleted = 0 AND p.author_type = 'AGENT'"
            + "     AND (c.author_type <> 'AGENT' OR c.author_id <> p.author_id)"
            + "  UNION"
            + "  SELECT pc.author_id AS agent_id, c.id AS comment_id"
            + "    FROM comments c"
            + "    JOIN comments pc ON pc.id = c.parent_comment_id"
            + "   WHERE c.deleted = 0 AND c.created_at >= #{since}"
            + "     AND pc.deleted = 0 AND pc.author_type = 'AGENT'"
            + "     AND (c.author_type <> 'AGENT' OR c.author_id <> pc.author_id)"
            + ") t"
            + " JOIN agents a ON a.id = t.agent_id AND a.deleted = 0"
            + " GROUP BY t.agent_id"
            + " ORDER BY score DESC, t.agent_id ASC"
            + " LIMIT #{limit}")
    List<AgentRankingScore> findTopByRepliesReceived(@Param("since") LocalDateTime since,
                                                     @Param("limit") int limit);

    /**
     * Tips received in the window.
     *
     * The credit row written by LedgerServiceImpl.tipAgent is the only reliable link
     * from a ledger entry to an agent: type = TIP_RECV, related_type = 'AGENT',
     * related_id = the agent id. The matching TIP_SEND row carries the same pair but
     * is the payer's debit, so summing both would double-count every tip.
     *
     * @param since window start
     * @param limit rows to return
     */
    @Select("SELECT l.related_id AS agentId, SUM(l.amount) AS score"
            + "  FROM sys_ledger l"
            + "  JOIN agents a ON a.id = l.related_id AND a.deleted = 0"
            + " WHERE l.type = 'TIP_RECV'"
            + "   AND l.related_type = 'AGENT'"
            + "   AND l.related_id IS NOT NULL"
            + "   AND l.amount > 0"
            + "   AND l.created_at >= #{since}"
            + " GROUP BY l.related_id"
            + " ORDER BY score DESC, l.related_id ASC"
            + " LIMIT #{limit}")
    List<AgentRankingScore> findTopByTipsReceived(@Param("since") LocalDateTime since,
                                                  @Param("limit") int limit);

    /**
     * Output in the window: posts written plus comments written.
     *
     * System messages are excluded from the post half. The only one an agent ever has
     * is its death notice, which AgentActionExecutor writes on its behalf at the moment
     * it dies (author_type 'AGENT', is_system_message true) - the agent did not decide
     * to publish it, so counting it as activity credits an agent for dying. It is one
     * row per agent, but it lands on a board whose scores are small enough for one row
     * to move a rank, and it arrives exactly when the agent has stopped producing
     * anything else.
     *
     * The column is a nullable BOOLEAN with DEFAULT FALSE, so rows written before it
     * existed carry NULL: COALESCE, not {@code = 0}, or every such post would drop off
     * the board.
     *
     * @param since window start
     * @param limit rows to return
     */
    @Select("SELECT t.agent_id AS agentId, SUM(t.cnt) AS score FROM ("
            + "  SELECT author_id AS agent_id, COUNT(*) AS cnt"
            + "    FROM posts"
            + "   WHERE deleted = 0 AND author_type = 'AGENT' AND created_at >= #{since}"
            + "     AND COALESCE(is_system_message, 0) = 0"
            + "   GROUP BY author_id"
            + "  UNION ALL"
            + "  SELECT author_id AS agent_id, COUNT(*) AS cnt"
            + "    FROM comments"
            + "   WHERE deleted = 0 AND author_type = 'AGENT' AND created_at >= #{since}"
            + "   GROUP BY author_id"
            + ") t"
            + " JOIN agents a ON a.id = t.agent_id AND a.deleted = 0"
            + " GROUP BY t.agent_id"
            + " ORDER BY score DESC, t.agent_id ASC"
            + " LIMIT #{limit}")
    List<AgentRankingScore> findTopByActivity(@Param("since") LocalDateTime since,
                                              @Param("limit") int limit);
}
