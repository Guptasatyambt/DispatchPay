# DispatchPay backend

Spring Boot 4 / Java 21 modular monolith (Step 1 of the development plan).

## Run locally

```sh
cp .env.example .env          # set DB_PASSWORD
docker compose up -d          # PostgreSQL 17
./gradlew bootRun             # dev profile is the default; reads .env
```

Probes: `GET /actuator/health/liveness`, `GET /actuator/health/readiness` (readiness includes the database).

## Tests

```sh
./gradlew test                # needs Docker (Testcontainers starts PostgreSQL)
```

## Profiles and secrets

| Profile   | Purpose                                   | Configuration source                         |
|-----------|-------------------------------------------|----------------------------------------------|
| `dev`     | Local development (default)               | `.env` (git-ignored) + `application-dev.yml` |
| `staging` | Shared environment                        | Environment variables only: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `OTP_PEPPER`; probes on `MANAGEMENT_PORT` (8081) |
| `test`    | Automated tests                           | Testcontainers                               |

## Authentication (M01.S01–S02)

| Endpoint | Purpose |
|---|---|
| `POST /v1/auth/otp/request` `{mobile, deviceId?}` | Always `202` with a generic body for any valid Indian mobile; `429` + `Retry-After` when limited |
| `POST /v1/auth/otp/verify` `{mobile, code, deviceId?, deviceName?}` | Creates the account on first sign-in, opens a device session, returns tokens |
| `POST /v1/auth/token/refresh` `{refreshToken}` | Rotates the refresh token; reusing an old one revokes the session |

- Access token: HS256 JWT, 15 min, claims `sub` (user id) and `sid` (session id) only — no permissions.
- Refresh token: opaque 256-bit value, stored as SHA-256, single use, 30-day idle / 90-day absolute session lifetime.
- OTP: 6 digits, 5 min, 5 attempts, 30 s resend cooldown, stored as HMAC with `OTP_PEPPER`.
- Limits (fixed windows in PostgreSQL): 3 / 15 min and 10 / day per mobile, 10 / h per device, 30 / h per IP for requests; 60 / h per IP for verify and refresh.
- SMS: implement `OtpSender` for the real provider. The `dev` profile prints codes to the console; other profiles fail to start without a sender.
- Behind a proxy, set `server.forward-headers-strategy` so IP limits see the real client address.

## Platform conventions (M00)

- **Errors**: RFC 9457 `application/problem+json` with `code`, `correlationId`, `timestamp`, and `errors[]` for validation. Codes live in `ErrorCode`; 422 for validation, 409 for conflicts.
- **Correlation**: `X-Correlation-Id` is accepted (8–64 chars of `[A-Za-z0-9._-]`) or generated, echoed on the response, logged via MDC and carried on outbox events.
- **Idempotency**: mutating commands go through `IdempotentCommandExecutor` with an `Idempotency-Key` header. Same key + same content replays the stored response (`Idempotency-Replayed: true`); different content → 409 `IDEMPOTENCY_KEY_REUSED`. Failed commands are not stored.
- **Outbox**: `OutboxWriter.append(...)` inside the business transaction; `OutboxPublisher` polls with `FOR UPDATE SKIP LOCKED`, retries with exponential backoff, parks as `DEAD` after `max-attempts`. Handlers implement `OutboxEventHandler` and are de-duplicated per event via `processed_event`.
- **Pagination**: declare a `PageQuery` controller parameter (`?page=0&size=20`, max 100) and return `PageResponse<T>`.
- **Time and money**: instants are UTC (`Clock` bean); money will be integer minor units, quantities `NUMERIC`.
