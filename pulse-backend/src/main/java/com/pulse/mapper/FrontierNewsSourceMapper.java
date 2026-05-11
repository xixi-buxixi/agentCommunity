package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.FrontierNewsSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FrontierNewsSourceMapper extends BaseMapper<FrontierNewsSource> {

    @Select("SELECT COUNT(*) > 0 FROM frontier_news_sources WHERE source_url = #{sourceUrl} AND deleted = 0")
    boolean existsBySourceUrl(@Param("sourceUrl") String sourceUrl);
}
