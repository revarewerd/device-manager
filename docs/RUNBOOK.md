# 🔧 Device Manager — Runbook

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Запуск

### Через SBT (разработка)

```bash
cd services/device-manager
sbt run
```

### Через Docker

```bash
cd services/device-manager
docker build -t device-manager .
docker run -p 8092:8092 \
  -e DATABASE_URL=jdbc:postgresql://postgres:5432/tracker \
  -e DATABASE_USER=tracker \
  -e DATABASE_PASSWORD=secret \
  -e REDIS_HOST=redis \
  -e KAFKA_BROKERS=kafka:9092 \
  device-manager
```

### Через Docker Compose (тестовый стенд)

```bash
cd test-stand
docker-compose up -d device-manager
```

## Health Check

```bash
curl http://localhost:8092/health
# {"status":"ok","service":"device-manager","version":"1.0.0","checks":{"database":"ok","redis":"ok","kafka":"ok"}}
```

---

## Типичные ошибки

### 1. IMEI already registered (409 Conflict)

```
{"error": "conflict", "message": "IMEI 352093081234567 already registered"}
```

**Причина:** Попытка создать устройство с уже существующим IMEI.

**Решение:**
```sql
-- Проверить существующее устройство
SELECT id, name, organization_id, is_active FROM devices WHERE imei = '352093081234567';
-- Если is_active = false, можно реактивировать
UPDATE devices SET is_active = true WHERE imei = '352093081234567';
```

---

### 2. Redis ключ device:{imei} отсутствует

**Симптом:** Connection Manager не видит контекст устройства.

**Диагностика:**
```bash
redis-cli HGETALL device:352093081234567
```

**Решение:**
```bash
# Принудительная синхронизация одного устройства
curl -X POST http://localhost:8092/api/v1/admin/sync-device/352093081234567

# Или дождаться ежедневной синхронизации (03:00 UTC)
```

---

### 3. Команда зависла в статусе «pending»

**Диагностика:**
```sql
-- Найти зависшие команды (старше 1 часа)
SELECT id, device_id, command_type, status, created_at
FROM device_commands
WHERE status = 'pending' AND created_at < NOW() - INTERVAL '1 hour'
ORDER BY created_at DESC;
```

```bash
# Проверить Kafka consumer lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group device-manager-audit
```

**Причины:**
- Connection Manager не обработал команду → проверить CM логи
- Трекер оффлайн → команда в `pending_commands:{imei}` Redis
- Kafka lag растёт → проверить CM consumer

**Решение:**
```sql
-- Отменить зависшие команды старше 24 часов
UPDATE device_commands
SET status = 'timeout', failed_at = NOW()
WHERE status = 'pending' AND created_at < NOW() - INTERVAL '24 hours';
```

---

### 4. Database connection refused

```
ERROR: Connection refused: localhost:5432
```

**Решение:**
1. Проверить что PostgreSQL запущен: `docker ps | grep postgres`
2. Проверить переменные: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`
3. Проверить Flyway миграции: `sbt "runMain org.flywaydb.commandline.Main migrate"`

---

### 5. Kafka producer timeout

```
ERROR: Failed to send record to device-commands: TimeoutException
```

**Причины:**
- Kafka broker недоступен
- Топик не создан

**Решение:**
```bash
# Проверить Kafka
kafka-topics --bootstrap-server localhost:9092 --list | grep device

# Создать топик если отсутствует
kafka-topics --bootstrap-server localhost:9092 --create \
  --topic device-commands --partitions 12 --replication-factor 1
```

---

## Мониторинг

### Prometheus метрики

| Метрика | Тип | Описание |
|---------|-----|----------|
| `dm_devices_total{org}` | Gauge | Количество устройств по организации |
| `dm_devices_online{org}` | Gauge | Количество онлайн устройств |
| `dm_commands_total{type,status}` | Counter | Команды по типу и статусу |
| `dm_commands_duration_seconds{type}` | Histogram | Время выполнения команд |
| `dm_api_requests_total{method,path,status}` | Counter | HTTP запросы |
| `dm_api_request_duration_seconds{method,path}` | Histogram | Время ответа API |
| `dm_redis_sync_total{result}` | Counter | Результаты ежедневной синхронизации |
| `dm_redis_sync_duration_seconds` | Histogram | Время синхронизации |

### Алерты

```yaml
groups:
  - name: device-manager
    rules:
      - alert: HighCommandFailRate
        expr: rate(dm_commands_total{status="failed"}[5m]) / rate(dm_commands_total[5m]) > 0.1
        for: 5m
        annotations:
          summary: "Более 10% команд завершаются ошибкой"

      - alert: APIHighLatency
        expr: histogram_quantile(0.99, dm_api_request_duration_seconds) > 0.5
        for: 5m
        annotations:
          summary: "P99 API latency > 500ms"

      - alert: DeviceManagerDown
        expr: up{job="device-manager"} == 0
        for: 1m
        annotations:
          summary: "Device Manager недоступен"

      - alert: RedisDesync
        expr: dm_redis_sync_total{result="drift"} > 100
        for: 1h
        annotations:
          summary: "Более 100 устройств с расхождением Redis/PostgreSQL"
```

### Grafana Dashboard

**Рекомендуемые панели:**
1. Устройства online/offline (Gauge)
2. Команды по типу (Stacked bar)
3. API latency P50/P95/P99 (Graph)
4. Kafka consumer lag (Graph)
5. Redis sync anomalies (Counter)

---

## Логирование

**Ключевые log markers:**

```
INFO  [DeviceService] Device created: imei=352093081234567, org=org-uuid
INFO  [CommandService] Command sent: requestId=cmd-uuid, type=SetInterval, device=352093081234567
WARN  [RedisSyncService] Drift detected: device:352093081234567, field=speedLimit, redis=90, db=110
ERROR [AuditConsumer] Failed to process command-audit: requestId=cmd-uuid, error=...
```
