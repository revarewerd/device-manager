> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-02` | Версия: `1.0`

# Device Manager — TESTING.md

## Обзор

Все тесты написаны на **ZIO Test** (`ZIOSpecDefault`).
Запуск: `sbt test` из директории `services/device-manager/`.

**Общая статистика:** 69 тестов, 0 failures

---

## Тестовые модули

### 1. Доменные типы и ошибки (1 файл, 30 тестов)

Расположение: `src/test/scala/com/wayrecall/device/domain/DomainSpec.scala`

| Suite | Тестов | Что тестируется |
|---|---|---|
| Opaque Types | 4 | DeviceId, VehicleId, OrganizationId, SensorProfileId — apply/value roundtrip |
| Imei | 8 | Валидный 15-значный, невалидные (14/16 цифр, буквы, пустая строка, дефисы), unsafe bypass, все нули |
| Protocol enum | 1 | Все 6 вариантов: Teltonika, Wialon, Ruptela, NavTelecom, Galileo, Custom |
| DeviceStatus enum | 1 | Все 4 варианта: Active, Inactive, Suspended, Deleted |
| Device | 3 | Все поля, без optional, copy для обновления статуса |
| Errors | 14 | Полная иерархия DomainError: ValidationError(6), NotFoundError(2), ConflictError(2), AccessError(1), InfrastructureError(4), Throwable behaviour, pattern matching |

> **Важно:** `DeviceStatus` НЕ содержит `Disabled` — для деактивации используется `Inactive`.
> **Важно:** `Device` НЕ содержит поле `disabledReason`.

### 2. Kafka события (1 файл, 20 тестов)

Расположение: `src/test/scala/com/wayrecall/device/domain/EventsSpec.scala`

| Suite | Тестов | Что тестируется |
|---|---|---|
| Device Events | 10 | DeviceCreated (с/без vehicleId), DeviceUpdated (карта изменений), DeviceDeleted (с/без причины), DeviceActivated, DeviceDeactivated, DeviceAssignedToVehicle (с/без previous), DeviceUnassignedFromVehicle |
| Vehicle Events | 3 | VehicleCreated (имя + тип), VehicleUpdated (пустая карта), VehicleDeleted |
| Organization Events | 3 | OrganizationCreated (имя + email), OrganizationUpdated, OrganizationDeactivated |
| DeviceConfigCommand | 3 | EnableDevice, DisableDevice (Redis Pub/Sub), UpdateImeiMapping |

**Проверяемые контракты:**
- `eventType` строки для Kafka маршрутизации (`device.created`, `device.updated` и т.д.)
- `source` всегда `"device-manager"`
- Optional поля (vehicleId, reason, previousVehicleId)

### 3. Сервисный слой (1 файл, 19 тестов)

Расположение: `src/test/scala/com/wayrecall/device/service/DeviceServiceSpec.scala`

**Подход:** InMemoryDeviceRepository + MockEventPublisher + MockRedisSync — все на ZIO `Ref`

| Suite | Тестов | Что тестируется |
|---|---|---|
| createDevice | 4 | Создание, возврат полного Device, проверка IMEI в repo, публикация события |
| getDevice | 4 | Найдено по id, не найдено, найдено по IMEI, не найдено по IMEI |
| activateDevice | 2 | Активация → `DeviceStatus.Active`, публикация DeviceActivated |
| deactivateDevice | 2 | Деактивация → `DeviceStatus.Inactive`, публикация DeviceDeactivated |
| assignToVehicle | 3 | Привязка, vehicleId обновлён, публикация DeviceAssignedToVehicle |
| deleteDevice | 2 | Soft delete → `DeviceStatus.Deleted`, публикация DeviceDeleted |
| conflictDevice | 2 | IMEI дубликат → ConflictError, устройство не найдено → NotFoundError |

**InMemoryDeviceRepository:**
- `Ref[Map[DeviceId, Device]]` — хранилище устройств
- `Ref[Long]` — автоинкремент для DeviceId
- `create` возвращает `DeviceId` (НЕ Device)
- `countByOrganization` возвращает `Int` (НЕ Long)
- Все методы trait реализованы: findById, findByImei, update, delete, findByOrganization, findByStatus, findAllActive, assignToVehicle, unassignFromVehicle, updateLastSeen, updateStatus, existsByImei

---

## Как запускать

```bash
# Все тесты
cd services/device-manager && sbt test

# Только домен
sbt "testOnly com.wayrecall.device.domain.*"

# Только сервисный слой
sbt "testOnly com.wayrecall.device.service.*"

# Конкретный файл
sbt "testOnly com.wayrecall.device.domain.DomainSpec"
```

---

## Подход к тестированию

### In-Memory Repository Pattern

`DeviceServiceSpec` использует `InMemoryDeviceRepository` — полную реализацию `DeviceRepository` на ZIO `Ref`:
- Все операции CRUD в памяти
- Автоинкремент ID через `Ref[Long]`
- MockEventPublisher собирает опубликованные события в `Ref[List[DomainEvent]]`
- MockRedisSync — заглушка (все методы → `ZIO.unit`)

### Что НЕ протестировано (требует integration tests)

- **Doobie SQL запросы** — нужен `testcontainers` с PostgreSQL
- **Kafka producer** — публикация в реальные топики
- **Redis Pub/Sub** — команды `EnableDevice`, `DisableDevice` для CM
- **HTTP API endpoints** — маршрутизация zio-http, сериализация ответов
- **Flyway миграции** — корректность SQL схемы
- **Multi-tenant isolation** — organization_id границы в реальной БД
