# jwt-demo-reactive: Портировка основного проекта

## ✅ Завершено на этом этапе

### 1. Инфраструктура и конфигурация
- ✅ **WebClientConfig** — Конфигурация реактивного WebClient с поддержкой наблюдаемости Micrometer
- ✅ **SecurityService** — Адаптация для работы с `ReactiveSecurityContextHolder` для получения информации о пользователе
- ✅ **GlobalExceptionHandler** — Добавлена обработка `KeycloakAuthException`

### 2. Аутентификация (OAuth2 + Keycloak)
- ✅ **KeycloakReactiveAuthService** — Полная портировка логики OAuth2 с поддержкой:
  - Реактивные методы login/refresh/logout
  - DPoP proof forwarding
  - Micrometer counters для мониторинга (auth.login, auth.refresh, auth.logout)
  - Обработка ошибок с корректным преобразованием HttpStatusCode → HttpStatus
- ✅ **AuthController** — Реактивный REST контроллер с:
  - Моно обработкой для login/refresh/logout
  - Обработкой исключений KeycloakAuthException
  - Поддержкой DPoP заголовков

### 3. Управление клиентами и аккаунтами
- ✅ **ClientMapper** — MapStruct интеграция для преобразования сущностей
- ✅ **ClientService** — Реактивная версия с:
  - Проверкой уникальности телефона
  - Созданием связанного Account с нулевым балансом
  - Поиском по имени/фамилии с ограничением результатов
- ✅ **ClientController** — Реактивный контроллер с операциями:
  - POST /api/clients — создание клиента (202 Accepted)
  - GET /api/clients/{id} — получение клиента
  - GET /api/clients/search?q=... — поиск клиентов

### 4. Асинхронная обработка запросов
- ✅ **RequestService** — Реактивная обработка с:
  - Scheduler-ориентированный воркер (`@Scheduled`) для фоновой обработки client create
  - Batch claim pending-запросов через БД и guarded обновления из `PROCESSING`
  - Управление состояниями: PENDING → PROCESSING → COMPLETED|FAILED
  - Хранение JSON payload в БД
- ✅ **RequestController** — Реактивный контроллер для:
  - GET /api/requests/{id} — получение статуса запроса

### 5. Security фильтры
- ✅ **RateLimitingWebFilter** — Исправлены ошибки:
  - Использование `getMethod()` вместо `getMethodValue()`
  - Интеграция с ReactiveSecurityContextHolder
  - Поддержка Bucket4j rate limiting
- ✅ **DpopAuthenticationWebFilter** — Исправлена обработка HTTP метода для DPoP валидации
- ✅ **TraceIdResponseHeaderWebFilter** — Существует и работает (переносит trace ID в заголовки ответов)

### 6. Модели и репозитории (R2DBC)
- ✅ **Client, Account, Request** — Модели R2DBC уже приготовлены
- ✅ **ClientRepository, AccountRepository, RequestRepository** — R2DBC репозитории с реактивными методами

### 7. Тестирование
- ✅ **AbstractIntegrationTest** — Базовый класс для интеграционных тестов с Testcontainers
- ✅ **AuthControllerIT** — Интеграционные smoke сценарии auth controller
- ✅ **KeycloakIntegrationIT** — Полные auth сценарии с реальным Keycloak контейнером
- ✅ **KeycloakNegativeIT** — Негативные upstream сценарии через WireMock
- ✅ **AuthValidationIT** — Отдельный IT на unsupported media type (`/api/auth/login`)
- ✅ **DpopIntegrationIT** — DPoP для auth + protected endpoints (включая replay и missing proof)
- ✅ **RateLimitingIT** — порядок правил (`order`), key strategy (`IP`, `CLIENT_ID`), i18n 429 (`en`/`ru`)
- ✅ **SecurityChainRegressionIT** — регрессии на отсутствие двойной обработки security-фильтров
- ✅ **RequestIntegrationIT** — расширены edge-cases для `/api/requests/{id}` (404, invalid UUID, 403, idempotency)
- ✅ **RequestWorkerRetryIT** — retry/backoff регрессии worker-а (transient retry, exhaustion, non-transient no-retry)
- ✅ **AccountIntegrationIT** — добавлены конкурентные сценарии optimistic/pessimistic обновления баланса
- ✅ **Test rate-limit config** — зафиксирован полный `app.rate-limit.rules[]`, чтобы не ломать биндинг
- ✅ **TestTextUtils** — общий helper для text/log assertions в IT
- ✅ **IT lifecycle stabilization** — устранены флаки WireMock/R2DBC в `mvn verify`

## 🎯 Приоритеты

Единый список приоритетов (`High/Medium/Low`) вынесен в [`ROADMAP.md`](ROADMAP.md).

## 🔧 Технические детали

### Ключевые различия между JPA и R2DBC
- **Синхронные операции** → **Mono/Flux реактивы**
- **@Transactional** → **@Transactional + ReactiveTransactionManager** или **TransactionTemplate**
- **SecurityContextHolder** → **ReactiveSecurityContextHolder**
- **RestTemplate** → **WebClient**

### Версии зависимостей
- Spring Boot: 4.0.3
- Java: 25
- MapStruct: 1.6.3
- Testcontainers: 1.20.4
- Bucket4j: 8.0.1

## 📝 Команды для разработки

```bash
# Компиляция без тестов
mvn clean compile -DskipTests

# Запуск unit тестов
mvn test

# Запуск интеграционных тестов
mvn verify

# Локальный запуск (требуется Docker)
# 1. Скопировать .env.example → .env
# 2. docker compose up -d postgres postgres-app keycloak
# 3. mvn spring-boot:run
```

## 📌 Единый статус-блок (актуально на 2026-04-10)

Канонический статус проекта: [`STATUS_SNAPSHOT.md`](STATUS_SNAPSHOT.md).

