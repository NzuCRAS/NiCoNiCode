package com.niconicode.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.niconicode.conversation.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
