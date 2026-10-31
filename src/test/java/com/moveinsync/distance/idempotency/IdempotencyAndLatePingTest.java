package com.moveinsync.distance.idempotency;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.InvoiceDelta;
import com.moveinsync.distance.domain.model.ReconstructedTrip;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.pipeline.DistanceReconstructionPipeline;
import com.moveinsync.distance.resilience.DegradedTripRecomputeQueue;
import com.moveinsync.distance.resilience.SimpleCircuitBreaker;
import com.moveinsync.distance.routing.MockRoutingProvider;
import com.moveinsync.distance.service.TripReconstructionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyAndLatePingTest {

    @Test
    @DisplayName("Identical inputs and rule versions must yield identical billable distance to 0.000 km")
    void shouldGuaranteeStrictIdempotency() {
        DistanceReconstructionPipeline pipeline = new DistanceReconstructionPipeline();
        MockRoutingProvider mockProvider = new MockRoutingProvider(1.25);
        SimpleCircuitBreaker cb = new SimpleCircuitBreaker();
        TripReconstructionService service = new TripReconstructionService(pipeline, mockProvider, cb, new DegradedTripRecomputeQueue());

        long t = 1714546800000L;
        List<GpsPing> pings = List.of(
                new GpsPing("p1", "trip-idem", 12.9300, 77.6200, t, 5.0),
                new GpsPing("p2", "trip-idem", 12.9340, 77.6240, t + 30_000L, 5.0),
                new GpsPing("p3", "trip-idem", 12.9380, 77.6280, t + 60_000L, 5.0)
        );

        ReconstructedTrip run1 = service.reconstruct("trip-idem", "T1", pings, List.of(), null, TripConfig.defaultConfig());
        ReconstructedTrip run2 = service.reconstruct("trip-idem", "T1", pings, List.of(), null, TripConfig.defaultConfig());

        assertThat(run1.totalBillableDistanceKm()).isEqualTo(run2.totalBillableDistanceKm());
    }

    @Test
    @DisplayName("Late pings arriving post-billing must emit signed invoice delta without altering closed invoice")
    void shouldEmitSignedDeltaOnLatePingsForBilledTrip() {
        DistanceReconstructionPipeline pipeline = new DistanceReconstructionPipeline();
        MockRoutingProvider mockProvider = new MockRoutingProvider(1.25);
        SimpleCircuitBreaker cb = new SimpleCircuitBreaker();
        TripReconstructionService service = new TripReconstructionService(pipeline, mockProvider, cb, new DegradedTripRecomputeQueue());

        long t = 1714546800000L;
        List<GpsPing> initialPings = List.of(
                new GpsPing("p1", "trip-billed", 12.9300, 77.6200, t, 5.0),
                new GpsPing("p2", "trip-billed", 12.9340, 77.6240, t + 30_000L, 5.0)
        );

        ReconstructedTrip trip = service.reconstruct("trip-billed", "T1", initialPings, List.of(), null, TripConfig.defaultConfig());
        double originalBilledKm = trip.totalBillableDistanceKm();

        // Close the billing cycle
        service.markTripBilled("trip-billed");
        assertThat(service.isTripBilled("trip-billed")).isTrue();

        // Late pings arrive after driver reconnects (at plausible speed: ~0.6 km in 30s = ~72 km/h)
        List<GpsPing> latePings = List.of(
                new GpsPing("p3-late", "trip-billed", 12.9380, 77.6280, t + 60_000L, 5.0)
        );

        InvoiceDelta delta = service.ingestLatePings("trip-billed", latePings);

        assertThat(delta.originalDistanceKm()).isEqualTo(originalBilledKm);
        assertThat(delta.newDistanceKm()).isGreaterThan(originalBilledKm);
        assertThat(delta.signedDeltaKm()).isPositive();
        assertThat(delta.tripId()).isEqualTo("trip-billed");

        // Stored billed distance remains intact
        ReconstructedTrip stored = service.getTrip("trip-billed");
        assertThat(stored.totalBillableDistanceKm()).isEqualTo(originalBilledKm);
    }
}
