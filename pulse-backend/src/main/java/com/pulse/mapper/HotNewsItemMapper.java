package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.HotNewsItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for daily hot news items.
 */
@Mapper
public interface HotNewsItemMapper extends BaseMapper<HotNewsItem> {

    @Delete("DELETE FROM hot_news_items WHERE report_id = #{reportId}")
    int deleteByReportId(@Param("reportId") Long reportId);

    @Select("SELECT * FROM hot_news_items WHERE report_id = #{reportId} ORDER BY section_order ASC, IFNULL(rank_no, 999999) ASC, id ASC")
    List<HotNewsItem> findByReportId(@Param("reportId") Long reportId);
}
