package com.moveinsync.distance.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Custom lightweight Circuit Breaker tailored for routing provider integrations.
 * Protects system latency and cost during upstream provider outages.
 */
public class SimpleCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long resetTimeoutMillis;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastStateChangeTime = new AtomicLong(System.currentTimeMillis());

    public SimpleCircuitBreaker() {
        this(3, 3000L); // Trip after 3 consecutive failures, retry after 3 seconds
    }

    public SimpleCircuitBreaker(int failureThreshold, long resetTimeoutMillis) {
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMillis = resetTimeoutMillis;
    }

    public boolean allowExecution() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }

        if (current == State.OPEN) {
            long elapsed = System.currentTimeMillis() - lastStateChangeTime.get();
            if (elapsed > resetTimeoutMillis) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    lastStateChangeTime.set(System.currentTimeMillis());
                    return true;
                }
            }
            return false;
        }

        // HALF_OPEN: allow trial requests through
        return true;
    }

    public void recordSuccess() {
        failureCount.set(0);
        if (state.get() != State.CLOSED) {
            state.set(State.CLOSED);
            lastStateChangeTime.set(System.currentTimeMillis());
        }
    }

    public void recordFailure() {
        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold || state.get() == State.HALF_OPEN) {
            state.set(State.OPEN);
            lastStateChangeTime.set(System.currentTimeMillis());
        }
    }

    public State getState() {
        // Evaluate timeout if currently OPEN
        if (state.get() == State.OPEN) {
            long elapsed = System.currentTimeMillis() - lastStateChangeTime.get();
            if (elapsed > resetTimeoutMillis) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    lastStateChangeTime.set(System.currentTimeMillis());
                }
            }
        }
        return state.get();
    }

    public void tripManually() {
        failureCount.set(failureThreshold);
        state.set(State.OPEN);
        lastStateChangeTime.set(System.currentTimeMillis());
    }

    public void reset() {
        failureCount.set(0);
        state.set(State.CLOSED);
        lastStateChangeTime.set(System.currentTimeMillis());
    }
}
