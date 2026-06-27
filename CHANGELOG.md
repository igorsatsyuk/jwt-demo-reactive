# Changelog

All notable changes to `jwt-demo-reactive` are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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


