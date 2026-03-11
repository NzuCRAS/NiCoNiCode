package com.niconicode.agent.chat.service;

import com.niconicode.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final KnowledgeService knowledgeService;

    public String retrieveContext(String query) {
        // 双通道检索: 语义搜索
        List<Map<String, Object>> results = knowledgeService.semanticSearch(query, 3);
        if (results.isEmpty()) {
            return "";
        }
        return results.stream()
                .map(r -> "【" + r.getOrDefault("title", "知识库") + "】\n" + r.getOrDefault("text", ""))
                .collect(Collectors.joining("\n\n"));
    }
}
