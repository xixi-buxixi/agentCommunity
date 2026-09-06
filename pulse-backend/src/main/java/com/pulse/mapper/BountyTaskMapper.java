package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.entity.BountyTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bounty Task Mapper
 */
@Mapper
public interface BountyTaskMapper extends BaseMapper<BountyTask> {

    @Update("UPDATE bounty_tasks SET accepted_count = accepted_count + 1 WHERE id = #{taskId} AND deleted = 0")
    int incrementAcceptedCount(@Param("taskId") Long taskId);

    @Update("UPDATE bounty_tasks SET submission_count = submission_count + 1 WHERE id = #{taskId} AND deleted = 0")
    int incrementSubmissionCount(@Param("taskId") Long taskId);

    @Update("UPDATE bounty_tasks SET status = #{status} WHERE id = #{taskId} AND deleted = 0")
    int updateStatus(@Param("taskId") Long taskId, @Param("status") Integer status);

    /**
     * Compare-and-set the task status.
     *
     * Every state transition that moves money (settle, cancel, expire) must go
     * through this method: it is the single point that makes the transition
     * happen at most once, so frozen points can never be released twice.
     *
     * @return 1 when this call performed the transition, 0 when another
     *         caller already moved the task out of the expected states
     */
    @Update("<script>" +
            "UPDATE bounty_tasks SET status = #{newStatus} " +
            "WHERE id = #{taskId} AND deleted = 0 AND status IN " +
            "<foreach item='expected' collection='expectedStatuses' open='(' separator=',' close=')'>" +
            "#{expected}</foreach>" +
            "</script>")
    int updateStatusIfIn(@Param("taskId") Long taskId,
                         @Param("newStatus") Integer newStatus,
                         @Param("expectedStatuses") List<Integer> expectedStatuses);

    @Select("SELECT COUNT(*) FROM bounty_tasks " +
            "WHERE agent_id = #{agentId} AND created_at >= #{startTime} AND deleted = 0")
    int countByAgentIdSince(@Param("agentId") Long agentId, @Param("startTime") LocalDateTime startTime);

    /**
     * Bounties this agent published that are in one given status.
     *
     * Backs the public profile's completed_bounty_count, called with
     * {@link com.pulse.enums.BountyStatus#COMPLETED} (2) - the status the audit path
     * sets in the same transaction that settles the frozen reward, so a COMPLETED row
     * is exactly a bounty that finished and paid out. The other terminal statuses
     * (ABANDONED, EXPIRED, CANCELLED) are not settlements and are not counted.
     *
     * agent_id is NULL for a human-published bounty, so a caller passing a null agent
     * id gets 0 rather than every human bounty on the platform - {@code = NULL} matches
     * nothing in SQL. There is no window: this is a lifetime total.
     *
     * @param agentId publisher agent ID
     * @param status  bounty status code
     * @return number of live bounty_tasks rows matching both
     */
    @Select("SELECT COUNT(*) FROM bounty_tasks " +
            "WHERE agent_id = #{agentId} AND status = #{status} AND deleted = 0")
    int countByAgentIdAndStatus(@Param("agentId") Long agentId, @Param("status") Integer status);
}
