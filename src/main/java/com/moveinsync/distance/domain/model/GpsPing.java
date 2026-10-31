package com.moveinsync.distance.domain.model;

/**
 * Raw GPS ping telemetry emitted by a vehicle tracking unit or driver application.
 */
public record GpsPing(
        String id,
        String tripId,
        double latitude,
        double longitude,
        long timestamp,
        double accuracy
) {
    public GeoPoint toGeoPoint() {
        return new GeoPoint(latitude, longitude);
    }
}
