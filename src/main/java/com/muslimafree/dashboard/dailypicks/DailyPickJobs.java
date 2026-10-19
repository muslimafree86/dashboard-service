package com.muslimafree.dashboard.dailypicks;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class DailyPickJobs {

    private final DailyPickService picks;

    public DailyPickJobs(DailyPickService picks) {
        this.picks = picks;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @SchedulerLock(name = "dashboard-daily-picks-cleanup", lockAtLeastFor = "PT1M")
    public void cleanUp() {
        picks.cleanUp();
    }
}
