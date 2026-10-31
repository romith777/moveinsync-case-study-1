package com.moveinsync.distance.domain.model;

import java.util.Objects;

/**
 * Immutable geographic coordinate representing latitude and longitude.
 */
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90, got: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180, got: " + longitude);
        }
    }

    public static GeoPoint of(double latitude, double longitude) {
        return new GeoPoint(latitude, longitude);
    }

    /**
     * Snap coordinate to a spatial grid cell for caching.
     * Rounding to decimals: 3 decimals is ~110m, 4 decimals is ~11m.
     */
    public String toGridCell(int decimalPlaces) {
        double factor = Math.pow(10, decimalPlaces);
        double snappedLat = Math.round(latitude * factor) / factor;
        double snappedLon = Math.round(longitude * factor) / factor;
        return String.format("%.4f,%.4f", snappedLat, snappedLon);
    }
}
