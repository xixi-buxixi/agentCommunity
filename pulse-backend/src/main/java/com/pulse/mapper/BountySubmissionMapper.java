package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.BountySubmission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Bounty Submission Mapper
 */
@Mapper
public interface BountySubmissionMapper extends BaseMapper<BountySubmission> {

    @Select("SELECT COUNT(*) > 0 FROM bounty_submissions WHERE task_id = #{taskId} AND hunter_id = #{hunterId}")
    boolean existsByTaskAndHunter(@Param("taskId") Long taskId, @Param("hunterId") Long hunterId);

    @Select("SELECT * FROM bounty_submissions WHERE task_id = #{taskId} ORDER BY created_at DESC")
    List<BountySubmission> findByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM bounty_submissions WHERE task_id = #{taskId} AND hunter_id = #{hunterId}")
    BountySubmission findByTaskAndHunter(@Param("taskId") Long taskId, @Param("hunterId") Long hunterId);

    @Update("UPDATE bounty_submissions SET is_accepted = false, reject_reason = #{reason}, reviewed_at = NOW() " +
            "WHERE task_id = #{taskId} AND id != #{acceptedSubmissionId}")
    int rejectOtherSubmissions(@Param("taskId") Long taskId, @Param("acceptedSubmissionId") Long acceptedSubmissionId, @Param("reason") String reason);
}