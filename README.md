# Device Manager Service

Микросервис для управления GPS/ГЛОНАСС устройствами в системе WayRecall Tracker.

## 📋 Описание

Device Manager отвечает за:
- CRUD операции с устройствами
- Управление жизненным циклом устройств (активация, деактивация)
- Привязка устройств к транспортным средствам
- Синхронизация состояния с Connection Manager через Redis
- Публикация событий в Kafka

## 🏗️ Архитектура

```
┌─────────────────────────────────────────────────────────────┐
│                      Device Manager                          │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│  │  REST API   │    │   Health    │    │   Metrics   │     │
│  │  /api/*     │    │  /health/*  │    │  /metrics   │     │
│  └──────┬──────┘    └─────────────┘    └─────────────┘     │
│         │                                                    │
│  ┌──────▼──────────────────────────────────────────────┐   │
│  │                  Service Layer                        │   │
│  │  ┌─────────────────────────────────────────────┐     │   │
│  │  │              DeviceService                   │     │   │
│  │  │  - createDevice()  - activateDevice()       │     │   │
│  │  │  - getDevice()     - deactivateDevice()     │     │   │
│  │  │  - updateDevice()  - assignToVehicle()      │     │   │
│  │  │  - deleteDevice()  - unassignFromVehicle()  │     │   │
│  │  └─────────────────────────────────────────────┘     │   │
│  └──────┬──────────────────────────────────────────────┘   │
│         │                                                    │
│  ┌──────▼──────────────────────────────────────────────┐   │
│  │              Infrastructure Layer                     │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │   Kafka     │  │   Redis     │  │  PostgreSQL │  │   │
│  │  │  Publisher  │  │    Sync     │  │  Repository │  │   │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  │   │
│  └─────────┼────────────────┼────────────────┼─────────┘   │
└────────────┼────────────────┼────────────────┼──────────────┘
             │                │                │
             ▼                ▼                ▼
        ┌─────────┐     ┌─────────┐      ┌─────────┐
        │  Kafka  │     │  Redis  │      │PostgreSQL│
        └─────────┘     └─────────┘      └─────────┘
```

## 🛠️ Технологии

| Компонент | Технология |
|-----------|------------|
| Язык | Scala 3.4.0 |
| FP Framework | ZIO 2.0.20 |
| HTTP Server | ZIO HTTP 3.0.0-RC4 |
| База данных | PostgreSQL + Doobie 1.0.0-RC4 |
| Сообщения | Apache Kafka 3.6.1 |
| Кэш | Redis (Lettuce 6.3.0) |
| Логирование | Logback + SLF4J |
| Сборка | SBT |

## 📁 Структура проекта

```
src/main/scala/com/wayrecall/device/
├── Main.scala                     # Точка входа
├── api/
│   ├── DeviceRoutes.scala        # REST эндпоинты устройств
│   └── HealthRoutes.scala        # Health check
├── config/
│   └── AppConfig.scala           # Конфигурация приложения
├── consumer/
│   └── UnknownDeviceConsumer.scala # Kafka consumer
├── domain/
│   ├── Entities.scala            # Доменные сущности
│   ├── Errors.scala              # ADT ошибок
│   └── Events.scala              # Доменные события
├── infrastructure/
│   ├── KafkaPublisher.scala      # Публикация в Kafka
│   └── RedisSyncService.scala    # Синхронизация с Redis
├── repository/
│   └── DeviceRepository.scala    # PostgreSQL репозиторий
└── service/
    └── DeviceService.scala       # Бизнес-логика
```

## 🚀 Запуск

### Требования

- JDK 21+
- SBT 1.9+
- Docker и Docker Compose

### Локальный запуск

```bash
# Запуск инфраструктуры
docker-compose -f ../../infra/docker-compose.yml up -d

# Компиляция
sbt compile

# Запуск
sbt run
```

### Конфигурация

Переменные окружения:

