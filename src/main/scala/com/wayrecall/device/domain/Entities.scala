package com.wayrecall.device.domain

import zio.json.*
import java.time.Instant
import java.util.UUID

// ============================================================
// ИДЕНТИФИКАТОРЫ (Value Objects)
// ============================================================

/**
 * Типизированные идентификаторы для type-safe работы с ID
 * 
 * Используем opaque types для zero-cost абстракции:
 * - Компилятор проверяет типы на этапе компиляции
 * - В runtime это обычные Long/String без оверхеда
 */

/** ID устройства (GPS трекера) */
opaque type DeviceId = Long
object DeviceId:
  def apply(value: Long): DeviceId = value
  extension (id: DeviceId) def value: Long = id
  given JsonCodec[DeviceId] = JsonCodec.long.transform(DeviceId.apply, _.value)

/** ID транспортного средства */
opaque type VehicleId = Long
object VehicleId:
  def apply(value: Long): VehicleId = value
  extension (id: VehicleId) def value: Long = id
  given JsonCodec[VehicleId] = JsonCodec.long.transform(VehicleId.apply, _.value)

/** ID организации */
opaque type OrganizationId = Long
object OrganizationId:
  def apply(value: Long): OrganizationId = value
  extension (id: OrganizationId) def value: Long = id
  given JsonCodec[OrganizationId] = JsonCodec.long.transform(OrganizationId.apply, _.value)

/** ID профиля датчиков */
opaque type SensorProfileId = Long
object SensorProfileId:
  def apply(value: Long): SensorProfileId = value
  extension (id: SensorProfileId) def value: Long = id
  given JsonCodec[SensorProfileId] = JsonCodec.long.transform(SensorProfileId.apply, _.value)

/** IMEI устройства (15-значный номер) */
opaque type Imei = String
object Imei:
  def apply(value: String): Either[String, Imei] =
    if value.matches("^\\d{15}$") then Right(value)
    else Left(s"IMEI должен быть 15-значным числом: $value")
  
  def unsafe(value: String): Imei = value
  
  extension (imei: Imei) def value: String = imei
  
  given JsonCodec[Imei] = JsonCodec.string.transformOrFail(
    s => Imei(s).left.map(e => e),
    _.value
  )

// ============================================================
// ПЕРЕЧИСЛЕНИЯ
// ============================================================

/**
 * Протокол связи с трекером
 * 
 * Каждый протокол имеет свой формат пакетов и порт в Connection Manager
 */
enum Protocol derives JsonCodec:
  case Teltonika   // Порт 5001 - бинарный Codec 8/8E
  case Wialon      // Порт 5002 - текстовый протокол
  case Ruptela     // Порт 5003 - бинарный
  case NavTelecom  // Порт 5004 - бинарный
  case Galileo     // Порт 5005 - бинарный
  case Custom      // Кастомный протокол

/**
 * Статус устройства
 */
enum DeviceStatus derives JsonCodec:
  case Active      // Активно, может подключаться
  case Inactive    // Неактивно, соединения отклоняются
  case Suspended   // Приостановлено (неоплата)
  case Deleted     // Помечено как удалённое (soft delete)

/**
 * Тип транспортного средства
 */
enum VehicleType derives JsonCodec:
  case Car         // Легковой автомобиль
  case Truck       // Грузовик
  case Bus         // Автобус
  case Motorcycle  // Мотоцикл
  case Trailer     // Прицеп
  case Special     // Спецтехника
  case Other       // Другое

// ============================================================
// ОСНОВНЫЕ СУЩНОСТИ
// ============================================================

/**
 * Устройство (GPS трекер)
 * 
 * Физическое устройство, которое подключается к Connection Manager
 * и передаёт GPS-координаты.
 * 
 * @param id Уникальный идентификатор в системе
 * @param imei IMEI устройства (глобально уникальный)
 * @param name Человекочитаемое имя (опционально)
 * @param protocol Протокол связи
 * @param status Текущий статус
 * @param organizationId Владелец устройства
 * @param vehicleId Привязанное ТС (опционально)
 * @param sensorProfileId Профиль датчиков (опционально)
 * @param phoneNumber Номер SIM-карты (опционально)
 * @param firmwareVersion Версия прошивки (опционально)
 * @param lastSeenAt Последнее подключение
 * @param createdAt Дата создания
 * @param updatedAt Дата обновления
 */
