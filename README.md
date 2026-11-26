# DispatchPay backend

Spring Boot 4 / Java 21 modular monolith (Step 1 of the development plan).

## Run locally

```sh
cp .env.example .env          # set DB_PASSWORD, STORAGE_ACCESS_KEY, STORAGE_SECRET_KEY
docker compose up -d          # PostgreSQL 17 + MinIO (S3-compatible object storage)
./gradlew bootRun             # dev profile is the default; reads .env
```

Probes: `GET /actuator/health/liveness`, `GET /actuator/health/readiness` (readiness includes the database and the storage bucket). The `dev` profile creates the bucket (`dispatchpay-dev`) when it is missing; the MinIO console is at http://localhost:9001.

## Tests

```sh
./gradlew test                # needs Docker (Testcontainers starts PostgreSQL)
```

## Profiles and secrets

| Profile   | Purpose                                   | Configuration source                         |
|-----------|-------------------------------------------|----------------------------------------------|
| `dev`     | Local development (default)               | `.env` (git-ignored) + `application-dev.yml` |
| `staging` | Shared environment                        | Environment variables only: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `OTP_PEPPER`, `STORAGE_ENDPOINT`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` (optional `STORAGE_REGION`, `STORAGE_PUBLIC_ENDPOINT`); probes on `MANAGEMENT_PORT` (8081). The bucket must already exist |
| `test`    | Automated tests                           | Testcontainers (PostgreSQL, MinIO)           |

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

- **Tenant selection**: business-scoped endpoints take the business in the `X-Business-Id` header. Declare a `BusinessAccess` controller parameter to resolve it; use `access.require(Permission.X)` / `access.requireOwner()` and `access.requireGodown(id)` (or `access.godownScope().includes(id)`).
- Missing header → `422 BUSINESS_NOT_SELECTED`; malformed → `422 VALIDATION_FAILED`; a business the caller does not belong to (or that does not exist) → the same `404 NOT_FOUND`; suspended membership → `403 MEMBERSHIP_SUSPENDED` (other businesses stay accessible).
- Access is read from the database on every request, never from the token, so revoked grants and suspensions apply to the next request.
- The owner holds the whole `Permission` catalog and every godown. Workers are default-deny: exactly the rows in `membership_permission` (written by M01.S09; codes unknown to the application are ignored). A worker's godown scope is exactly the rows in `membership_godown` (M01.S10). Managing people, grants and ownership is owner-only and not in the grantable catalog.

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

## Worker access changes (M01.S09–S12)

| Endpoint | Purpose |
|---|---|
| `PUT /v1/iam/workers/{id}/permissions` `{permissions[], version}` + `X-Business-Id` + `Idempotency-Key` | Owner only. Replaces the worker's grants with exactly `permissions` (catalog codes only; anything else → `422`). Returns the worker detail with the new `version` |
| `PUT /v1/iam/workers/{id}/godowns` `{godownIds[], version}` + `X-Business-Id` + `Idempotency-Key` | Owner only. Replaces the worker's godown scope with exactly `godownIds`. Any id that is not a godown of this business (another tenant's or unknown, reported alike) → `422` and nothing changes; the database also refuses cross-tenant scope rows |
| `POST /v1/iam/workers/{id}/suspend` + `X-Business-Id` | Owner only. Suspends the membership in this business; repeating is a no-op. Returns the worker detail |
| `POST /v1/iam/workers/{id}/reactivate` `{permissions[], version}` + `X-Business-Id` + `Idempotency-Key` | Owner only. Reactivates a suspended worker with exactly the listed (reviewed) grants: grants held before suspension are kept only if listed again. Not suspended → `409 CONFLICT` |

- **Authorization version**: the membership's `version` (shown in the worker detail) increases with every access change, including suspension. A permission change sent with an older `version` → `409 VERSION_CONFLICT`; concurrent changes serialize on the business row and the owner is re-checked under that lock.
- Access is evaluated from the database on every request (there is no permission cache), so a revoked permission or a suspension blocks the worker's next request, even with an access token issued earlier.
- Suspension affects only this business: the user's account, sessions and other memberships are untouched. A suspended worker gets `403 MEMBERSHIP_SUSPENDED` here, including when confirming anything prepared before suspension.
- **Godown scope**: a worker may act only in the godowns assigned to them, whatever action permissions they hold. Operations on a godown call `access.requireGodown(id)` → `403 ACCESS_DENIED` outside scope.
- Every change is audited with its before/after (see below).

## Godowns (M03.S01)

| Endpoint | Purpose |
|---|---|
| `GET /v1/godowns?page=&size=` + `X-Business-Id` | Godowns in the caller's scope (owner: all; worker: assigned ones), by name: `id`, `name`, `createdAt`, `updatedAt`, `version` |
| `POST /v1/godowns` `{name}` + `X-Business-Id` + `Idempotency-Key` | Requires `GODOWN_MANAGE`. Returns `201` |
| `PATCH /v1/godowns/{id}` `{name, version}` + `X-Business-Id` + `Idempotency-Key` | Requires `GODOWN_MANAGE` and the godown in scope. Older `version` → `409 VERSION_CONFLICT` |

- Names are unique per business, ignoring case (`409 CONFLICT`).
- A godown of another business, an unknown or malformed id → `404 NOT_FOUND`; one of this business outside a worker's scope → `403 ACCESS_DENIED`.
- Godowns are referenced through `(business_id, id)` composite keys, so other tables cannot point at another tenant's godown.

## Products (M03.S02–S04)

| Endpoint | Purpose |
|---|---|
| `GET /v1/products?q=&includeInactive=&page=&size=` + `X-Business-Id` | Requires `PRODUCT_READ`. Search by SKU, name or approved alias (case-insensitive, contains; wildcards are literal). Exact matches first, then by name. Each item: `id`, `sku`, `name`, `baseUnit`, `status`, `brand`, `packSize`, `price`, `matchedAliases[]`. Inactive products only with `includeInactive=true` |
| `GET /v1/products/{id}` + `X-Business-Id` | Requires `PRODUCT_READ`. Any product of the business, active or not, with `aliases[]`, `conversions[]` and `version` |
| `POST /v1/products` `{sku, name, baseUnit, brand?, packSize?, price?}` + `X-Business-Id` + `Idempotency-Key` | Requires `PRODUCT_MANAGE`. Created `ACTIVE`. `201` |
| `PATCH /v1/products/{id}` `{sku?, name?, status?, brand?, packSize?, price?, version}` + `X-Business-Id` + `Idempotency-Key` | Requires `PRODUCT_MANAGE`. Omitted fields stay; `null` clears `brand`, `packSize` or `price`; unknown fields → `422`. `status` is `ACTIVE`/`INACTIVE`. Older `version` → `409 VERSION_CONFLICT`; an edit that changes nothing keeps the version |
| `POST /v1/products/{id}/aliases` `{alias}` + `X-Business-Id` + `Idempotency-Key` | Requires `PRODUCT_MANAGE`. Adds an alias approved by the caller. `201` |
| `PUT /v1/products/{id}/unit-conversions` `{conversions: [{unit, factor}], version}` + `X-Business-Id` + `Idempotency-Key` | Requires `PRODUCT_MANAGE`. Replaces all conversions; `factor` = base units in one `unit` (e.g. `BOX` = 12 for a `PCS` product) |

- **SKU**: 1–64 of `[A-Za-z0-9._/-]`, unique per business ignoring case (`409 CONFLICT`); another business may use the same SKU.
- **Brand** (≤ 120 chars) and **pack size** (≤ 60 chars, free text such as `500 g` or `Pack of 6`) are optional and help tell similar products apart. **Price** is optional: the selling price of one base unit in integer paise (`2800` = ₹28.00), 0 to 10¹²; decimals → `422`.
- **Units**: `PCS`, `PAIR`, `DOZEN`, `G`, `KG`, `QUINTAL`, `TONNE`, `ML`, `L`, `M`, `BOX`, `CARTON`, `PACK`, `BAG`, `BUNDLE`, `ROLL`, `BOTTLE`, `CAN`, `SET` (case-insensitive on input). Anything else → `422`.
- **Stable history**: products are never deleted (deactivate instead) and the base unit never changes (`422`; the database enforces both). Documents should still copy the SKU, name and unit they used.
- **Aliases**: matched after lower-casing and collapsing whitespace. Unique per product (`409`), but the same alias may be approved for several products, and search then returns every one of them as a candidate — nothing picks one silently.
- **Conversions** are rejected (`422`, nothing changes) when: the unit is the base unit; the factor is not positive; it has more than 6 decimals or 13 integer digits; a unit is listed twice; or both units are standard units of one dimension and the factor contradicts the fixed ratio (a `KG` product's `G` is `0.001`, a `PCS` product's `DOZEN` is `12`, a `DOZEN` product's `PCS` is `0.083333`). Packaging units take any valid factor. Factors are `NUMERIC(19,6)` and returned without trailing zeros.
- Products, aliases and conversions are tenant-scoped through composite keys; another business's product → `404`.

## Customers (M03.S05–S07)

| Endpoint | Purpose |
|---|---|
| `GET /v1/customers?q=&page=&size=` + `X-Business-Id` | Requires `CUSTOMER_READ`. Search by username, name, phone or approved alias (case- and spacing-insensitive, contains). Exact username, name or alias matches first, then by name. Each item: `id`, `username`, `name`, `contactPerson`, `phone`, `matchedAliases[]` |
| `GET /v1/customers/{id}` + `X-Business-Id` | Requires `CUSTOMER_READ`. Contacts, `credit`, `aliases[]`, `createdByMembershipId`, `updatedByMembershipId`, timestamps and `version` |
| `POST /v1/customers` `{username, name, contactPerson?, phone?, address?, paymentTermsDays?, creditLimit?}` + `X-Business-Id` + `Idempotency-Key` | Requires `CUSTOMER_MANAGE`. `201` |
| `PATCH /v1/customers/{id}` `{…, version}` + `X-Business-Id` + `Idempotency-Key` | Requires `CUSTOMER_MANAGE`. Omitted fields stay; `null` clears any field except `username` and `name`; unknown fields → `422`. Older `version` → `409 VERSION_CONFLICT` |
| `POST /v1/customers/{id}/aliases` `{alias}` + `X-Business-Id` + `Idempotency-Key` | Requires `CUSTOMER_MANAGE`. Adds an alias explicitly approved by the caller. `201` |

- **Username**: required, 3–40 of `[A-Za-z0-9._-]` starting with a letter or digit, stored lower-case. Unique within the business ignoring case (`409 CONFLICT`, also enforced by the database); another business may use the same username. It can be changed but not cleared. Customers created before it existed were given `c-` + their id.
- **Receivables fields**: `credit {paymentTermsDays, creditLimit}` is returned only to holders of `CUSTOMER_RECEIVABLES_READ` (otherwise `credit` is `null`), and setting either field needs that permission too (`403`). `paymentTermsDays` is 0–365 (0 = cash); `creditLimit` is in paise, `null` = no limit set.
- **Attribution**: every customer records the member who created it and who last changed it; each change is also audited with its diff.
- **Never merged**: names (unlike usernames) are not unique; similar or identical names stay separate customers and no alias is created automatically. An alias is unique per customer (`409`) but may be approved for several customers; search then returns every plausible match.
- `phone` is 6–20 digits with an optional leading `+`, spaces or `-`. Another business's customer → `404`; search never returns another tenant's customers.

## Imports (M04.S01–S04)

| Endpoint | Purpose |
|---|---|
| `POST /v1/imports` `{type, fileName, sizeBytes}` + `X-Business-Id` + `Idempotency-Key` | Registers an import (`PRODUCTS`, `CUSTOMERS` or `OPENING_STOCK`) and returns `201` with `status: AWAITING_UPLOAD` and `upload {url, method: PUT, headers, expiresAt}` |
| `PUT <upload.url>` (to object storage, not the API) | Upload the file with exactly the returned `headers` (`content-type: text/csv`, `content-length`). The URL lives 15 min and accepts only the declared size |
| `POST /v1/imports/{id}/validate` + `Idempotency-Key` | `202`, `status: VALIDATING`. A worker parses the file into staged rows; nothing else changes. Not uploaded yet → `409 FILE_NOT_UPLOADED` |
| `GET /v1/imports/{id}?page=&size=&errorsOnly=` | Status, `totalRows`, `errorRows`, `failure {code, message}`, `commitAvailable`, and a page of `rows[] {rowNumber, values, errors[], resourceId}` |
| `POST /v1/imports/{id}/commit` + `Idempotency-Key` | `202`, `status: COMMITTING`. Only a `VALIDATED` import (zero row errors); otherwise `409 CONFLICT`. Opening stock → `409 IMPORT_COMMIT_UNAVAILABLE` |
| `GET /v1/imports/{id}/errors` | `{url, fileName, expiresAt}`: a 5-minute download link for a CSV of the rows with errors. No row errors → `409` |

- **Lifecycle**: `AWAITING_UPLOAD → VALIDATING → VALIDATED | INVALID`, then `VALIDATED → COMMITTING → COMMITTED | FAILED`. `INVALID`, `COMMITTED` and `FAILED` are final: to retry, fix the file and create a new import.
- **Access**: every endpoint needs `IMPORT_MANAGE` plus the type's permission — `PRODUCT_MANAGE`, `CUSTOMER_MANAGE` or `STOCK_INWARD` (`403` otherwise). Customer `paymentTermsDays`/`creditLimit` also need `CUSTOMER_RECEIVABLES_READ`; opening-stock godowns must be in the member's scope. Another business's import → `404`.
- **Files**: CSV only — a `.csv` name without path separators, `text/csv`, UTF-8 (a BOM is fine), at most 2 MB and 5,000 data rows (`dispatchpay.imports.*`). The first row names the columns (any order, case-insensitive); unknown, duplicate or missing required columns reject the file. Blank rows are skipped; row numbers count the header as row 1, as in a spreadsheet.
- **Columns** (`*` required). Money is integer paise; lists are `|`-separated.
  - `PRODUCTS`: `sku*, name*, baseUnit*, brand, packSize, price, aliases, conversions` — e.g. `SOAP-01,Lux Soap,PCS,Lux,100 g,2800,lux|lux sabun,BOX=12|CARTON=144`
  - `CUSTOMERS`: `username*, name*, contactPerson, phone, address, paymentTermsDays, creditLimit, aliases`
  - `OPENING_STOCK`: `sku*, godown*, quantity*, unit` — godown by name; `unit` defaults to the base unit and otherwise needs a conversion; one row per product and godown
- **Create only**: an existing SKU or username, or one repeated in the file, is a row error. Values follow exactly the same rules as the API.
- **Errors**: file problems end validation with `failure.code` `FILE_MISSING`, `FILE_TOO_LARGE`, `FILE_NOT_CSV`, `FILE_ENCODING`, `HEADER_INVALID`, `NO_ROWS` or `TOO_MANY_ROWS`. Row problems give `failure.code: ROW_ERRORS` and per-row `errors[] {column, code, message}` with stable codes (`REQUIRED`, `TOO_LONG`, `INVALID_FORMAT`, `INVALID_NUMBER`, `OUT_OF_RANGE`, `INVALID_UNIT`, `INVALID_CONVERSION`, `DUPLICATE_ALIAS`, `DUPLICATE_IN_FILE`, `ALREADY_EXISTS`, `PERMISSION_REQUIRED`, `UNKNOWN_PRODUCT`, `PRODUCT_INACTIVE`, `UNKNOWN_GODOWN`, `GODOWN_NOT_IN_SCOPE`, `UNIT_NOT_CONVERTIBLE`, `COLUMN_COUNT`).
- **Commit is all-or-nothing**: the worker re-checks every staged row against current data and the committing member's current access, then creates all of them in one transaction. If anything no longer passes (e.g. a SKU created meanwhile) nothing is written: `FAILED` with `COMMIT_CONFLICT` and the offending rows' errors. Lost access → `ACCESS_REVOKED`. Each committed row records the id it created; retries cannot duplicate.
- **Workers**: the import's status is the job. Workers claim it with a lease (`FOR UPDATE SKIP LOCKED`), read the file outside any transaction and finish in one transaction only while still holding the lease. A crashed worker's step is taken over when its lease (5 min) expires; a step failing `max-attempts` (5) times ends as `PROCESSING_FAILED`.
- **Storage**: the bucket is private. The source file is `imports/{businessId}/{importId}/source.csv`; the error report is written next to it on first download. Its cells starting with `= + - @`, tab or CR get a leading `'` so spreadsheets never evaluate them.
- **Audit**: `import.create`, `import.validate`, `import.commit` (the member), `import.validation.finish`, `import.commit.finish` (system, with the member as initiator). Imported products and customers are audited as `product.create`/`customer.create` with `source: IMPORT`, actor `SYSTEM` and the committing member as initiator; that member is also their creator and the approver of their aliases.

