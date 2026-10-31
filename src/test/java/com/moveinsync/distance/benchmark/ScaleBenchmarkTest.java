package com.moveinsync.distance.benchmark;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.MilestoneType;
import com.moveinsync.distance.domain.model.ReconstructedTrip;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripMilestone;
import com.moveinsync.distance.domain.model.TripStatus;
import com.moveinsync.distance.pipeline.DistanceReconstructionPipeline;
import com.moveinsync.distance.resilience.SimpleCircuitBreaker;
import com.moveinsync.distance.routing.GridSnappedRoutingCache;
import com.moveinsync.distance.routing.MockRoutingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScaleBenchmarkTest {

    @Test
    @DisplayName("Flagship Scale Benchmark: Process 10,000 synthetic trips (~1,500 pings each) with failure injection at trip #5,000")
    void executeScaleBenchmark() {
        int totalTrips = 10_000;
        int pingsPerTrip = 1_500;
        int failureTripIndex = 5_000;

        System.out.println("================================================================================");
        System.out.println("STARTING SCALE BENCHMARK: 10,000 TRIPS x 1,500 PINGS (~15,000,000 PINGS)");
        System.out.println("================================================================================");

        MockRoutingProvider mockProvider = new MockRoutingProvider(1.25);
        GridSnappedRoutingCache cache = new GridSnappedRoutingCache(mockProvider);
        SimpleCircuitBreaker circuitBreaker = new SimpleCircuitBreaker(3, 10_000L);
        DistanceReconstructionPipeline pipeline = new DistanceReconstructionPipeline();
        TripConfig config = TripConfig.defaultConfig();

        // Pre-generate a template trip with realistic telemetry, gap, and teleport spike
        List<GpsPing> basePings = generateSyntheticTripPings("template-trip", pingsPerTrip);
        GeoPoint plannedOffice = GeoPoint.of(12.9800, 77.7000);
        List<TripMilestone> milestones = List.of(
                new TripMilestone("m1", "t", MilestoneType.EMPLOYEE_PICKUP, "E1", GeoPoint.of(12.9350, 77.6250), 1714546800000L + 600_000L, null, null, null, false)
        );

        long[] latenciesMs = new long[totalTrips];
        int healthyTrips = 0;
        int degradedTrips = 0;

        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long memBefore = runtime.totalMemory() - runtime.freeMemory();
        long benchmarkStart = System.currentTimeMillis();

        for (int i = 0; i < totalTrips; i++) {
            // Halfway failure injection
            if (i == failureTripIndex) {
                System.out.println(">> [FAILURE INJECTION] Inducing routing provider failure at trip #" + i);
                mockProvider.setFailAll(true);
                cache.clearCache(); // Force lookup to reach the broken provider
            }

            long tripStart = System.currentTimeMillis();
            String tripId = "trip-" + i;

            ReconstructedTrip trip = pipeline.reconstruct(
                    tripId,
                    "TENANT-CORP-A",
                    basePings,
                    milestones,
                    plannedOffice,
                    config,
                    cache,
                    circuitBreaker
            );

            long tripDuration = System.currentTimeMillis() - tripStart;
            latenciesMs[i] = tripDuration;

            if (trip.status() == TripStatus.FINAL) {
                healthyTrips++;
            } else if (trip.status() == TripStatus.DEGRADED) {
                degradedTrips++;
            }
        }

        long totalTimeMs = System.currentTimeMillis() - benchmarkStart;
        long memAfter = runtime.totalMemory() - runtime.freeMemory();
        long memDeltaMb = Math.max(0, (memAfter - memBefore) / (1024 * 1024));

        Arrays.sort(latenciesMs);
        long p50Latency = latenciesMs[(int) (totalTrips * 0.50)];
        long p90Latency = latenciesMs[(int) (totalTrips * 0.90)];
        long p99Latency = latenciesMs[(int) (totalTrips * 0.99)];
        double throughputTripsSec = (totalTrips / (totalTimeMs / 1000.0));
        double throughputPingsSec = ((totalTrips * (double) pingsPerTrip) / (totalTimeMs / 1000.0));

        System.out.println("================================================================================");
        System.out.println("BENCHMARK RESULTS REPORT");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Total Trips Processed      : " + totalTrips);
        System.out.println("Total Pings Ingested       : " + (totalTrips * pingsPerTrip) + " points");
        System.out.println("Total Execution Time       : " + totalTimeMs + " ms (" + (totalTimeMs / 1000.0) + " s)");
        System.out.println("Throughput (Trips/sec)     : " + String.format("%.2f", throughputTripsSec) + " trips/s");
        System.out.println("Throughput (Pings/sec)     : " + String.format("%.2f", throughputPingsSec) + " pings/s");
        System.out.println("p50 Latency per Trip       : " + p50Latency + " ms");
        System.out.println("p90 Latency per Trip       : " + p90Latency + " ms");
        System.out.println("p99 Latency per Trip       : " + p99Latency + " ms");
        System.out.println("Memory Footprint Delta     : " + memDeltaMb + " MB");
        System.out.println("Healthy Trips (FINAL)      : " + healthyTrips);
        System.out.println("Degraded Fallback Trips    : " + degradedTrips);
        System.out.println("Routing Dispatched Calls   : " + mockProvider.getDispatchedCallCount());
        System.out.println("Routing Cache Hits         : " + cache.getCacheHits());
        System.out.println("================================================================================");

        assertThat(healthyTrips).isEqualTo(failureTripIndex);
        assertThat(degradedTrips).isEqualTo(totalTrips - failureTripIndex);
        assertThat(cache.getCacheHits()).isGreaterThan(0);
    }

    private List<GpsPing> generateSyntheticTripPings(String tripId, int count) {
        List<GpsPing> pings = new ArrayList<>(count);
        double startLat = 12.9300;
        double startLon = 77.6200;
        long startTime = 1714546800000L;

        for (int i = 0; i < count; i++) {
            long timestamp = startTime + (i * 3000L); // Ping every 3 seconds
            double lat = startLat + (i * 0.0001);
            double lon = startLon + (i * 0.0001);

            // Inject 1 duplicate ping
            if (i == 10) {
                pings.add(new GpsPing("dup-" + i, tripId, lat, lon, timestamp, 5.0));
            }
            // Inject 1 low-accuracy fix
            else if (i == 25) {
                pings.add(new GpsPing("inacc-" + i, tripId, lat, lon, timestamp, 180.0));
            }
            // Inject 1 teleportation spike (9 km jump in 3s)
            else if (i == 50) {
                pings.add(new GpsPing("teleport-" + i, tripId, lat + 0.08, lon + 0.08, timestamp, 5.0));
            } else {
                pings.add(new GpsPing("p-" + i, tripId, lat, lon, timestamp, 5.0));
            }
        }
        return pings;
    }
}
