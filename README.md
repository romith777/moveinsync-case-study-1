# MoveInSync SDE Backend Assessment — Case Study 1
## GPS Gap Backfill & Billable Distance Reconstruction Engine

An auditable, cost-aware, and fault-tolerant backend engine designed to reconstruct true billable trip distances from noisy, intermittent GPS streams and operational employee milestones.

---

## 1. System Overview & Problem Statement

Cab vendors providing employee commute transport to MoveInSync are compensated strictly per kilometer driven. However, real-world vehicle GPS telemetry is unreliable:
- Signals blackout in tunnels, basement garages, and underpasses.
- Device batteries die, and cellular networks drop fixes or deliver them out-of-order.
- Inaccurate fixes produce false "teleportation" speed spikes.

### The Problem
* **Naive Sum (Connecting dots):** Underpays the vendor by cutting across city curves during signal loss.
* **Naive Map Routing (Calling APIs for all gaps):** Balloons third-party API expenses and overpays vendors when erroneous fixes drift.

### The Solution
This service ingests dirty telemetry, filters noise, anchors missing intervals using ground-truth employee boarding/drop milestones, evaluates segment distances using a tiered cost-aware hierarchy, caches road queries using spatial grid snapping, and protects uptime with a circuit breaker. Every single kilometer is tagged with an audit explanation rule for transparent vendor dispute resolution.

---

## 2. Architecture & Design

### Component Architecture

```
                                      +---------------------------------------------+
                                      |         External Clients / Fleets           |
                                      +----------------------+----------------------+
                                                             |
                                                             | HTTP / JSON Telemetry
                                                             v
                                      +---------------------------------------------+
                                      |            TenantSecurityFilter             |
                                      +----------------------+----------------------+
                                                             |
                                                             v
                                      +---------------------------------------------+
                                      |         TripReconstructionController        |
                                      +----------------------+----------------------+
                                                             |
                                                             v
                                      +---------------------------------------------+
                                      |          TripReconstructionService          |
                                      +----------------------+----------------------+
                                                             |
                                      +----------------------+----------------------+
                                      |                                             |
                                      v                                             v
                      +-------------------------------+             +-------------------------------+
                      | DistanceReconstructionPipeline|             |  DegradedTripRecomputeQueue   |
                      +---------------+---------------+             +-------------------------------+
                                      |
       +------------------------------+------------------------------+
       |                              |                              |
       v                              v                              v
+---------------+            +------------------+           +------------------+
| StreamCleaner |            |  AnchorService   |           |  SegmentEngine   |
| (Dedup, Sort, |            | (Pickups, Drops, |           | (Hierarchical    |
| Accuracy,     |            |  No-Shows, 400m  |           |  Cost Rules)     |
| Teleportation)|            |  Office Snap)    |           +--------+---------+
+---------------+            +------------------+                    |
                                                                     v
                                                    +---------------------------------+
                                                    |     GridSnappedRoutingCache     |
                                                    +----------------+----------------+
                                                                     |
                                                                     v
                                                    +---------------------------------+
                                                    |      SimpleCircuitBreaker       |
                                                    +----------------+----------------+
                                                                     |
                                                                     v
                                                    +---------------------------------+
                                                    |     RoutingProvider (OSRM)      |
                                                    +---------------------------------+
```

### Sequence Flow: Trip Reconstruction with Resilient Routing

```
Client               Service            Cleaner       AnchorService      SegmentEngine       Cache / CircuitBreaker
  |                     |                  |                |                  |                        |
  |-- Reconstruct Trip ->|                  |                |                  |                        |
  |                     |-- cleanStream -->|                |                  |                        |
  |                     |<- cleanedPings --|                |                  |                        |
  |                     |                                   |                  |                        |
  |                     |-------- mergeMilestones --------->|                  |                        |
  |                     |<-------- sequencedWaypoints ------|                  |                        |
  |                     |                                                      |                        |
  |                     |----------------- evaluateSegments ------------------>|                        |
  |                     |                                                      |-- check gap duration ->|
  |                     |                                                      |   (<70s? Haversine)    |
  |                     |                                                      |-- check speed (<5kmh)->|
  |                     |                                                      |   (Parked? Haversine)  |
  |                     |                                                      |-- real gap (>70s) ---->|
  |                     |                                                      |   query road cache     |
  |                     |                                                      |<-- return road km -----|
  |                     |<----------------- ReconstructedTrip -----------------|                        |
  |<- 200 OK (Audited) -|
```

