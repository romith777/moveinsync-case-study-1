package com.moveinsync.distance.api.dto;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.model.TripMilestone;

import java.util.List;

public record ReconstructTripRequest(
        String tenantId,
        List<GpsPing> rawPings,
        List<TripMilestone> milestones,
        GeoPoint plannedOffice,
        TripConfig config
) {}
