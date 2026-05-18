# Parcel Tracker

A parcel tracking service built incrementally to learn the stack:
**Scala + Play Framework · Postgres · Google Pub/Sub · Cassandra · Graphite/Grafana · Angular**

---

## End-state architecture

```
                         ┌─────────────────────────────────┐
                         │         Play REST API            │
          ┌──────────┐   │                                  │
          │ Angular  │──▶│  ParcelController                │
          │    UI    │   │  ParcelRepository  ──────────────┼──▶ Postgres
          └──────────┘   │  ParcelEventPublisher ───────────┼──▶ Pub/Sub topic
                         │                                  │       │
                         └─────────────────────────────────┘       │
                                                                    │
                    ┌───────────────────────────────────────────────┘
                    │
                    ├──▶ Cassandra consumer  (event / audit log)
                    │
                    └──▶ Graphite / Grafana  (metrics & dashboards)
```

---

## Current state (Phase 4)

```
  HTTP Client
      │
      │  REST calls
      ▼
┌─────────────────────────────────────┐
│          ParcelController           │
│          (Play Framework)           │
└──────────────────┬──────────────────┘
                   │
      ┌────────────┼──────────────────────┐
      │            │                      │
      ▼            ▼                      ▼
┌──────────┐ ┌──────────────────┐ ┌─────────────────┐
│ Postgres │ │ParcelEventPublish│ │  MetricsService  │
│ (Docker) │ │er (Phase 2)      │ │  (Phase 4)       │
└──────────┘ └────────┬─────────┘ └────────┬─────────┘
                      │                    │
                      ▼                    ▼
             ┌─────────────────┐  ┌─────────────────┐
             │  Pub/Sub topic  │  │    Graphite      │
             │  (Docker :8085) │  │  (Docker :2003)  │
             └────────┬────────┘  └────────┬─────────┘
                      │                    │
                      ▼                    ▼
             ┌─────────────────┐  ┌─────────────────┐
             │CassandraConsumer│  │     Grafana      │
             │  (Phase 3)      │  │  (Docker :3000)  │
             └────────┬────────┘  └─────────────────┘
                      │
                      ▼
             ┌─────────────────┐
             │   Cassandra     │
             │  (Docker :9042) │
             └─────────────────┘
```

### PATCH /parcels/:id/status — detailed flow

```
Client ──PATCH──▶ ParcelController
                        │
                        ├─1──▶ ParcelRepository.updateStatus()
                        │              │
                        │         Postgres UPDATE
                        │              │
                        │       updated Parcel ◀──────────┐
                        │                                  │ RETURNING *
                        ├─2──▶ ParcelEventPublisher.publish(parcel)
                        │              │
                        │      PubsubMessage { eventType, parcelId,
                        │                      newStatus, occurredAt }
                        │              │
                        │       Pub/Sub topic ──▶ CassandraConsumer
                        │                               │
                        │                         Cassandra write
                        │                         (parcel_events)
                        ├─3──▶ MetricsService.incrementStatusChange()
                        │              │
                        │      parcels.status_change.<status> counter
                        │              │
                        │           Graphite (flushed every 10s)
                        │
                        └──▶ 200 OK  { ...updated parcel JSON }
```

---

## Phase roadmap

| Phase | What | Status |
|-------|------|--------|
| 1 | Play REST API + Postgres | ✅ Done |
| 2 | Pub/Sub event publishing on status change | ✅ Done |
| 3 | Cassandra consumer — subscribes to topic, writes immutable event log | ✅ Done |
| 4 | Graphite/Grafana — metrics per status transition | ✅ Done |
| 5 | Angular UI — parcel list + status timeline | ⬜ Next |

---

## Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| Language | Scala 2.13 | Statically typed, concise, JVM |
| Framework | Play 2.9 | Routing, DI, JSON, evolutions |
| DB library | Anorm 2.6 | Raw SQL, typed result parsing |
| Database | Postgres 16 | Relational source of truth |
| Messaging | Google Pub/Sub | Decoupled event delivery |
| Event store | Cassandra (Phase 3) | Immutable append-only log |
| Metrics | Graphite / Grafana (Phase 4) | Time-series dashboards |
| Frontend | Angular (Phase 5) | SPA, familiar to the team |

---

## Project layout

```
app/
  controllers/
    ParcelController.scala      ← HTTP layer: parse request, call repo, return JSON
  models/
    Parcel.scala                ← case class + JSON codecs
    ParcelEvent.scala           ← Cassandra event record + JSON codecs
  repositories/
    ParcelRepository.scala      ← all SQL lives here (Anorm)
  services/
    ParcelEventPublisher.scala  ← Pub/Sub publisher (Phase 2)
    CassandraConsumer.scala     ← Pub/Sub subscriber + Cassandra writer (Phase 3)
    MetricsService.scala        ← Dropwizard counters → Graphite (Phase 4)
conf/
  routes                        ← URL → controller mapping (compiled by Play)
  application.conf              ← DB, Pub/Sub, Cassandra, Metrics settings (HOCON)
  logback.xml                   ← log levels per package
  evolutions/default/1.sql      ← CREATE TABLE parcels (Play runs this on startup)
docker-compose.yml              ← Postgres, Pub/Sub, Cassandra, Graphite, Grafana
test/
  controllers/
    ParcelControllerSpec.scala  ← unit tests, all dependencies mocked
```

---

## Running locally

```bash
# 1. Start infrastructure
docker compose up

# 2. Start the API (first run downloads dependencies)
sbt run
# API is live at http://localhost:9000
```

---

## API reference

### POST /parcels
```bash
curl -s -X POST http://localhost:9000/parcels \
  -H "Content-Type: application/json" \
  -d '{"senderName":"Alice","recipientName":"Bob","recipientAddress":"123 Main St"}' | jq
```

### GET /parcels/:id
```bash
curl -s http://localhost:9000/parcels/1 | jq
```

### PATCH /parcels/:id/status
```bash
curl -s -X PATCH http://localhost:9000/parcels/1/status \
  -H "Content-Type: application/json" \
  -d '{"status":"IN_TRANSIT"}' | jq
```
Valid statuses (unenforced at DB level for now): `PENDING` · `IN_TRANSIT` · `DELIVERED` · `RETURNED`

### GET /parcels
```bash
curl -s http://localhost:9000/parcels | jq
```

---

## Verifying Pub/Sub events (Phase 2)

```bash
# 1. Create a subscription (once)
curl -s -X PUT \
  "http://localhost:8085/v1/projects/local-project/subscriptions/parcel-sub" \
  -H "Content-Type: application/json" \
  -d '{"topic":"projects/local-project/topics/parcel-status-changed"}' | jq

# 2. Trigger a status change
curl -s -X PATCH http://localhost:9000/parcels/1/status \
  -H "Content-Type: application/json" \
  -d '{"status":"DELIVERED"}' | jq

# 3. Pull the message
curl -s -X POST \
  "http://localhost:8085/v1/projects/local-project/subscriptions/parcel-sub:pull" \
  -H "Content-Type: application/json" \
  -d '{"maxMessages":10}' | jq

# 4. Decode the base64 payload
echo "<data field from above>" | base64 -d
# {"eventType":"STATUS_CHANGED","parcelId":1,"newStatus":"DELIVERED","occurredAt":"..."}
```

The `sbt run` terminal also logs confirmation for every publish:
```
DEBUG services.ParcelEventPublisher - [Pub/Sub] Sending event for parcel Some(1): {...}
INFO  services.ParcelEventPublisher - [Pub/Sub] STATUS_CHANGED for parcel Some(1) → newStatus=DELIVERED (messageId=1)
```

---

## Running tests

```bash
sbt test        # run once
sbt ~test       # watch mode — re-runs on file save
```

Tests use Mockito to stub the repository and publisher — no Docker needed.
