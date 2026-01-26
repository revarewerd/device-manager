package com.wayrecall.device.domain

// ============================================================
// ОШИБКИ (ADT для типизированной обработки)
// ============================================================

/**
 * Базовый trait для всех ошибок сервиса
 * 
 * Используем ADT (Algebraic Data Type) для:
 * - Exhaustive pattern matching
 * - Типизированная обработка ошибок
 * - Осмысленные сообщения для API
 */
sealed trait DomainError extends Throwable:
  def message: String
  def code: String
  override def getMessage: String = message

// ============================================================
// ОШИБКИ ВАЛИДАЦИИ
// ============================================================

/**
 * Ошибки валидации входных данных
 */
sealed trait ValidationError extends DomainError:
  val code = "VALIDATION_ERROR"

object ValidationError:
  
  /** Невалидный IMEI */
  final case class InvalidImei(imei: String) extends ValidationError:
    val message = s"Невалидный IMEI: '$imei'. IMEI должен быть 15-значным числом"
  
  /** Пустое обязательное поле */
  final case class EmptyField(fieldName: String) extends ValidationError:
    val message = s"Поле '$fieldName' не может быть пустым"
  
  /** Значение вне допустимого диапазона */
  final case class ValueOutOfRange(fieldName: String, value: Any, min: Any, max: Any) extends ValidationError:
    val message = s"Значение поля '$fieldName' ($value) должно быть в диапазоне [$min, $max]"
  
  /** Невалидный формат email */
  final case class InvalidEmail(email: String) extends ValidationError:
    val message = s"Невалидный email: '$email'"
  
  /** Невалидный формат телефона */
  final case class InvalidPhone(phone: String) extends ValidationError:
    val message = s"Невалидный номер телефона: '$phone'"
  
  /** Превышен лимит */
  final case class LimitExceeded(resource: String, current: Int, limit: Int) extends ValidationError:
    val message = s"Превышен лимит $resource: $current из $limit"

// ============================================================
// ОШИБКИ ПОИСКА (NOT FOUND)
// ============================================================

/**
 * Ошибки когда сущность не найдена
 */
sealed trait NotFoundError extends DomainError:
  val code = "NOT_FOUND"

object NotFoundError:
  
  /** Устройство не найдено */
  final case class DeviceNotFound(id: DeviceId) extends NotFoundError:
    val message = s"Устройство с ID ${id.value} не найдено"
  
  /** Устройство не найдено по IMEI */
  final case class DeviceNotFoundByImei(imei: String) extends NotFoundError:
    val message = s"Устройство с IMEI '$imei' не найдено"
  
  /** ТС не найдено */
  final case class VehicleNotFound(id: VehicleId) extends NotFoundError:
    val message = s"Транспортное средство с ID ${id.value} не найдено"
  
  /** Организация не найдена */
  final case class OrganizationNotFound(id: OrganizationId) extends NotFoundError:
    val message = s"Организация с ID ${id.value} не найдено"
  
  /** Профиль датчиков не найден */
  final case class SensorProfileNotFound(id: SensorProfileId) extends NotFoundError:
    val message = s"Профиль датчиков с ID ${id.value} не найден"

// ============================================================
// ОШИБКИ КОНФЛИКТОВ
// ============================================================

/**
 * Ошибки конфликтов (дубликаты, занятые ресурсы)
 */
sealed trait ConflictError extends DomainError:
  val code = "CONFLICT"

object ConflictError:
  
  /** IMEI уже существует */
  final case class ImeiAlreadyExists(imei: String) extends ConflictError:
    val message = s"Устройство с IMEI '$imei' уже существует"
  
  /** Устройство уже привязано к ТС */
  final case class DeviceAlreadyAssigned(deviceId: DeviceId, vehicleId: VehicleId) extends ConflictError:
    val message = s"Устройство ${deviceId.value} уже привязано к ТС ${vehicleId.value}"
  
  /** ТС уже имеет привязанное устройство */
  final case class VehicleAlreadyHasDevice(vehicleId: VehicleId, existingDeviceId: DeviceId) extends ConflictError:
    val message = s"К ТС ${vehicleId.value} уже привязано устройство ${existingDeviceId.value}"
  
  /** Email уже занят */
  final case class EmailAlreadyExists(email: String) extends ConflictError:
    val message = s"Email '$email' уже используется"

// ============================================================
// ОШИБКИ ДОСТУПА
// ============================================================

/**
 * Ошибки прав доступа
 */
sealed trait AccessError extends DomainError:
  val code = "ACCESS_DENIED"

object AccessError:
  
  /** Нет доступа к устройству */
  final case class DeviceAccessDenied(deviceId: DeviceId, organizationId: OrganizationId) extends AccessError:
    val message = s"Организация ${organizationId.value} не имеет доступа к устройству ${deviceId.value}"
  
  /** Нет доступа к ТС */
  final case class VehicleAccessDenied(vehicleId: VehicleId, organizationId: OrganizationId) extends AccessError:
    val message = s"Организация ${organizationId.value} не имеет доступа к ТС ${vehicleId.value}"
  
  /** Организация деактивирована */
  final case class OrganizationInactive(organizationId: OrganizationId) extends AccessError:
    val message = s"Организация ${organizationId.value} деактивирована"

// ============================================================
// ОШИБКИ ИНФРАСТРУКТУРЫ
// ============================================================

/**
 * Ошибки инфраструктуры (БД, Kafka, Redis)
 */
sealed trait InfrastructureError extends DomainError:
  val code = "INFRASTRUCTURE_ERROR"

object InfrastructureError:
  
  /** Ошибка БД */
  final case class DatabaseError(cause: String) extends InfrastructureError:
    val message = s"Ошибка базы данных: $cause"
  
  /** Ошибка Kafka */
  final case class KafkaError(cause: String) extends InfrastructureError:
    val message = s"Ошибка Kafka: $cause"
  
  /** Ошибка Redis */
  final case class RedisError(cause: String) extends InfrastructureError:
    val message = s"Ошибка Redis: $cause"
  
  /** Таймаут операции */
  final case class TimeoutError(operation: String, timeoutMs: Long) extends InfrastructureError:
    val message = s"Таймаут операции '$operation' (${timeoutMs}ms)"
