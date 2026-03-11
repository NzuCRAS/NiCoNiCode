package com.niconicode.agent.tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.niconicode.agent.tracker.entity.TrackedTech;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TrackedTechMapper extends BaseMapper<TrackedTech> {
}
