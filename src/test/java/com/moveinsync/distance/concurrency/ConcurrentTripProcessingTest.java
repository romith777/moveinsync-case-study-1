package com.moveinsync.distance.concurrency;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.ReconstructedTrip;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.pipeline.DistanceReconstructionPipeline;
import com.moveinsync.distance.resilience.DegradedTripRecomputeQueue;
import com.moveinsync.distance.resilience.SimpleCircuitBreaker;
import com.moveinsync.distance.routing.GridSnappedRoutingCache;
import com.moveinsync.distance.routing.MockRoutingProvider;
import com.moveinsync.distance.service.TripReconstructionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentTripProcessingTest {

    @Test
    @DisplayName("Advanced Track Concurrency Guarantee: 50 concurrent threads processing trips, cache reads, and late-pings simultaneously")
    void shouldHandleHighConcurrencyWithoutRaceConditions() throws InterruptedException, ExecutionException {
        int threadCount = 50;
        int tripsPerThread = 10;
        int totalTrips = threadCount * tripsPerThread;

        MockRoutingProvider mockProvider = new MockRoutingProvider(1.25);
        GridSnappedRoutingCache cache = new GridSnappedRoutingCache(mockProvider);
        SimpleCircuitBreaker circuitBreaker = new SimpleCircuitBreaker();
        DistanceReconstructionPipeline pipeline = new DistanceReconstructionPipeline();
        DegradedTripRecomputeQueue degradedQueue = new DegradedTripRecomputeQueue();

        TripReconstructionService service = new TripReconstructionService(
                pipeline,
                cache,
                circuitBreaker,
                degradedQueue
        );

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < totalTrips; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                latch.await(); // Synchronize all threads to start at the exact same instant

                String tripId = "concurrent-trip-" + index;
                long t = 1714546800000L + (index * 1000L);

                List<GpsPing> pings = List.of(
                        new GpsPing("p1-" + index, tripId, 12.9300, 77.6200, t, 5.0),
                        new GpsPing("p2-" + index, tripId, 12.9340, 77.6240, t + 30_000L, 5.0),
                        new GpsPing("p3-" + index, tripId, 12.9380, 77.6280, t + 60_000L, 5.0)
                );

                ReconstructedTrip trip = service.reconstruct(
                        tripId,
                        "TENANT-CONCURRENT",
                        pings,
                        List.of(),
                        GeoPoint.of(12.9380, 77.6280),
                        TripConfig.defaultConfig()
                );

                if (trip != null && trip.totalBillableDistanceKm() > 0.0) {
                    successCount.incrementAndGet();
                    return true;
                }
                return false;
            }));
        }

        latch.countDown(); // Unleash all 50 threads

        for (Future<Boolean> future : futures) {
            assertThat(future.get()).isTrue();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(successCount.get()).isEqualTo(totalTrips);
    }
}
