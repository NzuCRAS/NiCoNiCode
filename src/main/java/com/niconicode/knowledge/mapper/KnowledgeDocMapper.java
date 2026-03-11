package com.niconicode.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.niconicode.knowledge.entity.KnowledgeDoc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDoc> {

    @Select("SELECT * FROM knowledge_doc WHERE status='ACTIVE' AND MATCH(title, content) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) LIMIT #{limit}")
    List<KnowledgeDoc> fullTextSearch(@Param("keyword") String keyword, @Param("limit") int limit);
}
