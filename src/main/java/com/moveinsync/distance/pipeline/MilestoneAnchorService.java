package com.moveinsync.distance.pipeline;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.MilestoneType;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripMilestone;
import com.moveinsync.distance.domain.model.TripWaypoint;
import com.moveinsync.distance.domain.util.GeoUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stage 2: Milestone Anchoring & Gap Patching.
 * Injects operational ground-truth (pickups, drops, no-shows, office arrival) to reconstruct
 * path trajectory across signal blackouts.
 */
public class MilestoneAnchorService {

    public List<TripWaypoint> mergeAnchors(
            List<GpsPing> cleanedPings,
            List<TripMilestone> milestones,
            GeoPoint plannedOffice,
            TripConfig config
    ) {
        List<TripWaypoint> waypoints = new ArrayList<>();

        // Add all valid GPS pings
        if (cleanedPings != null) {
            for (GpsPing ping : cleanedPings) {
                waypoints.add(TripWaypoint.fromPing(ping));
            }
        }

        // Process milestones into anchor waypoints
        if (milestones != null) {
            for (TripMilestone milestone : milestones) {
                if (milestone.type() == MilestoneType.EMPLOYEE_NO_SHOW && !config.includeNoShowAnchors()) {
                    continue;
                }

                if (milestone.type() == MilestoneType.TRIP_END) {
                    continue; // Handled specially by office anchor logic
                }

                TripMilestone.ResolvedAnchor resolved = milestone.resolveAnchor();
                if (resolved != null) {
                    waypoints.add(TripWaypoint.fromAnchor(
                            resolved.point(),
                            resolved.timestamp(),
                            "ANCHOR_" + milestone.type().name() + "_" + resolved.source(),
                            "Employee: " + (milestone.employeeId() != null ? milestone.employeeId() : "N/A")
                    ));
                }
            }
        }

        // Sort all waypoints chronologically
        waypoints.sort(Comparator.comparingLong(TripWaypoint::timestamp));

        // Office Anchor Logic (Trip End)
        if (plannedOffice != null && !waypoints.isEmpty()) {
            TripWaypoint lastWaypoint = waypoints.get(waypoints.size() - 1);
            double distanceToOfficeMeters = GeoUtils.haversineDistanceKm(lastWaypoint.point(), plannedOffice) * 1000.0;

            if (distanceToOfficeMeters > config.officeRadiusMeters()) {
                // Cab ended further than 400m from office -> snap to planned office location
                waypoints.set(waypoints.size() - 1, new TripWaypoint(
                        plannedOffice,
                        lastWaypoint.timestamp(),
                        "ANCHOR_OFFICE_PLANNED_SNAP",
                        "Snapped from " + Math.round(distanceToOfficeMeters) + "m away"
                ));
            }
        }

        return deduplicateWaypoints(waypoints);
    }

    private List<TripWaypoint> deduplicateWaypoints(List<TripWaypoint> sortedWaypoints) {
        if (sortedWaypoints.isEmpty()) {
            return List.of();
        }

        List<TripWaypoint> result = new ArrayList<>(sortedWaypoints.size());
        TripWaypoint last = null;

        for (TripWaypoint current : sortedWaypoints) {
            if (last == null) {
                result.add(current);
                last = current;
                continue;
            }

            // If same timestamp and very close point (< 5 meters), skip redundant waypoint
            if (current.timestamp() == last.timestamp() &&
                    GeoUtils.haversineDistanceKm(last.point(), current.point()) < 0.005) {
                continue;
            }

            result.add(current);
            last = current;
        }

        return result;
    }
}
