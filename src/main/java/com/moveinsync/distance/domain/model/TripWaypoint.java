package com.moveinsync.distance.domain.model;

/**
 * A sequenced spatial waypoint along a trip trajectory (originating from a GPS ping or an anchor point).
 */
public record TripWaypoint(
        GeoPoint point,
        long timestamp,
        String source,
        String metadata
) {
    public static TripWaypoint fromPing(GpsPing ping) {
        return new TripWaypoint(ping.toGeoPoint(), ping.timestamp(), "GPS_PING", ping.id());
    }

    public static TripWaypoint fromAnchor(GeoPoint point, long timestamp, String source, String metadata) {
        return new TripWaypoint(point, timestamp, source, metadata);
    }
}
