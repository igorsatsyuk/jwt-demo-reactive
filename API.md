# API Reference

Base URL: `http://localhost:8081`

- Swagger UI: `http://localhost:8081/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8081/v3/api-docs`

---

## Response Envelope

All endpoints return `AppResponse<T>`:

```json
{
  "code": 0,
  "data": { "...": "..." },
  "message": "OK"
}
```

Error envelope:

```json
{
  "code": 40001,
  "data": null,
  "message": "Validation error details"
}
```

### Response Headers

The app can attach correlation headers:

```http
X-Trace-Id: <32-char-hex-trace-id>
X-Request-Id: <request-id>
```

Use these values to correlate API errors with logs and traces.

### Application Error Codes (`AppResponse.ErrorCode`)

| Code  | Meaning |
|-------|---------|
| 0     | Success |
| 40001 | Bad request |
| 40101 | Unauthorized |
| 40102 | Invalid grant |
| 40103 | Invalid token |
| 40301 | Forbidden |
| 40401 | Not found |
| 40901 | Conflict |
| 42901 | Too many requests |
| 50000 | Internal server error |

---

## Authentication

DPoP support:
- Auth endpoints accept optional header `DPoP: <proof-jwt>` and pass it to Keycloak.
- Protected endpoints accept:
  - `Authorization: Bearer <access_token>`
  - `Authorization: DPoP <access_token>` + `DPoP: <proof-jwt>`
- If introspection payload contains `cnf.jkt`, DPoP proof is mandatory.

### POST /api/auth/login

Authenticate with username/password and client credentials.

Optional header:

```http
DPoP: <proof-jwt>
```

Request body:

```json
{
  "username": "user",
  "password": "password",
  "clientId": "spring-app",
  "clientSecret": "<client-secret>"
}
```

Success response (`200`):

```json
{
  "code": 0,
  "data": {
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "token_type": "Bearer",
    "expires_in": 300
  },
  "message": "OK"
}
```

Typical errors:
- `400` + `40001` validation issues
- `401` + `40101` Keycloak auth failure
- `429` + `42901` rate limit exceeded

---

### POST /api/auth/refresh

Request body:

```json
{
  "refreshToken": "eyJ...",
  "clientId": "spring-app",
  "clientSecret": "<client-secret>"
}
```

Success response (`200`): same shape as login.

Typical errors:
- `400` + `40001` validation issues
- `401` + `40102` invalid/expired refresh token

---

### POST /api/auth/logout

Request body:

```json
{
  "refreshToken": "eyJ...",
  "clientId": "spring-app",
  "clientSecret": "<client-secret>"
}
```

Success response (`200`):

```json
{
  "code": 0,
  "data": null,
  "message": "OK"
}
```

Typical errors:
- `400` + `40001` validation issues
- `401` + `40103` invalid token / upstream auth error

---

## Client Endpoints (Protected)

Accepted auth schemes for all protected endpoints:

```http
Authorization: Bearer <access_token>
```

or

```http
Authorization: DPoP <access_token>
DPoP: <proof-jwt>
```

> **Data isolation**: all client and account reads are scoped to the calling OAuth2 client (`azp` claim from the access token). Each OAuth2 system only sees clients and accounts it has access to via the `client_access` join table.

### POST /api/clients

Create asynchronous client creation request.

Required role: `CLIENT_CREATE`

Request body:

```json
{
  "firstName": "John",
  "lastName": "Doe",
  "phone": "+37061234567",
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000"
}
```

Validation:
- `firstName`: required, 1..50
- `lastName`: required, 1..50
- `phone`: required, `+[0-9]{7,15}`
- `idempotencyKey`: optional UUID

**Idempotency**: if `idempotencyKey` is provided, the server uses it for idempotent deduplication per client. Repeating the same key with the same payload returns the existing request (202). Repeating with a different payload returns 409 Conflict.

**Duplicate phone**: if a client with the same phone already exists, the server adds access for the calling OAuth2 client and returns the existing client (COMPLETED) instead of failing with 409.

Success response (`202`):

```json
{
  "code": 0,
  "data": {
    "requestId": "2e6a42a8-8bb7-4f7d-b4d6-71eb31ec8a13",
    "status": "PENDING"
  },
  "message": "OK"
}
```

Then poll `GET /api/requests/{requestId}` until terminal status.

---

### GET /api/requests/{id}

Get async request status and final payload (if processed).

Required role: `CLIENT_CREATE`

Path param:
- `id`: UUID request id

Success response (`200`, processing):

