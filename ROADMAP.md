# ROADMAP

Актуально на: **2026-04-18**

Канонический список приоритетов для проекта `jwt-demo-reactive`.
Все отчеты ссылаются на этот файл, чтобы избежать рассинхронизации.

## High

- [x] Полные интеграционные auth тесты (Keycloak + WireMock)
- [x] Validation IT для `POST /api/auth/login` (unsupported media type)
- [x] DPoP integration/regression тесты (auth + protected endpoints)
- [x] RateLimiting IT (rule order, key strategy, i18n 429)
- [x] Security chain regression (single-pass, без двойной обработки фильтров)
- [x] Scheduler-driven async worker вместо `subscribe()` в request-path
- [x] Retry/backoff регрессии для transient/non-transient сценариев worker-а

## Medium

- [x] Оценить необходимость Quartz/распределенного lock для multi-instance request worker
- [x] Подготовить критерии выбора single-instance vs distributed lock
- [x] Добавить reclaim stale `PROCESSING` в worker (timeout-based requeue без Quartz)
- [x] Добавить reclaim-метрики `reclaimed_count` и `stale_processing_age`
- [x] Добавить индекс под reclaim-фильтр (`status`, `type`, `status_changed_at`)
- [x] Добавить 2-instance IT (два контекста против одной БД) для проверки отсутствия дублей `request.id`
- [x] Дополнительные проверки валидации и сообщений ошибок для account/request API
- [x] Финализировать API docs (Swagger/OpenAPI)
- [ ] Performance: оптимизация запросов и оценка кэширования
  - [x] SQL: перенести limit поиска клиентов в SQL (без post-fetch `take`)
  - [x] Security: добавить кэш интроспекции opaque token (TTL + max-size)
  - [x] DB: убрать лишние round-trip в create-пути клиента (`existsByPhone` -> upsert/insert-first стратегия)
  - [x] Worker: сократить reclaim-запросы (объединение метрик/апдейта, где возможно)
  - [x] DB: добавить индекс под claim-путь request worker (`status`, `type`, `created_at`)
  - [ ] Observability: добавить базовый perf smoke сценарий и метрики сравнения до/после

## Low

- [ ] Дополнительный мониторинг и кастомные метрики
- [ ] Reactive streams optimization
- [ ] GraphQL (опционально)

