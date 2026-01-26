package com.wayrecall.device.service

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.device.domain.*
import com.wayrecall.device.repository.*
import java.time.Instant
import java.util.UUID

// ============================================================
// UNIT ТЕСТЫ СЕРВИСА УСТРОЙСТВ
// ============================================================

/**
 * Тесты бизнес-логики DeviceService
 * 
 * Используем in-memory реализации зависимостей:
 * - InMemoryDeviceRepository
 * - MockEventPublisher
 * - MockRedisSync
 */
object DeviceServiceSpec extends ZIOSpecDefault:
  
  // ----------------------------------------------------------
  // Mock реализации
  // ----------------------------------------------------------
  
  /**
   * In-memory репозиторий для тестов
   */
  class InMemoryDeviceRepository extends DeviceRepository:
    private val devices = Ref.unsafe.make(Map.empty[DeviceId, Device])
    private var idCounter = 0L
    
    override def create(request: CreateDeviceRequest): IO[DomainError, Device] =
      for
        now <- Clock.instant
        _ <- devices.update(_.values.find(_.imei == request.imei) match
          case Some(_) => throw new RuntimeException("IMEI already exists")
          case None => identity
        ).mapError(_ => ConflictError.ImeiAlreadyExists(request.imei.value))
        
        id = { idCounter += 1; DeviceId(idCounter) }
        device = Device(
          id = id,
          imei = request.imei,
          name = request.name,
          protocol = request.protocol,
          status = DeviceStatus.Inactive,
          organizationId = request.organizationId,
          vehicleId = request.vehicleId,
          sensorProfileId = request.sensorProfileId,
          phoneNumber = request.phoneNumber,
          firmwareVersion = None,
          lastSeenAt = None,
          disabledReason = None,
          createdAt = now,
          updatedAt = now
        )
        _ <- devices.update(_ + (id -> device))
      yield device
    
    override def findById(id: DeviceId): IO[DomainError, Option[Device]] =
      devices.get.map(_.get(id))
    
    override def findByImei(imei: Imei): IO[DomainError, Option[Device]] =
      devices.get.map(_.values.find(_.imei == imei))
    
    override def update(id: DeviceId, request: UpdateDeviceRequest): IO[DomainError, Device] =
      for
        now <- Clock.instant
        existing <- findById(id).flatMap {
          case Some(d) => ZIO.succeed(d)
          case None => ZIO.fail(NotFoundError.DeviceNotFound(id))
        }
        updated = existing.copy(
          name = request.name.orElse(existing.name),
          protocol = request.protocol.getOrElse(existing.protocol),
          status = request.status.getOrElse(existing.status),
          vehicleId = request.vehicleId.getOrElse(existing.vehicleId),
          sensorProfileId = request.sensorProfileId.getOrElse(existing.sensorProfileId),
          phoneNumber = request.phoneNumber.orElse(existing.phoneNumber),
          firmwareVersion = request.firmwareVersion.orElse(existing.firmwareVersion),
          disabledReason = request.disabledReason.orElse(existing.disabledReason),
          updatedAt = now
        )
        _ <- devices.update(_ + (id -> updated))
      yield updated
    
    override def delete(id: DeviceId): IO[DomainError, Unit] =
      devices.update(_ - id)
    
    override def findByOrganization(orgId: OrganizationId): IO[DomainError, List[Device]] =
      devices.get.map(_.values.filter(_.organizationId == orgId).toList)
    
    override def existsByImei(imei: Imei): IO[DomainError, Boolean] =
      devices.get.map(_.values.exists(_.imei == imei))
    
    override def countByOrganization(orgId: OrganizationId): IO[DomainError, Long] =
      devices.get.map(_.values.count(_.organizationId == orgId).toLong)
    
    override def findActiveByOrganization(orgId: OrganizationId): IO[DomainError, List[Device]] =
      devices.get.map(_.values.filter(d => 
        d.organizationId == orgId && d.status == DeviceStatus.Active
      ).toList)
  
  /**
   * Mock EventPublisher - просто собирает события
   */
  class MockEventPublisher extends EventPublisher:
    val events = Ref.unsafe.make(List.empty[DomainEvent])
    
    override def publish(event: DomainEvent): IO[DomainError, Unit] =
      events.update(_ :+ event)
    
    def getEvents: UIO[List[DomainEvent]] = events.get
  
  /**
   * Mock RedisSync - ничего не делает
   */
  class MockRedisSync extends RedisSync:
    override def syncDevice(device: Device): IO[DomainError, Unit] = ZIO.unit
    override def enableDevice(imei: Imei): IO[DomainError, Unit] = ZIO.unit
    override def disableDevice(imei: Imei, reason: String): IO[DomainError, Unit] = ZIO.unit
    override def updateImeiMapping(imei: Imei, vehicleId: VehicleId): IO[DomainError, Unit] = ZIO.unit
    override def removeImeiMapping(imei: Imei): IO[DomainError, Unit] = ZIO.unit
  
  // ----------------------------------------------------------
  // Тесты
  // ----------------------------------------------------------
  
  def spec = suite("DeviceServiceSpec")(
    
    // Тест создания устройства
    test("создание устройства с валидным IMEI") {
      val repo = new InMemoryDeviceRepository
      val publisher = new MockEventPublisher
      val redis = new MockRedisSync
      val service = DeviceService.Live(repo, publisher, redis)
      
      val cmd = CreateDeviceCommand(
        imei = "123456789012345",
        name = Some("Тестовое устройство"),
        protocol = Protocol.Teltonika,
        organizationId = 1L,
        vehicleId = None,
        sensorProfileId = None,
        phoneNumber = None
      )
      
      for
        device <- service.createDevice(cmd)
        events <- publisher.getEvents
      yield assertTrue(
        device.imei.value == "123456789012345",
        device.name.contains("Тестовое устройство"),
        device.protocol == Protocol.Teltonika,
        device.status == DeviceStatus.Inactive,
        events.size == 1,
        events.head.isInstanceOf[DeviceCreated]
      )
    },
    
    // Тест ошибки при невалидном IMEI
    test("ошибка при создании устройства с невалидным IMEI") {
      val repo = new InMemoryDeviceRepository
      val publisher = new MockEventPublisher
      val redis = new MockRedisSync
      val service = DeviceService.Live(repo, publisher, redis)
      
      val cmd = CreateDeviceCommand(
        imei = "12345", // Слишком короткий
        name = None,
        protocol = Protocol.Teltonika,
        organizationId = 1L,
        vehicleId = None,
        sensorProfileId = None,
        phoneNumber = None
      )
      
      for
        result <- service.createDevice(cmd).exit
      yield assertTrue(
        result.isFailure,
        result.foldExit(
          cause => cause.failureOption.exists(_.isInstanceOf[ValidationError]),
          _ => false
        )
      )
    },
    
    // Тест получения устройства по ID
    test("получение устройства по ID") {
      val repo = new InMemoryDeviceRepository
      val publisher = new MockEventPublisher
      val redis = new MockRedisSync
      val service = DeviceService.Live(repo, publisher, redis)
      
      val cmd = CreateDeviceCommand(
        imei = "123456789012345",
        name = Some("Test"),
        protocol = Protocol.Teltonika,
        organizationId = 1L,
        vehicleId = None,
        sensorProfileId = None,
        phoneNumber = None
      )
      
      for
        created <- service.createDevice(cmd)
        fetched <- service.getDevice(created.id)
      yield assertTrue(
        fetched.id == created.id,
        fetched.imei == created.imei
      )
    },
    
    // Тест ошибки при несуществующем устройстве
    test("ошибка при получении несуществующего устройства") {
      val repo = new InMemoryDeviceRepository
      val publisher = new MockEventPublisher
      val redis = new MockRedisSync
      val service = DeviceService.Live(repo, publisher, redis)
      
      for
        result <- service.getDevice(DeviceId(999L)).exit
      yield assertTrue(
        result.isFailure,
        result.foldExit(
          cause => cause.failureOption.exists(_.isInstanceOf[NotFoundError]),
          _ => false
        )
      )
    },
    
    // Тест активации устройства
    test("активация устройства") {
      val repo = new InMemoryDeviceRepository
      val publisher = new MockEventPublisher
      val redis = new MockRedisSync
      val service = DeviceService.Live(repo, publisher, redis)
      
      val cmd = CreateDeviceCommand(
        imei = "123456789012345",
        name = Some("Test"),
        protocol = Protocol.Teltonika,
        organizationId = 1L,
        vehicleId = Some(1L),
        sensorProfileId = None,
        phoneNumber = None
      )
      
      for
        created <- service.createDevice(cmd)
        activated <- service.activateDevice(created.id)
        events <- publisher.getEvents
      yield assertTrue(
        activated.status == DeviceStatus.Active,
        events.exists(_.isInstanceOf[DeviceActivated])
      )
    },
    
    // Тест деактивации устройства
    test("деактивация устройства") {
      val repo = new InMemoryDeviceRepository
      val publisher = new MockEventPublisher
      val redis = new MockRedisSync
      val service = DeviceService.Live(repo, publisher, redis)
      
      val cmd = CreateDeviceCommand(
        imei = "123456789012345",
        name = Some("Test"),
        protocol = Protocol.Teltonika,
        organizationId = 1L,
        vehicleId = Some(1L),
        sensorProfileId = None,
        phoneNumber = None
      )
      
      for
        created <- service.createDevice(cmd)
        _ <- service.activateDevice(created.id)
        deactivated <- service.deactivateDevice(created.id, "Тестовое отключение")
        events <- publisher.getEvents
      yield assertTrue(
        deactivated.status == DeviceStatus.Disabled,
        deactivated.disabledReason.contains("Тестовое отключение"),
        events.exists(_.isInstanceOf[DeviceDeactivated])
      )
    },
    
    // Тест уникальности IMEI
    test("ошибка при дублировании IMEI") {
      val repo = new InMemoryDeviceRepository
      val publisher = new MockEventPublisher
      val redis = new MockRedisSync
      val service = DeviceService.Live(repo, publisher, redis)
      
      val cmd = CreateDeviceCommand(
        imei = "123456789012345",
        name = Some("Test"),
        protocol = Protocol.Teltonika,
        organizationId = 1L,
        vehicleId = None,
        sensorProfileId = None,
        phoneNumber = None
      )
      
      for
        _ <- service.createDevice(cmd)
        result <- service.createDevice(cmd).exit
      yield assertTrue(
        result.isFailure,
        result.foldExit(
          cause => cause.failureOption.exists(_.isInstanceOf[ConflictError]),
          _ => false
        )
      )
    }
  )