final case class Device(
    id: DeviceId,
    imei: Imei,
    name: Option[String],
    protocol: Protocol,
    status: DeviceStatus,
    organizationId: OrganizationId,
    vehicleId: Option[VehicleId],
    sensorProfileId: Option[SensorProfileId],
    phoneNumber: Option[String],
    firmwareVersion: Option[String],
    lastSeenAt: Option[Instant],
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec

/**
 * Транспортное средство
 * 
 * Логическая сущность, к которой привязывается устройство.
 * Отображается на карте, в отчётах, уведомлениях.
 * 
 * @param id Уникальный идентификатор
 * @param organizationId Владелец ТС
 * @param name Название (обязательно)
 * @param vehicleType Тип ТС
 * @param licensePlate Госномер (опционально)
 * @param vin VIN-код (опционально)
 * @param brand Марка (опционально)
 * @param model Модель (опционально)
 * @param year Год выпуска (опционально)
 * @param color Цвет (опционально)
 * @param fuelType Тип топлива (опционально)
 * @param fuelTankCapacity Объём бака в литрах (опционально)
 * @param iconUrl URL иконки на карте (опционально)
 * @param createdAt Дата создания
 * @param updatedAt Дата обновления
 */
final case class Vehicle(
    id: VehicleId,
    organizationId: OrganizationId,
    name: String,
    vehicleType: VehicleType,
    licensePlate: Option[String],
    vin: Option[String],
    brand: Option[String],
    model: Option[String],
    year: Option[Int],
    color: Option[String],
    fuelType: Option[String],
    fuelTankCapacity: Option[Double],
    iconUrl: Option[String],
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec

/**
 * Организация (клиент системы)
 * 
 * Владеет устройствами и транспортными средствами.
 * Имеет пользователей с разными ролями.
 * 
 * @param id Уникальный идентификатор
 * @param name Название организации
 * @param inn ИНН (опционально)
 * @param email Контактный email
 * @param phone Контактный телефон (опционально)
 * @param address Адрес (опционально)
 * @param timezone Часовой пояс (по умолчанию Europe/Moscow)
 * @param maxDevices Лимит устройств по тарифу
 * @param isActive Активна ли организация
 * @param createdAt Дата создания
 * @param updatedAt Дата обновления
 */
final case class Organization(
    id: OrganizationId,
    name: String,
    inn: Option[String],
    email: String,
    phone: Option[String],
    address: Option[String],
    timezone: String,
    maxDevices: Int,
    isActive: Boolean,
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec

/**
 * Профиль датчиков
 * 
 * Описывает как интерпретировать IO-элементы от трекера:
 * - Какой IO-элемент соответствует какому датчику
 * - Формулы преобразования (например, напряжение → уровень топлива)
 * - Единицы измерения
 * 
 * @param id Уникальный идентификатор
 * @param organizationId Владелец профиля
 * @param name Название профиля
 * @param description Описание
 * @param sensors Список датчиков (JSON)
 * @param createdAt Дата создания
 * @param updatedAt Дата обновления
 */
final case class SensorProfile(
    id: SensorProfileId,
    organizationId: OrganizationId,
    name: String,
    description: Option[String],
    sensors: List[SensorConfig],
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec

/**
 * Конфигурация одного датчика в профиле
 * 
 * @param name Название датчика (отображается в UI)
 * @param ioElementId ID IO-элемента от трекера
 * @param sensorType Тип датчика (fuel, temperature, door и т.д.)
 * @param unit Единица измерения
 * @param formula Формула преобразования (опционально)
 * @param minValue Минимальное валидное значение
 * @param maxValue Максимальное валидное значение
 */
final case class SensorConfig(
    name: String,
    ioElementId: Int,
    sensorType: String,
    unit: String,
    formula: Option[String],
    minValue: Option[Double],
    maxValue: Option[Double]
) derives JsonCodec
