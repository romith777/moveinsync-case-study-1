package com.moveinsync.distance.pipeline;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.ReconstructedTrip;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripMilestone;
import com.moveinsync.distance.domain.model.TripStatus;
import com.moveinsync.distance.domain.model.TripWaypoint;
import com.moveinsync.distance.resilience.SimpleCircuitBreaker;
import com.moveinsync.distance.routing.GridSnappedRoutingCache;
import com.moveinsync.distance.routing.RoutingProvider;

import java.util.List;

/**
 * End-to-end pipeline orchestrator for trip distance reconstruction.
 * Pure POJO design: completely independent of web servers or databases for instant unit testing.
 */
public class DistanceReconstructionPipeline {

    private final GpsStreamCleaner streamCleaner;
    private final MilestoneAnchorService anchorService;
    private final SegmentDistanceEngine segmentEngine;

    public DistanceReconstructionPipeline() {
        this.streamCleaner = new GpsStreamCleaner();
        this.anchorService = new MilestoneAnchorService();
        this.segmentEngine = new SegmentDistanceEngine();
    }

    public ReconstructedTrip reconstruct(
            String tripId,
            String tenantId,
            List<GpsPing> rawPings,
            List<TripMilestone> milestones,
            GeoPoint plannedOffice,
            TripConfig config,
            RoutingProvider routingProvider,
            SimpleCircuitBreaker circuitBreaker
    ) {
        long startTime = System.currentTimeMillis();

        // Stage 1: Clean raw stream
        GpsStreamCleaner.CleanResult cleanResult = streamCleaner.clean(rawPings, config);

        // Stage 2: Merge anchors and waypoints
        List<TripWaypoint> waypoints = anchorService.mergeAnchors(
                cleanResult.cleanedPings(),
                milestones,
                plannedOffice,
                config
        );

        // Track routing metrics prior to evaluation
        int initialRoutingCalls = routingProvider.getDispatchedCallCount();
        int initialCacheHits = (routingProvider instanceof GridSnappedRoutingCache cache) ? cache.getCacheHits() : 0;

        // Stage 3: Segment distance evaluation
        SegmentDistanceEngine.SegmentEvaluationResult segmentResult = segmentEngine.evaluateSegments(
                waypoints,
                config,
                routingProvider,
                circuitBreaker
        );

        int routingCallsMade = routingProvider.getDispatchedCallCount() - initialRoutingCalls;
        int cacheHits = (routingProvider instanceof GridSnappedRoutingCache cache) ? (cache.getCacheHits() - initialCacheHits) : 0;
        long durationMs = System.currentTimeMillis() - startTime;

        TripStatus status = segmentResult.hasDegradedSegments() ? TripStatus.DEGRADED : TripStatus.FINAL;

        return new ReconstructedTrip(
                tripId,
                tenantId,
                config.ruleVersion(),
                segmentResult.totalDistanceKm(),
                segmentResult.segments(),
                status,
                cleanResult.rawCount(),
                cleanResult.cleanedPings().size(),
                cleanResult.droppedCount(),
                routingCallsMade,
                cacheHits,
                durationMs
        );
    }
}
