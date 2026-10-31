package com.moveinsync.distance.routing;

import com.moveinsync.distance.domain.model.GeoPoint;
import com.moveinsync.distance.domain.util.GeoUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance, deterministic mock routing provider with fault-injection capabilities.
 */
public class MockRoutingProvider implements RoutingProvider {

    private final double windingFactor;
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicBoolean failAll = new AtomicBoolean(false);
    private final AtomicInteger failAfterCalls = new AtomicInteger(-1);

    public MockRoutingProvider() {
        this(1.28); // Standard urban road network multiplier over Haversine
    }

    public MockRoutingProvider(double windingFactor) {
        this.windingFactor = windingFactor;
    }

    @Override
    public double getDrivingDistanceKm(GeoPoint from, GeoPoint to) throws RoutingException {
        int currentCount = callCount.incrementAndGet();

        if (failAll.get()) {
            throw new RoutingException("Provider Down: 503 Service Unavailable");
        }

        int failureThreshold = failAfterCalls.get();
        if (failureThreshold >= 0 && currentCount > failureThreshold) {
            throw new RoutingException("Injected Network Timeout after " + failureThreshold + " calls");
        }

        double aerialDistance = GeoUtils.haversineDistanceKm(from, to);
        return GeoUtils.round(aerialDistance * windingFactor, 3);
    }

    @Override
    public int getDispatchedCallCount() {
        return callCount.get();
    }

    @Override
    public void resetCallCount() {
        callCount.set(0);
    }

    public void setFailAll(boolean fail) {
        this.failAll.set(fail);
    }

    public void setFailAfterCalls(int threshold) {
        this.failAfterCalls.set(threshold);
    }
}
