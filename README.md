# Device Manager Service

Сервис управления устройствами и командами для TrackerGPS платформы.

## Функции

### 1. Управление устройствами
- CRUD операции для устройств (создание, чтение, обновление, удаление)
- Регистрация новых GPS-трекеров по IMEI
- Привязка устройств к транспортным средствам
- Хранение конфигурации устройств (протокол, частота отправки данных)

### 2. Управление командами
- REST API для отправки команд на устройства
- Очередь команд в Redis Sorted Set (FIFO по timestamp)
- Статусы команд: pending, sent, acknowledged, failed
- История выполнения команд

### 3. Redis Command Queue
- **Pending Commands:** Redis Sorted Set `pending_commands:{imei}` с score=timestamp
- **Command Status:** Redis Hash `command:{commandId}` с полями status, sentAt, ackAt
- **TTL:** 24 часа для completed команд

## Архитектура

```
┌─────────────────┐
│   REST API      │ (ZIO HTTP)
│  /api/devices   │
│  /api/commands  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Device Service  │
│ - CRUD          │
│ - Validation    │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌────────┐  ┌──────────┐
│ Redis  │  │PostgreSQL│
│Commands│  │ Devices  │
└────────┘  └──────────┘
```

## Технологии

- **Scala 3.4.0**
- **ZIO 2.0.20** - эффекты, конкурентность
- **ZIO HTTP 3.0** - REST API
- **Lettuce** - Redis клиент
- **PostgreSQL** - хранение устройств

## Запуск

```bash
# Компиляция
sbt compile

# Запуск
sbt run

# Тесты
sbt test
```

## API Endpoints

### Devices
- `GET /api/devices` - список всех устройств
- `GET /api/devices/:imei` - информация об устройстве
- `POST /api/devices` - регистрация нового устройства
- `PUT /api/devices/:imei` - обновление устройства
- `DELETE /api/devices/:imei` - удаление устройства

### Commands
- `POST /api/commands` - отправка команды на устройство
- `GET /api/commands/:commandId` - статус команды
- `GET /api/devices/:imei/commands` - история команд устройства
