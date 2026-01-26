# 📚 Руководство по изучению Device Manager

Это руководство поможет систематически изучить архитектуру и код Device Manager.

## 🎯 Цель изучения

После изучения вы будете понимать:
- Как устроена слоёная архитектура на ZIO
- Как работает Dependency Injection через ZLayer
- Как интегрировать PostgreSQL, Kafka, Redis в функциональном стиле
- Как проектировать REST API на ZIO HTTP

---

## 📖 Порядок изучения

### Этап 1: Доменный слой (30 мин)

**Начните с понимания "что" делает сервис, прежде чем смотреть "как".**

#### 1.1 Сущности (`domain/Entities.scala`)
```
Читать: строки 1-150
```
- Что такое `Device`, `Vehicle`, `Protocol`?
- Как используются opaque types (`DeviceId`, `Imei`, `VehicleId`)?
- Почему `Imei` имеет валидацию в `apply`?
- Как реализованы JSON кодеки через `derives JsonCodec`?

**Вопросы для самопроверки:**
1. Почему `Imei` возвращает `Either[String, Imei]` вместо просто `Imei`?
2. Что даёт использование `opaque type` вместо `case class`?
3. Какие статусы может иметь устройство?

#### 1.2 Ошибки (`domain/Errors.scala`)
```
Читать: весь файл (~100 строк)
```
- Иерархия ошибок через `sealed trait`
- Почему используем ADT вместо исключений?
- Как ошибки маппятся на HTTP статусы?

**Вопросы для самопроверки:**
1. Чем `ValidationError` отличается от `NotFoundError`?
2. Почему `InfrastructureError` отделён от бизнес-ошибок?

#### 1.3 События (`domain/Events.scala`)
```
Читать: весь файл (~80 строк)
```
- Event Sourcing lite - что публикуем в Kafka
- Структура событий (eventId, timestamp, payload)

---

### Этап 2: Конфигурация (15 мин)

#### 2.1 Типизированная конфигурация (`config/AppConfig.scala`)
```
Читать: весь файл (~150 строк)
```
- Как ZIO Config загружает HOCON
- Вложенные case class'ы для группировки настроек
- Метод `jdbcUrl` - вычисляемое свойство

#### 2.2 HOCON файл (`resources/application.conf`)
```
Читать: весь файл (~80 строк)
```
- Синтаксис HOCON
- Переопределение через переменные окружения `${?VAR_NAME}`

---

### Этап 3: Репозиторий (45 мин)

#### 3.1 PostgreSQL через Doobie (`repository/DeviceRepository.scala`)
```
Читать: весь файл (~300 строк)
```

**Ключевые концепции:**

1. **Trait как интерфейс** (строки 1-80)
   - Абстракция над хранилищем
   - Возвращаем `IO[DomainError, T]` вместо `Task[T]`

2. **Meta instances** (строки 100-130)
   - Как Doobie маппит кастомные типы на SQL
   - `Meta[Imei]`, `Meta[DeviceId]`, etc.

3. **SQL запросы** (строки 150-250)
   - `sql"..."` интерполятор Doobie
   - `query[Device]` vs `update`
   - Как работает `.option`, `.to[List]`

4. **ZIO интеграция** (строки 260-300)
   - `transactor.trans.apply(query)` - запуск в транзакции
   - `interop-cats` для совместимости ZIO и Cats Effect

**Практика:**
- Найдите метод `findByImei` - как он использует индекс?
- Найдите `create` - как возвращается созданная запись?

---

### Этап 4: Бизнес-логика (60 мин)

#### 4.1 DeviceService (`service/DeviceService.scala`)
```
Читать: весь файл (~490 строк)
```

**Структура файла:**

1. **Trait интерфейс** (строки 1-110)
   - Контракт сервиса
   - Документация бизнес-правил в комментариях

2. **Command DTO** (строки 120-145)
   - `CreateDeviceCommand`, `UpdateDeviceCommand`
   - Отделение API DTO от доменных сущностей

3. **Accessor методы** (строки 150-200)
   - `def createDevice(...): ZIO[DeviceService, ...]`
   - Паттерн ZIO для работы с сервисами

4. **Live реализация** (строки 210-450)
   - Инъекция зависимостей через конструктор
   - Бизнес-логика: валидация → репозиторий → события

5. **ZLayer** (строки 460-470)
   - Композиция зависимостей

**Ключевые методы для изучения:**

```scala
// Полный цикл создания устройства
createDevice:
  1. Валидация IMEI
  2. Проверка уникальности  
  3. Создание в БД
  4. Публикация события
  5. Синхронизация Redis
```

