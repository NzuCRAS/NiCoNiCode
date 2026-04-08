package com.niconicode.agent.tracker.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义网络（知识图谱）响应：nodes + edges。
 *
 * 目标：让前端可用图结构（实体-关系）渲染，而不是树状分组。
 */
@Data
public class TrackerNetworkResp {

	private List<Node> nodes = new ArrayList<>();
	private List<Edge> edges = new ArrayList<>();

	@Data
	public static class Node {
		/**
		 * 全局唯一节点ID，建议使用前缀：
		 * - category:{name}
		 * - tech:{id}
		 * - report:{id}
		 */
		private String id;

		/** 节点类型：CATEGORY / TECH / REPORT / SOURCE（预留） */
		private String type;

		/** 展示用标签 */
		private String label;

		/** 可扩展属性（techIndex、publishedAt、trackingMode 等） */
		private Map<String, Object> props = new HashMap<>();
	}

	@Data
	public static class Edge {
		/** 全局唯一边ID */
		private String id;

		/** 起点节点ID */
		private String source;

		/** 终点节点ID */
		private String target;

		/** 关系类型：HAS_TECH / HAS_REPORT / MENTIONS / FROM_SOURCE（预留） */
		private String type;

		/** 权重（可用于线粗/吸引力），默认 1 */
		private double weight = 1.0;

		/** 可扩展属性（证据：reportId/sourceUrl 等） */
		private Map<String, Object> props = new HashMap<>();
	}
}

