package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.entity.BountyTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}