package com.moveinsync.distance.domain.model;

import java.util.List;

/**
 * The final reconstructed, billable trip entity containing full audit trails.
 */
public record ReconstructedTrip(
        String tripId,
        String tenantId,
        String ruleVersion,
        double totalBillableDistanceKm,
        List<TripSegment> segments,
        TripStatus status,
        int rawPingsCount,
        int cleanedPingsCount,
        int droppedPingsCount,
        int routingCallsMade,
        int cacheHits,
        long processingTimeMs
) {
}
