# jwt-demo-reactive

Реактивный портированный проект на базе Spring Boot 4 / Java 25 с поддержкой WebFlux, R2DBC и OAuth2.

## Стек

- Java 25
- Spring Boot 4.0.3
- Spring WebFlux (реактивный веб-стек)
- Spring Security + OAuth2 Resource Server
- PostgreSQL
- R2DBC (реактивный доступ к БД)
- Flyway (миграции БД)
- MapStruct (маппинг сущностей)
- Lombok (уменьшение boilerplate кода)
- Bucket4j (rate limiting)
- Testcontainers (интеграционное тестирование)

## Где находится проект

`C:\Users\igors\IdeaProjects\jwt-demo\jwt-demo-reactive`

## Быстрый запуск

### Компиляция
```pwsh
Set-Location "C:\Users\igors\IdeaProjects\jwt-demo\jwt-demo-reactive"
mvn clean compile -DskipTests
```

### Локальный запуск (требует Docker)
```pwsh
# 1. В корне jwt-demo запустить инфраструктуру
docker compose up -d postgres postgres-app keycloak

# 2. Запустить реактивное приложение
mvn spring-boot:run
```

### Проверка тестов
```pwsh
# Unit-тесты
mvn test

# Интеграционные тесты (требует Docker)
mvn verify

# Точечный прогон auth/security-интеграций
mvn -DskipTests=false "-Dit.test=KeycloakIntegrationIT,KeycloakNegativeIT,AuthValidationIT,DpopIntegrationIT,RateLimitingIT,SecurityChainRegressionIT" verify
```

Подтвержденные интеграционные сценарии:
- `KeycloakIntegrationIT` — auth happy-path (login/refresh/logout) с реальным Keycloak
- `KeycloakNegativeIT` — негативные upstream сценарии через WireMock
- `AuthValidationIT` — unsupported media type для `POST /api/auth/login`
- `DpopIntegrationIT` — DPoP для `/api/auth/**` + DPoP-bound protected endpoint (включая replay/без proof)
- `RateLimitingIT` — порядок правил (`order`), key strategy (`IP`, `CLIENT_ID`) и i18n сообщений 429 (`en`/`ru`)
- `SecurityChainRegressionIT` — отсутствие двойной обработки security-фильтров (DPoP/RateLimit single-pass)
- `RequestIntegrationIT` — async request lifecycle через scheduler-worker (`PENDING -> COMPLETED|FAILED`) и nested response payload
- `RequestWorkerRetryIT` — retry/backoff сценарии worker-а (success-after-retry, retry exhaustion, no-retry for non-transient)

## 📊 Что уже портировано

✅ **Аутентификация**
- KeycloakReactiveAuthService (полная реактивная логика login/refresh/logout)
- AuthController (/api/auth/*)
- Поддержка DPoP proofs
- Миcrometer счетчики для мониторинга

✅ **Управление клиентами**
- ClientService (создание, получение, поиск)
- ClientController (/api/clients/*)
- ClientMapper (MapStruct интеграция)
- Автоматическое создание связанного Account

✅ **Асинхронная обработка**
- RequestService (управление состояниями запросов)
- RequestController (/api/requests/{id})
- Состояния: PENDING → PROCESSING → COMPLETED|FAILED

✅ **Security фильтры**
- RateLimitingWebFilter (Bucket4j интеграция)
- DpopAuthenticationWebFilter (DPoP валидация)
- TraceIdResponseHeaderWebFilter (трассировка)

✅ **Инфраструктура**
- WebClientConfig (реактивный HTTP клиент)
- SecurityService (интеграция с ReactiveSecurityContextHolder)
- GlobalExceptionHandler (единая обработка ошибок)

✅ **Интеграционные auth/security тесты**
- KeycloakIntegrationIT (real Keycloak Testcontainer)
- KeycloakNegativeIT (WireMock stubs)
- AuthValidationIT (unsupported media type)
- DpopIntegrationIT (auth + protected endpoints)
- RateLimitingIT (rule order, keys, 429 i18n)
- SecurityChainRegressionIT (отсутствие двойной обработки фильтров)
- RequestIntegrationIT (scheduler lifecycle request)
- RequestWorkerRetryIT (retry/backoff policy + log assertions)

✅ **Общие test utility**
- `TestTextUtils` для консистентных text/log assertions в IT

## 🚀 Следующие задачи

1. **Устойчивость async-воркера** — расширение edge-case регрессий для request/account
2. **Полное покрытие функциональности** — миграция оставшихся компонентов
3. **Документация и API-контракты** — финализация Swagger и гайдов

## 📌 Единый статус-блок (актуально на 2026-04-09)

Канонический статус проекта: [`STATUS_SNAPSHOT.md`](STATUS_SNAPSHOT.md).

Проект полностью готов к компиляции, полные auth-интеграции зафиксированы тестами, запуск локально возможен с Docker.

## 🔗 Дополнительная информация

- **PROGRESS.md** — Детальный список портированных компонентов и следующих шагов
- **AGENTS.md** (главный проект) — Архитектурные паттерны и описание проекта