| Переменная | Описание | По умолчанию |
|------------|----------|--------------|
| `APP_HTTP_PORT` | Порт HTTP API | 8082 |
| `APP_DATABASE_HOST` | Хост PostgreSQL | localhost |
| `APP_DATABASE_PORT` | Порт PostgreSQL | 5432 |
| `APP_DATABASE_PASSWORD` | Пароль PostgreSQL | postgres |
| `APP_KAFKA_BOOTSTRAP_SERVERS` | Адреса Kafka | localhost:9092 |
| `APP_REDIS_HOST` | Хост Redis | localhost |
| `APP_REDIS_PORT` | Порт Redis | 6379 |

## 📡 API

### Устройства

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/devices?organizationId=1` | Список устройств организации |
| GET | `/api/devices/:id` | Получить устройство |
| GET | `/api/devices/imei/:imei` | Получить по IMEI |
| POST | `/api/devices` | Создать устройство |
| PUT | `/api/devices/:id` | Обновить устройство |
| DELETE | `/api/devices/:id` | Удалить устройство |
| POST | `/api/devices/:id/activate` | Активировать |
| POST | `/api/devices/:id/deactivate` | Деактивировать |
| POST | `/api/devices/:id/assign/:vehicleId` | Привязать к ТС |
| DELETE | `/api/devices/:id/vehicle` | Отвязать от ТС |

### Health Check

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/health` | Liveness probe |
| GET | `/health/ready` | Readiness probe |

### Примеры запросов

```bash
# Создание устройства
curl -X POST http://localhost:8082/api/devices \
  -H "Content-Type: application/json" \
  -d '{
    "imei": "123456789012345",
    "name": "Трекер #1",
    "protocol": "teltonika",
    "organizationId": 1
  }'

# Активация устройства
curl -X POST http://localhost:8082/api/devices/1/activate

# Привязка к ТС
curl -X POST http://localhost:8082/api/devices/1/assign/10
```

## 📦 Kafka топики

### Публикуемые события

| Топик | Событие | Описание |
|-------|---------|----------|
| `device-events` | `DeviceCreated` | Устройство создано |
| `device-events` | `DeviceUpdated` | Устройство обновлено |
| `device-events` | `DeviceDeleted` | Устройство удалено |
| `device-events` | `DeviceActivated` | Устройство активировано |
| `device-events` | `DeviceDeactivated` | Устройство деактивировано |

### Потребляемые события

| Топик | Событие | Описание |
|-------|---------|----------|
| `unknown-devices` | `UnknownDeviceConnected` | Подключение неизвестного устройства |

## 🗄️ Redis ключи

| Ключ | Тип | Описание |
|------|-----|----------|
| `vehicle:{imei}` | STRING | vehicleId для быстрого lookup |
| `device:{imei}` | STRING | JSON с информацией об устройстве |

### Pub/Sub каналы

| Канал | Описание |
|-------|----------|
| `device-config-changed` | Уведомления для Connection Manager |

## 🧪 Тестирование

```bash
# Все тесты
sbt test

# Конкретный тест
sbt "testOnly com.wayrecall.device.service.DeviceServiceSpec"
```

## 🐳 Docker

```bash
# Сборка образа
sbt assembly
docker build -t device-manager .

# Запуск
docker run -p 8082:8082 \
  -e APP_DATABASE_HOST=host.docker.internal \
  -e APP_KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -e APP_REDIS_HOST=host.docker.internal \
  device-manager
```

## 📊 Мониторинг

### Логирование

Логи записываются в:
- Консоль (цветной формат)
- `logs/device-manager.log` (ротация по размеру и дате)
- `logs/device-manager-error.log` (только ошибки)

### Уровни логирования

```
LOG_LEVEL=DEBUG  # Для разработки
LOG_LEVEL=INFO   # Для production
```

## 📚 Связанные сервисы

- **Connection Manager** - приём данных от устройств
- **Data Processor** - обработка телеметрии
- **Business Logic API** - REST API для клиентов

## 🔧 Разработка

### Стиль кода

- Чисто функциональный подход (ZIO)
- Все комментарии на русском языке
- ADT для ошибок
- Слоёная архитектура

### Полезные команды SBT

```bash
sbt ~compile        # Непрерывная компиляция
sbt assembly        # Сборка fat JAR
sbt scalafmtAll     # Форматирование кода
```
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
