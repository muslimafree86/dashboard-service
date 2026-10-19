package com.muslimafree.dashboard.location;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Keeps the search-location index current: a frequent incremental pass (queued place changes, device locations going
 * stale) and a rare full pass as a safety net.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class LocationJobs {

    private final LocationIndexer indexer;

    public LocationJobs(LocationIndexer indexer) {
        this.indexer = indexer;
    }

    @Scheduled(fixedDelayString = "${app.location.sync-interval}", initialDelayString = "PT30S")
    @SchedulerLock(name = "dashboard-location-sync", lockAtLeastFor = "PT5S")
    public void sync() {
        indexer.applyQueuedChanges();
        indexer.expireStaleDevices();
    }

    @Scheduled(fixedDelayString = "${app.location.reconcile-interval}", initialDelayString = "PT1M")
    @SchedulerLock(name = "dashboard-location-reconcile", lockAtLeastFor = "PT1M")
    public void reconcile() {
        indexer.reconcileAll();
    }
}
