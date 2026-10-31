package com.moveinsync.distance.pipeline;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.SegmentRule;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripSegment;
import com.moveinsync.distance.domain.model.TripWaypoint;
import com.moveinsync.distance.domain.util.GeoUtils;
import com.moveinsync.distance.resilience.SimpleCircuitBreaker;
import com.moveinsync.distance.routing.RoutingException;
import com.moveinsync.distance.routing.RoutingProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3: Cost-Aware Segment Distance Engine.
 * Evaluates distances hierarchically across short gaps, parked stops, road routing,
 * and circuit-breaker fallbacks.
 */
public class SegmentDistanceEngine {

    public record SegmentEvaluationResult(
            List<TripSegment> segments,
            double totalDistanceKm,
            boolean hasDegradedSegments
    ) {}

    public SegmentEvaluationResult evaluateSegments(
            List<TripWaypoint> waypoints,
            TripConfig config,
            RoutingProvider routingProvider,
            SimpleCircuitBreaker circuitBreaker
    ) {
        if (waypoints == null || waypoints.size() < 2) {
            return new SegmentEvaluationResult(List.of(), 0.0, false);
        }

        List<TripSegment> segments = new ArrayList<>(waypoints.size() - 1);
        double totalDistance = 0.0;
        boolean hasDegraded = false;

        for (int i = 0; i < waypoints.size() - 1; i++) {
            TripWaypoint start = waypoints.get(i);
            TripWaypoint end = waypoints.get(i + 1);

            long durationMs = end.timestamp() - start.timestamp();
            long durationSec = durationMs / 1000L;
            double aerialDistanceKm = GeoUtils.haversineDistanceKm(start.point(), end.point());
            double aerialSpeedKmH = GeoUtils.impliedSpeedKmH(aerialDistanceKm, durationMs);

            TripSegment segment;

            // 1. Cheap Case 1: Short gap (<70s) at plausible speed -> Haversine
            if (durationSec <= config.shortGapMaxSeconds() && aerialSpeedKmH <= config.maxSpeedKmH()) {
                double rounded = GeoUtils.round(aerialDistanceKm, 3);
                segment = new TripSegment(
                        i + 1,
                        start.point(),
                        end.point(),
                        start.timestamp(),
                        end.timestamp(),
                        rounded,
                        SegmentRule.SHORT_GAP_AERIAL,
                        "Gap " + durationSec + "s <= " + config.shortGapMaxSeconds() + "s at " + Math.round(aerialSpeedKmH) + " km/h",
                        false
                );
            }
            // 2. Cheap Case 2: Parked / stationary (<5 km/h) -> Haversine without routing API call
            else if (aerialSpeedKmH <= config.parkedSpeedLimitKmH()) {
                double rounded = GeoUtils.round(aerialDistanceKm, 3);
                segment = new TripSegment(
                        i + 1,
                        start.point(),
                        end.point(),
                        start.timestamp(),
                        end.timestamp(),
                        rounded,
                        SegmentRule.PARKED,
                        "Vehicle parked/waiting; speed " + GeoUtils.round(aerialSpeedKmH, 2) + " km/h <= " + config.parkedSpeedLimitKmH() + " km/h",
                        false
                );
            }
            // 3. Real Gap: Ask routing provider or trip circuit breaker
            else {
                boolean canExecute = circuitBreaker.allowExecution();
                if (canExecute) {
                    try {
                        double routedKm = routingProvider.getDrivingDistanceKm(start.point(), end.point());
                        circuitBreaker.recordSuccess();

                        // Speed & Distance Sanity Check
                        if (routedKm > config.maxRouteDistanceKm()) {
                            segment = new TripSegment(
                                    i + 1,
                                    start.point(),
                                    end.point(),
                                    start.timestamp(),
                                    end.timestamp(),
                                    0.0,
                                    SegmentRule.REJECTED_SPEED,
                                    "Routed distance " + routedKm + " km exceeded threshold of " + config.maxRouteDistanceKm() + " km",
                                    false
                            );
                        } else {
                            double impliedRoutedSpeed = GeoUtils.impliedSpeedKmH(routedKm, durationMs);
                            double fallbackKm = aerialDistanceKm * config.fallbackMultiplier();
                            double impliedFallbackSpeed = GeoUtils.impliedSpeedKmH(fallbackKm, durationMs);

                            if (impliedRoutedSpeed > config.maxSpeedKmH() && impliedFallbackSpeed > config.maxSpeedKmH()) {
                                segment = new TripSegment(
                                        i + 1,
                                        start.point(),
                                        end.point(),
                                        start.timestamp(),
                                        end.timestamp(),
                                        0.0,
                                        SegmentRule.REJECTED_SPEED,
                                        "Implied routed speed " + Math.round(impliedRoutedSpeed) + " km/h exceeds limit " + config.maxSpeedKmH() + " km/h",
                                        false
                                );
                            } else {
                                segment = new TripSegment(
                                        i + 1,
                                        start.point(),
                                        end.point(),
                                        start.timestamp(),
                                        end.timestamp(),
                                        GeoUtils.round(routedKm, 3),
                                        SegmentRule.ROUTED,
                                        "Real driving gap " + durationSec + "s routed via road provider",
                                        false
                                );
                            }
                        }
                    } catch (RoutingException ex) {
                        circuitBreaker.recordFailure();
                        hasDegraded = true;
                        double fallbackKm = GeoUtils.round(aerialDistanceKm * config.fallbackMultiplier(), 3);
                        segment = new TripSegment(
                                i + 1,
                                start.point(),
                                end.point(),
                                start.timestamp(),
                                end.timestamp(),
                                fallbackKm,
                                SegmentRule.DEGRADED_CIRCUIT_FALLBACK,
                                "Provider failure: " + ex.getMessage() + "; applied fallback 1.4x",
                                true
                        );
                    }
                } else {
                    // Circuit breaker is OPEN
                    hasDegraded = true;
                    double fallbackKm = GeoUtils.round(aerialDistanceKm * config.fallbackMultiplier(), 3);
                    segment = new TripSegment(
                            i + 1,
                            start.point(),
                            end.point(),
                            start.timestamp(),
                            end.timestamp(),
                            fallbackKm,
                            SegmentRule.DEGRADED_CIRCUIT_FALLBACK,
                            "Circuit breaker OPEN; applied fallback 1.4x aerial distance",
                            true
                    );
                }
            }

            segments.add(segment);
            totalDistance += segment.distanceKm();
        }

        double finalBillableDistance = GeoUtils.round(totalDistance, 3);
        return new SegmentEvaluationResult(segments, finalBillableDistance, hasDegraded);
    }
}
