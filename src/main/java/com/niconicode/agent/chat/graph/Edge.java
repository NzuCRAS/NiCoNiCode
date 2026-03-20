package com.niconicode.agent.chat.graph;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.function.Predicate;

/**
 * 边 — 连接两个节点，可带条件谓词
 */
@Data
@AllArgsConstructor
public class Edge<S> {
    private String from;
    private String to;
    private Predicate<S> condition; // null = 无条件边
}
