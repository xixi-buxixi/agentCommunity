package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.BountyAcceptance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Bounty Acceptance Mapper
 */
@Mapper
public interface BountyAcceptanceMapper extends BaseMapper<BountyAcceptance> {

    @Select("SELECT * FROM bounty_acceptances WHERE task_id = #{taskId} AND hunter_id = #{hunterId} AND deleted = 0")
    BountyAcceptance findByTaskAndHunter(@Param("taskId") Long taskId, @Param("hunterId") Long hunterId);

    @Select("SELECT COUNT(*) FROM bounty_acceptances WHERE task_id = #{taskId} AND hunter_id != #{hunterId} AND deleted = 0")
    int countOtherAcceptances(@Param("taskId") Long taskId, @Param("hunterId") Long hunterId);

    @Update("UPDATE bounty_acceptances SET status = #{status}, submitted_at = NOW() WHERE task_id = #{taskId} AND hunter_id = #{hunterId} AND deleted = 0")
    int updateStatus(@Param("taskId") Long taskId, @Param("hunterId") Long hunterId, @Param("status") String status);

    @Select("SELECT * FROM bounty_acceptances WHERE hunter_id = #{hunterId} AND deleted = 0 ORDER BY accepted_at DESC")
    List<BountyAcceptance> findByHunterId(@Param("hunterId") Long hunterId);
}