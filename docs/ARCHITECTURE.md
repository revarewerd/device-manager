# 🏗 Device Manager — Архитектура

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Обзор

Device Manager — REST-сервис для управления GPS-трекерами. Обеспечивает CRUD устройств,
отправку команд на трекеры через Kafka → Connection Manager, и синхронизацию контекста
устройств в Redis.

## Общая диаграмма

```mermaid
flowchart LR
    subgraph Clients["Клиенты"]
        UI[Web Frontend]
        API_GW[API Gateway]
    end

    subgraph DM["Device Manager :8092"]
        Routes[REST API Routes]
        DS[DeviceService]
        CS[CommandService]
        GS[GroupService]
        SyncJob[Daily Sync Job]
        AuditConsumer[CommandAuditConsumer]
    end

    subgraph Storage["Хранилище"]
        PG[(PostgreSQL<br/>devices, groups,<br/>commands)]
        Redis[(Redis<br/>device context)]
    end

    subgraph Kafka_Out["Kafka"]
        DC[device-commands]
        CA[command-audit]
        DE[device-events]
        DSt[device-status]
    end

    subgraph CM["Connection Manager"]
        CM_Handler[CommandHandler]
    end

    Clients --> Routes
    Routes --> DS --> PG
    Routes --> CS --> PG
    Routes --> GS --> PG
    DS --> Redis
    CS --> DC --> CM_Handler
    CM_Handler --> CA --> AuditConsumer --> PG
    DS --> DE
    DSt --> AuditConsumer
    SyncJob --> Redis
    SyncJob --> PG
```

## Слои сервиса

```mermaid
graph TB
    subgraph API["api/ — HTTP маршруты"]
        DeviceRoutes[DeviceRoutes]
        CommandRoutes[CommandRoutes]
        GroupRoutes[GroupRoutes]
        HealthRoutes[HealthRoutes]
    end

    subgraph Service["service/ — Бизнес-логика"]
        DeviceService[DeviceService]
        CommandService[CommandService]
        GroupService[GroupService]
        SyncService[RedisSyncService]
    end

    subgraph Repository["repository/ — Доступ к данным"]
        DeviceRepo[DeviceRepository]
        CommandRepo[CommandRepository]
        GroupRepo[GroupRepository]
    end

    subgraph Infra["Инфраструктура"]
        PG[(PostgreSQL)]
        Redis[(Redis)]
        Kafka[Kafka Producer/Consumer]
    end

    API --> Service --> Repository --> Infra
    Service --> Redis
    Service --> Kafka
```

## Поток создания устройства

```mermaid
sequenceDiagram
    participant C as Client
    participant R as DeviceRoutes
    participant S as DeviceService
    participant PG as PostgreSQL
    participant Redis as Redis
    participant K as Kafka

    C->>R: POST /api/v1/devices
    R->>S: createDevice(request)
    S->>S: validate(request)
    S->>PG: INSERT INTO devices
    PG-->>S: deviceId
    S->>Redis: HSET device:{imei}<br/>(vehicleId, orgId, name,<br/>speedLimit, hasGeozones,<br/>fuelTankVolume)
    Redis-->>S: OK
    S->>K: publish device-events<br/>(DeviceCreated)
    S-->>R: Device
    R-->>C: 201 Created
```

## Поток отправки команды

```mermaid
sequenceDiagram
    participant C as Client
    participant R as CommandRoutes
    participant CS as CommandService
    participant PG as PostgreSQL
    participant Redis as Redis
    participant K as Kafka
    participant CM as Connection Manager
    participant T as Tracker

    C->>R: POST /api/v1/devices/{id}/commands
    R->>CS: sendCommand(deviceId, command)
    CS->>PG: SELECT device WHERE id = deviceId
    PG-->>CS: device (imei, protocol)
    CS->>Redis: GET device:{imei}:instanceId
    Redis-->>CS: instanceId (если онлайн)
    CS->>PG: INSERT INTO device_commands<br/>(status = 'pending')
    PG-->>CS: commandId (UUID)

    alt Устройство онлайн
        CS->>K: publish device-commands<br/>(key = instanceId)
        K->>CM: consume device-commands
        CM->>T: TCP/binary command
        T-->>CM: ACK
        CM->>K: publish command-audit<br/>(status = 'delivered')
    else Устройство оффлайн
        CS->>Redis: ZADD pending_commands:{imei}<br/>(score=timestamp, command)
        Note over CS: Команда ожидает<br/>подключения трекера
    end

    K->>CS: consume command-audit
    CS->>PG: UPDATE device_commands<br/>SET status = 'delivered'
    CS-->>R: CommandStatus
    R-->>C: 202 Accepted
```

