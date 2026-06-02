package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.HotNewsReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * Mapper for daily hot news reports.
 */
@Mapper
public interface HotNewsReportMapper extends BaseMapper<HotNewsReport> {

    @Select("SELECT * FROM hot_news_reports WHERE report_date = #{reportDate} AND source = #{source} AND deleted = 0 LIMIT 1")
    HotNewsReport findByReportDateAndSource(@Param("reportDate") LocalDate reportDate,
                                            @Param("source") String source);

    @Select("SELECT * FROM hot_news_reports WHERE deleted = 0 ORDER BY COALESCE(published_at, created_at) DESC, report_date DESC, id DESC LIMIT 1")
    HotNewsReport findLatestReport();
}
