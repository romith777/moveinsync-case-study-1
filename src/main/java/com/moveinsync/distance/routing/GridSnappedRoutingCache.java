package com.moveinsync.distance.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.distance.domain.model.GeoPoint;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caches routing distance calculations using spatial grid-cell snapping.
 * Prevents redundant third-party routing API calls and enforces cost budget.
 */
public class GridSnappedRoutingCache implements RoutingProvider {

    private final RoutingProvider delegate;
    private final int gridDecimalPlaces;
    private final Cache<String, Double> cache;
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);

    public GridSnappedRoutingCache(RoutingProvider delegate) {
        this(delegate, 3, 50_000, Duration.ofHours(24));
    }

    public GridSnappedRoutingCache(RoutingProvider delegate, int gridDecimalPlaces, long maximumSize, Duration ttl) {
        this.delegate = delegate;
        this.gridDecimalPlaces = gridDecimalPlaces;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttl)
                .recordStats()
                .build();
    }

    @Override
    public double getDrivingDistanceKm(GeoPoint from, GeoPoint to) throws RoutingException {
        String cacheKey = createKey(from, to);
        Double cachedDistance = cache.getIfPresent(cacheKey);

        if (cachedDistance != null) {
            cacheHits.incrementAndGet();
            return cachedDistance;
        }

        cacheMisses.incrementAndGet();
        double computedDistance = delegate.getDrivingDistanceKm(from, to);
        cache.put(cacheKey, computedDistance);
        return computedDistance;
    }

    private String createKey(GeoPoint from, GeoPoint to) {
        return from.toGridCell(gridDecimalPlaces) + "->" + to.toGridCell(gridDecimalPlaces);
    }

    public int getCacheHits() {
        return cacheHits.get();
    }

    public int getCacheMisses() {
        return cacheMisses.get();
    }

    @Override
    public int getDispatchedCallCount() {
        return delegate.getDispatchedCallCount();
    }

    @Override
    public void resetCallCount() {
        delegate.resetCallCount();
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    public void clearCache() {
        cache.invalidateAll();
    }
}
