package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.Notification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * Notification Mapper (notification centre).
 *
 * The three statements below are hand written rather than expressed as wrappers because
 * each one has to be a single round trip that also carries the ownership predicate:
 * "mark read" must never be a select-then-update, or a caller could read somebody else's
 * row id and update it in the gap.
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * Unread badge count for one user.
     */
    @Select("SELECT COUNT(*) FROM notifications "
            + "WHERE recipient_user_id = #{userId} AND is_read = 0 AND deleted = 0")
    long countUnread(@Param("userId") Long userId);

    /**
     * Mark one notification read, but only if it belongs to this user and is still unread.
     *
     * The recipient predicate is the authorization check itself, so somebody else's id
     * updates 0 rows and the service reports "not found" - a notification the caller may
     * not see must not be distinguishable from one that does not exist.
     *
     * @return 1 when this call marked it, 0 when the row is absent, not the caller's, or
     *         already read
     */
    @Update("UPDATE notifications SET is_read = 1, read_at = #{now} "
            + "WHERE id = #{id} AND recipient_user_id = #{userId} AND is_read = 0 AND deleted = 0")
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Does this notification exist for this user at all, read or unread?
     *
     * Only consulted after {@link #markRead} updated nothing, to tell "already read"
     * (success, nothing to do) apart from "not yours / gone" (404).
     */
    @Select("SELECT COUNT(*) FROM notifications "
            + "WHERE id = #{id} AND recipient_user_id = #{userId} AND deleted = 0")
    int existsForUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * How many notifications of one type, about one linked record, this user already has
     * since a cut-off.
     *
     * The de-duplication window for conditions that persist rather than happen once. An
     * agent skipped for an empty balance is skipped again on every tick for as long as
     * the balance stays empty, and telling the owner every fifteen minutes would bury the
     * first (useful) message under a hundred identical ones.
     *
     * Counting existing rows rather than keeping a per-agent timestamp somewhere: the
     * notification table already IS the record of what the owner has been told, and a
     * second store would be a second thing to keep in step.
     */
    @Select("SELECT COUNT(*) FROM notifications "
            + "WHERE recipient_user_id = #{userId} AND type = #{type} "
            + "AND link_type = #{linkType} AND link_id = #{linkId} "
            + "AND created_at >= #{since} AND deleted = 0")
    int countByTypeAndLinkSince(@Param("userId") Long userId,
                                @Param("type") String type,
                                @Param("linkType") String linkType,
                                @Param("linkId") Long linkId,
                                @Param("since") LocalDateTime since);

    /**
     * Mark every unread notification of one user read.
     *
     * @return number of rows actually flipped
     */
    @Update("UPDATE notifications SET is_read = 1, read_at = #{now} "
            + "WHERE recipient_user_id = #{userId} AND is_read = 0 AND deleted = 0")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Is the same thing already sitting unread in this user's inbox?
     *
     * "The same thing" is recipient + type + link + actor, inside a short window. That
     * tuple is what a person actually reads as one line ("alice replied to your post"),
     * so three replies from alice under the same post in two minutes are one line's worth
     * of information and three rows of noise.
     *
     * UNREAD only, deliberately: once the user has read the line, the next event of the
     * same shape is news again - suppressing it would mean the second visitor to a post
     * is never reported at all.
     *
     * Null-safe equality ({@code <=>}) on the four optional columns: link_type, link_id,
     * actor_type and actor_id are all nullable, and plain {@code =} would make a NULL
     * never match itself, so notifications without a link would never de-duplicate. The
     * statement is plain text (no {@code <script>}), so the angle brackets are SQL rather
     * than XML - see MapperAnnotationSqlParseTest.
     *
     * @param since only rows created at or after this instant count
     * @return number of matching unread notifications, 0 when this one is new
     */
    @Select("SELECT COUNT(*) FROM notifications "
            + "WHERE recipient_user_id = #{userId} AND type = #{type} "
            + "AND is_read = 0 AND deleted = 0 AND created_at >= #{since} "
            + "AND link_type <=> #{linkType} AND link_id <=> #{linkId} "
            + "AND actor_type <=> #{actorType} AND actor_id <=> #{actorId}")
    int countRecentDuplicates(@Param("userId") Long userId,
                              @Param("type") String type,
                              @Param("linkType") String linkType,
                              @Param("linkId") Long linkId,
                              @Param("actorType") String actorType,
                              @Param("actorId") Long actorId,
                              @Param("since") LocalDateTime since);

    /**
     * Physically delete one batch of old, already-read notifications.
     *
     * A real DELETE rather than the soft delete the rest of the table uses: this is
     * retention, and a soft-deleted row occupies exactly as much space as a live one.
     *
     * Unread rows are never touched, whatever their age - an unread notification is the
     * one case where the user has demonstrably not seen it yet. Rows already soft-deleted
     * are included: they are read-and-dismissed by definition.
     *
     * LIMIT is what keeps the transaction short; the caller repeats until it returns 0.
     *
     * @return number of rows deleted by this batch
     */
    @Delete("DELETE FROM notifications "
            + "WHERE is_read = 1 AND created_at < #{cutoff} LIMIT #{limit}")
    int deleteReadOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
