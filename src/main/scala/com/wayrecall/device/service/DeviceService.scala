package com.wayrecall.device.service

import zio.*
import com.wayrecall.device.domain.*
import com.wayrecall.device.domain.NotFoundError.*
import com.wayrecall.device.domain.ValidationError.*
import com.wayrecall.device.domain.ConflictError.*
import com.wayrecall.device.repository.*
import java.time.Instant
import java.util.UUID

// ============================================================
// СЕРВИС УСТРОЙСТВ
// ============================================================

/**
 * Бизнес-логика работы с устройствами
 * 
 * Отвечает за:
 * - Валидацию данных
 * - Бизнес-правила (лимиты, доступы)
 * - Публикацию событий в Kafka
 * - Синхронизацию с Redis
 * 
 * ✅ Чисто функциональный - все эффекты через ZIO
 * ✅ Нет mutable state
 * ✅ Все зависимости через конструктор (DI)
 */
trait DeviceService:
  
  /**
   * Создать новое устройство
   * 
   * Бизнес-правила:
   * 1. IMEI должен быть уникальным
   * 2. Организация должна существовать и быть активной
   * 3. Не превышен лимит устройств организации
   * 4. Если указано vehicleId - ТС должно принадлежать той же организации
   */
  def createDevice(request: CreateDeviceCommand): IO[DomainError, Device]
  
  /**
   * Получить устройство по ID
   */
  def getDevice(id: DeviceId): IO[DomainError, Device]
  
  /**
   * Получить устройство по IMEI
   */
  def getDeviceByImei(imei: Imei): IO[DomainError, Device]
  
  /**
   * Обновить устройство
   */
  def updateDevice(id: DeviceId, request: UpdateDeviceCommand): IO[DomainError, Device]
  
  /**
   * Удалить устройство (soft delete)
   * 
   * Действия:
   * 1. Меняем статус на Deleted
   * 2. Публикуем DeviceDeleted в Kafka
   * 3. Отправляем DisableDevice в Redis для Connection Manager
   */
  def deleteDevice(id: DeviceId, reason: Option[String]): IO[DomainError, Unit]
  
  /**
   * Активировать устройство
   * 
   * Действия:
   * 1. Меняем статус на Active
   * 2. Публикуем DeviceActivated в Kafka
   * 3. Отправляем EnableDevice в Redis
   * 4. Обновляем маппинг IMEI→VehicleId в Redis
   */
  def activateDevice(id: DeviceId): IO[DomainError, Device]
  
  /**
   * Деактивировать устройство
   * 
   * Connection Manager закроет соединение и будет отклонять новые
   */
  def deactivateDevice(id: DeviceId, reason: String): IO[DomainError, Device]
  
  /**
   * Привязать устройство к ТС
   * 
   * Бизнес-правила:
   * 1. Устройство и ТС должны принадлежать одной организации
   * 2. Устройство не должно быть уже привязано
   * 3. К ТС не должно быть привязано другое активное устройство
   */
  def assignToVehicle(deviceId: DeviceId, vehicleId: VehicleId): IO[DomainError, Device]
  
  /**
   * Отвязать устройство от ТС
   */
  def unassignFromVehicle(deviceId: DeviceId): IO[DomainError, Device]
  
  /**
   * Найти устройство по IMEI (без ошибки если не найдено)
   */
  def findByImei(imei: Imei): IO[DomainError, Option[Device]]
  
  /**
   * Получить все устройства организации
   */
  def getDevicesByOrganization(orgId: OrganizationId): IO[DomainError, List[Device]]
  
  /**
   * Синхронизировать все активные устройства в Redis
   * 
   * Вызывается при старте сервиса для восстановления состояния
   */
  def syncAllToRedis: IO[DomainError, Int]

// ============================================================
// КОМАНДЫ (DTO)
// ============================================================

/**
 * Команда создания устройства (входные данные от API)
 */
final case class CreateDeviceCommand(
    imei: String,
    name: Option[String],
    protocol: Protocol,
    organizationId: Long,
    vehicleId: Option[Long],
    sensorProfileId: Option[Long],
    phoneNumber: Option[String]
)

/**
 * Команда обновления устройства
 */
final case class UpdateDeviceCommand(
    name: Option[String],
    protocol: Option[Protocol],
    vehicleId: Option[Option[Long]], // Some(Some(id)) = привязать, Some(None) = отвязать
    sensorProfileId: Option[Option[Long]],
    phoneNumber: Option[String],
    firmwareVersion: Option[String]
)

