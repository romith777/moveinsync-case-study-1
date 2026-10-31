package com.moveinsync.distance.resilience;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripMilestone;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory buffer queue of trips processed in DEGRADED status awaiting provider recovery.
 */
public class DegradedTripRecomputeQueue {

    public record DegradedTripTask(
            String tripId,
            String tenantId,
            List<GpsPing> rawPings,
            List<TripMilestone> milestones,
            GeoPoint plannedOffice,
            TripConfig config
    ) {}

    private final ConcurrentLinkedQueue<DegradedTripTask> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(DegradedTripTask task) {
        queue.offer(task);
    }

    public DegradedTripTask poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