## Audit (M02.S01–S02)

**Writing** — `AuditWriter.append(AuditEvent)` inside the mutation's transaction (it refuses to run without one), so an event exists exactly when its mutation commits.

- An event records: business, `actorType` (`USER`/`SYSTEM`), actor membership, initiator, approver, `source` (`MANUAL`, `VOICE`, `IMPORT`, `SYNC`, `SYSTEM`), `action`, resource type and id, field diff `{field: {before, after}}`, and the request's correlation id. `AuditEvent.manual(...)` covers a member's direct API action.
- A `SYSTEM` actor never carries a membership (checked in code and by `ck_audit_event_actor`); background work names the user who caused it as initiator instead.
- Membership references are tenant-safe composite keys: an event can only be attributed to members of its own business.
- `audit_event` is append-only: `UPDATE` and `DELETE` are refused by trigger.
- Fields whose names contain `token`, `secret`, `password`, `pepper`, `otp` or `hash` are stored as `[REDACTED]`. Invitations record only the masked mobile.
- Audited today: `business.create`, `iam.invitation.create`, `iam.invitation.accept`, `iam.worker.permissions.replace`, `iam.worker.godowns.replace`, `iam.worker.suspend`, `iam.worker.reactivate`, `godown.create`, `godown.update`, `product.create`, `product.update`, `product.alias.add`, `product.conversions.replace`, `customer.create`, `customer.update`, `customer.alias.add`, `import.create`, `import.validate`, `import.validation.finish`, `import.commit`, `import.commit.finish`.

