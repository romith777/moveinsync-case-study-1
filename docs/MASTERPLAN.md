# MoveInSync Advanced Track — Case Study 1
## GPS Gap Backfill & Billable Distance Reconstruction
### Comprehensive Architectural Masterplan

---

## 1. Project Overview & Business Value

In corporate employee transportation, vendors supplying cabs are compensated strictly on a **per-kilometer basis**. 
- **The Challenge:** Real-world GPS data is inherently noisy and prone to blackouts (tunnels, basement garages, dead batteries, network latency, multipath drift).
- **The Pitfalls:** 
  - A naive straight-line sum of received pings severely underpays drivers for circuitous routes driven during blackouts.
  - Naive calls to third-party routing APIs (Google Maps/OSRM) for every missing second balloon operating costs and over-pay on noisy "teleporting" pings.
- **The Objective:** Build an explainable, deterministic, cost-aware distance reconstruction engine that:
  1. Sanitizes telemetry streams (deduplication, ordering, accuracy filtering, teleportation elimination).
  2. Anchors gaps with ground-truth operational milestones (employee pickups/drops, office arrival).
  3. Evaluates segments hierarchically (Haversine vs. Parked vs. Cached Road Routing vs. Circuit-Breaker Fallback).
  4. Delivers verifiable auditability (every kilometer tagged with its exact rule) and idempotent recomputation with signed adjustment deltas.

---

## 2. Core Architectural Principles & Evaluation Checklist

| Evaluation Criterion | Implementation Architecture in this Project |
| :--- | :--- |
| **Object-Oriented Design (OOPS)** | Layered domain-driven design: `Controller -> Service -> Domain/Pipeline -> Repository`. Strategy & Chain of Responsibility patterns for ping filters and distance evaluators. |
| **Clean Separation** | Core reconstruction algorithm implemented as pure, zero-dependency domain classes testable without launching Spring Boot, databases, or web servers. |
| **Performance & Scale** | In-memory spatial grid snapping + high-performance multi-level caching (Caffeine). High-throughput benchmark processing 10,000 trips (~15M pings) with p50/p99 latency reporting. |
| **Fault Tolerance & Resilience** | Custom Circuit Breaker for external routing provider. Graceful degradation to $1.4 \times \text{Haversine}$ tagged as `DEGRADED` with automated asynchronous recomputation upon recovery. |
| **Financial Idempotency** | Strictly deterministic floating-point arithmetic (rounded to 3 decimal places using `BigDecimal`). Never mutate closed invoices; emit signed reconciliation deltas (`+1.8 km`) on late pings. |
| **Security & Multi-Tenancy** | Tenant and client isolation on all REST endpoints with role-based access control (Admin, Operator, Vendor). Redacted PII (no phone, home address, or names in logs). |
| **Observability** | Structured logging, Micrometer metrics (throughput, error rate, circuit breaker state, routing cache hit ratio, p99 processing latency). |
| **Containerization** | Multi-stage `Dockerfile` and `docker-compose.yml` for single-command bootstrap. |

---

## 3. High-Level Pipeline Architecture

```
                  Raw GPS Telemetry Stream + Trip Milestones
                                      │
                                      ▼
             ┌─────────────────────────────────────────────────┐
             │       STAGE 1: Stream Cleaning & Sanitation      │
             │  • Deduplicate pings (coord + timestamp)        │
             │  • Sort chronologically by timestamp            │
             │  • Accuracy filter (drop radius > maxAccuracy)  │
             │  • Teleportation filter (>120 km/h implied)     │
             └────────────────────────┬────────────────────────┘
                                      │ Cleaned Chronological Pings
                                      ▼
             ┌─────────────────────────────────────────────────┐
             │     STAGE 2: Anchor Ground-Truth Milestones     │
             │  • Employee Pickup / Drop actual coordinates    │
             │  • Fallback: Scheduled Stop / Home Profile      │
             │  • No-Show Anchors (feature-flagged)            │
             │  • Office End Point: Snap if <= 400m            │
             └────────────────────────┬────────────────────────┘
                                      │ Unified Sequenced Waypoints
                                      ▼
             ┌─────────────────────────────────────────────────┐
             │        STAGE 3: Cost-Aware Segment Engine       │
             │  • Short gap (<70s)   ──► SHORT_GAP_AERIAL      │
             │  • Stopped (<5 km/h)  ──► PARKED                │
             │  • Real Gap           ──► ROUTED (OSRM / Router)│
             │  • Speed check        ──► REJECTED_SPEED        │
             └────────────────────────┬────────────────────────┘
                                      │ Segment Audit Trail
                                      ▼
             ┌─────────────────────────────────────────────────┐
             │    STAGE 4: Resilience, Cache & Reconciliation  │
             │  • Snapped Grid-Cell Routing Cache (Geohash)    │
             │  • Circuit Breaker -> Fallback (Haversine * 1.4)│
             │  • Idempotency Check & Signed Delta Generation  │
             └─────────────────────────────────────────────────┘
```

