package com.niconicode.agent.chat.graph;

/**
 * 函数式节点接口 — 状态图的基本执行单元
 */
@FunctionalInterface
public interface Node<S> {
    S execute(S state);
}
