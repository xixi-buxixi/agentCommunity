package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * User Mapper
 *
 * Provides CRUD operations for User entities.
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}