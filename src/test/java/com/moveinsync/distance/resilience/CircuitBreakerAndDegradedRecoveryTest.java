package com.moveinsync.distance.resilience;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.ReconstructedTrip;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripStatus;
import com.moveinsync.distance.pipeline.DistanceReconstructionPipeline;
import com.moveinsync.distance.routing.MockRoutingProvider;
import com.moveinsync.distance.service.TripReconstructionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerAndDegradedRecoveryTest {

    @Test
    @DisplayName("Should recover DEGRADED trips automatically when provider becomes healthy")
    void shouldRecoverDegradedTripsOnProviderHealth() {
        MockRoutingProvider mockProvider = new MockRoutingProvider(1.25);
        SimpleCircuitBreaker circuitBreaker = new SimpleCircuitBreaker(2, 500L);
        DegradedTripRecomputeQueue queue = new DegradedTripRecomputeQueue();
        DistanceReconstructionPipeline pipeline = new DistanceReconstructionPipeline();

        TripReconstructionService service = new TripReconstructionService(
                pipeline,
                mockProvider,
                circuitBreaker,
                queue
        );

        // 1. Incur provider outage
        mockProvider.setFailAll(true);

        long t1 = 1714546800000L;
        long t2 = t1 + 900_000L;
        List<GpsPing> pings = List.of(
                new GpsPing("p1", "trip-fail", 12.9300, 77.6200, t1, 5.0),
                new GpsPing("p2", "trip-fail", 13.0100, 77.6600, t2, 5.0)
        );

        ReconstructedTrip trip = service.reconstruct(
                "trip-fail",
                "TENANT-1",
                pings,
                List.of(),
                null,
                TripConfig.defaultConfig()
        );

        assertThat(trip.status()).isEqualTo(TripStatus.DEGRADED);
        assertThat(queue.size()).isEqualTo(1);

        // 2. Provider recovers
        mockProvider.setFailAll(false);
        circuitBreaker.reset();

        // 3. Process degraded recovery queue
        int recovered = service.processDegradedQueue();

        assertThat(recovered).isEqualTo(1);
        ReconstructedTrip updated = service.getTrip("trip-fail");
        assertThat(updated.status()).isEqualTo(TripStatus.FINAL);
        assertThat(queue.isEmpty()).isTrue();
    }
}
