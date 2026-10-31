package com.moveinsync.distance.domain.model;

/**
 * Financial reconciliation delta emitted when delayed GPS pings arrive post-billing.
 */
public record InvoiceDelta(
        String tripId,
        String tenantId,
        double originalDistanceKm,
        double newDistanceKm,
        double signedDeltaKm,
        String ruleVersion,
        long recomputedTimestamp,
        String reason
) {
    public static InvoiceDelta compute(String tripId, String tenantId, double originalKm, double newKm, String version, String reason) {
        double delta = Math.round((newKm - originalKm) * 1000.0) / 1000.0;
        return new InvoiceDelta(
                tripId,
                tenantId,
                originalKm,
                newKm,
                delta,
                version,
                System.currentTimeMillis(),
                reason
        );
    }
}
