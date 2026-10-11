# Alert Service

Consumes `energy-alerts` Kafka events produced by `usage-service`, persists them
in the `alert` table (MySQL), and sends a notification email through Mailpit.

## Startup order

The `alert` table is created by **user-service's** Flyway migration `V5__alert_table.sql`.
alert-service has `ddl-auto: none` and no Flyway of its own, so:

1. `docker compose up -d` (Kafka + Mailpit + InfluxDB) — from `backend/`
2. `user-service` — runs migrations V1–V5 (creates the `alert` table)
3. `device-service` — needed by usage-service enrichment
4. `usage-service` — produces alerts
5. `ingestion-service` — produces usage events
6. `alert-service` — consumes alerts

## Ports

- alert-service: `8102`
- Mailpit SMTP: `1025`, Mailpit UI: `http://localhost:8025`

## Verify the flow

Easiest: run the integration test (needs Kafka, MySQL, Mailpit running):

```bash
./mvnw test -Dtest=AlertEndToEndTest
```

It produces a real `AlertingEvent` to `energy-alerts` (same JsonSerializer as
usage-service) and asserts the DB row and the `sent=true` status.

Then check:
- alert-service logs (`Alert id ... sent to ...`)
- `SELECT * FROM alert;` in MySQL
- Mailpit UI at http://localhost:8025

To test the full pipeline instead, run all services in the order above and let
ingestion/usage produce a real threshold breach.
