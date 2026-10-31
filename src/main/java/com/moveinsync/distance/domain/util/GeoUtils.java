package com.moveinsync.distance.domain.util;

import com.moveinsync.distance.domain.model.GeoPoint;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Geometric and mathematical utility functions for GPS coordinate calculations.
 */
public final class GeoUtils {

    // WGS-84 Earth mean radius in kilometers
    public static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoUtils() {
        // utility class
    }

    /**
     * Calculates the great-circle distance between two geographic coordinates using the Haversine formula.
     *
     * @param p1 Starting point
     * @param p2 Ending point
     * @return Distance in kilometers
     */
    public static double haversineDistanceKm(GeoPoint p1, GeoPoint p2) {
        if (p1.equals(p2)) {
            return 0.0;
        }

        double lat1Rad = Math.toRadians(p1.latitude());
        double lon1Rad = Math.toRadians(p1.longitude());
        double lat2Rad = Math.toRadians(p2.latitude());
        double lon2Rad = Math.toRadians(p2.longitude());

        double deltaLat = lat2Rad - lat1Rad;
        double deltaLon = lon2Rad - lon1Rad;

        double sinHalfDeltaLat = Math.sin(deltaLat / 2.0);
        double sinHalfDeltaLon = Math.sin(deltaLon / 2.0);

        double a = (sinHalfDeltaLat * sinHalfDeltaLat) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) * (sinHalfDeltaLon * sinHalfDeltaLon);

        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));

        return EARTH_RADIUS_KM * c;
    }

    /**
     * Calculates the implied speed in kilometers per hour between two points across an elapsed duration.
     *
     * @param distanceKm Distance traversed in kilometers
     * @param durationMillis Elapsed time in milliseconds
     * @return Speed in km/h (or 0.0 if duration <= 0)
     */
    public static double impliedSpeedKmH(double distanceKm, long durationMillis) {
        if (durationMillis <= 0 || distanceKm <= 0.0) {
            return 0.0;
        }
        double hours = durationMillis / 3_600_000.0;
        return distanceKm / hours;
    }

    /**
     * Deterministically rounds a double value to specified decimal places using BigDecimal.
     */
    public static double round(double value, int scale) {
        return BigDecimal.valueOf(value)
                .setScale(scale, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
