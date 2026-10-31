package com.moveinsync.distance.domain.model;

/**
 * Tunable parameters and business rules for the distance reconstruction engine.
 */
public record TripConfig(
        double maxSpeedKmH,
        double parkedSpeedLimitKmH,
        long shortGapMaxSeconds,
        double officeRadiusMeters,
        double maxAccuracyMeters,
        double maxRouteDistanceKm,
        double fallbackMultiplier,
        boolean includeNoShowAnchors,
        String ruleVersion
) {
    public static TripConfig defaultConfig() {
        return new TripConfig(
                120.0,    // maxSpeedKmH
                5.0,      // parkedSpeedLimitKmH
                70L,      // shortGapMaxSeconds
                400.0,    // officeRadiusMeters
                50.0,     // maxAccuracyMeters
                100.0,    // maxRouteDistanceKm
                1.4,      // fallbackMultiplier
                true,     // includeNoShowAnchors
                "v1.0"    // ruleVersion
        );
    }
}
