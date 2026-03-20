package com.niconicode.agent.chat.service;

import com.niconicode.agent.chat.dto.SubTask;
import com.niconicode.agent.chat.dto.SubTask.SubTaskStatus;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskDagExecutor {

    private final RagService ragService;
    private final TraceLogger traceLogger;

    private static final int MAX_SUB_TASKS = 5;
    private static final long TOTAL_TIMEOUT_MS = 8_000;
    private static final long SINGLE_RAG_TIMEOUT_MS = 3_000;

    public DagExecutionResult execute(List<SubTask> tasks, TraceLogger.TraceContext traceCtx) {
        long startTime = System.currentTimeMillis();

        if (tasks == null || tasks.isEmpty()) {
            return emptyResult();
        }

        // 截断过多子任务
        if (tasks.size() > MAX_SUB_TASKS) {
            traceLogger.trace(traceCtx, "DAG_EXEC", "truncated from " + tasks.size() + " to " + MAX_SUB_TASKS);
            tasks = tasks.subList(0, MAX_SUB_TASKS);
        }

        // 构建 ID → SubTask 索引
        Map<String, SubTask> taskMap = new LinkedHashMap<>();
        for (SubTask t : tasks) {
            taskMap.put(t.getId(), t);
        }

        // 构建入度表
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        // 后继节点表
        Map<String, List<String>> successors = new HashMap<>();

        for (SubTask t : tasks) {
            inDegree.putIfAbsent(t.getId(), 0);
            successors.putIfAbsent(t.getId(), new ArrayList<>());

            if (t.getDependsOn() != null) {
                for (String dep : t.getDependsOn()) {
                    if (taskMap.containsKey(dep)) {
                        inDegree.merge(t.getId(), 1, Integer::sum);
                        successors.computeIfAbsent(dep, k -> new ArrayList<>()).add(t.getId());
                    }
                }
            }
        }

        // 环检测：拓扑排序后如果处理节点数 < 总数，说明有环
        if (hasCycle(tasks, inDegree, successors)) {
            traceLogger.trace(traceCtx, "DAG_EXEC", "cycle detected, fallback to linear execution");
            return executeLinear(tasks, traceCtx, startTime);
        }

        // 按波次执行
        Map<String, Integer> mutableInDegree = new LinkedHashMap<>(inDegree);
        int wave = 0;

        while (!mutableInDegree.isEmpty()) {
            // 超时检查
            if (System.currentTimeMillis() - startTime > TOTAL_TIMEOUT_MS) {
                traceLogger.trace(traceCtx, "DAG_EXEC", "total timeout exceeded at wave " + wave);
                break;
            }

            // 找出所有入度为 0 的节点
            List<String> ready = mutableInDegree.entrySet().stream()
                    .filter(e -> e.getValue() == 0)
                    .map(Map.Entry::getKey)
                    .toList();

            if (ready.isEmpty()) {
                // 不应发生（已做环检测），兜底退出
                traceLogger.trace(traceCtx, "DAG_EXEC", "no ready tasks but graph not empty, breaking");
                break;
            }

            final int currentWave = wave;
            traceLogger.trace(traceCtx, "DAG_WAVE", "wave=" + currentWave + ", tasks=" + ready);

            // 并行执行当前波次所有节点
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String taskId : ready) {
                SubTask task = taskMap.get(taskId);
                task.setStatus(SubTaskStatus.RUNNING);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    long taskStart = System.currentTimeMillis();
                    try {
                        String intentName = task.getIntent() != null ? task.getIntent().name() : null;
                        String ragResult = ragService.retrieveContext(task.getQuery(), intentName);
                        task.setRagContext(ragResult);
                        task.setStatus(SubTaskStatus.DONE);
                        int duration = (int) (System.currentTimeMillis() - taskStart);
                        traceLogger.trace(traceCtx, "DAG_TASK_DONE",
                                "id=" + taskId + ", duration=" + duration + "ms" +
                                        ", ragChars=" + (ragResult != null ? ragResult.length() : 0));
                    } catch (Exception e) {
                        task.setStatus(SubTaskStatus.FAILED);
                        int duration = (int) (System.currentTimeMillis() - taskStart);
                        log.warn("DAG subtask {} failed after {}ms: {}", taskId, duration, e.getMessage());
                        traceLogger.trace(traceCtx, "DAG_TASK_FAIL",
                                "id=" + taskId + ", duration=" + duration + "ms, error=" + e.getMessage());
                    }
                });

                futures.add(future);
            }

            // 等待当前波次完成（单个 RAG 超时 + 缓冲）
            try {
                long remaining = TOTAL_TIMEOUT_MS - (System.currentTimeMillis() - startTime);
                long waitMs = Math.min(remaining, SINGLE_RAG_TIMEOUT_MS + 500);
                if (waitMs > 0) {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(waitMs, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                traceLogger.trace(traceCtx, "DAG_WAVE", "wave=" + currentWave + " timeout/error: " + e.getMessage());
            }

            // 更新入度：移除已完成节点，减少后继节点入度
            for (String taskId : ready) {
                mutableInDegree.remove(taskId);
                List<String> succs = successors.getOrDefault(taskId, Collections.emptyList());
                for (String succ : succs) {
                    mutableInDegree.computeIfPresent(succ, (k, v) -> v - 1);
                }
            }

            wave++;
        }

        // 合并所有子任务的 RAG 结果
        String merged = mergeRagContexts(tasks);

        long totalDuration = System.currentTimeMillis() - startTime;
        traceLogger.traceQueryDag(traceCtx, tasks.size(), wave,
                (int) tasks.stream().filter(t -> t.getStatus() == SubTaskStatus.DONE).count(),
                (int) totalDuration);

        DagExecutionResult result = new DagExecutionResult();
        result.setMergedRagContext(merged);
        result.setCompletedTasks(tasks);
        result.setTotalDurationMs(totalDuration);
        return result;
    }

    private boolean hasCycle(List<SubTask> tasks, Map<String, Integer> inDegree,
                             Map<String, List<String>> successors) {
        Map<String, Integer> tempDegree = new LinkedHashMap<>(inDegree);
        int processed = 0;

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> e : tempDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        while (!queue.isEmpty()) {
            String node = queue.poll();
            processed++;
            for (String succ : successors.getOrDefault(node, Collections.emptyList())) {
                int newDeg = tempDegree.merge(succ, -1, Integer::sum);
                if (newDeg == 0) queue.add(succ);
            }
        }

        return processed < tasks.size();
    }

    private DagExecutionResult executeLinear(List<SubTask> tasks, TraceLogger.TraceContext traceCtx, long startTime) {
        for (SubTask task : tasks) {
            if (System.currentTimeMillis() - startTime > TOTAL_TIMEOUT_MS) break;

            task.setStatus(SubTaskStatus.RUNNING);
            try {
                String intentName = task.getIntent() != null ? task.getIntent().name() : null;
                task.setRagContext(ragService.retrieveContext(task.getQuery(), intentName));
                task.setStatus(SubTaskStatus.DONE);
            } catch (Exception e) {
                task.setStatus(SubTaskStatus.FAILED);
                log.warn("Linear subtask {} failed: {}", task.getId(), e.getMessage());
            }
        }

        DagExecutionResult result = new DagExecutionResult();
        result.setMergedRagContext(mergeRagContexts(tasks));
        result.setCompletedTasks(tasks);
        result.setTotalDurationMs(System.currentTimeMillis() - startTime);
        return result;
    }

    private String mergeRagContexts(List<SubTask> tasks) {
        StringBuilder merged = new StringBuilder();
        for (SubTask task : tasks) {
            if (task.getRagContext() != null && !task.getRagContext().isBlank()) {
                if (!merged.isEmpty()) {
                    merged.append("\n\n");
                }
                merged.append("【").append(task.getQuery()).append("】\n");
                merged.append(task.getRagContext());
            }
        }
        return merged.toString();
    }

    private DagExecutionResult emptyResult() {
        DagExecutionResult r = new DagExecutionResult();
        r.setMergedRagContext("");
        r.setCompletedTasks(Collections.emptyList());
        r.setTotalDurationMs(0);
        return r;
    }

    @Data
    public static class DagExecutionResult {
        private String mergedRagContext;
        private List<SubTask> completedTasks;
        private long totalDurationMs;
    }
}
