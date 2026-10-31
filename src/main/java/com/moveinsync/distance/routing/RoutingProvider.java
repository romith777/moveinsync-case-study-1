package com.moveinsync.distance.routing;

import com.moveinsync.distance.domain.model.GeoPoint;

/**
 * Interface contract for road network routing distance calculations (e.g. OSRM, Google Maps).
 */
public interface RoutingProvider {

    /**
     * Obtains the real driving road network distance between two points.
     *
     * @param from Start coordinates
     * @param to   End coordinates
     * @return Driving distance in kilometers
     * @throws RoutingException if routing call fails or times out
     */
    double getDrivingDistanceKm(GeoPoint from, GeoPoint to) throws RoutingException;

    /**
     * Total number of network API calls dispatched to the underlying provider.
     */
    int getDispatchedCallCount();

    /**
     * Resets call counter.
     */
    void resetCallCount();
}
