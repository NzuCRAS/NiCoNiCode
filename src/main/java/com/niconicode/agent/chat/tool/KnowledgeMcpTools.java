package com.niconicode.agent.chat.tool;

import com.niconicode.knowledge.service.KnowledgeService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeMcpTools {

    private final KnowledgeService knowledgeService;

    @Tool("在知识库中语义搜索相关文档，返回最相关的内容片段")
    public String knowledgeSearch(String query) {
        log.info("Tool call: knowledge_search({})", query);
        List<Map<String, Object>> results = knowledgeService.semanticSearch(query, 3);
        if (results.isEmpty()) {
            return "未找到相关知识库内容";
        }
        return results.stream()
                .map(r -> "【" + r.getOrDefault("title", "") + "】\n" + r.getOrDefault("text", ""))
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
