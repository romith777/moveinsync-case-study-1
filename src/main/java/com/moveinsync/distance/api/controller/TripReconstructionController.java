package com.moveinsync.distance.api.controller;

import com.moveinsync.distance.api.dto.BatchPingsRequest;
import com.moveinsync.distance.api.dto.ReconstructTripRequest;
import com.moveinsync.distance.domain.model.InvoiceDelta;
import com.moveinsync.distance.domain.model.ReconstructedTrip;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.resilience.SimpleCircuitBreaker;
import com.moveinsync.distance.routing.GridSnappedRoutingCache;
import com.moveinsync.distance.routing.RoutingProvider;
import com.moveinsync.distance.service.TripReconstructionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TripReconstructionController {

    private final TripReconstructionService reconstructionService;
    private final SimpleCircuitBreaker circuitBreaker;
    private final RoutingProvider routingProvider;

    public TripReconstructionController(
            TripReconstructionService reconstructionService,
            SimpleCircuitBreaker circuitBreaker,
            RoutingProvider routingProvider
    ) {
        this.reconstructionService = reconstructionService;
        this.circuitBreaker = circuitBreaker;
        this.routingProvider = routingProvider;
    }

    @PostMapping("/trips/{tripId}/reconstruct")
    public ResponseEntity<ReconstructedTrip> reconstruct(
            @PathVariable String tripId,
            @RequestBody ReconstructTripRequest request
    ) {
        TripConfig config = request.config() != null ? request.config() : TripConfig.defaultConfig();
        ReconstructedTrip trip = reconstructionService.reconstruct(
                tripId,
                request.tenantId(),
                request.rawPings(),
                request.milestones(),
                request.plannedOffice(),
                config
        );
        return ResponseEntity.ok(trip);
    }

    @GetMapping("/trips/{tripId}")
    public ResponseEntity<ReconstructedTrip> getTrip(@PathVariable String tripId) {
        ReconstructedTrip trip = reconstructionService.getTrip(tripId);
        if (trip == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(trip);
    }

    @PostMapping("/trips/{tripId}/bill")
    public ResponseEntity<Map<String, Object>> billTrip(@PathVariable String tripId) {
        ReconstructedTrip trip = reconstructionService.getTrip(tripId);
        if (trip == null) {
            return ResponseEntity.notFound().build();
        }
        reconstructionService.markTripBilled(tripId);
        Map<String, Object> response = new HashMap<>();
        response.put("tripId", tripId);
        response.put("billedDistanceKm", trip.totalBillableDistanceKm());
        response.put("status", "BILLED_CLOSED");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/trips/{tripId}/pings/late")
    public ResponseEntity<InvoiceDelta> ingestLatePings(
            @PathVariable String tripId,
            @RequestBody BatchPingsRequest request
    ) {
        InvoiceDelta delta = reconstructionService.ingestLatePings(tripId, request.pings());
        return ResponseEntity.ok(delta);
    }

    @GetMapping("/invoices/deltas")
    public ResponseEntity<List<InvoiceDelta>> getInvoiceDeltas() {
        return ResponseEntity.ok(reconstructionService.getInvoiceDeltas());
    }

    @PostMapping("/admin/circuit-breaker/trip")
    public ResponseEntity<Map<String, String>> tripCircuitBreaker() {
        circuitBreaker.tripManually();
        return ResponseEntity.ok(Map.of("circuitState", circuitBreaker.getState().name()));
    }

    @PostMapping("/admin/circuit-breaker/reset")
    public ResponseEntity<Map<String, String>> resetCircuitBreaker() {
        circuitBreaker.reset();
        return ResponseEntity.ok(Map.of("circuitState", circuitBreaker.getState().name()));
    }

    @PostMapping("/admin/degraded-queue/process")
    public ResponseEntity<Map<String, Object>> processDegradedQueue() {
        int count = reconstructionService.processDegradedQueue();
        return ResponseEntity.ok(Map.of("recomputedTripsCount", count));
    }

    @GetMapping("/admin/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("circuitBreakerState", circuitBreaker.getState().name());
        metrics.put("dispatchedRoutingCalls", routingProvider.getDispatchedCallCount());
        if (routingProvider instanceof GridSnappedRoutingCache cache) {
            metrics.put("cacheHits", cache.getCacheHits());
            metrics.put("cacheMisses", cache.getCacheMisses());
            double total = cache.getCacheHits() + cache.getCacheMisses();
            double hitRate = total > 0 ? (cache.getCacheHits() / total) * 100.0 : 0.0;
            metrics.put("cacheHitRatePercent", Math.round(hitRate * 100.0) / 100.0);
        }
        return ResponseEntity.ok(metrics);
    }
}
