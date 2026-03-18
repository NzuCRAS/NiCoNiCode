package com.niconicode.agent.tracker.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.mapper.TrackedTechMapper;
import com.niconicode.agent.tracker.service.TrackerService;
import com.niconicode.agent.chat.service.TraceLogger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrackingScheduler {

    private final TrackedTechMapper techMapper;
    private final TrackerService trackerService;
    private final TraceLogger traceLogger;

    /**
     * P1-M fix: 调度线程与执行线程分离。
     * scheduleExecutor 只负责触发定时任务（单线程即可）；
     * workExecutor 是有界线程池，真正并发执行每个技术的 Multi-Agent 流水线。
     * 线程数上限 = 3：GitHub API rate limit 约 60 req/h（未认证），
     * 每次检测约 6 次调用，3 并发约 18 req/触发，留有余量。
     */
    private final ScheduledExecutorService scheduleExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tracker-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final ExecutorService workExecutor =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "tracker-worker-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> scheduledTask;
    private long intervalMinutes = 60; // 默认1小时

    /** 防止上一轮未跑完时重复触发批量检查 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        schedule();
        log.info("TrackingScheduler initialized with interval: {} minutes", intervalMinutes);
    }

    @PreDestroy
    public void shutdown() {
        scheduleExecutor.shutdown();
        workExecutor.shutdown();
    }

    private void schedule() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduledTask = scheduleExecutor.scheduleAtFixedRate(
                this::checkAllTechs, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    public void reschedule(long minutes) {
        if (minutes < 30) {
            throw new IllegalArgumentException("最小间隔为30分钟");
        }
        this.intervalMinutes = minutes;
        schedule();
        log.info("TrackingScheduler rescheduled to interval: {} minutes", minutes);
    }

    public long getIntervalMinutes() {
        return intervalMinutes;
    }

    /**
     * P1-L+M fix: 并发批量检查所有追踪技术。
     * - 用 AtomicBoolean 防止上一轮未完成时重复触发
     * - CompletableFuture.runAsync 提交到有界 workExecutor（最多3线程并发）
     * - allOf().join() 等待本轮全部完成后才释放 running 标志
     */
    public void checkAllTechs() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Previous tracking round is still running, skipping this trigger.");
            return;
        }

        log.info("Starting scheduled tech tracking check...");
        List<TrackedTech> techs = techMapper.selectList(
                new LambdaQueryWrapper<TrackedTech>().eq(TrackedTech::getStatus, "ACTIVE"));

        TraceLogger.TraceContext batchCtx = traceLogger.startTrace(-1L, 0L);
        traceLogger.trace(batchCtx, "BATCH_START", "techCount=" + techs.size());

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        // 为每个技术创建一个异步任务，提交到 workExecutor
        List<CompletableFuture<Void>> futures = techs.stream()
                .map(tech -> CompletableFuture.runAsync(() -> {
                    try {
                        trackerService.checkTechUpdate(tech.getId());
                        success.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        log.error("Failed to check tech: {}", tech.getName(), e);
                    }
                }, workExecutor))
                .toList();

        // 等待本轮全部完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, ex) -> {
                    traceLogger.trace(batchCtx, "BATCH_END",
                            "success=" + success.get() + ", failed=" + failed.get());
                    traceLogger.endTrace(batchCtx);
                    log.info("Scheduled tech tracking check completed: success={}, failed={}, total={}",
                            success.get(), failed.get(), techs.size());
                    running.set(false);
                });
    }

    /**
     * 供 AdminController 调用的手动触发入口，复用 workExecutor 而非裸 new Thread。
     * 直接委托给 checkAllTechs()，其内部已有防重复触发保护。
     */
    public void triggerManualCheck() {
        workExecutor.submit(this::checkAllTechs);
    }
}
