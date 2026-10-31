package com.moveinsync.distance.domain.model;

/**
 * Classification rule used to evaluate billable distance for each individual trip segment.
 * Ensures 100% explainability for vendor billing dispute resolution.
 */
public enum SegmentRule {
    /**
     * Consecutive points less than 70 seconds apart at a plausible speed.
     * Evaluated using straight-line (Haversine) distance.
     */
    SHORT_GAP_AERIAL,

    /**
     * Extended gap where implied speed is under 5 km/h (vehicle waiting/parked).
     * Evaluated using straight-line distance without incurring routing API cost.
     */
    PARKED,

    /**
     * Legitimate driving gap routed through the road-network routing provider.
     */
    ROUTED,

    /**
     * Segment rejected because distance > 100 km or implied speed exceeds the maximum limit.
     */
    REJECTED_SPEED,

    /**
     * Routing provider unavailable (circuit breaker tripped).
     * Fallback to straight-line distance * 1.4 marked as degraded.
     */
    DEGRADED_CIRCUIT_FALLBACK
}
