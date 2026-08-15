package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.Setting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统设置 Mapper.
 */
@Mapper
public interface SettingMapper extends BaseMapper<Setting> {
}
