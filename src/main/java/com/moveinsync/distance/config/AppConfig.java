package com.moveinsync.distance.config;

import com.moveinsync.distance.pipeline.DistanceReconstructionPipeline;
import com.moveinsync.distance.resilience.DegradedTripRecomputeQueue;
import com.moveinsync.distance.resilience.SimpleCircuitBreaker;
import com.moveinsync.distance.routing.GridSnappedRoutingCache;
import com.moveinsync.distance.routing.MockRoutingProvider;
import com.moveinsync.distance.routing.RoutingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public DistanceReconstructionPipeline distanceReconstructionPipeline() {
        return new DistanceReconstructionPipeline();
    }

    @Bean
    public MockRoutingProvider mockRoutingProvider() {
        return new MockRoutingProvider(1.28);
    }

    @Bean
    public RoutingProvider routingProvider(MockRoutingProvider mockRoutingProvider) {
        return new GridSnappedRoutingCache(mockRoutingProvider);
    }

    @Bean
    public SimpleCircuitBreaker simpleCircuitBreaker() {
        return new SimpleCircuitBreaker(3, 3000L);
    }

    @Bean
    public DegradedTripRecomputeQueue degradedTripRecomputeQueue() {
        return new DegradedTripRecomputeQueue();
    }
}
