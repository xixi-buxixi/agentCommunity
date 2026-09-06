package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.Notification;
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
     * Mark every unread notification of one user read.
     *
     * @return number of rows actually flipped
     */
    @Update("UPDATE notifications SET is_read = 1, read_at = #{now} "
            + "WHERE recipient_user_id = #{userId} AND is_read = 0 AND deleted = 0")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
