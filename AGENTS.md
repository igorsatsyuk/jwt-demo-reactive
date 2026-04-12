# AGENTS.md

## Scope and baseline
- This guide is for `jwt-demo-reactive` (Spring Boot 4, Java 25, reactive stack).
- Existing AI/dev instructions discovered via glob: `README.md` (no project-local `AGENT.md`/`AGENTS.md`/`.cursorrules` etc. were found in this module).

## Big picture architecture (follow these boundaries)
- Entry points are reactive REST controllers in `src/main/java/lt/satsyuk/controller` returning `Mono<...>` and wrapping payloads into `AppResponse` (`AuthController`, `ClientController`, `RequestController`).
- Business logic lives in services under `src/main/java/lt/satsyuk/service`; controllers should stay thin and delegate orchestration.
- Persistence is R2DBC-first via Spring Data repositories (`ClientRepository`, `AccountRepository`, `RequestRepository`) and SQL migrations in `src/main/resources/db/migration`.
- Async client creation is a two-step flow: `POST /api/clients` -> enqueue request (`RequestService.submitClientCreateRequest`) -> scheduled worker claims/processes rows (`RequestService.processPendingRequests`).
- Security boundary is WebFlux resource server with opaque token introspection + custom role conversion (`SecurityConfig`, `KeycloakReactiveOpaqueTokenIntrospector`, `KeycloakOpaqueRoleConverter`).

## Request/data flow details that matter
- Standard response envelope is `AppResponse<T>` (`code=0` for success; domain error codes otherwise) in `src/main/java/lt/satsyuk/dto/AppResponse.java`.
- Request worker state machine is persisted in DB: `PENDING -> PROCESSING -> COMPLETED|FAILED` (`RequestStatus`, `RequestRepository`, `RequestService`).
- Multi-instance safety relies on SQL claiming with `FOR UPDATE SKIP LOCKED` in `RequestRepository.claimPendingClientCreateBatch`.
- Stale `PROCESSING` reclaim is explicit (`RequestRepository.reclaimStaleClientCreateRequests`) and indexed by `V2__add_request_reclaim_index.sql`.

## Security and filter chain specifics
- Public endpoints are limited to `/api/auth/**`, Swagger, and `/actuator/prometheus`; everything else requires auth (`SecurityConfig.securityWebFilterChain`).
- DPoP is enforced by `DpopAuthenticationWebFilter` when DPoP scheme/proof or token binding (`cnf.jkt`) is present.
- Rate limiting is rule-driven from `app.rate-limit.rules[*]` and executed in rule order (`RateLimitingWebFilter.sortedRules` + `RateLimitProperties.Rule.order`).
- Client identity for rate-limit keys comes from token attributes `azp`/`client_id` via `SecurityService`.

## Build/test workflow used in this codebase
- Build quickly: `mvn clean compile -DskipTests` (documented in `README.md`).
- Unit tests: `mvn test` (`maven-surefire-plugin` includes `*Test*`, excludes `*IT*` in `pom.xml`).
- Integration tests: `mvn verify` (`maven-failsafe-plugin` runs `*IT*`; Testcontainers-based infra in `AbstractIntegrationTest`).
- Coverage is split and merged (`jacoco-ut.exec`, `jacoco-it.exec`, merged report in `target/site/jacoco-merged`) configured in `pom.xml`.

## Test and infra patterns to preserve
- Integration base class `AbstractIntegrationTest` starts PostgreSQL Testcontainer and applies Flyway before each test setup.
- Keycloak-backed ITs extend `KeycloakIntegrationTest` (real Keycloak container + dynamic properties).
- Negative/upstream auth scenarios extend `WireMockIntegrationTest` (stubbed token/introspection/logout endpoints).
- Async behavior assertions use Awaitility polling (`RequestIntegrationIT`, `RequestWorkerMultiInstanceIT`).

## Project-specific coding conventions (non-generic)
- Keep APIs reactive end-to-end (`Mono`/`Flux`), avoid blocking in production paths; tests may use `.block()` for setup/assertions.
- Use localized messages through `MessageService` + `messages*.properties`; avoid hardcoding user-facing error text in handlers.
- For validation/error shaping, prefer `GlobalExceptionHandler` + typed domain exceptions (`*NotFoundException`, `PhoneAlreadyExistsException`, etc.).
- For new async request types, update all three layers together: DB constraints/migration (`request.type` check), worker claim/process logic, and integration tests.

