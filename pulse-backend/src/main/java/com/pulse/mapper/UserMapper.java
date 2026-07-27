package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * User Mapper
 *
 * Provides CRUD operations and atomic points update for User entities.
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * Atomic points freeze (concurrency safe)
     * Adds to pending_bounty without changing total points.
     * Only succeeds if available balance (points - pending_bounty) >= amount
     *
     * @param id User ID
     * @param amount Amount to freeze
     * @return Number of rows affected (0 if insufficient balance)
     */
    @Update("UPDATE users SET pending_bounty = pending_bounty + #{amount}, " +
            "updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0 " +
            "AND (points - pending_bounty) >= #{amount}")
    int deductAndFreezePointsAtomic(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * Atomic frozen points settlement (concurrency safe)
     * Deducts the frozen amount from total points and releases the freeze.
     *
     * @param id User ID
     * @param amount Amount to settle
     * @return Number of rows affected (0 if pending_bounty < amount or points < amount)
     */
    @Update("UPDATE users SET points = points - #{amount}, " +
            "pending_bounty = pending_bounty - #{amount}, " +
            "updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0 " +
            "AND pending_bounty >= #{amount} AND points >= #{amount}")
    int settleFrozenPointsAtomic(@Param("id") Long id, @Param("amount") BigDecimal amount);

    // NOTE: releaseAndAddPointsAtomic was removed here. It had no callers, and
    // selectByIds was removed too - that one declared a batch select with neither
    // an XML statement nor an annotation, so the first call would have thrown
    // BindingException at runtime.

    /**
     * Atomic frozen points release (concurrency safe)
     * Releases frozen points back to available balance without changing total points.
     *
     * @param id User ID
     * @param amount Amount to refund
     * @return Number of rows affected (0 if pending_bounty < amount)
     */
    @Update("UPDATE users SET pending_bounty = pending_bounty - #{amount}, " +
            "updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0 " +
            "AND pending_bounty >= #{amount}")
    int refundPointsAtomic(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * Atomic points addition (concurrency safe)
     * Adds points without touching pending_bounty
     *
     * @param id User ID
     * @param amount Amount to add
     * @return Number of rows affected
     */
    @Update("UPDATE users SET points = points + #{amount}, " +
            "updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int addPointsAtomic(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * Atomic spend of available points (concurrency safe).
     * Used for direct transfers such as tipping, where points leave the account
     * immediately instead of being frozen first. The freeze reserved for bounties
     * (pending_bounty) is respected, so a tip can never spend frozen points.
     *
     * @param id User ID
     * @param amount Amount to spend
     * @return Number of rows affected (0 if available balance is insufficient)
     */
    @Update("UPDATE users SET points = points - #{amount}, " +
            "updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0 " +
            "AND (points - pending_bounty) >= #{amount}")
    int deductAvailablePointsAtomic(@Param("id") Long id, @Param("amount") BigDecimal amount);

}
