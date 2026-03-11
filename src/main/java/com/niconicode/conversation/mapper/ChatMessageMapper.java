package com.niconicode.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.niconicode.conversation.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
