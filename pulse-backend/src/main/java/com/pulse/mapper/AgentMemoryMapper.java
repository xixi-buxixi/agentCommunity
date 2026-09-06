package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.AgentMemory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent Memory Mapper
 *
 * Hand-written statements repeat "deleted = 0": MyBatis Plus only injects the
 * @TableLogic predicate into its own generated SQL, not into @Select annotations.
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {

    /**
     * The memories eligible for prompt injection, already in injection order.
     *
     * The WHERE clause is the first of two gates: DISABLED (0), DEPRECATED (2),
     * soft-deleted and expired rows can never leave the database for a prompt. The
     * service applies the same predicate again in Java, because "a bad memory must not
     * reach the model" is the one rule in this feature that a future query edit must
     * not be able to break silently.
     *
     * PERSONA_TRAIT sorts before PERSONA_FACT: a distilled trait says more about who
     * the agent is than any single action it took.
     */
    @Select("SELECT * FROM agent_memories "
            + "WHERE agent_id = #{agentId} AND status = 1 AND deleted = 0 "
            + "AND (expires_at IS NULL OR expires_at > NOW()) "
            + "ORDER BY CASE memory_type WHEN 'PERSONA_TRAIT' THEN 0 ELSE 1 END, "
            + "importance_score DESC, created_at DESC, id DESC "
            + "LIMIT #{limit}")
    List<AgentMemory> findInjectable(@Param("agentId") Long agentId, @Param("limit") int limit);

    /**
     * Live memories of one type, most important first. Used to show the reflection
     * model what the agent already believes about itself.
     *
     * Same predicate as {@link #findInjectable}: ACTIVE and unexpired. Reflection is an
     * injection path too - a trait distilled from a memory the owner disabled would
     * come back as a brand new ACTIVE trait, which is the owner brake being laundered
     * rather than respected.
     */
    @Select("SELECT * FROM agent_memories "
            + "WHERE agent_id = #{agentId} AND memory_type = #{memoryType} "
            + "AND status = 1 AND deleted = 0 "
            + "AND (expires_at IS NULL OR expires_at > NOW()) "
            + "ORDER BY importance_score DESC, created_at DESC, id DESC LIMIT #{limit}")
    List<AgentMemory> findActiveByType(@Param("agentId") Long agentId,
                                       @Param("memoryType") String memoryType,
                                       @Param("limit") int limit);

    /**
     * Live cards of one type written since a cut-off, oldest first so the behaviour
     * pack reads chronologically. ACTIVE and unexpired only - see
     * {@link #findActiveByType}.
     */
    @Select("SELECT * FROM agent_memories "
            + "WHERE agent_id = #{agentId} AND memory_type = #{memoryType} "
            + "AND created_at >= #{since} AND status = 1 AND deleted = 0 "
            + "AND (expires_at IS NULL OR expires_at > NOW()) "
            + "ORDER BY created_at ASC, id ASC LIMIT #{limit}")
    List<AgentMemory> findByTypeSince(@Param("agentId") Long agentId,
                                      @Param("memoryType") String memoryType,
                                      @Param("since") LocalDateTime since,
                                      @Param("limit") int limit);

    /**
     * The trait cards the owner has published on the agent's public profile.
     *
     * Four independent gates, all of which must hold at read time rather than at
     * publish time: the card is a PERSONA_TRAIT, its scope is PUBLIC, it is ACTIVE,
     * and it has not expired. status is checked here and not only when the card was
     * published because disabling a published card deliberately leaves its scope
     * alone - the owner's brake hides the card without discarding the decision to
     * publish it, so re-enabling restores the card to the profile as it was.
     *
     * Ordered by confidence first: the profile is a summary of who the agent is, and
     * a trait the reflection job is sure about says more than a recent guess. id
     * breaks ties so two cards written in the same second cannot swap places between
     * two reads of the same page.
     *
     * @param agentId Agent ID
     * @param limit   rows to return
     */
    @Select("SELECT * FROM agent_memories "
            + "WHERE agent_id = #{agentId} AND memory_type = 'PERSONA_TRAIT' "
            + "AND scope = 'PUBLIC' AND status = 1 AND deleted = 0 "
            + "AND (expires_at IS NULL OR expires_at > NOW()) "
            + "ORDER BY confidence_score DESC, created_at DESC, id DESC "
            + "LIMIT #{limit}")
    List<AgentMemory> findPublicTraits(@Param("agentId") Long agentId, @Param("limit") int limit);

    /**
     * Ids of the agent's own non-retired traits. Used to reject reflection output that
     * references somebody else's memory before any write is attempted.
     */
    @Select("SELECT id FROM agent_memories "
            + "WHERE agent_id = #{agentId} AND memory_type = 'PERSONA_TRAIT' "
            + "AND status <> 2 AND deleted = 0")
    List<Long> findTraitIds(@Param("agentId") Long agentId);

    /**
     * Apply a model-proposed revision of one trait.
     *
     * The predicate is the SQL half of the anti-hijack check: agent_id and
     * memory_type are pinned, so a hallucinated id belonging to another agent (or to a
     * PERSONA_FACT) updates nothing. status is deliberately left untouched - a trait
     * the owner disabled must stay disabled, and a retired one ({@code status = 2})
     * cannot be edited at all. {@code version = version + 1} is computed in the
     * database, so two reflection runs can never collapse onto the same version.
     *
     * {@code evidence} is ALWAYS written when the content is revised - a supplied note
     * replaces the old one, an absent note clears it. Keeping the previous evidence
     * would leave the card citing text it no longer contains, and a mismatched citation
     * is more harmful than none: it makes a rewritten trait look independently
     * corroborated.
     *
     * @return 1 when the trait was really this agent's, 0 otherwise
     */
    @Update("UPDATE agent_memories "
            + "SET content = #{content}, evidence = #{evidence}, "
            + "importance_score = #{importanceScore}, confidence_score = #{confidenceScore}, "
            + "version = version + 1, created_by = 'REFLECTION', updated_at = NOW() "
            + "WHERE id = #{id} AND agent_id = #{agentId} "
            + "AND memory_type = 'PERSONA_TRAIT' AND status <> 2 AND deleted = 0")
    int applyTraitRevision(@Param("id") Long id,
                           @Param("agentId") Long agentId,
                           @Param("content") String content,
                           @Param("evidence") String evidence,
                           @Param("importanceScore") Integer importanceScore,
                           @Param("confidenceScore") Integer confidenceScore);

    /**
     * Retire traits the model declared obsolete, scoped to the agent and the type so a
     * hallucinated id cannot retire somebody else's memory.
     *
     * @return number of rows actually retired
     */
    @Update({"<script>",
            "UPDATE agent_memories SET status = 2, updated_at = NOW() ",
            "WHERE agent_id = #{agentId} AND memory_type = 'PERSONA_TRAIT' ",
            // Inside <script> the SQL is parsed as XML, so a bare <> is a broken tag and
            // kills the whole mapper (and with it the Spring context) at startup.
            "AND status &lt;&gt; 2 AND deleted = 0 AND id IN ",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    int deprecateTraitsForAgent(@Param("agentId") Long agentId, @Param("ids") List<Long> ids);

    /**
     * Count the cards of one type that still count against the retention cap
     * (DEPRECATED cards are already retired, so they are excluded).
     */
    @Select("SELECT COUNT(*) FROM agent_memories "
            + "WHERE agent_id = #{agentId} AND memory_type = #{memoryType} "
            + "AND status <> 2 AND deleted = 0")
    int countLiveByType(@Param("agentId") Long agentId, @Param("memoryType") String memoryType);

    /**
     * The cards to retire first when the cap is exceeded: least important, then
     * oldest. id breaks ties so the order is deterministic within one timestamp.
     */
    @Select("SELECT id FROM agent_memories "
            + "WHERE agent_id = #{agentId} AND memory_type = #{memoryType} "
            + "AND status <> 2 AND deleted = 0 "
            + "ORDER BY importance_score ASC, created_at ASC, id ASC LIMIT #{limit}")
    List<Long> findRetentionCandidates(@Param("agentId") Long agentId,
                                       @Param("memoryType") String memoryType,
                                       @Param("limit") int limit);

    /**
     * Apply an owner edit as one conditional UPDATE.
     *
     * Read-modify-updateById was racy in two ways:
     * - the retention sweep (or a future reflection job) could set status=2 between
     *   the read and the write, and the write would then put the stale ACTIVE status
     *   back - resurrecting a DEPRECATED card past the service-level check;
     * - two concurrent corrections both read version=1 and both wrote version=2.
     *
     * The version predicate makes the write fail (0 rows) when the row moved under
     * us, so version is strictly monotonic. A status write additionally requires the
     * status we read ({@code status = expectedStatus}, so two opposite concurrent
     * toggles cannot both report success) and {@code status <> 2}, so "DEPRECATED is
     * terminal" holds in SQL and not just in Java. A row with a NULL version (only
     * possible on a pre-migration row) never matches and is reported as a conflict,
     * which is the honest answer.
     *
     * A scope write to PUBLIC carries its own predicate pair - {@code memory_type =
     * 'PERSONA_TRAIT'} and {@code status <> 2} - so "only a live trait can be
     * published" holds in SQL as well as in Java. The service checks both first, for a
     * precise error message; this is what covers a retirement that lands between that
     * read and this write. Withdrawal (scope SELF) is unguarded on purpose: it only
     * ever removes a card from the public page.
     *
     * @param status         new status, or null to leave it alone
     * @param content        corrected body, or null to leave it (and the version) alone
     * @param scope          new visibility scope, or null to leave it alone
     * @param expectedStatus status read before the edit; only used when a status is
     *                       being written
     * @return 1 when applied, 0 when the row changed underneath the caller
     */
    @Update({"<script>",
            "UPDATE agent_memories SET updated_at = NOW()",
            "<if test='status != null'>, status = #{status}</if>",
            "<if test='content != null'>, content = #{content}, version = #{newVersion},",
            "created_by = #{createdBy}</if>",
            "<if test='scope != null'>, scope = #{scope}</if>",
            "WHERE id = #{id} AND deleted = 0 AND version = #{expectedVersion}",
            "<if test='status != null'> AND status &lt;&gt; 2 AND status = #{expectedStatus}</if>",
            "<if test=\"scope == 'PUBLIC'\">",
            "  AND memory_type = 'PERSONA_TRAIT' AND status &lt;&gt; 2",
            "</if>",
            "</script>"})
    int applyOwnerEdit(@Param("id") Long id,
                       @Param("status") Integer status,
                       @Param("content") String content,
                       @Param("newVersion") Integer newVersion,
                       @Param("createdBy") String createdBy,
                       @Param("expectedVersion") Integer expectedVersion,
                       @Param("expectedStatus") Integer expectedStatus,
                       @Param("scope") String scope);

    /**
     * Retire cards in one statement (status 2 = DEPRECATED).
     */
    @Update({"<script>",
            "UPDATE agent_memories SET status = 2, updated_at = NOW() ",
            // Escaped for the same reason as in deprecateTraitsForAgent above
            "WHERE deleted = 0 AND status &lt;&gt; 2 AND id IN ",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    int deprecateByIds(@Param("ids") List<Long> ids);

    /**
     * Physically delete one batch of long-retired cards.
     *
     * The retention sweep on the write path only RETIRES cards (status = 2), so the table
     * keeps every card an agent ever formed. That is right for a card retired an hour ago
     * - an owner may want to see what their agent stopped believing - and pointless for
     * one retired a month ago.
     *
     * status = 2 is the entire selection, and it is the whole safety argument: ACTIVE (1)
     * cards are what the agent thinks, and DISABLED (0) cards are what its OWNER decided
     * it may not think. Deleting a disabled card would silently lift the owner's brake,
     * because nothing would stop the same fact being learned again as a fresh ACTIVE card.
     *
     * updated_at is the age that matters, not created_at: it is when the card was retired,
     * which is when the clock on keeping it should start.
     *
     * LIMIT keeps each statement short; the caller repeats until it returns 0.
     *
     * @return number of rows deleted by this batch
     */
    @Delete("DELETE FROM agent_memories "
            + "WHERE status = 2 AND updated_at < #{cutoff} LIMIT #{limit}")
    int deleteDeprecatedOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
