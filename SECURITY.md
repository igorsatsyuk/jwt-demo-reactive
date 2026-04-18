# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.x (current) | Yes |

## Reporting a Vulnerability

If you discover a security issue, report it privately.

1. Do not open a public issue with exploit details
2. Contact maintainers via private channel/email
3. Include:
   - technical description
   - reproduction steps
   - impact
   - possible mitigation/fix

Target response timeline:
- Acknowledgment: within 48 hours
- Initial triage: within 5 business days
- Fix target: severity-based

---

## Security Architecture (Reactive)

- Authentication: Keycloak + OAuth2/OIDC
- Token validation: opaque token introspection (`KeycloakReactiveOpaqueTokenIntrospector`)
- Authorization: role checks via `@PreAuthorize` + reactive method security
- API security chain: WebFlux `SecurityWebFilterChain`
- DPoP support: custom reactive filters/converters (`DpopAwareServerBearerTokenAuthenticationConverter`, `DpopAuthenticationWebFilter`)

### Public Endpoints

Configured in `src/main/java/lt/satsyuk/config/SecurityConfig.java`:

- `/api/auth/**`
- `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`
- `/actuator/prometheus`
- `/actuator/health/**`, `/actuator/info`, `/actuator/metrics/**`

All other routes require authentication.

---

## DPoP Enforcement Rules

Protected endpoints support:

- `Authorization: Bearer <token>`
- `Authorization: DPoP <token>` + `DPoP: <proof-jwt>`

DPoP proof validation becomes mandatory when at least one condition is true:
- DPoP auth scheme is used
- `DPoP` header is present
- token introspection contains `cnf.jkt` binding

Validation checks include method/URL binding, `ath`, timestamp skew window, replay protection (`jti`), and key thumbprint match when `cnf.jkt` exists.

---

## Rate Limiting

Implemented by `src/main/java/lt/satsyuk/security/RateLimitingWebFilter.java` with Bucket4j + Caffeine caches.

Configured default rules (`src/main/resources/application.properties`):

- `POST /api/auth/login`: 5 req / 60s (key: IP)
- `/api/clients/**`: 20 req / 60s (key: CLIENT_ID for configured client ids)

Important details:
- Rules are evaluated in ascending `order`
- Rule matching includes path, optional method set, optional client-id allow list
- Client id is extracted from token attributes `azp` then fallback `client_id`
- Throttled response: HTTP `429`, `AppResponse.code=42901`, localized message

---

## Secrets and Configuration

Do not commit real secrets/tokens. Keep credentials in environment variables.

Key variables used by the app:

| Variable | Description |
|----------|-------------|
| `SPRING_DATASOURCE_PASSWORD` / `SPRING_R2DBC_PASSWORD` | App DB password |
| `KEYCLOAK_RESOURCE_CLIENT_ID` | Resource-server client for introspection |
| `KEYCLOAK_RESOURCE_CLIENT_SECRET` | Resource-server secret for introspection |
| `KEYCLOAK_AUTH_SERVER_URL` | Keycloak base URL |
| `SECURITY_DPOP_ENABLED` | Enables/disables DPoP validation |

Recommended practice:
- keep `.env` out of VCS
- use `.env.example` with placeholders
- rotate secrets on leak suspicion

---

## Traceability and Incident Response

The app can attach correlation headers:
- `X-Trace-Id`
- `X-Request-Id`

Use them for incident triage across logs and tracing backends.

Quick triage workflow:
1. Capture `X-Trace-Id` from failing response
2. Find matching logs in Loki
3. Inspect span timeline in Tempo
4. Confirm auth/DPoP/rate-limit branch from security logs

---

## Secure Development Checklist

- All new endpoints must have explicit authorization requirements
- User-facing errors must go through i18n message keys
- Prefer typed domain exceptions handled by `GlobalExceptionHandler`
- Keep reactive execution non-blocking in production paths
- Add/adjust integration tests for positive + negative security scenarios (Keycloak/WireMock)

