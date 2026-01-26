package com.wayrecall.device.repository

import zio.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import cats.effect.kernel.Async
import cats.syntax.all.*
import com.wayrecall.device.domain.*
import com.wayrecall.device.domain.NotFoundError.*
import com.wayrecall.device.domain.ConflictError.*
import java.time.Instant

// ============================================================
// РЕПОЗИТОРИЙ УСТРОЙСТВ
// ============================================================

/**
 * Репозиторий для работы с устройствами в PostgreSQL
 * 
 * ✅ Чисто функциональный через ZIO
 * ✅ Использует Doobie для типобезопасного SQL
 * ✅ Все запросы параметризованы (защита от SQL injection)
 */
trait DeviceRepository:
  
  // ========== CRUD операции ==========
  
  /**
   * Создать устройство
   * 
   * @return ID созданного устройства
   */
  def create(device: CreateDeviceRequest): IO[DomainError, DeviceId]
  
  /**
   * Найти устройство по ID
   */
  def findById(id: DeviceId): IO[DomainError, Option[Device]]
  
  /**
   * Найти устройство по IMEI
   */
  def findByImei(imei: Imei): IO[DomainError, Option[Device]]
  
  /**
   * Обновить устройство
   */
  def update(id: DeviceId, request: UpdateDeviceRequest): IO[DomainError, Device]
  
  /**
   * Удалить устройство (soft delete - меняем статус)
   */
  def delete(id: DeviceId): IO[DomainError, Unit]
  
  // ========== Поиск и фильтрация ==========
  
  /**
   * Найти все устройства организации
   */
  def findByOrganization(orgId: OrganizationId): IO[DomainError, List[Device]]
  
  /**
   * Найти устройства по статусу
   */
  def findByStatus(status: DeviceStatus): IO[DomainError, List[Device]]
  
  /**
   * Найти все активные устройства (для синхронизации с Redis)
   */
  def findAllActive: IO[DomainError, List[Device]]
  
  // ========== Специфичные операции ==========
  
  /**
   * Привязать устройство к ТС
   */
  def assignToVehicle(deviceId: DeviceId, vehicleId: VehicleId): IO[DomainError, Unit]
  
  /**
   * Отвязать устройство от ТС
   */
  def unassignFromVehicle(deviceId: DeviceId): IO[DomainError, Unit]
  
  /**
   * Обновить время последнего подключения
   */
  def updateLastSeen(imei: Imei, timestamp: Instant): IO[DomainError, Unit]
  
  /**
   * Изменить статус устройства
   */
  def updateStatus(id: DeviceId, status: DeviceStatus): IO[DomainError, Unit]
  
  /**
   * Проверить, существует ли IMEI
   */
  def existsByImei(imei: Imei): IO[DomainError, Boolean]
  
  /**
   * Подсчитать устройства организации
   */
  def countByOrganization(orgId: OrganizationId): IO[DomainError, Int]

// ============================================================
// DTO ДЛЯ СОЗДАНИЯ/ОБНОВЛЕНИЯ
// ============================================================

/**
 * Запрос на создание устройства
 */
final case class CreateDeviceRequest(
    imei: Imei,
    name: Option[String],
    protocol: Protocol,
    organizationId: OrganizationId,
    vehicleId: Option[VehicleId],
    sensorProfileId: Option[SensorProfileId],
    phoneNumber: Option[String]
)

/**
 * Запрос на обновление устройства
 */
final case class UpdateDeviceRequest(
    name: Option[String],
    protocol: Option[Protocol],
    status: Option[DeviceStatus],
    vehicleId: Option[Option[VehicleId]], // Some(None) = отвязать
    sensorProfileId: Option[Option[SensorProfileId]],
    phoneNumber: Option[String],
    firmwareVersion: Option[String]
)

// ============================================================
// LIVE РЕАЛИЗАЦИЯ (DOOBIE + POSTGRESQL)
// ============================================================