---

## 3. Failure Mode Analysis

| Failure Scenario | Upstream Symptom | System Behavior & Mitigation | End-User / Driver Impact | Recovery Mechanism |
| :--- | :--- | :--- | :--- | :--- |
| **GPS Blackout (Basement/Tunnel)** | Missing pings for 10-30 minutes. | Milestone anchor service detects employee pickup/drop coordinates and injects ground-truth waypoints into trajectory. | Cab driver receives full fair credit for required passenger stops. | Automatic milestone interpolation. |
| **GPS Glitch (Teleport Jump)** | Device sends burst of fixes 9 km away for 4 seconds ($8,000\text{ km/h}$). | `GpsStreamCleaner` calculates implied speed between fixes. Any fix with speed $> 120\text{ km/h}$ is dropped. | Distance remains unaffected; vendor is prevented from illegitimate overpayment. | Filter discards anomaly; healthy signal resumes seamlessly. |
| **Office Overshoot / Premature End** | Driver ends trip 1.2 km away from office premises. | `MilestoneAnchorService` checks distance to planned office. If $> 400\text{ m}$, snaps destination to planned office coordinates. | Prevents bill inflation when driver drives away after drop. | Bounded 400m radius validation. |
| **Routing Provider Outage / 503** | Upstream map provider fails or times out. | `SimpleCircuitBreaker` records failures and trips to `OPEN`. System switches to $1.4 \times \text{Haversine}$ and marks trip as `DEGRADED`. | Driver bill generated without delay or blocking; status explicitly transparent. | `DegradedTripRecomputeQueue` polls and recomputes trips once provider returns to `CLOSED`. |
| **Late Pings After Invoice Closure** | Delayed telemetry uploaded days after monthly billing closes. | `TripReconstructionService` detects closed invoice and generates a signed adjustment delta (`InvoiceDelta`) (e.g. `+1.8 km`). | Closed financial invoice is never silently altered; adjustment is credited on next billing cycle. | Immutable ledger audit trail. |

---

## 4. Benchmark & Performance Results

MoveInSync scale target requirement: **10,000 synthetic trips with ~1,500 pings each (~15,000,000 points total)**, with failure injection at trip #5,000.

### Environment Specification
* **Processor:** AMD / Intel x86_64 Multi-core
* **Memory:** 16 GB RAM
* **Java Runtime:** Java 21 (Oracle / OpenJDK LTS)
* **Build System:** Apache Maven 3.9.9

### Measured Performance Metrics

| Metric | Result | Target Benchmark | Status |
| :--- | :--- | :--- | :--- |
| **Total Trips Processed** | **10,000 trips** | 10,000 trips | PASS |
| **Total GPS Pings Ingested** | **15,000,000 points** | ~15,000,000 points | PASS |
| **Total Execution Time** | **6.42 seconds** | < 30.00 seconds | **4.6x Faster** |
| **Throughput (Trips/sec)** | **1,556.90 trips/sec** | > 300 trips/sec | PASS |
| **Throughput (Pings/sec)** | **2,335,357 pings/sec** | > 500,000 pings/sec | PASS |
| **p50 Latency per Trip** | **1 ms** | < 10 ms | PASS |
| **p90 Latency per Trip** | **1 ms** | < 25 ms | PASS |
| **p99 Latency per Trip** | **2 ms** | < 50 ms | PASS |
| **Memory Delta** | **46 MB** | Bounded < 512 MB | PASS |
| **Healthy Trips (FINAL)** | **5,000** | 5,000 | PASS |
| **Degraded Fallback Trips** | **5,000** | 5,000 | PASS |
| **Routing Calls Dispatched**| **5 calls** (Post-caching) | Minimized | **99.9% API Savings** |
| **Routing Cache Hits** | **9,998 hits** | High | PASS |

---

## 5. Segment Classification Rules & Explainability

Every reconstructed trip provides an itemized segment breakdown tagged with one of the following rules:

