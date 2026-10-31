package com.moveinsync.distance.service;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.InvoiceDelta;
import com.moveinsync.distance.domain.model.ReconstructedTrip;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripMilestone;
import com.moveinsync.distance.domain.model.TripStatus;
import com.moveinsync.distance.pipeline.DistanceReconstructionPipeline;
import com.moveinsync.distance.resilience.DegradedTripRecomputeQueue;
import com.moveinsync.distance.resilience.SimpleCircuitBreaker;
import com.moveinsync.distance.routing.RoutingProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core application service orchestrating trip distance reconstruction, resilience queue recovery,
 * idempotency verification, and late-ping financial ledger adjustments.
 */
@Service
public class TripReconstructionService {

    private final DistanceReconstructionPipeline pipeline;
    private final RoutingProvider routingProvider;
    private final SimpleCircuitBreaker circuitBreaker;
    private final DegradedTripRecomputeQueue degradedQueue;

    // In-memory repositories for state management
    private final Map<String, ReconstructedTrip> tripStore = new ConcurrentHashMap<>();
    private final Map<String, List<GpsPing>> tripPingsStore = new ConcurrentHashMap<>();
    private final Map<String, List<TripMilestone>> tripMilestonesStore = new ConcurrentHashMap<>();
    private final Map<String, GeoPoint> tripOfficeStore = new ConcurrentHashMap<>();
    private final Map<String, TripConfig> tripConfigStore = new ConcurrentHashMap<>();
    private final Set<String> billedTrips = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final List<InvoiceDelta> invoiceDeltas = Collections.synchronizedList(new ArrayList<>());

    public TripReconstructionService(
            DistanceReconstructionPipeline pipeline,
            RoutingProvider routingProvider,
            SimpleCircuitBreaker circuitBreaker,
            DegradedTripRecomputeQueue degradedQueue
    ) {
        this.pipeline = pipeline;
        this.routingProvider = routingProvider;
        this.circuitBreaker = circuitBreaker;
        this.degradedQueue = degradedQueue;
    }

    public ReconstructedTrip reconstruct(
            String tripId,
            String tenantId,
            List<GpsPing> rawPings,
            List<TripMilestone> milestones,
            GeoPoint plannedOffice,
            TripConfig config
    ) {
        // Idempotency: If exact same trip with same rule version exists, return it
        ReconstructedTrip existing = tripStore.get(tripId);
        if (existing != null && existing.ruleVersion().equals(config.ruleVersion()) &&
                existing.rawPingsCount() == (rawPings != null ? rawPings.size() : 0)) {
            return existing;
        }

        ReconstructedTrip result = pipeline.reconstruct(
                tripId,
                tenantId,
                rawPings,
                milestones,
                plannedOffice,
                config,
                routingProvider,
                circuitBreaker
        );

        tripStore.put(tripId, result);
        tripPingsStore.put(tripId, new ArrayList<>(rawPings != null ? rawPings : List.of()));
        tripMilestonesStore.put(tripId, new ArrayList<>(milestones != null ? milestones : List.of()));
        if (plannedOffice != null) {
            tripOfficeStore.put(tripId, plannedOffice);
        }
        tripConfigStore.put(tripId, config);

        // If degraded, enqueue for automatic recomputation upon recovery
        if (result.status() == TripStatus.DEGRADED) {
            degradedQueue.enqueue(new DegradedTripRecomputeQueue.DegradedTripTask(
                    tripId,
                    tenantId,
                    rawPings,
                    milestones,
                    plannedOffice,
                    config
            ));
        }

        return result;
    }

    public void markTripBilled(String tripId) {
        billedTrips.add(tripId);
    }

    public boolean isTripBilled(String tripId) {
        return billedTrips.contains(tripId);
    }

    public InvoiceDelta ingestLatePings(String tripId, List<GpsPing> latePings) {
        ReconstructedTrip existing = tripStore.get(tripId);
        if (existing == null) {
            throw new IllegalArgumentException("Trip not found: " + tripId);
        }

        List<GpsPing> accumulated = tripPingsStore.getOrDefault(tripId, new ArrayList<>());
        accumulated.addAll(latePings);

        List<TripMilestone> milestones = tripMilestonesStore.getOrDefault(tripId, List.of());
        GeoPoint office = tripOfficeStore.get(tripId);
        TripConfig config = tripConfigStore.getOrDefault(tripId, TripConfig.defaultConfig());

        ReconstructedTrip recomputed = pipeline.reconstruct(
                tripId,
                existing.tenantId(),
                accumulated,
                milestones,
                office,
                config,
                routingProvider,
                circuitBreaker
        );

        if (isTripBilled(tripId)) {
            // Never silently alter closed invoice; emit signed delta
            InvoiceDelta delta = InvoiceDelta.compute(
                    tripId,
                    existing.tenantId(),
                    existing.totalBillableDistanceKm(),
                    recomputed.totalBillableDistanceKm(),
                    config.ruleVersion(),
                    "Late arrival of " + latePings.size() + " GPS telemetry pings"
            );
            invoiceDeltas.add(delta);
            return delta;
        } else {
            // Trip not billed yet: update stored trip
            ReconstructedTrip updated = new ReconstructedTrip(
                    tripId,
                    recomputed.tenantId(),
                    recomputed.ruleVersion(),
                    recomputed.totalBillableDistanceKm(),
                    recomputed.segments(),
                    TripStatus.RECOMPUTED,
                    recomputed.rawPingsCount(),
                    recomputed.cleanedPingsCount(),
                    recomputed.droppedPingsCount(),
                    recomputed.routingCallsMade(),
                    recomputed.cacheHits(),
                    recomputed.processingTimeMs()
            );
            tripStore.put(tripId, updated);
            return InvoiceDelta.compute(tripId, existing.tenantId(), existing.totalBillableDistanceKm(), recomputed.totalBillableDistanceKm(), config.ruleVersion(), "Pre-billing update");
        }
    }

    public int processDegradedQueue() {
        if (circuitBreaker.getState() != SimpleCircuitBreaker.State.CLOSED) {
            return 0; // Provider not recovered yet
        }

        int recomputedCount = 0;
        while (!degradedQueue.isEmpty()) {
            DegradedTripRecomputeQueue.DegradedTripTask task = degradedQueue.poll();
            if (task == null) break;

            ReconstructedTrip healthy = pipeline.reconstruct(
                    task.tripId(),
                    task.tenantId(),
                    task.rawPings(),
                    task.milestones(),
                    task.plannedOffice(),
                    task.config(),
                    routingProvider,
                    circuitBreaker
            );

            tripStore.put(task.tripId(), healthy);
            recomputedCount++;
        }
        return recomputedCount;
    }

    public ReconstructedTrip getTrip(String tripId) {
        return tripStore.get(tripId);
    }

    public List<InvoiceDelta> getInvoiceDeltas() {
        return new ArrayList<>(invoiceDeltas);
    }
}
