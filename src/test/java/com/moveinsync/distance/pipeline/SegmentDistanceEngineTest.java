package com.moveinsync.distance.pipeline;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.SegmentRule;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripWaypoint;
import com.moveinsync.distance.resilience.SimpleCircuitBreaker;
import com.moveinsync.distance.routing.MockRoutingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentDistanceEngineTest {

    private SegmentDistanceEngine engine;
    private MockRoutingProvider mockProvider;
    private SimpleCircuitBreaker circuitBreaker;
    private TripConfig config;

    @BeforeEach
    void setUp() {
        engine = new SegmentDistanceEngine();
        mockProvider = new MockRoutingProvider(1.25);
        circuitBreaker = new SimpleCircuitBreaker(3, 3000L);
        config = TripConfig.defaultConfig();
    }

    @Test
    @DisplayName("Short gap <= 70s should evaluate as SHORT_GAP_AERIAL using Haversine")
    void shouldEvaluateShortGapAerial() {
        long t1 = 1714546800000L;
        long t2 = t1 + 45_000L; // 45s gap <= 70s

        // ~500m distance in 45s => ~40 km/h
        GeoPoint p1 = GeoPoint.of(12.9300, 77.6200);
        GeoPoint p2 = GeoPoint.of(12.9340, 77.6220);

        List<TripWaypoint> waypoints = List.of(
                TripWaypoint.fromAnchor(p1, t1, "PING", "1"),
                TripWaypoint.fromAnchor(p2, t2, "PING", "2")
        );

        SegmentDistanceEngine.SegmentEvaluationResult result = engine.evaluateSegments(waypoints, config, mockProvider, circuitBreaker);

        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.SHORT_GAP_AERIAL);
        assertThat(mockProvider.getDispatchedCallCount()).isZero(); // Zero routing API calls
    }

    @Test
    @DisplayName("Extended gap with speed < 5 km/h should evaluate as PARKED without calling routing API")
    void shouldEvaluateParkedGap() {
        long t1 = 1714546800000L;
        long t2 = t1 + 600_000L; // 10 minutes gap (> 70s)

        // Only 50 meters moved in 10 minutes => 0.3 km/h (< 5 km/h)
        GeoPoint p1 = GeoPoint.of(12.9300, 77.6200);
        GeoPoint p2 = GeoPoint.of(12.9304, 77.6202);

        List<TripWaypoint> waypoints = List.of(
                TripWaypoint.fromAnchor(p1, t1, "PING", "1"),
                TripWaypoint.fromAnchor(p2, t2, "PING", "2")
        );

        SegmentDistanceEngine.SegmentEvaluationResult result = engine.evaluateSegments(waypoints, config, mockProvider, circuitBreaker);

        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.PARKED);
        assertThat(mockProvider.getDispatchedCallCount()).isZero();
    }

    @Test
    @DisplayName("Real gap > 70s with driving speed should query routing provider as ROUTED")
    void shouldRouteRealDrivingGap() {
        long t1 = 1714546800000L;
        long t2 = t1 + 900_000L; // 15 minutes gap

        // 10 km distance in 15 mins => 40 km/h
        GeoPoint p1 = GeoPoint.of(12.9300, 77.6200);
        GeoPoint p2 = GeoPoint.of(13.0100, 77.6600);

        List<TripWaypoint> waypoints = List.of(
                TripWaypoint.fromAnchor(p1, t1, "PING", "1"),
                TripWaypoint.fromAnchor(p2, t2, "PING", "2")
        );

        SegmentDistanceEngine.SegmentEvaluationResult result = engine.evaluateSegments(waypoints, config, mockProvider, circuitBreaker);

        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.ROUTED);
        assertThat(mockProvider.getDispatchedCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should trip circuit breaker and fallback to 1.4x straight line when provider fails")
    void shouldTripCircuitBreakerOnProviderFailure() {
        mockProvider.setFailAll(true); // Simulate provider down

        long t1 = 1714546800000L;
        long t2 = t1 + 900_000L;

        GeoPoint p1 = GeoPoint.of(12.9300, 77.6200);
        GeoPoint p2 = GeoPoint.of(13.0100, 77.6600);

        List<TripWaypoint> waypoints = List.of(
                TripWaypoint.fromAnchor(p1, t1, "PING", "1"),
                TripWaypoint.fromAnchor(p2, t2, "PING", "2")
        );

        SegmentDistanceEngine.SegmentEvaluationResult result = engine.evaluateSegments(waypoints, config, mockProvider, circuitBreaker);

        assertThat(result.hasDegradedSegments()).isTrue();
        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).rule()).isEqualTo(SegmentRule.DEGRADED_CIRCUIT_FALLBACK);
        assertThat(result.segments().get(0).isDegraded()).isTrue();
    }
}
