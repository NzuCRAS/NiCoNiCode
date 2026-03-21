package com.niconicode.agent.chat.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ToolExecutionService {

    private final List<Object> toolObjects;
    private final ToolCacheService toolCacheService;
    private final List<ToolSpecification> toolSpecifications = new ArrayList<>();
    private final Map<String, ToolExecutor> toolExecutors = new HashMap<>();

    public ToolExecutionService(
            com.niconicode.agent.chat.tool.KnowledgeMcpTools knowledgeMcpTools,
            com.niconicode.agent.chat.tool.TechTrackerTools techTrackerTools,
            ToolCacheService toolCacheService) {
        this.toolObjects = List.of(knowledgeMcpTools, techTrackerTools);
        this.toolCacheService = toolCacheService;
    }

    @PostConstruct
    public void init() {
        for (Object toolObject : toolObjects) {
            registerTools(toolObject);
        }
        log.info("Registered {} tool specifications: {}", toolSpecifications.size(),
                toolSpecifications.stream().map(ToolSpecification::name).toList());
    }

    private void registerTools(Object toolObject) {
        for (Method method : toolObject.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                toolSpecifications.add(spec);
                toolExecutors.put(spec.name(), new DefaultToolExecutor(toolObject, method));
                log.debug("Registered tool: {} from {}", spec.name(), toolObject.getClass().getSimpleName());
            }
        }
    }

    public List<ToolSpecification> getToolSpecifications() {
        return toolSpecifications;
    }

    public String executeTool(ToolExecutionRequest request) {
        String toolName = request.name();
        ToolExecutor executor = toolExecutors.get(toolName);
        if (executor == null) {
            log.warn("Unknown tool requested: {}", toolName);
            return "错误: 未知的工具「" + toolName + "」";
        }

        // 缓存检查
        String cached = toolCacheService.getCached(toolName, request.arguments());
        if (cached != null) {
            log.info("Tool {} cache HIT, returning cached result", toolName);
            return cached;
        }

        try {
            log.info("Executing tool: {} with args: {}", toolName, request.arguments());
            String result = executor.execute(request, null);
            log.debug("Tool {} result length: {}", toolName, result != null ? result.length() : 0);

            // 缓存结果
            toolCacheService.put(toolName, request.arguments(), result);

            return result;
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolName, e);
            return "工具执行出错: " + e.getMessage();
        }
    }
}
