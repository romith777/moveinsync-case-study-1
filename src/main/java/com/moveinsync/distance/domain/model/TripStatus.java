package com.moveinsync.distance.domain.model;

/**
 * Status of a reconstructed trip.
 */
public enum TripStatus {
    /**
     * Standard reconstruction completed successfully with healthy routing.
     */
    FINAL,

    /**
     * Routing provider failed during reconstruction; fallback estimation was applied.
     * Queued for automatic recomputation when provider recovers.
     */
    DEGRADED,

    /**
     * Recomputed after recovery or upon ingestion of late pings.
     */
    RECOMPUTED
}
