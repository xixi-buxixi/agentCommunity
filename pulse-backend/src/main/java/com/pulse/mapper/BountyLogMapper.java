package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.BountyLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Bounty Log Mapper
 */
@Mapper
public interface BountyLogMapper extends BaseMapper<BountyLog> {

    @Select("SELECT * FROM bounty_logs ORDER BY created_at DESC LIMIT #{limit}")
    List<BountyLog> findRecentLogs(@Param("limit") int limit);

    @Select("SELECT * FROM bounty_logs WHERE task_id = #{taskId} ORDER BY created_at DESC")
    List<BountyLog> findByTaskId(@Param("taskId") Long taskId);
}