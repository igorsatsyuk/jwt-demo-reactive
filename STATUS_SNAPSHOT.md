# STATUS SNAPSHOT

Актуально на: **2026-04-10**

```text
Проект: jwt-demo-reactive
Сборка: ✅ полный `mvn verify` проходит (47 tests, 0 failures, 0 errors)
Интеграционные тесты: ✅ расширены edge-case сценарии для `/api/requests/{id}` и конкурентных account update
Стабилизация IT-инфраструктуры: ✅ устранены падения WireMock (`Not listening on HTTP port`) и R2DBC (`Failed to obtain R2DBC Connection`) в verify
Подтвержденные IT: AccountIntegrationIT, AuthControllerIT, AuthValidationIT, DpopIntegrationIT, KeycloakIntegrationIT, KeycloakNegativeIT, RateLimitingIT, SecurityChainRegressionIT, RequestIntegrationIT, RequestWorkerRetryIT
PR: ✅ https://github.com/igorsatsyuk/jwt-demo-reactive/pull/1
Статус: ✅ READY FOR REVIEW
```