---

## 4. Phase-by-Phase Execution Roadmap

### Phase 1: Environment Setup & Project Scaffolding
- Set up Git repository with atomic commit strategy.
- Configure Java 21 LTS + Spring Boot 3.x project structure (Maven/Gradle wrapper).
- Establish project packages:
  - `com.moveinsync.distance.domain`: Core entities, value objects, immutable models.
  - `com.moveinsync.distance.pipeline`: Cleaning filters, milestone anchoring, segment classifier.
  - `com.moveinsync.distance.routing`: Routing provider interface, OSRM client, mock client with failure injection, grid-cell cache.
  - `com.moveinsync.distance.resilience`: Circuit breaker, fallback evaluator, degraded queue.
  - `com.moveinsync.distance.service`: Reconstruction orchestration, ledger reconciliation, idempotency.
  - `com.moveinsync.distance.api`: REST controllers, DTOs, security, tenant filters.

### Phase 2: Domain Modeling & Pure Algorithmic Pipeline
- Implement domain models: `GpsPing`, `Milestone`, `TripSegment`, `SegmentRule`, `ReconstructedTrip`, `InvoiceDelta`.
- Implement `GpsStreamCleaner`:
  - Deduplication via hash sets/timestamps.
  - Outlier filtering (Haversine distance / $\Delta t > 120\text{ km/h}$).
- Implement `MilestoneAnchorService`:
  - Merge pickup/drop milestones into the clean ping stream.
  - 400m office snap logic.
- Implement `SegmentDistanceEngine`:
  - Rule evaluation order: `SHORT_GAP_AERIAL`, `PARKED`, `ROUTED`, `REJECTED_SPEED`.
  - Comprehensive unit tests verifying each rule in isolation.

### Phase 3: Routing Provider, Caching & Circuit Breaker
- Define `RoutingProvider` interface (`getDrivingDistance(start, end)`).
- Implement `GridSnappedCache`:
  - Snap GPS coordinates to discrete spatial buckets (~100m–500m precision).
  - Cache driving distances with hit/miss metrics.
- Implement `ResilientRoutingClient`:
  - Circuit Breaker pattern (CLOSED -> OPEN -> HALF-OPEN).
  - On failure/timeout: tag trip `DEGRADED`, apply `Haversine * 1.4`.
  - Background recovery recomputation worker.

### Phase 4: Financial Idempotency & Late-Ping Ledger
- Deterministic roundings and rule versioning (`v1.0`).
- Persistent/in-memory trip ledger.
- Late ping reconciliation:
  - Detect newly ingested pings for a completed trip.
  - Run recomputation and calculate signed difference (`signedDeltaKm = newKm - oldKm`).
  - Unit test proving identical inputs produce identical distance to 0.000 km.

### Phase 5: Scale Benchmark & Failure Injection Suite
- Implement `SyntheticTripGenerator`:
  - Capable of creating 10,000 realistic trips with ~1,500 pings each (15 million coordinates total).
  - Injects realistic noise: underpass blackouts, basement dropouts, teleportation spikes, stationary traffic.
- Benchmark test:
  - Measure execution time, memory footprint, p50 and p99 latencies.
  - Failure injection test: shut down routing provider at trip #5,000 to verify automatic fallback and recovery.

### Phase 6: API Layer, Multi-Tenancy, Docker & Documentation
- Build REST endpoints:
  - `POST /api/v1/trips/{tripId}/reconstruct`
  - `POST /api/v1/trips/{tripId}/pings/batch`
  - `GET /api/v1/trips/{tripId}/audit`
  - `GET /api/v1/metrics`
- Implement tenant isolation filters and security headers.
- Write multi-stage `Dockerfile` and `docker-compose.yml`.
- Write high-grade `README.md` with:
  - Architecture diagrams (Mermaid).
  - Hard rule test guarantees.
  - Benchmark performance tables.
  - Demo script for the final submission video.
