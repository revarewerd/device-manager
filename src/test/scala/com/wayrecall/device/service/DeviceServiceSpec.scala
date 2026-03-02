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
 * - InMemoryDeviceRepository (Ref-based, потокобезопасный)
 * - MockEventPublisher (собирает события)
 * - MockRedisSync (no-op)
 */
object DeviceServiceSpec extends ZIOSpecDefault:
  
  // ----------------------------------------------------------
  // Mock реализации
  // ----------------------------------------------------------
  
  /**
   * In-memory репозиторий для тестов
   *
   * Полностью реализует DeviceRepository trait,
   * хранит данные в потокобезопасном Ref.
   */
  class InMemoryDeviceRepository(
      devicesRef: Ref[Map[DeviceId, Device]],
      counterRef: Ref[Long]
  ) extends DeviceRepository:
    
    override def create(request: CreateDeviceRequest): IO[DomainError, DeviceId] =
      for
        now     <- Clock.instant
        devices <- devicesRef.get
        // Проверка уникальности IMEI
        _       <- ZIO.when(devices.values.exists(_.imei == request.imei))(
                      ZIO.fail(ConflictError.ImeiAlreadyExists(request.imei.value))
                    )
        id      <- counterRef.updateAndGet(_ + 1).map(DeviceId.apply)
        device   = Device(
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
                     createdAt = now,
                     updatedAt = now
                   )
        _       <- devicesRef.update(_ + (id -> device))
      yield id
    
    override def findById(id: DeviceId): IO[DomainError, Option[Device]] =
      devicesRef.get.map(_.get(id))
    
    override def findByImei(imei: Imei): IO[DomainError, Option[Device]] =
      devicesRef.get.map(_.values.find(_.imei == imei))
    
    override def update(id: DeviceId, request: UpdateDeviceRequest): IO[DomainError, Device] =
      for
        now      <- Clock.instant
        devices  <- devicesRef.get
        existing <- ZIO.fromOption(devices.get(id))
                       .orElseFail(NotFoundError.DeviceNotFound(id))
        updated   = existing.copy(
                      name = request.name.orElse(existing.name),
                      protocol = request.protocol.getOrElse(existing.protocol),
                      status = request.status.getOrElse(existing.status),
                      vehicleId = request.vehicleId.getOrElse(existing.vehicleId),
                      sensorProfileId = request.sensorProfileId.getOrElse(existing.sensorProfileId),
                      phoneNumber = request.phoneNumber.orElse(existing.phoneNumber),
                      firmwareVersion = request.firmwareVersion.orElse(existing.firmwareVersion),
                      updatedAt = now
                    )
        _        <- devicesRef.update(_ + (id -> updated))
      yield updated
    
    override def delete(id: DeviceId): IO[DomainError, Unit] =
      devicesRef.update(_ - id)
    
    override def findByOrganization(orgId: OrganizationId): IO[DomainError, List[Device]] =
      devicesRef.get.map(_.values.filter(_.organizationId == orgId).toList)
    
    override def findByStatus(status: DeviceStatus): IO[DomainError, List[Device]] =
      devicesRef.get.map(_.values.filter(_.status == status).toList)
    
    override def findAllActive: IO[DomainError, List[Device]] =
      devicesRef.get.map(_.values.filter(_.status == DeviceStatus.Active).toList)
    
    override def assignToVehicle(deviceId: DeviceId, vehicleId: VehicleId): IO[DomainError, Unit] =
      devicesRef.update { devices =>
        devices.get(deviceId) match
          case Some(d) => devices + (deviceId -> d.copy(vehicleId = Some(vehicleId)))
          case None    => devices
      }
    
    override def unassignFromVehicle(deviceId: DeviceId): IO[DomainError, Unit] =
      devicesRef.update { devices =>
        devices.get(deviceId) match
          case Some(d) => devices + (deviceId -> d.copy(vehicleId = None))
          case None    => devices
      }
    
    override def updateLastSeen(imei: Imei, timestamp: Instant): IO[DomainError, Unit] =
      devicesRef.update { devices =>
        devices.values.find(_.imei == imei) match
          case Some(d) => devices + (d.id -> d.copy(lastSeenAt = Some(timestamp)))
          case None    => devices
      }
    
    override def updateStatus(id: DeviceId, status: DeviceStatus): IO[DomainError, Unit] =
      devicesRef.update { devices =>
        devices.get(id) match
          case Some(d) => devices + (id -> d.copy(status = status))
          case None    => devices
      }
    
    override def existsByImei(imei: Imei): IO[DomainError, Boolean] =
      devicesRef.get.map(_.values.exists(_.imei == imei))
    
    override def countByOrganization(orgId: OrganizationId): IO[DomainError, Int] =
      devicesRef.get.map(_.values.count(_.organizationId == orgId))
  
  object InMemoryDeviceRepository:
    def make: UIO[InMemoryDeviceRepository] =
      for
        devRef  <- Ref.make(Map.empty[DeviceId, Device])
        cntRef  <- Ref.make(0L)
      yield InMemoryDeviceRepository(devRef, cntRef)
  
  /**
   * Mock EventPublisher — собирает все опубликованные события
   */
  class MockEventPublisher(eventsRef: Ref[List[DomainEvent]]) extends EventPublisher:
    override def publish(event: DomainEvent): IO[DomainError, Unit] =
      eventsRef.update(_ :+ event)
    
    def getEvents: UIO[List[DomainEvent]] = eventsRef.get
  
  object MockEventPublisher:
    def make: UIO[MockEventPublisher] =
      Ref.make(List.empty[DomainEvent]).map(MockEventPublisher(_))
  
  /**
   * Mock RedisSync — no-op
   */
  class MockRedisSync extends RedisSync:
    override def syncDevice(device: Device): IO[DomainError, Unit] = ZIO.unit
    override def enableDevice(imei: Imei): IO[DomainError, Unit] = ZIO.unit
    override def disableDevice(imei: Imei, reason: String): IO[DomainError, Unit] = ZIO.unit
    override def updateImeiMapping(imei: Imei, vehicleId: VehicleId): IO[DomainError, Unit] = ZIO.unit
    override def removeImeiMapping(imei: Imei): IO[DomainError, Unit] = ZIO.unit
  
  /**
   * Фабрика: создаёт сервис с мок-зависимостями
   */
  private def makeService: UIO[(DeviceService.Live, MockEventPublisher)] =
    for
      repo      <- InMemoryDeviceRepository.make
      publisher <- MockEventPublisher.make
      redis      = new MockRedisSync
    yield (DeviceService.Live(repo, publisher, redis), publisher)
  
  /**
   * Команда создания устройства по умолчанию
   */
  private def defaultCmd(
      imei: String = "123456789012345",
      name: Option[String] = Some("Тестовое устройство"),
      protocol: Protocol = Protocol.Teltonika,
      orgId: Long = 1L,
      vehicleId: Option[Long] = None
  ): CreateDeviceCommand =
    CreateDeviceCommand(imei, name, protocol, orgId, vehicleId, None, None)
  
  // ----------------------------------------------------------
  // Тесты
  // ----------------------------------------------------------
  
  def spec = suite("DeviceServiceSpec")(
    createSuite,
    getSuite,
    activateSuite,
    deactivateSuite,
    assignSuite,
    deleteSuite,
    conflictSuite
  )
  
  // ==========================================================
  // Создание устройства
  // ==========================================================
  
  val createSuite = suite("createDevice")(
    test("Создание устройства с валидным IMEI") {
      for
        (service, publisher) <- makeService
        device               <- service.createDevice(defaultCmd())
        events               <- publisher.getEvents
      yield assertTrue(device.imei.value == "123456789012345") &&
            assertTrue(device.name.contains("Тестовое устройство")) &&
            assertTrue(device.protocol == Protocol.Teltonika) &&
            assertTrue(device.status == DeviceStatus.Inactive) &&
            assertTrue(events.size == 1) &&
            assertTrue(events.head.isInstanceOf[DeviceCreated])
    },
    test("Невалидный IMEI — ValidationError") {
      for
        (service, _) <- makeService
        result       <- service.createDevice(defaultCmd(imei = "12345")).exit
      yield assertTrue(result.isFailure) &&
            assertTrue(
              result.foldExit(
                cause => cause.failureOption.exists(_.isInstanceOf[ValidationError]),
                _ => false
              )
            )
    },
    test("IMEI с буквами — ValidationError") {
      for
        (service, _) <- makeService
        result       <- service.createDevice(defaultCmd(imei = "12345678901234a")).exit
      yield assertTrue(result.isFailure)
    },
    test("Пустое имя допустимо") {
      for
        (service, _) <- makeService
        device       <- service.createDevice(defaultCmd(name = None))
      yield assertTrue(device.name.isEmpty)
    }
  )
  
  // ==========================================================
  // Получение устройства
  // ==========================================================
  
  val getSuite = suite("getDevice")(
    test("Получение по ID") {
      for
        (service, _) <- makeService
        created      <- service.createDevice(defaultCmd())
        fetched      <- service.getDevice(created.id)
      yield assertTrue(fetched.id == created.id) &&
            assertTrue(fetched.imei == created.imei)
    },
    test("Несуществующий ID — NotFoundError") {
      for
        (service, _) <- makeService
        result       <- service.getDevice(DeviceId(999L)).exit
      yield assertTrue(result.isFailure) &&
            assertTrue(
              result.foldExit(
                cause => cause.failureOption.exists(_.isInstanceOf[NotFoundError]),
                _ => false
              )
            )
    },
    test("getDeviceByImei — найдено") {
      for
        (service, _) <- makeService
        _            <- service.createDevice(defaultCmd())
        imei         <- ZIO.fromEither(Imei("123456789012345"))
                           .mapError(e => new RuntimeException(e))
        device       <- service.getDeviceByImei(imei)
      yield assertTrue(device.imei.value == "123456789012345")
    },
    test("getDevicesByOrganization") {
      for
        (service, _) <- makeService
        _            <- service.createDevice(defaultCmd(imei = "111111111111111"))
        _            <- service.createDevice(defaultCmd(imei = "222222222222222"))
        _            <- service.createDevice(defaultCmd(imei = "333333333333333", orgId = 2L))
        devices      <- service.getDevicesByOrganization(OrganizationId(1L))
      yield assertTrue(devices.size == 2)
    }
  )
  
  // ==========================================================
  // Активация
  // ==========================================================
  
  val activateSuite = suite("activateDevice")(
    test("Активация переводит в Active") {
      for
        (service, publisher) <- makeService
        created              <- service.createDevice(defaultCmd(vehicleId = Some(1L)))
        activated            <- service.activateDevice(created.id)
        events               <- publisher.getEvents
      yield assertTrue(activated.status == DeviceStatus.Active) &&
            assertTrue(events.exists(_.isInstanceOf[DeviceActivated]))
    },
    test("Активация несуществующего — NotFoundError") {
      for
        (service, _) <- makeService
        result       <- service.activateDevice(DeviceId(999L)).exit
      yield assertTrue(result.isFailure)
    }
  )
  
  // ==========================================================
  // Деактивация
  // ==========================================================
  
  val deactivateSuite = suite("deactivateDevice")(
    test("Деактивация переводит в Inactive") {
      for
        (service, publisher) <- makeService
        created              <- service.createDevice(defaultCmd(vehicleId = Some(1L)))
        _                    <- service.activateDevice(created.id)
        deactivated          <- service.deactivateDevice(created.id, "Тестовое отключение")
        events               <- publisher.getEvents
      yield assertTrue(deactivated.status == DeviceStatus.Inactive) &&
            assertTrue(events.exists(_.isInstanceOf[DeviceDeactivated]))
    },
    test("Деактивация публикует DeviceDeactivated с причиной") {
      for
        (service, publisher) <- makeService
        created              <- service.createDevice(defaultCmd())
        _                    <- service.deactivateDevice(created.id, "Неоплата")
        events               <- publisher.getEvents
        deactEvent            = events.collect { case e: DeviceDeactivated => e }
      yield assertTrue(deactEvent.size == 1) &&
            assertTrue(deactEvent.head.reason == "Неоплата")
    }
  )
  
  // ==========================================================
  // Привязка к ТС
  // ==========================================================
  
  val assignSuite = suite("assignToVehicle / unassign")(
    test("Привязка устройства к ТС") {
      for
        (service, publisher) <- makeService
        created              <- service.createDevice(defaultCmd())
        assigned             <- service.assignToVehicle(created.id, VehicleId(42L))
        events               <- publisher.getEvents
      yield assertTrue(assigned.vehicleId.map(_.value).contains(42L)) &&
            assertTrue(events.exists(_.isInstanceOf[DeviceAssignedToVehicle]))
    },
    test("Отвязка от ТС") {
      for
        (service, publisher) <- makeService
        created              <- service.createDevice(defaultCmd())
        _                    <- service.assignToVehicle(created.id, VehicleId(42L))
        unassigned           <- service.unassignFromVehicle(created.id)
        events               <- publisher.getEvents
      yield assertTrue(unassigned.vehicleId.isEmpty) &&
            assertTrue(events.exists(_.isInstanceOf[DeviceUnassignedFromVehicle]))
    },
    test("Повторная привязка — ConflictError") {
      for
        (service, _) <- makeService
        created      <- service.createDevice(defaultCmd())
        _            <- service.assignToVehicle(created.id, VehicleId(42L))
        result       <- service.assignToVehicle(created.id, VehicleId(99L)).exit
      yield assertTrue(result.isFailure)
    }
  )
  
  // ==========================================================
  // Удаление
  // ==========================================================
  
  val deleteSuite = suite("deleteDevice")(
    test("Soft delete публикует DeviceDeleted") {
      for
        (service, publisher) <- makeService
        created              <- service.createDevice(defaultCmd())
        _                    <- service.deleteDevice(created.id, Some("Списано"))
        events               <- publisher.getEvents
      yield assertTrue(events.exists(_.isInstanceOf[DeviceDeleted]))
    },
    test("После удаления getDevice — NotFoundError") {
      for
        (service, _) <- makeService
        created      <- service.createDevice(defaultCmd())
        _            <- service.deleteDevice(created.id, None)
        result       <- service.getDevice(created.id).exit
      yield assertTrue(result.isFailure)
    }
  )
  
  // ==========================================================
  // Конфликты IMEI
  // ==========================================================
  
  val conflictSuite = suite("IMEI уникальность")(
    test("Дублирование IMEI — ConflictError") {
      for
        (service, _) <- makeService
        _            <- service.createDevice(defaultCmd())
        result       <- service.createDevice(defaultCmd()).exit
      yield assertTrue(result.isFailure) &&
            assertTrue(
              result.foldExit(
                cause => cause.failureOption.exists(_.isInstanceOf[ConflictError]),
                _ => false
              )
            )
    },
    test("Разные IMEI — оба создаются") {
      for
        (service, _) <- makeService
        d1           <- service.createDevice(defaultCmd(imei = "111111111111111"))
        d2           <- service.createDevice(defaultCmd(imei = "222222222222222"))
      yield assertTrue(d1.id != d2.id)
    }
  )
