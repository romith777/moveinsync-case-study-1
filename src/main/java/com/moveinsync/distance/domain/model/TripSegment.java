package com.moveinsync.distance.domain.model;

/**
 * An itemized, explainable segment between two consecutive points along a trip.
 */
public record TripSegment(
        int sequence,
        GeoPoint fromPoint,
        GeoPoint toPoint,
        long fromTimestamp,
        long toTimestamp,
        double distanceKm,
        SegmentRule rule,
        String explanation,
        boolean isDegraded
) {
    public long durationSeconds() {
        return Math.max(0, (toTimestamp - fromTimestamp) / 1000);
    }
}
