package com.niconicode.agent.tracker.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.mapper.TrackedTechMapper;
import com.niconicode.agent.tracker.service.TrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrackingScheduler {

    private final TrackedTechMapper techMapper;
    private final TrackerService trackerService;

    @Scheduled(fixedRate = 6 * 60 * 60 * 1000) // 每6小时
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
