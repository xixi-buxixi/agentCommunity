package com.pulse.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural checks on the leaderboard aggregates.
 *
 * These statements have no service-level seam to test through - the impl mocks the
 * mapper, so a mocked mapper never parses a line of SQL - and the project has no
 * in-memory database on the test classpath. What can still be pinned here is the
 * SHAPE of each statement, which is where the counting bug lived: a reply carries both
 * a post_id and a parent_comment_id, so two UNION ALL'd counts double-counted it
 * whenever the same agent had written both the post and the parent comment. That is
 * one line of SQL apart from correct, and nothing else in the suite would notice it
 * changing back.
 */
class AgentRankingMapperSqlTest {

    /**
     * The replied board counts DISTINCT (agent, comment) pairs.
     *
     * Sample data from the verification run: agent 10 wrote a post that collected the
     * comments 200, 201, 202, 203 and 207, and comment 203 is itself a reply to one of
     * agent 10's own comments. Five comments point at agent 10 inside the window. The
     * UNION ALL version scored it 6, because 203 matched both halves.
     *
     * UNION (not UNION ALL) over projected (agent_id, comment_id) pairs is what makes
     * that 5. It still counts one comment for TWO different agents when the post author
     * and the parent-comment author differ, which is correct: both were replied to.
     */
    @Test
    void theRepliedBoardDeduplicatesACommentThatMatchesBothHalves() {
        String sql = normalized(sqlOf("findTopByRepliesReceived"));

        // The de-duplicating set operator, and not the multiset one
        assertThat(sql).contains(" UNION SELECT ");
        assertThat(sql).doesNotContain("UNION ALL");

        // Both halves project the comment id, so the UNION has something to de-duplicate
        // ON. Counting per half (COUNT(*) ... GROUP BY author) would give UNION nothing
        // to compare and re-introduce the double count.
        assertThat(sql).contains("p.author_id AS agent_id, c.id AS comment_id");
        assertThat(sql).contains("pc.author_id AS agent_id, c.id AS comment_id");

        // The outer aggregate counts rows of the de-duplicated pair set
        assertThat(sql).contains("SELECT t.agent_id AS agentId, COUNT(*) AS score FROM (");
        assertThat(sql).doesNotContain("SUM(t.cnt)");
    }

    /**
     * The clauses that keep deleted and non-agent content off the board are unchanged
     * by the de-duplication.
     */
    @Test
    void theRepliedBoardStillExcludesDeletedAndNonAgentContent() {
        String sql = normalized(sqlOf("findTopByRepliesReceived"));

        assertThat(sql).contains("c.deleted = 0 AND c.created_at >= #{since}");
        assertThat(sql).contains("p.deleted = 0 AND p.author_type = 'AGENT'");
        assertThat(sql).contains("pc.deleted = 0 AND pc.author_type = 'AGENT'");
        assertThat(sql).contains("JOIN agents a ON a.id = t.agent_id AND a.deleted = 0");
        assertThat(sql).contains("ORDER BY score DESC, t.agent_id ASC");
        assertThat(sql).contains("LIMIT #{limit}");
    }

    /**
     * A self-reply is not a reply received.
     *
     * Both halves have to carry the predicate: an agent commenting under its own post
     * matches the post half, and an agent answering its own comment matches the parent
     * half, so guarding only one of them leaves the other route open. The board was
     * farmable by one agent talking to itself, and the same output was already being
     * counted on the activity board.
     *
     * author_type is part of the comparison because the user and agent id spaces
     * overlap: without it, a HUMAN whose user id equals the agent id would have their
     * reply discarded.
     */
    @Test
    void theRepliedBoardExcludesAnAgentRepliedToByItself() {
        String sql = normalized(sqlOf("findTopByRepliesReceived"));

        assertThat(sql).contains("(c.author_type <> 'AGENT' OR c.author_id <> p.author_id)");
        assertThat(sql).contains("(c.author_type <> 'AGENT' OR c.author_id <> pc.author_id)");
    }

    /**
     * The activity board legitimately uses UNION ALL: a post and a comment are two
     * different pieces of output, and its two halves draw from different tables, so
     * there is nothing to de-duplicate.
     */
    @Test
    void theActivityBoardKeepsItsUnionAll() {
        assertThat(normalized(sqlOf("findTopByActivity"))).contains("UNION ALL");
    }

    /**
     * The activity board does not count an agent's death notice.
     *
     * That post is written on the agent's behalf by AgentActionExecutor at the moment
     * it dies, under author_type 'AGENT', so it looked like output the agent produced.
     * It is one row, but on a board whose scores are small integers one row moves a
     * rank, and it lands exactly when the agent has stopped producing anything else.
     *
     * COALESCE and not {@code = 0}: the column is a nullable BOOLEAN with DEFAULT
     * FALSE, so rows written before it existed carry NULL and a plain comparison would
     * drop every one of them off the board.
     */
    @Test
    void theActivityBoardExcludesSystemMessages() {
        String sql = normalized(sqlOf("findTopByActivity"));

        assertThat(sql).contains("COALESCE(is_system_message, 0) = 0");
        // only the post half - comments have no such column
        assertThat(sql.split("COALESCE\\(is_system_message", -1)).hasSize(2);
    }

    /**
     * Both places that total an agent's tips filter the same way. They agree today only
     * because tipAgent never writes a negative TIP_RECV; the profile total and this
     * board must not start diverging the day a refund does.
     */
    @Test
    void bothTipTotalsFilterOnAPositiveAmount() throws Exception {
        assertThat(normalized(sqlOf("findTopByTipsReceived"))).contains("l.amount > 0");

        Method profileTotals = SysLedgerMapper.class.getMethod("findAgentTipTotals", Long.class);
        assertThat(normalized(profileTotals.getAnnotation(Select.class).value()[0]))
                .contains("amount > 0");
    }

    // ========== Fixtures ==========

    private String sqlOf(String methodName) {
        for (Method method : AgentRankingMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Select select = method.getAnnotation(Select.class);
                assertThat(select).as("%s carries no @Select", methodName).isNotNull();
                return select.value()[0];
            }
        }
        throw new AssertionError("no such mapper method: " + methodName);
    }

    /** Collapse the concatenation's indentation so the assertions can read as SQL. */
    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** Compile-time guard that the signature the assertions describe still exists. */
    @Test
    void theMapperSignaturesAreUnchanged() throws Exception {
        assertThat(AgentRankingMapper.class.getMethod("findTopByRepliesReceived",
                LocalDateTime.class, int.class)).isNotNull();
    }
}
