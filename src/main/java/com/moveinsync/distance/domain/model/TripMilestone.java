package com.moveinsync.distance.domain.model;

/**
 * Operational ground-truth event during a trip (e.g. employee boarding, dropping, no-show, office arrival).
 */
public record TripMilestone(
        String id,
        String tripId,
        MilestoneType type,
        String employeeId,
        GeoPoint actualLocation,
        Long actualTime,
        GeoPoint plannedLocation,
        Long plannedTime,
        GeoPoint homeProfileLocation,
        boolean isAdHocRider
) {
    /**
     * Resolves the anchor point and timestamp according to MoveInSync fallback priority rules:
     * 1. Actual coordinates and time if valid.
     * 2. If invalid and ad-hoc rider: profile home location and sign-in/planned time.
     * 3. If invalid and planned rider: planned location and planned time.
     */
    public ResolvedAnchor resolveAnchor() {
        if (actualLocation != null && actualTime != null) {
            return new ResolvedAnchor(actualLocation, actualTime, "ACTUAL");
        }
        if (isAdHocRider && homeProfileLocation != null && plannedTime != null) {
            return new ResolvedAnchor(homeProfileLocation, plannedTime, "AD_HOC_PROFILE_FALLBACK");
        }
        if (plannedLocation != null && plannedTime != null) {
            return new ResolvedAnchor(plannedLocation, plannedTime, "PLANNED_STOP_FALLBACK");
        }
        return null;
    }

    public record ResolvedAnchor(GeoPoint point, long timestamp, String source) {}
}