**Вопросы для самопроверки:**
1. Почему `eventPublisher.publish` обёрнут в `catchAll`?
2. Что произойдёт если Redis недоступен при `syncDevice`?
3. Как реализована активация устройства?

---

### Этап 5: Инфраструктура (30 мин)

#### 5.1 Kafka Publisher (`infrastructure/KafkaPublisher.scala`)
```
Читать: весь файл (~130 строк)
```
- Обёртка над Java Kafka Producer
- `ZIO.async` для callback-based API
- `ZLayer.scoped` для управления ресурсами

#### 5.2 Redis Sync (`infrastructure/RedisSyncService.scala`)
```
Читать: весь файл (~170 строк)
```
- Lettuce клиент
- Ключи `vehicle:{imei}`, `device:{imei}`
- Pub/Sub для уведомлений

---

### Этап 6: REST API (30 мин)

#### 6.1 Device Routes (`api/DeviceRoutes.scala`)
```
Читать: весь файл (~200 строк)
```
- ZIO HTTP роутинг
- Path параметры: `long("id")`, `string("imei")`
- Query параметры: `request.url.queryParams`
- Обработка ошибок → HTTP статусы

#### 6.2 Health Routes (`api/HealthRoutes.scala`)
```
Читать: весь файл (~50 строк)
```
- Liveness vs Readiness probes
- Kubernetes совместимость

---

### Этап 7: Точка входа (20 мин)

#### 7.1 Main.scala
```
Читать: весь файл (~115 строк)
```
- `ZIOAppDefault` - точка входа ZIO приложения
- `provide` - композиция слоёв
- Порядок инициализации

**Диаграмма зависимостей:**
```
Main
 └── program
      ├── AppConfig.live
      ├── DeviceRepository.live
      │    └── DatabaseConfig
      ├── KafkaPublisher.live
      │    └── KafkaConfig
      ├── RedisSyncService.live
      │    └── RedisConfig
      └── DeviceService.live
           ├── DeviceRepository
           ├── EventPublisher
           └── RedisSync
```

---

### Этап 8: Тесты (15 мин)

#### 8.1 DeviceServiceSpec (`test/.../DeviceServiceSpec.scala`)
```
Читать: весь файл (~300 строк)
```
- In-memory реализации для тестов
- ZIO Test assertions
- Изоляция тестов

---

## 🔄 Поток данных

```
HTTP Request
     │
     ▼
┌─────────────┐
│ DeviceRoutes│ ── парсинг JSON, валидация
└──────┬──────┘
       │
       ▼
┌─────────────┐
│DeviceService│ ── бизнес-правила
└──────┬──────┘
       │
   ┌───┴───┬───────────┐
   ▼       ▼           ▼
┌──────┐ ┌─────┐ ┌──────────┐
│ Repo │ │Kafka│ │  Redis   │
│(CRUD)│ │(pub)│ │(sync)    │
└──┬───┘ └──┬──┘ └────┬─────┘
   │        │         │
   ▼        ▼         ▼
PostgreSQL  Kafka   Redis
```

---

## 💡 Советы

1. **Начните с доменного слоя** - он определяет "словарь" системы
2. **Изучайте слой за слоем** - не прыгайте между файлами
3. **Запускайте тесты** - они показывают ожидаемое поведение
4. **Используйте IDE** - Cmd+Click для навигации к определениям
5. **Читайте комментарии** - они объясняют "почему", а не "что"

---

## 🧪 Практические задания

### Задание 1: Добавить поле
Добавьте поле `description: Option[String]` в `Device`:
- [ ] Обновите `Entities.scala`
- [ ] Обновите SQL миграцию
- [ ] Обновите `DeviceRepository`
- [ ] Обновите `CreateDeviceCommand`

### Задание 2: Новый эндпоинт
Добавьте `GET /api/devices/active` - список активных устройств:
- [ ] Метод в `DeviceRepository`
- [ ] Метод в `DeviceService`
- [ ] Route в `DeviceRoutes`

### Задание 3: Новое событие
Добавьте событие `DeviceFirmwareUpdated`:
- [ ] Определите в `Events.scala`
- [ ] Добавьте метод `updateFirmware` в сервис
- [ ] Опубликуйте событие в Kafka

---

## ⏱️ Ориентировочное время

| Этап | Время |
|------|-------|
| Доменный слой | 30 мин |
| Конфигурация | 15 мин |
| Репозиторий | 45 мин |
| Бизнес-логика | 60 мин |
| Инфраструктура | 30 мин |
| REST API | 30 мин |
| Main + Тесты | 35 мин |
| **Итого** | **~4 часа** |

Удачи в изучении! 🚀
