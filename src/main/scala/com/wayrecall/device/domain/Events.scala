package com.wayrecall.device.domain

import zio.json.*
import java.time.Instant

// ============================================================
// СОБЫТИЯ KAFKA
// ============================================================

/**
 * Базовый trait для всех событий
 * 
 * Каждое событие содержит:
 * - eventId: уникальный ID события
 * - eventType: тип события для маршрутизации
 * - timestamp: время создания события
 * - source: источник события (device-manager)
 */
sealed trait DomainEvent:
  def eventId: String
  def eventType: String
  def timestamp: Instant
  def source: String = "device-manager"

object DomainEvent:
  given JsonCodec[DomainEvent] = DeriveJsonCodec.gen[DomainEvent]

// ============================================================
// СОБЫТИЯ УСТРОЙСТВ
// ============================================================

/**
 * Устройство создано
 * 
 * Публикуется в топик: device-events
 * Консьюмеры: Connection Manager (обновляет Redis)
 */
final case class DeviceCreated(
    eventId: String,
    timestamp: Instant,
    deviceId: DeviceId,
    imei: Imei,
    protocol: Protocol,
    organizationId: OrganizationId,
    vehicleId: Option[VehicleId]
) extends DomainEvent:
  val eventType = "device.created"
  
object DeviceCreated:
  given JsonCodec[DeviceCreated] = DeriveJsonCodec.gen[DeviceCreated]

/**
 * Устройство обновлено
 * 
 * Публикуется при изменении любых полей устройства
 */
final case class DeviceUpdated(
    eventId: String,
    timestamp: Instant,
    deviceId: DeviceId,
    imei: Imei,
    changes: Map[String, String] // поле -> новое значение
) extends DomainEvent:
  val eventType = "device.updated"
  
object DeviceUpdated:
  given JsonCodec[DeviceUpdated] = DeriveJsonCodec.gen[DeviceUpdated]

/**
 * Устройство удалено (soft delete)
 */
final case class DeviceDeleted(
    eventId: String,
    timestamp: Instant,
    deviceId: DeviceId,
    imei: Imei,
    reason: Option[String]
) extends DomainEvent:
  val eventType = "device.deleted"

object DeviceDeleted:
  given JsonCodec[DeviceDeleted] = DeriveJsonCodec.gen[DeviceDeleted]

/**
 * Устройство активировано
 * 
 * Connection Manager должен начать принимать соединения от этого IMEI
 */
final case class DeviceActivated(
    eventId: String,
    timestamp: Instant,
    deviceId: DeviceId,
    imei: Imei
) extends DomainEvent:
  val eventType = "device.activated"

object DeviceActivated:
  given JsonCodec[DeviceActivated] = DeriveJsonCodec.gen[DeviceActivated]

/**
 * Устройство деактивировано
 * 
 * Connection Manager должен:
 * 1. Закрыть текущее соединение (если есть)
 * 2. Отклонять новые соединения от этого IMEI
 */
final case class DeviceDeactivated(
    eventId: String,
    timestamp: Instant,
    deviceId: DeviceId,
    imei: Imei,
    reason: String
) extends DomainEvent:
  val eventType = "device.deactivated"

object DeviceDeactivated:
  given JsonCodec[DeviceDeactivated] = DeriveJsonCodec.gen[DeviceDeactivated]

/**
 * Устройство привязано к ТС
 */
final case class DeviceAssignedToVehicle(
    eventId: String,
    timestamp: Instant,
    deviceId: DeviceId,
    imei: Imei,
    vehicleId: VehicleId,
    previousVehicleId: Option[VehicleId]
) extends DomainEvent:
  val eventType = "device.assigned-to-vehicle"

object DeviceAssignedToVehicle:
  given JsonCodec[DeviceAssignedToVehicle] = DeriveJsonCodec.gen[DeviceAssignedToVehicle]

/**
 * Устройство отвязано от ТС
 */
