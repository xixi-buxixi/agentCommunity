package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}