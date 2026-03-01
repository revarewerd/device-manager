# 📱 Device Manager — Сервис управления устройствами

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Обзор

**Device Manager** — центральный сервис Block 1 (Data Collection), отвечающий за CRUD устройств,
организаций, групп, а также управление командами для GPS-трекеров. Является единственным владельцем
master data устройств в системе.

| Параметр | Значение |
|----------|----------|
| **Блок** | 1 — Data Collection |
| **Порт** | 8092 (REST API) |
| **БД** | PostgreSQL (схема `devices`) |
| **Кеш** | Redis (`device:{imei}` HASH) |
| **Kafka** | Consume: `device-status` · Produce: `device-commands`, `command-audit`, `device-events` |
| **Протокол** | REST API (zio-http) |

## Основные функции

### 1. CRUD устройств
- Регистрация, редактирование, удаление GPS-трекеров
- Привязка к организации (multi-tenant изоляция по `organization_id`)
- Поддержка протоколов: `teltonika`, `wialon_ips`, `ruptela`, `navtelecom`

### 2. Команды на трекеры
- Отправка команд через Kafka → Connection Manager → трекер
- Поддержка: `SetInterval`, `RequestPosition`, `Reboot`, `BlockEngine`, `UnblockEngine`, `SetServer`, `SetApn`, `SetTimezone`, `SendSms`, `CustomCommand`
- Аудит статуса команд: `pending` → `sent` → `delivered` / `failed` / `timeout`

### 3. Группировка устройств
- Иерархические группы (parent/child)
- Операции над группами: фильтрация, отчёты, массовые команды

### 4. Синхронизация Redis ↔ PostgreSQL
- Device Manager пишет CONTEXT-поля в Redis при создании/обновлении устройства
- Connection Manager пишет POSITION и CONNECTION поля
- Ежедневная задача: проверка консистентности (orphaned, missing, drift)

## Быстрый старт

```bash
# 1. Поднять инфраструктуру
cd ../../test-stand && docker-compose up -d postgres redis kafka

# 2. Запустить сервис
cd ../services/device-manager
sbt run

# 3. Health check
curl http://localhost:8092/health

# 4. Создать устройство
curl -X POST http://localhost:8092/api/v1/devices \
  -H "Content-Type: application/json" \
  -d '{
    "imei": "352093081234567",
    "name": "Грузовик-01",
    "vehicleNumber": "А123BC77",
    "protocol": "teltonika",
    "organizationId": "org-uuid"
  }'
```

## Переменные окружения

| Переменная | По умолчанию | Описание |
|------------|-------------|----------|
| `HTTP_PORT` | `8092` | Порт REST API |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tracker` | PostgreSQL URL |
| `DATABASE_USER` | `tracker` | Пользователь БД |
| `DATABASE_PASSWORD` | — | Пароль БД |
| `REDIS_HOST` | `localhost` | Redis хост |
| `REDIS_PORT` | `6379` | Redis порт |
| `KAFKA_BROKERS` | `localhost:9092` | Kafka bootstrap servers |

## Связанные документы

- [ARCHITECTURE.md](ARCHITECTURE.md) — Внутренняя архитектура
- [API.md](API.md) — REST API endpoints
- [DATA_MODEL.md](DATA_MODEL.md) — PostgreSQL + Redis схемы
- [KAFKA.md](KAFKA.md) — Kafka топики
- [DECISIONS.md](DECISIONS.md) — Архитектурные решения
- [RUNBOOK.md](RUNBOOK.md) — Запуск, дебаг, ошибки
- [INDEX.md](INDEX.md) — Содержание документации

### Инфраструктура

- [infra/kafka/TOPICS.md](../../../infra/kafka/TOPICS.md) — Все Kafka топики
- [infra/databases/](../../../infra/databases/) — Схемы БД
- [docs/services/DEVICE_MANAGER.md](../../../docs/services/DEVICE_MANAGER.md) — Системный дизайн-документ
