package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.dto.AgentTipTotals;
import com.pulse.entity.SysLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * System Ledger Mapper
 */
@Mapper
public interface SysLedgerMapper extends BaseMapper<SysLedger> {

    @Select("SELECT * FROM sys_ledger WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    List<SysLedger> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT * FROM sys_ledger WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<SysLedger> findByUserId(@Param("userId") Long userId);

    /**
     * Tips received on behalf of one agent: how many and how much.
     *
     * The receiving row is the one LedgerServiceImpl.tipAgent writes for the agent's
     * owner - type TIP_RECV, related_type 'AGENT', related_id the agent id - so the
     * agent is identified by related_id, never by user_id. Counting by user_id would
     * attribute an owner's tips to every agent they own.
     *
     * TIP_SEND rows carry the same related pair but a negative amount and belong to the
     * tipper, so the type filter is what keeps the total from cancelling itself out.
     * BOUNTY_* rows written by PointsService always carry related_type 'BOUNTY' and are
     * therefore out of scope here by construction.
     *
     * COALESCE keeps the total at 0 rather than null when an agent has never been
     * tipped.
     *
     * The {@code amount > 0} clause matches AgentRankingMapper#findTopByTipsReceived
     * exactly. Today tipAgent only ever writes positive TIP_RECV rows, so the two
     * agree either way; the day a refund or a reversal writes a negative TIP_RECV,
     * the profile total and the tipped leaderboard would otherwise start answering
     * two different questions about the same agent.
     *
     * @param agentId Agent ID
     * @return Count and summed amount of TIP_RECV rows pointing at this agent
     */
    @Select("SELECT COUNT(*) AS tipCount, COALESCE(SUM(amount), 0) AS tipTotal " +
            "FROM sys_ledger " +
            "WHERE type = 'TIP_RECV' AND related_type = 'AGENT' AND related_id = #{agentId} " +
            "AND amount > 0")
    AgentTipTotals findAgentTipTotals(@Param("agentId") Long agentId);
}