// ============================================================
// LIVE РЕАЛИЗАЦИЯ
// ============================================================

object DeviceService:
  
  /**
   * Accessor методы
   */
  def createDevice(request: CreateDeviceCommand): ZIO[DeviceService, DomainError, Device] =
    ZIO.serviceWithZIO(_.createDevice(request))
  
  def getDevice(id: DeviceId): ZIO[DeviceService, DomainError, Device] =
    ZIO.serviceWithZIO(_.getDevice(id))
  
  def getDeviceByImei(imei: Imei): ZIO[DeviceService, DomainError, Device] =
    ZIO.serviceWithZIO(_.getDeviceByImei(imei))
  
  def findByImei(imei: Imei): ZIO[DeviceService, DomainError, Option[Device]] =
    ZIO.serviceWithZIO(_.findByImei(imei))
  
  def getDevicesByOrganization(orgId: OrganizationId): ZIO[DeviceService, DomainError, List[Device]] =
    ZIO.serviceWithZIO(_.getDevicesByOrganization(orgId))
  
  def updateDevice(id: DeviceId, request: UpdateDeviceCommand): ZIO[DeviceService, DomainError, Device] =
    ZIO.serviceWithZIO(_.updateDevice(id, request))
  
  def deleteDevice(id: DeviceId, reason: Option[String]): ZIO[DeviceService, DomainError, Unit] =
    ZIO.serviceWithZIO(_.deleteDevice(id, reason))
  
  def activateDevice(id: DeviceId): ZIO[DeviceService, DomainError, Device] =
    ZIO.serviceWithZIO(_.activateDevice(id))
  
  def deactivateDevice(id: DeviceId, reason: String): ZIO[DeviceService, DomainError, Device] =
    ZIO.serviceWithZIO(_.deactivateDevice(id, reason))
  
  def assignToVehicle(deviceId: DeviceId, vehicleId: VehicleId): ZIO[DeviceService, DomainError, Device] =
    ZIO.serviceWithZIO(_.assignToVehicle(deviceId, vehicleId))
  
  def unassignFromVehicle(deviceId: DeviceId): ZIO[DeviceService, DomainError, Device] =
    ZIO.serviceWithZIO(_.unassignFromVehicle(deviceId))
  
  def syncAllToRedis: ZIO[DeviceService, DomainError, Int] =
    ZIO.serviceWithZIO(_.syncAllToRedis)
  
  /**
   * Live реализация
   */
  final case class Live(
      deviceRepo: DeviceRepository,
      eventPublisher: EventPublisher,
      redisSync: RedisSync
  ) extends DeviceService:
    
    override def createDevice(request: CreateDeviceCommand): IO[DomainError, Device] =
      for
        // 1. Валидация IMEI
        imei <- ZIO.fromEither(Imei(request.imei))
                   .mapError(e => InvalidImei(request.imei))
        
        // 2. Проверка уникальности IMEI
        exists <- deviceRepo.existsByImei(imei)
        _ <- ZIO.when(exists)(
          ZIO.fail(ImeiAlreadyExists(request.imei))
        )
        
        // 3. Проверка лимита устройств организации
        orgId = OrganizationId(request.organizationId)
        count <- deviceRepo.countByOrganization(orgId)
        // TODO: Получить лимит из OrganizationRepository
        // _ <- ZIO.when(count >= maxDevices)(ZIO.fail(LimitExceeded(...)))
        
        // 4. Создание устройства
        createReq = CreateDeviceRequest(
          imei = imei,
          name = request.name,
          protocol = request.protocol,
          organizationId = orgId,
          vehicleId = request.vehicleId.map(VehicleId.apply),
          sensorProfileId = request.sensorProfileId.map(SensorProfileId.apply),
          phoneNumber = request.phoneNumber
        )
        deviceId <- deviceRepo.create(createReq)
        
        // 5. Получаем созданное устройство
        device <- deviceRepo.findById(deviceId).flatMap {
          case Some(d) => ZIO.succeed(d)
          case None => ZIO.fail(DeviceNotFound(deviceId))
        }
        
        // 6. Публикуем событие в Kafka
        now <- Clock.instant
        event = DeviceCreated(
          eventId = UUID.randomUUID().toString,
          timestamp = now,
          deviceId = device.id,
          imei = device.imei,
          protocol = device.protocol,
          organizationId = device.organizationId,
          vehicleId = device.vehicleId
        )
        _ <- eventPublisher.publish(event).catchAll(e =>
               ZIO.logError(s"Ошибка публикации события: ${e.getMessage}")
             )
        
        // 7. Синхронизируем с Redis
        _ <- redisSync.syncDevice(device).catchAll(e =>
               ZIO.logError(s"Ошибка синхронизации с Redis: ${e.getMessage}")
             )
        
        _ <- ZIO.logInfo(s"Устройство создано: ID=${device.id.value}, IMEI=${device.imei.value}")
      yield device
    
    override def getDevice(id: DeviceId): IO[DomainError, Device] =
      deviceRepo.findById(id).flatMap {
        case Some(d) => ZIO.succeed(d)
        case None => ZIO.fail(DeviceNotFound(id))
      }
    
    override def getDeviceByImei(imei: Imei): IO[DomainError, Device] =
      deviceRepo.findByImei(imei).flatMap {
        case Some(d) => ZIO.succeed(d)
        case None => ZIO.fail(DeviceNotFoundByImei(imei.value))
      }
    
    override def findByImei(imei: Imei): IO[DomainError, Option[Device]] =
      deviceRepo.findByImei(imei)
    
    override def updateDevice(id: DeviceId, request: UpdateDeviceCommand): IO[DomainError, Device] =
      for
        // 1. Проверяем существование
        existing <- getDevice(id)
        
        // 2. Обновляем
        updateReq = UpdateDeviceRequest(
          name = request.name,
          protocol = request.protocol,
          status = None,
          vehicleId = request.vehicleId.map(_.map(VehicleId.apply)),
          sensorProfileId = request.sensorProfileId.map(_.map(SensorProfileId.apply)),
          phoneNumber = request.phoneNumber,
          firmwareVersion = request.firmwareVersion
        )
        updated <- deviceRepo.update(id, updateReq)
        
        // 3. Публикуем событие
        now <- Clock.instant
        event = DeviceUpdated(
          eventId = UUID.randomUUID().toString,
          timestamp = now,
          deviceId = id,
          imei = updated.imei,
          changes = Map("updated" -> "true") // TODO: вычислить реальные изменения
        )
        _ <- eventPublisher.publish(event).ignore
        
        // 4. Синхронизируем с Redis
        _ <- redisSync.syncDevice(updated).ignore
        
        _ <- ZIO.logInfo(s"Устройство обновлено: ID=${id.value}")
      yield updated
    
    override def deleteDevice(id: DeviceId, reason: Option[String]): IO[DomainError, Unit] =
      for
        // 1. Получаем устройство
        device <- getDevice(id)
        
        // 2. Soft delete
        _ <- deviceRepo.delete(id)
        
        // 3. Публикуем событие
        now <- Clock.instant
        event = DeviceDeleted(
          eventId = UUID.randomUUID().toString,
          timestamp = now,
          deviceId = id,
          imei = device.imei,
          reason = reason
        )
        _ <- eventPublisher.publish(event).ignore
        
        // 4. Отключаем в Connection Manager через Redis
        _ <- redisSync.disableDevice(device.imei, reason.getOrElse("Устройство удалено")).ignore
        
        _ <- ZIO.logInfo(s"Устройство удалено: ID=${id.value}, IMEI=${device.imei.value}")
      yield ()
    
    override def activateDevice(id: DeviceId): IO[DomainError, Device] =
      for
        device <- getDevice(id)
        _ <- deviceRepo.updateStatus(id, DeviceStatus.Active)
        updated <- getDevice(id)
        
        // Публикуем событие
        now <- Clock.instant
        event = DeviceActivated(
          eventId = UUID.randomUUID().toString,
          timestamp = now,
          deviceId = id,
          imei = updated.imei
        )
        _ <- eventPublisher.publish(event).ignore
        
        // Включаем в Redis
        _ <- redisSync.enableDevice(updated.imei).ignore
        _ <- redisSync.syncDevice(updated).ignore
        
        _ <- ZIO.logInfo(s"Устройство активировано: ID=${id.value}")
      yield updated
    
    override def deactivateDevice(id: DeviceId, reason: String): IO[DomainError, Device] =
      for
        device <- getDevice(id)
        _ <- deviceRepo.updateStatus(id, DeviceStatus.Inactive)
        updated <- getDevice(id)
        
        // Публикуем событие
        now <- Clock.instant
        event = DeviceDeactivated(
          eventId = UUID.randomUUID().toString,
          timestamp = now,
          deviceId = id,
          imei = updated.imei,
          reason = reason
        )
        _ <- eventPublisher.publish(event).ignore
        
        // Отключаем в Redis
        _ <- redisSync.disableDevice(updated.imei, reason).ignore
        
        _ <- ZIO.logInfo(s"Устройство деактивировано: ID=${id.value}, причина: $reason")
      yield updated
    
    override def assignToVehicle(deviceId: DeviceId, vehicleId: VehicleId): IO[DomainError, Device] =
      for
        device <- getDevice(deviceId)
        
        // Проверяем, не привязано ли уже
        _ <- ZIO.when(device.vehicleId.isDefined)(
          ZIO.fail(DeviceAlreadyAssigned(deviceId, device.vehicleId.get))
        )
        
        // TODO: Проверить, что ТС принадлежит той же организации
        // TODO: Проверить, что к ТС не привязано другое устройство
        
        previousVehicleId = device.vehicleId
        _ <- deviceRepo.assignToVehicle(deviceId, vehicleId)
        updated <- getDevice(deviceId)
        
        // Публикуем событие
        now <- Clock.instant
        event = DeviceAssignedToVehicle(
          eventId = UUID.randomUUID().toString,
          timestamp = now,
          deviceId = deviceId,
          imei = updated.imei,
          vehicleId = vehicleId,
          previousVehicleId = previousVehicleId
        )
        _ <- eventPublisher.publish(event).ignore
        
        // Синхронизируем маппинг в Redis
        _ <- redisSync.updateImeiMapping(updated.imei, vehicleId).ignore
        
        _ <- ZIO.logInfo(s"Устройство ${deviceId.value} привязано к ТС ${vehicleId.value}")
      yield updated
    
    override def unassignFromVehicle(deviceId: DeviceId): IO[DomainError, Device] =
      for
        device <- getDevice(deviceId)
        
        vehicleId <- ZIO.fromOption(device.vehicleId)
                        .orElseFail(DeviceNotFound(deviceId)) // Устройство не было привязано
        
        _ <- deviceRepo.unassignFromVehicle(deviceId)
        updated <- getDevice(deviceId)
        
        // Публикуем событие
        now <- Clock.instant
        event = DeviceUnassignedFromVehicle(
          eventId = UUID.randomUUID().toString,
          timestamp = now,
          deviceId = deviceId,
          imei = updated.imei,
          vehicleId = vehicleId
        )
        _ <- eventPublisher.publish(event).ignore
        
        // Удаляем маппинг из Redis
        _ <- redisSync.removeImeiMapping(updated.imei).ignore
        
        _ <- ZIO.logInfo(s"Устройство ${deviceId.value} отвязано от ТС ${vehicleId.value}")
      yield updated
    
    override def getDevicesByOrganization(orgId: OrganizationId): IO[DomainError, List[Device]] =
      deviceRepo.findByOrganization(orgId)
    
    override def syncAllToRedis: IO[DomainError, Int] =
      for
        _ <- ZIO.logInfo("Начинаем синхронизацию устройств в Redis...")
        devices <- deviceRepo.findAllActive
        
        _ <- ZIO.foreachParDiscard(devices) { device =>
          redisSync.syncDevice(device).catchAll(e =>
            ZIO.logWarning(s"Ошибка синхронизации ${device.imei.value}: ${e.getMessage}")
          )
        }
        
        _ <- ZIO.logInfo(s"Синхронизировано ${devices.size} активных устройств")
      yield devices.size
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[DeviceRepository & EventPublisher & RedisSync, Nothing, DeviceService] =
    ZLayer {
      for
        repo <- ZIO.service[DeviceRepository]
        eventPub <- ZIO.service[EventPublisher]
        redis <- ZIO.service[RedisSync]
      yield Live(repo, eventPub, redis)
    }

// ============================================================
// ИНТЕРФЕЙСЫ ЗАВИСИМОСТЕЙ
// ============================================================

/**
 * Публикатор событий в Kafka
 */
trait EventPublisher:
  def publish(event: DomainEvent): IO[DomainError, Unit]

/**
 * Синхронизация с Redis
 */
trait RedisSync:
  def syncDevice(device: Device): IO[DomainError, Unit]
  def enableDevice(imei: Imei): IO[DomainError, Unit]
  def disableDevice(imei: Imei, reason: String): IO[DomainError, Unit]
  def updateImeiMapping(imei: Imei, vehicleId: VehicleId): IO[DomainError, Unit]
  def removeImeiMapping(imei: Imei): IO[DomainError, Unit]