## Ежедневная синхронизация Redis ↔ PostgreSQL

```mermaid
flowchart TB
    Start[Cron: 03:00 UTC] --> Scan[Сканировать все device:* ключи в Redis]
    Scan --> Compare{Сравнить с PostgreSQL}

    Compare -->|Orphaned| Orphaned[Redis ключ без<br/>записи в БД]
    Compare -->|Missing| Missing[Запись в БД без<br/>Redis ключа]
    Compare -->|Drift| Drift[Значения полей<br/>отличаются]
    Compare -->|OK| OK[Консистентно]

    Orphaned --> DeleteRedis[Удалить из Redis +<br/>LOG WARNING]
    Missing --> WriteRedis[Записать в Redis из БД +<br/>LOG INFO]
    Drift --> UpdateRedis[Повторно заполнить<br/>из БД + LOG WARNING]
    OK --> Next[Следующее устройство]

    DeleteRedis --> Next
    WriteRedis --> Next
    UpdateRedis --> Next
```

## Структура пакетов

```
com.wayrecall.tracker.devicemanager/
├── Main.scala                    # Точка входа, ZIO Layer composition
├── domain/
│   ├── Device.scala              # case class Device, Protocol enum
│   ├── DeviceGroup.scala         # case class DeviceGroup (hierarchical)
│   ├── Command.scala             # sealed trait Command (10 типов)
│   ├── DeviceCommand.scala       # case class DeviceCommand (status lifecycle)
│   └── DeviceError.scala         # sealed trait DeviceError
├── config/
│   └── AppConfig.scala           # Конфигурация сервиса (HOCON)
├── service/
│   ├── DeviceService.scala       # CRUD устройств + Redis sync
│   ├── CommandService.scala      # Отправка и отслеживание команд
│   ├── GroupService.scala        # Управление группами
│   └── RedisSyncService.scala    # Ежедневная синхронизация
├── repository/
│   ├── DeviceRepository.scala    # Doobie queries для devices
│   ├── CommandRepository.scala   # Doobie queries для device_commands
│   └── GroupRepository.scala     # Doobie queries для device_groups
├── api/
│   ├── DeviceRoutes.scala        # REST: /api/v1/devices
│   ├── CommandRoutes.scala       # REST: /api/v1/devices/{id}/commands
│   ├── GroupRoutes.scala         # REST: /api/v1/groups
│   └── HealthRoutes.scala        # GET /health, /metrics
├── kafka/
│   ├── CommandProducer.scala     # Publish device-commands
│   ├── EventProducer.scala       # Publish device-events
│   └── AuditConsumer.scala       # Consume command-audit, device-status
├── storage/
│   └── RedisClient.scala         # Redis HASH операции для device context
└── util/
    └── Pagination.scala          # Пагинация API ответов
```

## ZIO Layer Composition

```scala
// Main.scala — упрощённая схема
val appLayer: ZLayer[Any, Throwable, AppEnv] =
  // Конфигурация
  AppConfig.live ++
  // Инфраструктура
  PostgresDataSource.live ++
  DoobieTransactor.live ++
  RedisClient.live ++
  KafkaProducer.live ++
  KafkaConsumer.live ++
  // Репозитории
  DeviceRepository.live ++
  CommandRepository.live ++
  GroupRepository.live ++
  // Сервисы
  DeviceService.live ++
  CommandService.live ++
  GroupService.live ++
  RedisSyncService.live ++
  // API
  DeviceRoutes.live ++
  CommandRoutes.live ++
  GroupRoutes.live ++
  HealthRoutes.live
```
