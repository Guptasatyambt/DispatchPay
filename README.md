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

## Authentication (M01.S01–S03)

| Endpoint | Purpose |
|---|---|
| `POST /v1/auth/otp/request` `{mobile, deviceId?}` | Always `202` with a generic body for any valid Indian mobile; `429` + `Retry-After` when limited |
| `POST /v1/auth/otp/verify` `{mobile, code, deviceId?, deviceName?}` | Creates the account on first sign-in, opens a device session, returns tokens |
| `POST /v1/auth/token/refresh` `{refreshToken}` | Rotates the refresh token; reusing an old one revokes the session |
| `POST /v1/sessions/revoke` `{scope, sessionId?}` | Authenticated sign-out: `CURRENT` (this device), `SESSION` + `sessionId` (one of your devices; others' are `404`), or `ALL`. Returns `revokedSessionIds`; repeating is a no-op |

- Access token: HS256 JWT, 15 min, claims `sub` (user id) and `sid` (session id) only — no permissions.
- Every authenticated request checks the token's session in the database, so revoked or expired sessions and disabled accounts get `401` immediately, not after the token expires.
- Refresh token: opaque 256-bit value, stored as SHA-256, single use, 30-day idle / 90-day absolute session lifetime.
- OTP: 6 digits, 5 min, 5 attempts, 30 s resend cooldown, stored as HMAC with `OTP_PEPPER`.
- Limits (fixed windows in PostgreSQL): 3 / 15 min and 10 / day per mobile, 10 / h per device, 30 / h per IP for requests; 60 / h per IP for verify and refresh.
- SMS: implement `OtpSender` for the real provider. The `dev` profile prints codes to the console; other profiles fail to start without a sender.
- Behind a proxy, set `server.forward-headers-strategy` so IP limits see the real client address.

## Businesses (M01.S04)

| Endpoint | Purpose |
|---|---|
| `POST /v1/businesses` `{name, timezone?}` + `Idempotency-Key` | Any signed-in user registers a business and becomes its owner. `timezone` is an IANA zone (default `Asia/Kolkata`). Returns `201` with the business id, owner `membershipId` and `role: OWNER`; a retry with the same key replays it |

- Ownership is a single required `business.owner_membership_id`, so a business has exactly one owner by construction: no ownerless state, no second owner. Transfer (M01.S13–S14) changes that one column.
- Database-enforced: the owner must be a membership of the same business (deferred composite FK), one membership owns at most one business, and an active business's owner membership must be `ACTIVE` (deferred constraint trigger). Business and owner membership are inserted in one transaction.
- Memberships (`business_membership`) are unique per user and business and carry no role; whether a membership is the owner comes from the business row.

## Current access (M01.S05)

| Endpoint | Purpose |
|---|---|
| `GET /v1/iam/me` | The caller's `userId`, `mobile` and `memberships[]` (`membershipId`, `businessId`, `businessName`, `timezone`, `role` `OWNER`/`WORKER`, `status`), sorted by business name. Suspended memberships are listed so the app can show them; no business needs to be selected |
| `GET /v1/iam/permissions` + `X-Business-Id` | Effective access in the selected business: `role`, sorted `permissions[]` and `godownScope {all, godownIds}` |

- **Tenant selection**: business-scoped endpoints take the business in the `X-Business-Id` header. Declare a `BusinessAccess` controller parameter to resolve it; use `access.require(Permission.X)` / `access.requireOwner()` and `access.godownScope().includes(id)`.
- Missing header → `422 BUSINESS_NOT_SELECTED`; malformed → `422 VALIDATION_FAILED`; a business the caller does not belong to (or that does not exist) → the same `404 NOT_FOUND`; suspended membership → `403 MEMBERSHIP_SUSPENDED` (other businesses stay accessible).
- Access is read from the database on every request, never from the token, so revoked grants and suspensions apply to the next request.
- The owner holds the whole `Permission` catalog and every godown. Workers are default-deny: exactly the rows in `membership_permission` (written by M01.S09; codes unknown to the application are ignored). Godown scopes are empty for workers until godowns (M03.S01) and M01.S10. Managing people, grants and ownership is owner-only and not in the grantable catalog.

## Invitations (M01.S06–S07)

| Endpoint | Purpose |
|---|---|
| `POST /v1/iam/invitations` `{mobile}` + `X-Business-Id` + `Idempotency-Key` | Owner only. Invites a mobile; returns `201` with the invitation `id`, normalized `mobile`, `status: PENDING`, `expiresAt`. The token is **not** in the response |
| `POST /v1/iam/invitations/accept` `{token}` + `Idempotency-Key` | The signed-in user whose mobile was invited joins as an `ACTIVE` worker with no permissions; returns `201` with `membershipId`, `businessId`, `businessName`, `role: WORKER` |

- Token: 256-bit, URL-safe, stored only as SHA-256, never returned by the API or kept in the idempotency record. It reaches the invitee through `InvitationSender` (SMS adapter, called after commit; the `dev` profile logs it; other profiles fail to start without one). Delivery failures are logged; the owner resends by inviting again.
- Validity: `dispatchpay.invitation.ttl` (7 days).
- Re-inviting a mobile supersedes its pending invitation, so only the newest token works (one pending invitation per business and mobile, enforced by a partial unique index). Invitations for one business serialize on the business row, which also re-checks ownership.
- Invite errors: worker → `403 ACCESS_DENIED`; mobile already a member (including the owner) → `409 ALREADY_MEMBER`.
- Accept errors: unknown token, or a token for another mobile → `404 NOT_FOUND` (indistinguishable); already accepted → `409 INVITATION_ALREADY_USED`; replaced → `410 INVITATION_SUPERSEDED`; expired → `410 INVITATION_EXPIRED`. A retry with the same `Idempotency-Key` replays the original `201`; acceptances of one token serialize on the invitation row.

## Workers (M01.S08)

| Endpoint | Purpose |
|---|---|
| `GET /v1/iam/workers?page=&size=` + `X-Business-Id` | Owner only. Paginated non-owner memberships: `membershipId`, `mobile`, `status`, `joinedAt`, oldest first |
| `GET /v1/iam/workers/{membershipId}` + `X-Business-Id` | Owner only. The same plus `permissions[]` and `godownScope` |

- Only membership facts and the invited mobile are exposed: never the user id, other businesses, sessions or devices.
- A membership id from another business, the owner's own membership, an unknown or malformed id all return the same `404 NOT_FOUND`. Workers get `403 ACCESS_DENIED`.

## Platform conventions (M00)

- **Errors**: RFC 9457 `application/problem+json` with `code`, `correlationId`, `timestamp`, and `errors[]` for validation. Codes live in `ErrorCode`; 422 for validation, 409 for conflicts.
- **Correlation**: `X-Correlation-Id` is accepted (8–64 chars of `[A-Za-z0-9._-]`) or generated, echoed on the response, logged via MDC and carried on outbox events.
- **Idempotency**: mutating commands go through `IdempotentCommandExecutor` with an `Idempotency-Key` header. Same key + same content replays the stored response (`Idempotency-Replayed: true`); different content → 409 `IDEMPOTENCY_KEY_REUSED`. Failed commands are not stored.
- **Outbox**: `OutboxWriter.append(...)` inside the business transaction; `OutboxPublisher` polls with `FOR UPDATE SKIP LOCKED`, retries with exponential backoff, parks as `DEAD` after `max-attempts`. Handlers implement `OutboxEventHandler` and are de-duplicated per event via `processed_event`.
- **Pagination**: declare a `PageQuery` controller parameter (`?page=0&size=20`, max 100) and return `PageResponse<T>`.
- **Time and money**: instants are UTC (`Clock` bean); money will be integer minor units, quantities `NUMERIC`.
