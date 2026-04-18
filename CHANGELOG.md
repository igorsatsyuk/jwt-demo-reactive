# Changelog

All notable changes to `jwt-demo-reactive` are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Reactive project documentation set aligned with `jwt-demo` structure:
  - `API.md`
  - `SECURITY.md`
  - `CONTRIBUTING.md`
  - `CHANGELOG.md`
- Full API reference with endpoint matrix, roles, error envelope examples, i18n behavior, and actuator/rate-limit sections.
- Security policy documenting reactive filter-chain boundaries, DPoP enforcement triggers, and incident correlation headers.
- Contribution guide for reactive coding constraints, async worker change protocol, and integration-test patterns.

### Changed
- `README.md` now includes a dedicated `Documentation` section with direct links to `API.md`, `SECURITY.md`, `CONTRIBUTING.md`, and `CHANGELOG.md`.
- Documentation wording is normalized to the reactive architecture (`Mono<AppResponse<...>>`, R2DBC, scheduler-driven async worker).

### Fixed
- Eliminated documentation drift between `README.md` and standalone docs by making markdown files the canonical references for API/security/contribution workflows.
- Removed stale roadmap note in changelog that previously listed API docs finalization as pending.

### Security
- Documented required credential/env-variable boundaries for introspection and DPoP-related settings
- Documented trace correlation headers (`X-Trace-Id`, `X-Request-Id`) for incident triage


