package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

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

    /**
     * Atomic points release and add (concurrency safe)
     * Releases frozen points and adds reward in single atomic operation
     *
     * @param id User ID
     * @param releaseAmount Amount to release from pending_bounty
     * @param addAmount Amount to add to points
     * @return Number of rows affected
     */
    @Update("UPDATE users SET points = points + #{addAmount}, " +
            "pending_bounty = pending_bounty - #{releaseAmount}, " +
            "updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0 " +
            "AND pending_bounty >= #{releaseAmount}")
    int releaseAndAddPointsAtomic(@Param("id") Long id,
                                   @Param("releaseAmount") BigDecimal releaseAmount,
                                   @Param("addAmount") BigDecimal addAmount);

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
     * Batch select users by IDs
     * Used for N+1 query optimization
     *
     * @param ids List of user IDs
     * @return List of users
     */
    List<User> selectByIds(@Param("ids") List<Long> ids);
}
