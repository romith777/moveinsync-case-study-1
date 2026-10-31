package com.moveinsync.distance.api.dto;

import com.moveinsync.distance.domain.model.GpsPing;

import java.util.List;

public record BatchPingsRequest(
        List<GpsPing> pings
) {}