```json
{
  "code": 0,
  "data": {
    "requestId": "2e6a42a8-8bb7-4f7d-b4d6-71eb31ec8a13",
    "type": "CLIENT_CREATE",
    "status": "PROCESSING",
    "createdAt": "2026-03-19T12:00:00Z",
    "statusChangedAt": "2026-03-19T12:00:01Z",
    "response": null
  },
  "message": "OK"
}
```

Success response (`200`, completed):

```json
{
  "code": 0,
  "data": {
    "requestId": "2e6a42a8-8bb7-4f7d-b4d6-71eb31ec8a13",
    "type": "CLIENT_CREATE",
    "status": "COMPLETED",
    "createdAt": "2026-03-19T12:00:00Z",
    "statusChangedAt": "2026-03-19T12:00:02Z",
    "response": {
      "code": 0,
      "data": {
        "id": 1,
        "firstName": "John",
        "lastName": "Doe",
        "phone": "+37061234567"
      },
      "message": "OK"
    }
  },
  "message": "OK"
}
```

Typical errors:
- `400` + `40001` invalid UUID format
- `404` + `40401` request not found

---

### GET /api/clients/{id}

Get client by id.

Required role: `CLIENT_GET`

Success response (`200`):

```json
{
  "code": 0,
  "data": {
    "id": 1,
    "firstName": "John",
    "lastName": "Doe",
    "phone": "+37061234567"
  },
  "message": "OK"
}
```

---

### GET /api/clients/search?q={query}

Search by first/last name (case-insensitive).

Required role: `CLIENT_SEARCH`

Rules:
- `q` minimum length is validated by backend
- result size is capped by `app.clients.search.max-results`

Success response (`200`):

```json
{
  "code": 0,
  "data": [
    {
      "id": 1,
      "firstName": "Alice",
      "lastName": "Brown",
      "phone": "+37070000001"
    }
  ],
  "message": "OK"
}
```

Typical errors:
- `400` + `40001` query too short

---

## Account Endpoints (Protected)

> **Data isolation**: account reads and balance updates are scoped to the calling OAuth2 client via the `client_access` join table.

### POST /api/accounts/balance/pessimistic

Update account balance with pessimistic lock.

Required role: `UPDATE_BALANCE`

Request body:

```json
{
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000",
  "clientId": 1,
  "amount": 100.50
}
```

Validation:
- `idempotencyKey`: optional UUID for idempotent request tracking
- `clientId`: required, positive
- `amount`: required

**Idempotency**: if `idempotencyKey` is provided, duplicate requests with the same payload return the cached result. Different payload returns 409. Requests still in progress return 409 with `error.account.updateInProgress`.

Typical errors:
- `404` + `40401` account not found (or not accessible by this OAuth2 client)
- `409` + `40901` idempotency key conflict, request in progress, or optimistic lock conflict

---

### POST /api/accounts/balance/optimistic

Update account balance with optimistic lock retries.

Required role: `UPDATE_BALANCE`

Request body:

```json
{
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000",
  "clientId": 1,
  "amount": -50.00
}
```

**Idempotency**: same behavior as pessimistic endpoint above.

Typical errors:
- `404` + `40401` account not found (or not accessible by this OAuth2 client)
- `409` + `40901` idempotency key conflict, request in progress, or optimistic lock conflict after retries

---

### GET /api/accounts/client/{clientId}

Get account by client id.

Required role: `CLIENT_GET`

Success response shape for account endpoints:

```json
{
  "code": 0,
  "data": {
    "accountId": 1,
    "clientId": 1,
    "balance": 100.50
  },
  "message": "OK"
}
```

---

## Internationalization

The API supports language negotiation via `Accept-Language`.

Supported locales:
- `en`
- `ru`

Example:

```http
GET /api/clients/999999 HTTP/1.1
Authorization: Bearer <token>
Accept-Language: ru
```

```json
{
  "code": 40401,
  "data": null,
  "message": "Клиент с id=999999 не найден"
}
```

---

## Actuator Endpoints

| Endpoint | Access |
|----------|--------|
| `GET /actuator/health` | Public |
| `GET /actuator/info` | Public |
| `GET /actuator/metrics/**` | Public |
| `GET /actuator/prometheus` | Public |

---

## Rate Limits

Configured rules in `application.properties`:

| Rule | Endpoint pattern | Limit | Key strategy |
|------|------------------|-------|--------------|
| `login` | `/api/auth/login` (`POST`) | 5 req / 60 sec | IP |
| `clients` | `/api/clients/**` | 20 req / 60 sec | CLIENT_ID (for configured client ids) |

When throttled, API returns `429` with `AppResponse.code=42901`.

