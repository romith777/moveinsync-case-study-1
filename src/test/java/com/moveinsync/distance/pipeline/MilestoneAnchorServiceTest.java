package com.moveinsync.distance.pipeline;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.MilestoneType;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripMilestone;
import com.moveinsync.distance.domain.model.TripWaypoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MilestoneAnchorServiceTest {

    private final MilestoneAnchorService anchorService = new MilestoneAnchorService();
    private final TripConfig config = TripConfig.defaultConfig();

    @Test
    @DisplayName("Should anchor valid employee pickup point and fallback to planned stop when invalid")
    void shouldAnchorPickupsWithFallback() {
        long tStart = 1714546800000L;
        long tPickup = tStart + 300_000L; // 5 mins in
        long tEnd = tStart + 600_000L;    // 10 mins in

        List<GpsPing> pings = List.of(
                new GpsPing("p1", "trip-1", 12.9300, 77.6200, tStart, 5.0),
                new GpsPing("p2", "trip-1", 12.9500, 77.6400, tEnd, 5.0)
        );

        GeoPoint actualPickup = GeoPoint.of(12.9400, 77.6300);
        TripMilestone emp1 = new TripMilestone(
                "m1", "trip-1", MilestoneType.EMPLOYEE_PICKUP, "EMP-101",
                actualPickup, tPickup,
                GeoPoint.of(12.9410, 77.6310), tPickup,
                null, false
        );

        List<TripWaypoint> waypoints = anchorService.mergeAnchors(pings, List.of(emp1), null, config);

        assertThat(waypoints).hasSize(3);
        assertThat(waypoints.get(1).point()).isEqualTo(actualPickup);
        assertThat(waypoints.get(1).source()).isEqualTo("ANCHOR_EMPLOYEE_PICKUP_ACTUAL");
    }

    @Test
    @DisplayName("Should snap office anchor to planned location when cab ends > 400m away")
    void shouldSnapOfficeWhenBeyond400Meters() {
        long tStart = 1714546800000L;
        long tEnd = tStart + 1800_000L;

        GeoPoint plannedOffice = GeoPoint.of(12.9700, 77.6500);
        // Ending point 1 km away from office (> 400m)
        GeoPoint distantEnd = GeoPoint.of(12.9600, 77.6500);

        List<GpsPing> pings = List.of(
                new GpsPing("p1", "trip-1", 12.9300, 77.6200, tStart, 5.0),
                new GpsPing("p2", "trip-1", distantEnd.latitude(), distantEnd.longitude(), tEnd, 5.0)
        );

        List<TripWaypoint> waypoints = anchorService.mergeAnchors(pings, List.of(), plannedOffice, config);

        assertThat(waypoints).hasSize(2);
        TripWaypoint finalPoint = waypoints.get(1);
        assertThat(finalPoint.point()).isEqualTo(plannedOffice);
        assertThat(finalPoint.source()).isEqualTo("ANCHOR_OFFICE_PLANNED_SNAP");
    }

    @Test
    @DisplayName("Should preserve actual end point when within 400m of office")
    void shouldKeepActualEndPointWhenWithin400Meters() {
        long tStart = 1714546800000L;
        long tEnd = tStart + 1800_000L;

        GeoPoint plannedOffice = GeoPoint.of(12.9700, 77.6500);
        // Ending point ~100m away (< 400m)
        GeoPoint closeEnd = GeoPoint.of(12.9705, 77.6505);

        List<GpsPing> pings = List.of(
                new GpsPing("p1", "trip-1", 12.9300, 77.6200, tStart, 5.0),
                new GpsPing("p2", "trip-1", closeEnd.latitude(), closeEnd.longitude(), tEnd, 5.0)
        );

        List<TripWaypoint> waypoints = anchorService.mergeAnchors(pings, List.of(), plannedOffice, config);

        assertThat(waypoints).hasSize(2);
        TripWaypoint finalPoint = waypoints.get(1);
        assertThat(finalPoint.point()).isEqualTo(closeEnd);
    }
}