final case class DeviceUnassignedFromVehicle(
    eventId: String,
    timestamp: Instant,
    deviceId: DeviceId,
    imei: Imei,
    vehicleId: VehicleId
) extends DomainEvent:
  val eventType = "device.unassigned-from-vehicle"

object DeviceUnassignedFromVehicle:
  given JsonCodec[DeviceUnassignedFromVehicle] = DeriveJsonCodec.gen[DeviceUnassignedFromVehicle]

// ============================================================
// СОБЫТИЯ ТРАНСПОРТНЫХ СРЕДСТВ
// ============================================================

/**
 * ТС создано
 */
final case class VehicleCreated(
    eventId: String,
    timestamp: Instant,
    vehicleId: VehicleId,
    organizationId: OrganizationId,
    name: String,
    vehicleType: VehicleType
) extends DomainEvent:
  val eventType = "vehicle.created"

object VehicleCreated:
  given JsonCodec[VehicleCreated] = DeriveJsonCodec.gen[VehicleCreated]

/**
 * ТС обновлено
 */
final case class VehicleUpdated(
    eventId: String,
    timestamp: Instant,
    vehicleId: VehicleId,
    changes: Map[String, String]
) extends DomainEvent:
  val eventType = "vehicle.updated"

object VehicleUpdated:
  given JsonCodec[VehicleUpdated] = DeriveJsonCodec.gen[VehicleUpdated]

/**
 * ТС удалено
 */
final case class VehicleDeleted(
    eventId: String,
    timestamp: Instant,
    vehicleId: VehicleId,
    reason: Option[String]
) extends DomainEvent:
  val eventType = "vehicle.deleted"

object VehicleDeleted:
  given JsonCodec[VehicleDeleted] = DeriveJsonCodec.gen[VehicleDeleted]

// ============================================================
// СОБЫТИЯ ОРГАНИЗАЦИЙ
// ============================================================

/**
 * Организация создана
 */
final case class OrganizationCreated(
    eventId: String,
    timestamp: Instant,
    organizationId: OrganizationId,
    name: String,
    email: String
) extends DomainEvent:
  val eventType = "organization.created"

object OrganizationCreated:
  given JsonCodec[OrganizationCreated] = DeriveJsonCodec.gen[OrganizationCreated]

/**
 * Организация обновлена
 */
final case class OrganizationUpdated(
    eventId: String,
    timestamp: Instant,
    organizationId: OrganizationId,
    changes: Map[String, String]
) extends DomainEvent:
  val eventType = "organization.updated"

object OrganizationUpdated:
  given JsonCodec[OrganizationUpdated] = DeriveJsonCodec.gen[OrganizationUpdated]

/**
 * Организация деактивирована (все устройства должны быть отключены)
 */
final case class OrganizationDeactivated(
    eventId: String,
    timestamp: Instant,
    organizationId: OrganizationId,
    reason: String
) extends DomainEvent:
  val eventType = "organization.deactivated"

object OrganizationDeactivated:
  given JsonCodec[OrganizationDeactivated] = DeriveJsonCodec.gen[OrganizationDeactivated]

// ============================================================
// КОМАНДЫ ДЛЯ REDIS PUB/SUB
// ============================================================

/**
 * Команда для Connection Manager через Redis Pub/Sub
 * 
 * Канал: device-config-changed
 * Connection Manager подписан и реагирует на эти команды
 */
sealed trait DeviceConfigCommand derives JsonCodec:
  def imei: String

/**
 * Включить устройство (разрешить подключения)
 */
final case class EnableDevice(imei: String) extends DeviceConfigCommand derives JsonCodec

/**
 * Отключить устройство (запретить подключения, закрыть текущее)
 */
final case class DisableDevice(imei: String, reason: String) extends DeviceConfigCommand derives JsonCodec

/**
 * Обновить маппинг IMEI → VehicleId в Redis
 */
final case class UpdateImeiMapping(
    imei: String,
    vehicleId: Long
) extends DeviceConfigCommand derives JsonCodec
