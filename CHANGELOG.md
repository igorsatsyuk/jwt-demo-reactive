# Changelog

All notable changes to `jwt-demo-reactive` are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Row-level data isolation by auth_client_id**: all client and account reads are now scoped to the calling OAuth2 client (`azp` claim). New `client_access` M:N join table maps `(client_id, auth_client_id)` so multiple OAuth2 systems can share access to the same client record. Phone uniqueness remains global — duplicate phone now adds access and returns the existing client instead of 409.
- **Idempotency key support for balance updates**: `UpdateBalanceRequest` now accepts optional `UUID idempotencyKey` for idempotent deduplication. Balance operations are tracked in the `request` table with status lifecycle (PENDING → COMPLETED|FAILED). New request types `UPDATE_BALANCE_PESSIMISTIC` and `UPDATE_BALANCE_OPTIMISTIC`. Replays return cached results; in-progress requests return 409.
- `V6__create_client_access_table.sql` migration for the `client_access` join table.
- `V7__add_balance_update_request_types.sql` migration extending the `request.type` CHECK constraint.
- New exceptions: `ResourceAccessDeniedException` (403), `AccountUpdateInProgressException` (409).
- New i18n messages: `error.access.denied`, `error.account.updateInProgress`, `error.account.updateFailed` (en/ru).
- `ClientAccess` entity and `ClientAccessRepository` for R2DBC access control queries.
- `RequestService.createPendingRequestIfAbsent()`, `completeRequest()`, `failRequest()`, `jsonEquals()` for request lifecycle management.
- `AccountService` wraps balance operations with request lifecycle tracking including `StoredError` replay for failed requests.
- Unit and integration test coverage for data isolation scenarios and idempotency edge cases.
- Optional `idempotencyKey` (UUID) field in `POST /api/clients` for idempotent request deduplication per client. Same key + same payload returns existing request (202); different payload returns 409 Conflict.
- `auth_client_id` column on `request` table tracks the owning client from the token. `GET /api/requests/{id}` verifies ownership; old rows with `auth_client_id='unknown'` remain readable during transition.
- `HttpMessageNotReadableException` handler in `GlobalExceptionHandler` returns 400 with localized `error.request.invalidPayload` message.
- New error messages: `error.request.idempotencyKeyConflict`, `error.request.invalidPayload` (en/ru).
- Reactive project documentation set aligned with `jwt-demo` structure:
  - `API.md`
  - `SECURITY.md`
  - `CONTRIBUTING.md`
  - `CHANGELOG.md`
- Full API reference with endpoint matrix, roles, error envelope examples, i18n behavior, and actuator/rate-limit sections.
- Security policy documenting reactive filter-chain boundaries, DPoP enforcement triggers, and incident correlation headers.
- Contribution guide for reactive coding constraints, async worker change protocol, and integration-test patterns.
- Lightweight perf smoke toolkit:
  - `ops/perf/perf-smoke.ps1` to run `POST /api/auth/login` load and capture `/actuator/prometheus` metrics before/after
  - `ops/perf/README.md` with baseline workflow and report interpretation

### Changed
- `README.md` now includes a dedicated `Documentation` section with direct links to `API.md`, `SECURITY.md`, `CONTRIBUTING.md`, and `CHANGELOG.md`.
- Documentation wording is normalized to the reactive architecture (`Mono<AppResponse<...>>`, R2DBC, scheduler-driven async worker).

### Fixed
- Eliminated documentation drift between `README.md` and standalone docs by making markdown files the canonical references for API/security/contribution workflows.
- Removed stale roadmap note in changelog that previously listed API docs finalization as pending.

### Security
- Documented required credential/env-variable boundaries for introspection and DPoP-related settings
- Documented trace correlation headers (`X-Trace-Id`, `X-Request-Id`) for incident triage


