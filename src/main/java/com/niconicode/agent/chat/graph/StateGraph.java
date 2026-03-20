package com.niconicode.agent.chat.graph;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用状态图执行器 — 参照 LangGraph 理念，用图+状态流转编排管道。
 * 零外部依赖，约 80 行核心逻辑。
 */
@Slf4j
public class StateGraph<S> {

    public static final String END = "END";
    private static final int MAX_STEPS = 20;

    private final Map<String, Node<S>> nodes = new LinkedHashMap<>();
    private final List<Edge<S>> edges = new ArrayList<>();
    private String entryNode;

    public StateGraph<S> addNode(String name, Node<S> node) {
        nodes.put(name, node);
        return this;
    }

    public StateGraph<S> addEdge(String from, String to) {
        edges.add(new Edge<>(from, to, null));
        return this;
    }

    public StateGraph<S> addConditionalEdge(String from, String to, java.util.function.Predicate<S> condition) {
        edges.add(new Edge<>(from, to, condition));
        return this;
    }

    public StateGraph<S> setEntry(String entry) {
        this.entryNode = entry;
        return this;
    }

    /**
     * 从 entry 开始，沿边流转直到 END 或无匹配边。
     * MAX_STEPS 安全护栏防止无限循环。
     */
    public S execute(S state) {
        if (entryNode == null) {
            throw new IllegalStateException("Entry node not set");
        }

        String current = entryNode;
        for (int step = 0; step < MAX_STEPS; step++) {
            if (END.equals(current)) {
                break;
            }

            Node<S> node = nodes.get(current);
            if (node == null) {
                log.warn("[StateGraph] Node '{}' not found, stopping", current);
                break;
            }

            log.debug("[StateGraph] Executing node '{}'", current);
            try {
                state = node.execute(state);
            } catch (Exception e) {
                log.error("[StateGraph] Node '{}' failed: {}", current, e.getMessage(), e);
                break;
            }

            // 查找下一个节点：优先匹配条件边，无条件边作为默认
            String next = findNextNode(current, state);
            if (next == null) {
                log.debug("[StateGraph] No outgoing edge from '{}', stopping", current);
                break;
            }
            current = next;
        }

        return state;
    }

    private String findNextNode(String from, S state) {
        String unconditionalNext = null;
        for (Edge<S> edge : edges) {
            if (!edge.getFrom().equals(from)) continue;

            if (edge.getCondition() != null) {
                // 条件边：条件为 true 则立即采用
                if (edge.getCondition().test(state)) {
                    return edge.getTo();
                }
            } else {
                // 无条件边：记录，作为兜底
                unconditionalNext = edge.getTo();
            }
        }
        return unconditionalNext;
    }
}