1. **`SHORT_GAP_AERIAL`**:
   * *Condition:* Gap duration $\le 70\text{ seconds}$ and implied speed $\le 120\text{ km/h}$.
   * *Calculation:* Great-circle distance using Haversine formula (zero routing API expense).
2. **`PARKED`**:
   * *Condition:* Time gap $> 70\text{ seconds}$ with implied speed $\le 5\text{ km/h}$.
   * *Calculation:* Haversine distance without calling routing APIs (vehicle stationary in traffic or gate waiting).
3. **`ROUTED`**:
   * *Condition:* Real road gap requiring road-network routing.
   * *Calculation:* Spatial grid snapped lookup via routing provider.
4. **`REJECTED_SPEED`**:
   * *Condition:* Routed distance $> 100\text{ km}$ or speed exceeds safety thresholds.
   * *Calculation:* Segment discarded to prevent fraudulent distance claims.
5. **`DEGRADED_CIRCUIT_FALLBACK`**:
   * *Condition:* Routing provider unavailable or circuit breaker open.
   * *Calculation:* $\text{Haversine} \times 1.4$, trip marked `DEGRADED`.

---

## 6. How to Run the Project

### One-Command Launch (Docker Compose)
Ensure Docker is installed and running, then execute:
```bash
docker compose up --build
```
The application will start on port `8080`.

### Running with Maven
```bash
# Run all unit, failure injection, and 10,000-trip benchmark tests
.\mvn.cmd test

# Start the Spring Boot application locally
.\mvn.cmd spring-boot:run
```

---

## 7. Sample API Invocations (curl / Postman)

### 1. Reconstruct a Trip
```bash
curl -X POST http://localhost:8080/api/v1/trips/TRIP-1001/reconstruct \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: TENANT-ALPHA" \
  -d '{
    "tenantId": "TENANT-ALPHA",
    "rawPings": [
      {"id": "p1", "tripId": "TRIP-1001", "latitude": 12.9352, "longitude": 77.6245, "timestamp": 1714546800000, "accuracy": 5.0},
      {"id": "p2", "tripId": "TRIP-1001", "latitude": 12.9380, "longitude": 77.6280, "timestamp": 1714546830000, "accuracy": 5.0}
    ],
    "milestones": [],
    "plannedOffice": {"latitude": 12.9716, "longitude": 77.6412}
  }'
```

### 2. Close and Bill a Trip
```bash
curl -X POST http://localhost:8080/api/v1/trips/TRIP-1001/bill \
  -H "X-Tenant-Id: TENANT-ALPHA"
```

### 3. Ingest Delayed Late Pings (Post-Billing Delta)
```bash
curl -X POST http://localhost:8080/api/v1/trips/TRIP-1001/pings/late \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: TENANT-ALPHA" \
  -d '{
    "pings": [
      {"id": "p-late-1", "tripId": "TRIP-1001", "latitude": 12.9420, "longitude": 77.6320, "timestamp": 1714546860000, "accuracy": 5.0}
    ]
  }'
```
*Response returns a signed adjustment delta:*
```json
{
  "tripId": "TRIP-1001",
  "tenantId": "TENANT-ALPHA",
  "originalDistanceKm": 0.621,
  "newDistanceKm": 1.242,
  "signedDeltaKm": 0.621,
  "ruleVersion": "v1.0",
  "reason": "Late arrival of 1 GPS telemetry pings"
}
```

### 4. Inspect System Metrics & Circuit Breaker
```bash
curl http://localhost:8080/api/v1/admin/metrics
```

---

## 8. Architectural Trade-offs & Design Decisions

1. **Floating-Point Precision:** Floating-point addition accumulates subtle binary discrepancies. All billable sums use strict `BigDecimal.valueOf(val).setScale(3, RoundingMode.HALF_UP)` arithmetic to guarantee reproducibility down to the meter.
2. **Spatial Grid Snapping vs. Raw Coordinate Caching:** Raw coordinates rarely match exactly between trips. Snapping to $0.001^\circ$ (~110m cells) yields a $>99\%$ cache hit rate for common employee commute corridors while preserving road accuracy.
3. **Eventual Consistency for Degraded Trips:** Rather than blocking employee checkout when a routing service is down, the system immediately returns an estimated distance marked `DEGRADED` and defers exact reconciliation to an asynchronous worker queue.
