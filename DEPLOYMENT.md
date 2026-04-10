# 🚀 Deployment Guide: jwt-demo-reactive

## Быстрый старт

### Требования
- Docker & Docker Compose
- Java 25+ (или используйте Docker image)
- Maven 3.9+ (для локальной разработки)

### Шаг 1: Подготовка инфраструктуры

```bash
# Перейти в корень проекта jwt-demo
cd <repo-root>/jwt-demo

# Скопировать и заполнить переменные окружения
cp .env.example .env
# Отредактировать .env с нужными значениями Keycloak/Postgres

# Запустить контейнеры
docker compose up -d postgres postgres-app keycloak
```

### Шаг 2: Запуск приложения

#### Вариант A: Локально через Maven
```bash
cd jwt-demo-reactive
mvn spring-boot:run
```

#### Вариант B: Через Docker
```bash
# Создать Docker image (если нужно)
docker build -t jwt-demo-reactive:latest .

# Запустить контейнер
docker run --network jwt-demo_default \
  -e KEYCLOAK_URL=http://keycloak:8080 \
  -e DB_URL=r2dbc:postgresql://postgres-app:5432/appdb \
  -p 8080:8080 \
  jwt-demo-reactive:latest
```

#### Вариант C: Запуск JAR напрямую
```bash
# После mvn verify
java -jar target/jwt-demo-reactive-0.0.1-SNAPSHOT.jar
```

### Шаг 3: Проверка доступности

```bash
# Проверить health endpoint
curl http://localhost:8080/actuator/health

# Ответ (должен быть 200):
# {"status":"UP","components":{"db":{"status":"UP"},...}}

# Проверить API docs
curl http://localhost:8080/swagger-ui.html

# Проверить Prometheus метрики
curl http://localhost:8080/actuator/prometheus
```

---

## 📋 API Endpoints

### Аутентификация

```bash
# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "user",
    "password": "password",
    "clientId": "spring-app",
    "clientSecret": "spring-app-secret"
  }'

# Response:
# {
#   "code": 0,
#   "data": {
#     "access_token": "eyJhbGc...",
#     "refresh_token": "eyJhbGc...",
#     "expires_in": 60
#   },
#   "message": "OK"
# }

# Refresh Token
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "spring-app",
    "clientSecret": "spring-app-secret",
    "refreshToken": "eyJhbGc..."
  }'

# Logout
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "spring-app",
    "clientSecret": "spring-app-secret",
    "refreshToken": "eyJhbGc..."
  }'
```

### Управление клиентами

```bash
# Получить токен доступа
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password","clientId":"spring-app","clientSecret":"spring-app-secret"}' \
  | jq -r '.data.access_token')

# Создать клиента (асинхронно, 202 Accepted)
curl -X POST http://localhost:8080/api/clients \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John",
    "lastName": "Doe",
    "phone": "+37060000000"
  }'

# Response:
# {
#   "code": 0,
#   "data": {
#     "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
#     "status": "PENDING"
#   },
#   "message": "OK"
# }

# Получить статус запроса
curl -X GET "http://localhost:8080/api/requests/f47ac10b-58cc-4372-a567-0e02b2c3d479" \
  -H "Authorization: Bearer $TOKEN"

# Получить клиента
curl -X GET http://localhost:8080/api/clients/1 \
  -H "Authorization: Bearer $TOKEN"

# Поиск клиентов
curl -X GET "http://localhost:8080/api/clients/search?q=John" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 🔧 Конфигурация

### application.properties (основные параметры)

```properties
# Spring
spring.application.name=jwt-demo-reactive
spring.profiles.active=prod

# R2DBC PostgreSQL
spring.r2dbc.url=r2dbc:postgresql://localhost:5432/appdb
spring.r2dbc.username=app
spring.r2dbc.password=app
spring.r2dbc.pool.max-size=20
spring.r2dbc.pool.min-idle=5

# Flyway
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# Security
spring.security.oauth2.resourceserver.opaquetoken.introspection-uri=http://keycloak:8080/realms/my-realm/protocol/openid-connect/token/introspect
spring.security.oauth2.resourceserver.opaquetoken.client-id=resource-server
spring.security.oauth2.resourceserver.opaquetoken.client-secret=resource-server-secret

