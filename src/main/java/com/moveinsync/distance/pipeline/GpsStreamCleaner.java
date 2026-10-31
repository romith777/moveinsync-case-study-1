package com.moveinsync.distance.pipeline;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.TripConfig;
import com.moveinsync.distance.domain.util.GeoUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stage 1: Stream Cleaning & Sanitation.
 * Removes duplicates, sorts chronologically, drops inaccurate fixes, and filters teleportation spikes.
 */
public class GpsStreamCleaner {

    public record CleanResult(
            List<GpsPing> cleanedPings,
            int rawCount,
            int duplicateCount,
            int lowAccuracyCount,
            int teleportCount
    ) {
        public int droppedCount() {
            return duplicateCount + lowAccuracyCount + teleportCount;
        }
    }

    public CleanResult clean(List<GpsPing> rawPings, TripConfig config) {
        if (rawPings == null || rawPings.isEmpty()) {
            return new CleanResult(List.of(), 0, 0, 0, 0);
        }

        int rawCount = rawPings.size();
        int lowAccuracyCount = 0;
        int duplicateCount = 0;
        int teleportCount = 0;

        // 1. Accuracy filtering
        List<GpsPing> accuratePings = new ArrayList<>(rawCount);
        for (GpsPing ping : rawPings) {
            if (ping.accuracy() > config.maxAccuracyMeters() || ping.accuracy() < 0.0) {
                lowAccuracyCount++;
            } else {
                accuratePings.add(ping);
            }
        }

        // 2. Order by timestamp ascending
        accuratePings.sort(Comparator.comparingLong(GpsPing::timestamp));

        // 3. Deduplication & Teleportation Spike Filter
        List<GpsPing> cleaned = new ArrayList<>(accuratePings.size());
        GpsPing lastValid = null;

        for (GpsPing current : accuratePings) {
            if (lastValid == null) {
                cleaned.add(current);
                lastValid = current;
                continue;
            }

            // Deduplication (same coordinate and timestamp)
            if (current.timestamp() == lastValid.timestamp() &&
                    Double.compare(current.latitude(), lastValid.latitude()) == 0 &&
                    Double.compare(current.longitude(), lastValid.longitude()) == 0) {
                duplicateCount++;
                continue;
            }

            long timeDiffMs = current.timestamp() - lastValid.timestamp();
            if (timeDiffMs <= 0) {
                // Out-of-order timestamp or conflicting fix at identical millisecond
                duplicateCount++;
                continue;
            }

            double distanceKm = GeoUtils.haversineDistanceKm(lastValid.toGeoPoint(), current.toGeoPoint());
            double impliedSpeedKmH = GeoUtils.impliedSpeedKmH(distanceKm, timeDiffMs);

            // Teleport jump check (e.g. >120 km/h)
            if (impliedSpeedKmH > config.maxSpeedKmH()) {
                teleportCount++;
                // Skip this point, keep lastValid untouched
                continue;
            }

            cleaned.add(current);
            lastValid = current;
        }

        return new CleanResult(cleaned, rawCount, duplicateCount, lowAccuracyCount, teleportCount);
    }
}
