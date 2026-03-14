package com.niconicode.agent.tracker.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.mapper.TrackedTechMapper;
import com.niconicode.agent.tracker.service.TrackerService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrackingScheduler {

    private final TrackedTechMapper techMapper;
    private final TrackerService trackerService;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> scheduledTask;
    private long intervalMinutes = 360; // 默认6小时

    @PostConstruct
    public void init() {
        schedule();
        log.info("TrackingScheduler initialized with interval: {} minutes", intervalMinutes);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    private void schedule() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduledTask = executor.scheduleAtFixedRate(
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

    public void checkAllTechs() {
        log.info("Starting scheduled tech tracking check...");
        List<TrackedTech> techs = techMapper.selectList(
                new LambdaQueryWrapper<TrackedTech>().eq(TrackedTech::getStatus, "ACTIVE"));

        for (TrackedTech tech : techs) {
            try {
                trackerService.checkTechUpdate(tech.getId());
            } catch (Exception e) {
                log.error("Failed to check tech: {}", tech.getName(), e);
            }
        }
        log.info("Scheduled tech tracking check completed for {} techs", techs.size());
    }
}