object DeviceRepository:
  
  /**
   * Accessor методы для ZIO
   */
  def create(device: CreateDeviceRequest): ZIO[DeviceRepository, DomainError, DeviceId] =
    ZIO.serviceWithZIO(_.create(device))
  
  def findById(id: DeviceId): ZIO[DeviceRepository, DomainError, Option[Device]] =
    ZIO.serviceWithZIO(_.findById(id))
  
  def findByImei(imei: Imei): ZIO[DeviceRepository, DomainError, Option[Device]] =
    ZIO.serviceWithZIO(_.findByImei(imei))
  
  def findByOrganization(orgId: OrganizationId): ZIO[DeviceRepository, DomainError, List[Device]] =
    ZIO.serviceWithZIO(_.findByOrganization(orgId))
  
  def findAllActive: ZIO[DeviceRepository, DomainError, List[Device]] =
    ZIO.serviceWithZIO(_.findAllActive)
  
  /**
   * Live реализация с Doobie + HikariCP
   * 
   * @param xa Doobie Transactor (пул соединений)
   */
  final case class Live(xa: Transactor[Task]) extends DeviceRepository:
    
    import Queries.*
    
    override def create(request: CreateDeviceRequest): IO[DomainError, DeviceId] =
      for
        // Проверяем уникальность IMEI
        exists <- runQuery(checkImeiExists(request.imei))
        _ <- ZIO.when(exists)(
          ZIO.fail(ImeiAlreadyExists(request.imei.value))
        )
        
        // Создаём устройство
        now <- Clock.instant
        id <- runQuery(insertDevice(request, now))
        
        _ <- ZIO.logInfo(s"Устройство создано: ID=${id.value}, IMEI=${request.imei.value}")
      yield id
    
    override def findById(id: DeviceId): IO[DomainError, Option[Device]] =
      runQuery(selectDeviceById(id))
    
    override def findByImei(imei: Imei): IO[DomainError, Option[Device]] =
      runQuery(selectDeviceByImei(imei))
    
    override def update(id: DeviceId, request: UpdateDeviceRequest): IO[DomainError, Device] =
      for
        // Проверяем существование
        existing <- findById(id).flatMap {
          case Some(d) => ZIO.succeed(d)
          case None => ZIO.fail(DeviceNotFound(id))
        }
        
        // Обновляем
        now <- Clock.instant
        _ <- runQuery(updateDevice(id, request, now))
        
        // Возвращаем обновлённое
        updated <- findById(id).flatMap {
          case Some(d) => ZIO.succeed(d)
          case None => ZIO.fail(DeviceNotFound(id))
        }
        
        _ <- ZIO.logInfo(s"Устройство обновлено: ID=${id.value}")
      yield updated
    
    override def delete(id: DeviceId): IO[DomainError, Unit] =
      for
        now <- Clock.instant
        affected <- runQuery(softDeleteDevice(id, now))
        _ <- ZIO.when(affected == 0)(
          ZIO.fail(DeviceNotFound(id))
        )
        _ <- ZIO.logInfo(s"Устройство удалено (soft): ID=${id.value}")
      yield ()
    
    override def findByOrganization(orgId: OrganizationId): IO[DomainError, List[Device]] =
      runQuery(selectDevicesByOrganization(orgId))
    
    override def findByStatus(status: DeviceStatus): IO[DomainError, List[Device]] =
      runQuery(selectDevicesByStatus(status))
    
    override def findAllActive: IO[DomainError, List[Device]] =
      runQuery(selectActiveDevices)
    
    override def assignToVehicle(deviceId: DeviceId, vehicleId: VehicleId): IO[DomainError, Unit] =
      for
        now <- Clock.instant
        affected <- runQuery(assignDeviceToVehicle(deviceId, vehicleId, now))
        _ <- ZIO.when(affected == 0)(
          ZIO.fail(DeviceNotFound(deviceId))
        )
        _ <- ZIO.logInfo(s"Устройство ${deviceId.value} привязано к ТС ${vehicleId.value}")
      yield ()
    
    override def unassignFromVehicle(deviceId: DeviceId): IO[DomainError, Unit] =
      for
        now <- Clock.instant
        affected <- runQuery(unassignDeviceFromVehicle(deviceId, now))
        _ <- ZIO.when(affected == 0)(
          ZIO.fail(DeviceNotFound(deviceId))
        )
        _ <- ZIO.logInfo(s"Устройство ${deviceId.value} отвязано от ТС")
      yield ()
    
    override def updateLastSeen(imei: Imei, timestamp: Instant): IO[DomainError, Unit] =
      runQuery(updateDeviceLastSeen(imei, timestamp)).unit
    
    override def updateStatus(id: DeviceId, status: DeviceStatus): IO[DomainError, Unit] =
      for
        now <- Clock.instant
        affected <- runQuery(updateDeviceStatus(id, status, now))
        _ <- ZIO.when(affected == 0)(
          ZIO.fail(DeviceNotFound(id))
        )
        _ <- ZIO.logInfo(s"Статус устройства ${id.value} изменён на $status")
      yield ()
    
    override def existsByImei(imei: Imei): IO[DomainError, Boolean] =
      runQuery(checkImeiExists(imei))
    
    override def countByOrganization(orgId: OrganizationId): IO[DomainError, Int] =
      runQuery(countDevicesByOrganization(orgId))
    
    /**
     * Выполняет Doobie запрос через ZIO
     */
    private def runQuery[A](query: ConnectionIO[A]): IO[DomainError, A] =
      ZIO.fromFuture(_ => 
        import cats.effect.unsafe.implicits.global
        query.transact(xa).unsafeToFuture()
      ).mapError(e => InfrastructureError.DatabaseError(e.getMessage))
  
  /**
   * SQL запросы (отдельный объект для читаемости)
   */
  private object Queries:
    
    // Маппинги для enum
    given Meta[Protocol] = Meta[String].timap(Protocol.valueOf)(_.toString)
    given Meta[DeviceStatus] = Meta[String].timap(DeviceStatus.valueOf)(_.toString)
    given Meta[DeviceId] = Meta[Long].timap(DeviceId.apply)(_.value)
    given Meta[VehicleId] = Meta[Long].timap(VehicleId.apply)(_.value)
    given Meta[OrganizationId] = Meta[Long].timap(OrganizationId.apply)(_.value)
    given Meta[SensorProfileId] = Meta[Long].timap(SensorProfileId.apply)(_.value)
    given Meta[Imei] = Meta[String].timap(Imei.unsafe)(_.value)
    
    def checkImeiExists(imei: Imei): ConnectionIO[Boolean] =
      sql"""
        SELECT EXISTS(SELECT 1 FROM devices WHERE imei = ${imei.value})
      """.query[Boolean].unique
    
    def insertDevice(r: CreateDeviceRequest, now: Instant): ConnectionIO[DeviceId] =
      sql"""
        INSERT INTO devices (
          imei, name, protocol, status, organization_id, vehicle_id, 
          sensor_profile_id, phone_number, created_at, updated_at
        ) VALUES (
          ${r.imei}, ${r.name}, ${r.protocol}, ${DeviceStatus.Active}, 
          ${r.organizationId}, ${r.vehicleId}, ${r.sensorProfileId}, 
          ${r.phoneNumber}, $now, $now
        )
        RETURNING id
      """.query[DeviceId].unique
    
    def selectDeviceById(id: DeviceId): ConnectionIO[Option[Device]] =
      sql"""
        SELECT id, imei, name, protocol, status, organization_id, vehicle_id,
               sensor_profile_id, phone_number, firmware_version, 
               last_seen_at, created_at, updated_at
        FROM devices
        WHERE id = $id AND status != ${DeviceStatus.Deleted}
      """.query[Device].option
    
    def selectDeviceByImei(imei: Imei): ConnectionIO[Option[Device]] =
      sql"""
        SELECT id, imei, name, protocol, status, organization_id, vehicle_id,
               sensor_profile_id, phone_number, firmware_version,
               last_seen_at, created_at, updated_at
        FROM devices
        WHERE imei = ${imei.value} AND status != ${DeviceStatus.Deleted}
      """.query[Device].option
    
    def selectDevicesByOrganization(orgId: OrganizationId): ConnectionIO[List[Device]] =
      sql"""
        SELECT id, imei, name, protocol, status, organization_id, vehicle_id,
               sensor_profile_id, phone_number, firmware_version,
               last_seen_at, created_at, updated_at
        FROM devices
        WHERE organization_id = $orgId AND status != ${DeviceStatus.Deleted}
        ORDER BY created_at DESC
      """.query[Device].to[List]
    
    def selectDevicesByStatus(status: DeviceStatus): ConnectionIO[List[Device]] =
      sql"""
        SELECT id, imei, name, protocol, status, organization_id, vehicle_id,
               sensor_profile_id, phone_number, firmware_version,
               last_seen_at, created_at, updated_at
        FROM devices
        WHERE status = $status
        ORDER BY created_at DESC
      """.query[Device].to[List]
    
    def selectActiveDevices: ConnectionIO[List[Device]] =
      sql"""
        SELECT id, imei, name, protocol, status, organization_id, vehicle_id,
               sensor_profile_id, phone_number, firmware_version,
               last_seen_at, created_at, updated_at
        FROM devices
        WHERE status = ${DeviceStatus.Active}
        ORDER BY id
      """.query[Device].to[List]
    
    def updateDevice(id: DeviceId, r: UpdateDeviceRequest, now: Instant): ConnectionIO[Int] =
      // Динамический UPDATE с только изменёнными полями
      // Используем простой вариант для примера
      sql"""
        UPDATE devices SET
          name = COALESCE(${r.name}, name),
          updated_at = $now
        WHERE id = $id AND status != ${DeviceStatus.Deleted}
      """.update.run
    
    def softDeleteDevice(id: DeviceId, now: Instant): ConnectionIO[Int] =
      sql"""
        UPDATE devices SET
          status = ${DeviceStatus.Deleted},
          updated_at = $now
        WHERE id = $id AND status != ${DeviceStatus.Deleted}
      """.update.run
    
    def assignDeviceToVehicle(deviceId: DeviceId, vehicleId: VehicleId, now: Instant): ConnectionIO[Int] =
      sql"""
        UPDATE devices SET
          vehicle_id = $vehicleId,
          updated_at = $now
        WHERE id = $deviceId AND status != ${DeviceStatus.Deleted}
      """.update.run
    
    def unassignDeviceFromVehicle(deviceId: DeviceId, now: Instant): ConnectionIO[Int] =
      sql"""
        UPDATE devices SET
          vehicle_id = NULL,
          updated_at = $now
        WHERE id = $deviceId AND status != ${DeviceStatus.Deleted}
      """.update.run
    
    def updateDeviceLastSeen(imei: Imei, timestamp: Instant): ConnectionIO[Int] =
      sql"""
        UPDATE devices SET
          last_seen_at = $timestamp
        WHERE imei = ${imei.value}
      """.update.run
    
    def updateDeviceStatus(id: DeviceId, status: DeviceStatus, now: Instant): ConnectionIO[Int] =
      sql"""
        UPDATE devices SET
          status = $status,
          updated_at = $now
        WHERE id = $id
      """.update.run
    
    def countDevicesByOrganization(orgId: OrganizationId): ConnectionIO[Int] =
      sql"""
        SELECT COUNT(*) FROM devices
        WHERE organization_id = $orgId AND status != ${DeviceStatus.Deleted}
      """.query[Int].unique
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[Transactor[Task], Nothing, DeviceRepository] =
    ZLayer.fromFunction(Live.apply)