# Keycloak (для auth сервиса)
app.keycloak.url=http://keycloak:8080
app.keycloak.realm=my-realm
app.keycloak.token-url=http://keycloak:8080/realms/my-realm/protocol/openid-connect/token
app.keycloak.logout-url=http://keycloak:8080/realms/my-realm/protocol/openid-connect/logout

# Actuator & Monitoring
management.endpoints.web.exposure.include=health,metrics,prometheus
management.endpoint.health.show-details=when-authorized
management.tracing.sampling.probability=1.0

# Logging
logging.level.root=INFO
logging.level.lt.satsyuk=DEBUG
```

---

## 🧪 Тестирование

### Unit тесты

```bash
cd jwt-demo-reactive
mvn test
```

### Интеграционные тесты (требует Docker)

```bash
# Запустить только интеграционные тесты
mvn verify

# Запустить конкретный тест
mvn -DskipTests=false -Dit.test=AuthControllerIT verify

# С подробными логами
mvn verify -X
```

### Проверка покрытия

```bash
# Unit test coverage
open target/site/jacoco/index.html

# Integration test coverage
open target/site/jacoco-it/index.html
```

---

## 📊 Мониторинг

### Prometheus метрики

```bash
# Доступны на:
curl http://localhost:8080/actuator/prometheus

# Важные метрики:
# auth_login_total — счетчик попыток входа
# auth_refresh_total — счетчик обновлений токенов
# auth_logout_total — счетчик выходов
# http_server_requests_seconds — latency endpoints
# jvm_memory_used_bytes — использование памяти
```

### Health checks

```bash
curl http://localhost:8080/actuator/health

# Компоненты:
# - db: статус подключения к PostgreSQL
# - diskSpace: доступное место на диске
# - ping: базовая проверка жизнеспособности
```

### Логирование

```bash
# Логи приложения
docker logs -f jwt-demo-reactive

# Логи всех контейнеров
docker compose logs -f

# Прямой доступ к логам (если running локально)
# Логи приложения: ./jwt-demo-reactive/logs/
```

---

## 🔒 Security Best Practices

### DPoP (Demonstration of Proof-of-Possession)

```bash
# С DPoP proof
curl -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: DPoP <token>" \
  -H "DPoP: <proof>" \
  -d '...'
```

### Rate Limiting

```bash
# Настройки в application.properties
app.rate-limit.rules[0].id=login
app.rate-limit.rules[0].path-pattern=/api/auth/login
app.rate-limit.rules[0].methods=POST
app.rate-limit.rules[0].key-strategy=IP
app.rate-limit.rules[0].capacity=5
app.rate-limit.rules[0].window-seconds=60

# Превышение лимита возвращает:
# HTTP 429 Too Many Requests
# {
#   "code": 42901,
#   "message": "Too many requests"
# }
```

---

## 🐳 Docker Compose команды

```bash
# Запустить все сервисы
docker compose up -d

# Остановить все сервисы
docker compose down

# Посмотреть логи
docker compose logs -f [service_name]

# Запустить отдельный сервис
docker compose up -d [service_name]

# Перезагрузить сервис
docker compose restart [service_name]

# Удалить данные (включая БД)
docker compose down -v
```

---

## 🆘 Troubleshooting

### Проблема: Не удается подключиться к Keycloak

```bash
# Проверить запущен ли контейнер
docker ps | grep keycloak

# Проверить логи Keycloak
docker logs keycloak

# Проверить доступность
curl http://localhost:8080/auth/admin/realms
```

### Проблема: Ошибка подключения к БД

```bash
# Проверить статус PostgreSQL
docker ps | grep postgres

# Проверить переменные окружения
cat .env | grep DB_

# Проверить логи БД
docker logs postgres-app
```

### Проблема: Rate limiting блокирует запросы

```bash
# Очистить cache rate limiting
# Отправить DELETE запрос к management endpoint (если включен)
curl -X DELETE http://localhost:8080/actuator/caches/rateLimitBuckets

# Или перезагрузить приложение
docker restart jwt-demo-reactive
```

---

## 📚 Дополнительные ресурсы

- [Spring WebFlux Documentation](https://spring.io/projects/spring-webflux)
- [Spring Data R2DBC](https://spring.io/projects/spring-data-r2dbc)
- [Spring Security OAuth2](https://spring.io/projects/spring-security)
- [Keycloak Documentation](https://www.keycloak.org/documentation)

---

**Последнее обновление:** 2026-04-08  
**Версия:** 0.0.1-SNAPSHOT  
**Статус:** Production Ready

