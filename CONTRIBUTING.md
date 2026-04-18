# Contributing to jwt-demo-reactive

Thanks for contributing. This guide describes project-specific workflow and standards.

## Table of Contents

- Development Environment Setup
- Project Structure
- Coding Standards
- Branch Naming
- Commit Messages
- Pull Request Process
- Testing
- Code Review Checklist

---

## Development Environment Setup

### Prerequisites

| Tool | Version |
|------|---------|
| Java (JDK) | 25+ |
| Maven | 3.9+ |
| Docker Desktop | recent stable |
| Git | 2.40+ |

### First-time setup

1. Clone repo and enter directory
2. Create `.env` from template
3. Fill required secrets
4. Start local infra (`postgres`, `keycloak`)
5. Build and run tests

PowerShell example:

```pwsh
Set-Location <repo-root>
Copy-Item .env.example .env
docker compose up -d postgres keycloak
mvn clean compile -DskipTests
mvn test
```

---

## Project Structure

```text
src/main/java/lt/satsyuk/
|- auth/        # Auth entry points and handlers
|- config/      # Security, OpenAPI, rate-limit properties
|- controller/  # Reactive REST controllers (Mono<AppResponse<...>>)
|- dto/         # Request/response DTOs and envelope
|- exception/   # Domain exceptions + GlobalExceptionHandler
|- mapper/      # Mappers
|- model/       # Persistence models
|- repository/  # Reactive repositories (R2DBC)
|- security/    # DPoP, introspection helpers, filters
`- service/     # Business logic and orchestration

src/main/resources/
`- db/migration/  # Flyway migrations

src/test/java/lt/satsyuk/
|- api/integrationtest/  # Testcontainers integration suites
|- api/util/             # Test bases/utilities
`- ...                   # unit tests
```

---

## Coding Standards

### General

- Java 25
- Spring Boot 4, WebFlux-first
- Keep controllers thin; business logic in services
- Preserve non-blocking flow in production code

### Reactive Conventions (Important)

- APIs remain reactive end-to-end (`Mono`/`Flux`)
- Avoid `.block()` in production code
- `.block()` is acceptable in tests for setup/assertions
- Do not call `subscribe()` from request path for business side effects

### Validation, Errors, i18n

- Use Bean Validation on DTOs
- Use typed domain exceptions (`*NotFoundException`, `PhoneAlreadyExistsException`, etc.)
- Route error shaping through `GlobalExceptionHandler`
- User-facing messages must be message-key based via `MessageService` and `messages*.properties`

### Async Request Worker Rules

For new async request types, update all three layers together:
1. DB constraints/migrations (`request.type` checks)
2. Worker claim/process logic (`RequestService`/`RequestRepository`)
3. Integration coverage (including multi-instance/concurrency behavior)

---

## Branch Naming

Recommended prefixes:

| Prefix | Purpose | Example |
|--------|---------|---------|
| `feature/` | New feature | `feature/add-client-export` |
| `bugfix/` | Bug fix | `bugfix/fix-dpop-proof-check` |
| `hotfix/` | Urgent fix | `hotfix/fix-auth-regression` |
| `chore/` | Maintenance | `chore/update-dependencies` |
| `docs/` | Documentation changes | `docs/update-api-reference` |
| `test/` | Test changes | `test/add-worker-it` |

---

## Commit Messages

Use Conventional Commits:

```text
<type>(<scope>): <short description>
```

Examples:

```text
feat(request): add stale processing reclaim metric
fix(security): enforce dpop proof for bound token
test(integration): add multi-instance claim regression
```

---

## Pull Request Process

1. Branch from `master`
2. Implement changes with tests
3. Ensure checks pass
4. Open PR with clear description and risk notes
5. Address review comments

Baseline verification commands:

```pwsh
mvn test
mvn verify
```

---

## Testing

### Test Categories

| Type | Pattern | Command |
|------|---------|---------|
| Unit tests | `*Test*` | `mvn test` |
| Integration tests | `*IT*` | `mvn verify` |

### Integration Patterns to Preserve

- `AbstractIntegrationTest`: PostgreSQL Testcontainer + Flyway prep
- `KeycloakIntegrationTest`: real Keycloak container scenarios
- `WireMockIntegrationTest`: upstream negative scenarios
- Awaitility polling for async worker behavior assertions

### Run Specific Suites

```pwsh
mvn -Dtest=KeycloakAuthServiceTest test
mvn -DskipTests=false "-Dit.test=RequestWorkerMultiInstanceIT" verify
```

---

## Code Review Checklist

- [ ] Reactive flow preserved (no blocking in production path)
- [ ] Security rules and roles are explicit for new endpoints
- [ ] i18n keys added/updated in `messages.properties`, `messages_en.properties`, `messages_ru.properties`
- [ ] Flyway migrations included for schema changes
- [ ] Unit and integration tests added/updated
- [ ] `mvn test` and `mvn verify` pass locally
- [ ] Swagger/OpenAPI annotations updated for API changes

