package com.moveinsync.distance.pipeline;

import com.moveinsync.distance.domain.model.GpsPing;
import com.moveinsync.distance.domain.model.TripConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GpsStreamCleanerTest {

    private final GpsStreamCleaner cleaner = new GpsStreamCleaner();
    private final TripConfig config = TripConfig.defaultConfig();

    @Test
    @DisplayName("Should remove duplicate pings and sort chronologically")
    void shouldDeduplicateAndSort() {
        long t1 = 1714546800000L;
        long t2 = t1 + 10_000L;
        long t3 = t1 + 20_000L;

        List<GpsPing> raw = List.of(
                new GpsPing("p3", "trip-1", 12.9354, 77.6247, t3, 5.0),
                new GpsPing("p1", "trip-1", 12.9352, 77.6245, t1, 5.0),
                new GpsPing("p1-dup", "trip-1", 12.9352, 77.6245, t1, 5.0), // duplicate
                new GpsPing("p2", "trip-1", 12.9353, 77.6246, t2, 5.0)
        );

        GpsStreamCleaner.CleanResult result = cleaner.clean(raw, config);

        assertThat(result.rawCount()).isEqualTo(4);
        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.cleanedPings()).hasSize(3);
        assertThat(result.cleanedPings().get(0).id()).isEqualTo("p1");
        assertThat(result.cleanedPings().get(1).id()).isEqualTo("p2");
        assertThat(result.cleanedPings().get(2).id()).isEqualTo("p3");
    }

    @Test
    @DisplayName("Should drop low-accuracy fixes exceeding threshold")
    void shouldDropLowAccuracyFixes() {
        long t1 = 1714546800000L;

        List<GpsPing> raw = List.of(
                new GpsPing("p1", "trip-1", 12.9352, 77.6245, t1, 10.0),
                new GpsPing("p2-bad", "trip-1", 12.9353, 77.6246, t1 + 5000, 150.0), // accuracy 150m > 50m
                new GpsPing("p3", "trip-1", 12.9354, 77.6247, t1 + 10000, 8.0)
        );

        GpsStreamCleaner.CleanResult result = cleaner.clean(raw, config);

        assertThat(result.lowAccuracyCount()).isEqualTo(1);
        assertThat(result.cleanedPings()).hasSize(2);
        assertThat(result.cleanedPings().stream().map(GpsPing::id)).containsExactly("p1", "p3");
    }

    @Test
    @DisplayName("Should drop teleportation burst jumps (>120 km/h) without affecting trip distance")
    void shouldDropTeleportationJumps() {
        long t1 = 1714546800000L;

        // Base location in Bangalore: Koramangala (12.9352, 77.6245)
        // Normal next point in 10 seconds: ~100m away (~36 km/h)
        // Teleport burst: 9 km away in 4 seconds = 8,100 km/h!
        List<GpsPing> raw = List.of(
                new GpsPing("p1", "trip-1", 12.9352, 77.6245, t1, 5.0),
                new GpsPing("teleport-1", "trip-1", 13.0162, 77.6245, t1 + 4000, 5.0), // ~9 km away in 4s!
                new GpsPing("teleport-2", "trip-1", 13.0165, 77.6248, t1 + 6000, 5.0),
                new GpsPing("p2-valid", "trip-1", 12.9360, 77.6250, t1 + 10000, 5.0) // ~100m from p1 in 10s
        );

        GpsStreamCleaner.CleanResult result = cleaner.clean(raw, config);

        assertThat(result.teleportCount()).isEqualTo(2);
        assertThat(result.cleanedPings()).hasSize(2);
        assertThat(result.cleanedPings().stream().map(GpsPing::id)).containsExactly("p1", "p2-valid");
    }
}
