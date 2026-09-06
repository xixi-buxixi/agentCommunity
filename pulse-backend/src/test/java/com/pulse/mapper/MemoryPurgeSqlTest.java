package com.pulse.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural checks on the two statements that DELETE rather than update.
 *
 * Same reasoning as {@link AgentRankingMapperSqlTest}: the services mock their mappers,
 * so no service test ever parses a line of this SQL, and there is no in-memory database
 * on the test classpath. What can still be pinned is the shape of each statement - and
 * here the shape is the entire safety argument, because getting it wrong deletes data
 * that cannot be recovered.
 */
class MemoryPurgeSqlTest {

    /**
     * DEPRECATED (2) only, and this is the rule that matters most in the codebase's
     * delete statements.
     *
     * ACTIVE (1) is what the agent currently believes. DISABLED (0) is what its OWNER
     * decided it may not believe - a standing instruction, not a stale row: deleting a
     * disabled card silently lifts the brake, because nothing then stops the same fact
     * being learned again as a fresh ACTIVE card.
     */
    @Test
    void theMemoryPurgeDeletesRetiredCardsAndNothingElse() {
        String sql = normalized(deleteSqlOf(AgentMemoryMapper.class, "deleteDeprecatedOlderThan"));

        assertThat(sql).startsWith("DELETE FROM agent_memories");
        assertThat(sql).contains("WHERE status = 2");
        // No inequality, no IN list, no "status <> 1": one exact status and no other way
        // for a future edit to widen it by accident.
        assertThat(sql).doesNotContain("status <>");
        assertThat(sql).doesNotContain("status !=");
        assertThat(sql).doesNotContain("status IN");
    }

    /**
     * updated_at, not created_at: the age that matters is how long the card has been
     * retired, which is when the clock on keeping it should start. Filtering on
     * created_at would delete a card formed a year ago and retired this morning.
     */
    @Test
    void theMemoryPurgeAgesCardsFromWhenTheyWereRetired() {
        String sql = normalized(deleteSqlOf(AgentMemoryMapper.class, "deleteDeprecatedOlderThan"));

        assertThat(sql).contains("updated_at < #{cutoff}");
        assertThat(sql).doesNotContain("created_at");
        // The batch bound: without it one statement holds locks for the whole backlog.
        assertThat(sql).contains("LIMIT #{limit}");
    }

    /**
     * Unread notifications are kept whatever their age - that is the one case where the
     * recipient demonstrably has not seen the thing yet.
     */
    @Test
    void theNotificationCleanupOnlyDeletesReadRows() {
        String sql = normalized(deleteSqlOf(NotificationMapper.class, "deleteReadOlderThan"));

        assertThat(sql).startsWith("DELETE FROM notifications");
        assertThat(sql).contains("is_read = 1");
        assertThat(sql).contains("created_at < #{cutoff}");
        assertThat(sql).contains("LIMIT #{limit}");
    }

    /**
     * The duplicate check compares the nullable columns null-safely.
     *
     * link_type, link_id, actor_type and actor_id are all nullable, and plain "=" makes a
     * NULL never match itself - so a notification without a link would never de-duplicate
     * and the filter would silently do nothing for a whole class of rows.
     */
    @Test
    void theDuplicateCheckIsNullSafeAndUnreadOnly() {
        String sql = normalized(selectSqlOf(NotificationMapper.class, "countRecentDuplicates"));

        assertThat(sql).contains("link_type <=> #{linkType}");
        assertThat(sql).contains("link_id <=> #{linkId}");
        assertThat(sql).contains("actor_type <=> #{actorType}");
        assertThat(sql).contains("actor_id <=> #{actorId}");
        // Only unread rows suppress: once the line has been read, the next event of the
        // same shape is news again.
        assertThat(sql).contains("is_read = 0");
        assertThat(sql).contains("created_at >= #{since}");
    }

    /** Compile-time guard that the signatures the assertions describe still exist. */
    @Test
    void theMapperSignaturesAreUnchanged() throws Exception {
        assertThat(AgentMemoryMapper.class.getMethod("deleteDeprecatedOlderThan",
                LocalDateTime.class, int.class)).isNotNull();
        assertThat(NotificationMapper.class.getMethod("deleteReadOlderThan",
                LocalDateTime.class, int.class)).isNotNull();
    }

    // ========== Fixtures ==========

    private String deleteSqlOf(Class<?> mapper, String methodName) {
        Method method = methodOf(mapper, methodName);
        Delete delete = method.getAnnotation(Delete.class);
        assertThat(delete).as("%s carries no @Delete", methodName).isNotNull();
        return String.join(" ", delete.value());
    }

    private String selectSqlOf(Class<?> mapper, String methodName) {
        Method method = methodOf(mapper, methodName);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as("%s carries no @Select", methodName).isNotNull();
        return String.join(" ", select.value());
    }

    private Method methodOf(Class<?> mapper, String methodName) {
        for (Method method : mapper.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError("no such mapper method: " + mapper.getSimpleName() + "#" + methodName);
    }

    /** Collapse the concatenation's indentation so the assertions can read as SQL. */
    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