**Reading** — `GET /v1/audit-events` + `X-Business-Id`, requires `AUDIT_READ` (the owner holds it; workers only if granted, else `403`). Newest first, paginated (`page`, `size`), optional `resourceType`, `resourceId`, `from` (inclusive) and `to` (exclusive) ISO-8601 instants; invalid filters → `422`. Only the selected business's history is visible, and sensitive fields are redacted again on read.

## Platform conventions (M00)

- **Errors**: RFC 9457 `application/problem+json` with `code`, `correlationId`, `timestamp`, and `errors[]` for validation. Codes live in `ErrorCode`; 422 for validation, 409 for conflicts.
- **Correlation**: `X-Correlation-Id` is accepted (8–64 chars of `[A-Za-z0-9._-]`) or generated, echoed on the response, logged via MDC and carried on outbox events.
- **Idempotency**: mutating commands go through `IdempotentCommandExecutor` with an `Idempotency-Key` header. Same key + same content replays the stored response (`Idempotency-Replayed: true`); different content → 409 `IDEMPOTENCY_KEY_REUSED`. Failed commands are not stored.
- **Outbox**: `OutboxWriter.append(...)` inside the business transaction; `OutboxPublisher` polls with `FOR UPDATE SKIP LOCKED`, retries with exponential backoff, parks as `DEAD` after `max-attempts`. Handlers implement `OutboxEventHandler` and are de-duplicated per event via `processed_event`.
- **Pagination**: declare a `PageQuery` controller parameter (`?page=0&size=20`, max 100) and return `PageResponse<T>`.
- **Time and money**: instants are UTC (`Clock` bean); money will be integer minor units, quantities `NUMERIC`.
