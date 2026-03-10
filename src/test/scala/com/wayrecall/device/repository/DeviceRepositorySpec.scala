package com.wayrecall.device.repository

import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.time.Instant

import com.wayrecall.device.domain.*

// === Дополнительные тесты DeviceRepository ===
// InMemoryDeviceRepository уже существует в DeviceServiceSpec; здесь тестируем расширенные методы

object DeviceRepositorySpec extends ZIOSpecDefault:

  // Простая InMemory реализация без привязки к существующей (чтобы не ломать зависимости)
  final case class TestDeviceRepo(
    store: Ref[Map[Long, Device]],
    seq:   Ref[Long]
  ) extends DeviceRepository:

    def create(request: CreateDeviceRequest): IO[DomainError, DeviceId] =
      for
        exists <- existsByImei(request.imei)
        _      <- ZIO.when(exists)(ZIO.fail(ConflictError.ImeiAlreadyExists(request.imei.value)))
        id     <- seq.updateAndGet(_ + 1)
        now     = Instant.now()
        device  = Device(
          id              = DeviceId(id),
          imei            = request.imei,
          name            = request.name,
          protocol        = request.protocol,
          status          = DeviceStatus.Active,
          organizationId  = request.organizationId,
          vehicleId       = request.vehicleId,
          sensorProfileId = request.sensorProfileId,
          phoneNumber     = request.phoneNumber,
          firmwareVersion = None,
          lastSeenAt      = None,
          createdAt       = now,
          updatedAt       = now
        )
        _ <- store.update(_ + (id -> device))
      yield DeviceId(id)

    def findById(id: DeviceId): IO[DomainError, Option[Device]] =
      store.get.map(_.get(id.value))

    def findByImei(imei: Imei): IO[DomainError, Option[Device]] =
      store.get.map(_.values.find(_.imei == imei))

    def update(id: DeviceId, request: UpdateDeviceRequest): IO[DomainError, Device] =
      store.modify { m =>
        m.get(id.value) match
          case Some(d) =>
            val upd = d.copy(
              name = request.name.orElse(d.name),
              protocol = request.protocol.getOrElse(d.protocol),
              phoneNumber = request.phoneNumber.orElse(d.phoneNumber),
              firmwareVersion = request.firmwareVersion.orElse(d.firmwareVersion),
              updatedAt = Instant.now()
            )
            (Right(upd), m.updated(id.value, upd))
          case None => (Left(NotFoundError.DeviceNotFound(id)), m)
      }.flatMap(ZIO.fromEither(_))

    def delete(id: DeviceId): IO[DomainError, Unit] =
      store.update(_ - id.value)

    def findByOrganization(orgId: OrganizationId): IO[DomainError, List[Device]] =
      store.get.map(_.values.filter(_.organizationId == orgId).toList)

    def findByStatus(status: DeviceStatus): IO[DomainError, List[Device]] =
      store.get.map(_.values.filter(_.status == status).toList)

    def findAllActive: IO[DomainError, List[Device]] =
      findByStatus(DeviceStatus.Active)

    def assignToVehicle(deviceId: DeviceId, vehicleId: VehicleId): IO[DomainError, Unit] =
      store.update(_.updatedWith(deviceId.value)(_.map(_.copy(vehicleId = Some(vehicleId)))))

    def unassignFromVehicle(deviceId: DeviceId): IO[DomainError, Unit] =
      store.update(_.updatedWith(deviceId.value)(_.map(_.copy(vehicleId = None))))

    def updateLastSeen(imei: Imei, timestamp: Instant): IO[DomainError, Unit] =
      store.update { m =>
        m.map { case (k, v) => if v.imei == imei then (k, v.copy(lastSeenAt = Some(timestamp))) else (k, v) }
      }

    def updateStatus(id: DeviceId, status: DeviceStatus): IO[DomainError, Unit] =
      store.update(_.updatedWith(id.value)(_.map(_.copy(status = status))))

    def existsByImei(imei: Imei): IO[DomainError, Boolean] =
      store.get.map(_.values.exists(_.imei == imei))

    def countByOrganization(orgId: OrganizationId): IO[DomainError, Int] =
      findByOrganization(orgId).map(_.length)

  object TestDeviceRepo:
    val live: ULayer[DeviceRepository] = ZLayer {
      for
        s <- Ref.make(Map.empty[Long, Device])
        q <- Ref.make(0L)
      yield TestDeviceRepo(s, q)
    }

  // Тестовый IMEI
  private val imei1 = Imei.unsafe("123456789012345")
  private val imei2 = Imei.unsafe("987654321098765")
  private val orgId = OrganizationId(1L)

  private def createReq(imei: Imei) = CreateDeviceRequest(
    imei            = imei,
    name            = Some("Тестовый трекер"),
    protocol        = Protocol.Teltonika,
    organizationId  = orgId,
    vehicleId       = None,
    sensorProfileId = None,
    phoneNumber     = None
  )

  def spec = suite("DeviceRepository тесты")(
    suite("CRUD операции")(
      test("создание и поиск по ID") {
        for
          repo  <- ZIO.service[DeviceRepository]
          id    <- repo.create(createReq(imei1))
          found <- repo.findById(id)
        yield assertTrue(
          found.isDefined,
          found.get.imei == imei1,
          found.get.status == DeviceStatus.Active
        )
      },
      test("поиск по IMEI") {
        for
          repo  <- ZIO.service[DeviceRepository]
          _     <- repo.create(createReq(imei1))
          found <- repo.findByImei(imei1)
        yield assertTrue(
          found.isDefined,
          found.get.name.contains("Тестовый трекер")
        )
      },
      test("удаление устройства") {
        for
          repo  <- ZIO.service[DeviceRepository]
          id    <- repo.create(createReq(imei1))
          _     <- repo.delete(id)
          found <- repo.findById(id)
        yield assertTrue(found.isEmpty)
      }
    ),

    suite("Статусы и фильтрация")(
      test("findByStatus возвращает устройства с нужным статусом") {
        for
          repo <- ZIO.service[DeviceRepository]
          id1  <- repo.create(createReq(imei1))
          id2  <- repo.create(createReq(imei2))
          _    <- repo.updateStatus(id2, DeviceStatus.Inactive)
          list <- repo.findByStatus(DeviceStatus.Active)
        yield assertTrue(
          list.length == 1,
          list.head.imei == imei1
        )
      },
      test("findAllActive — только активные") {
        for
          repo <- ZIO.service[DeviceRepository]
          _    <- repo.create(createReq(imei1))
          id2  <- repo.create(createReq(imei2))
          _    <- repo.updateStatus(id2, DeviceStatus.Suspended)
          list <- repo.findAllActive
        yield assertTrue(list.length == 1)
      },
      test("countByOrganization считает корректно") {
        for
          repo  <- ZIO.service[DeviceRepository]
          _     <- repo.create(createReq(imei1))
          _     <- repo.create(createReq(imei2))
          count <- repo.countByOrganization(orgId)
        yield assertTrue(count == 2)
      }
    ),

    suite("Привязка к ТС")(
      test("assignToVehicle и unassign") {
        for
          repo    <- ZIO.service[DeviceRepository]
          id      <- repo.create(createReq(imei1))
          _       <- repo.assignToVehicle(id, VehicleId(42L))
          found1  <- repo.findById(id)
          _       <- repo.unassignFromVehicle(id)
          found2  <- repo.findById(id)
        yield assertTrue(
          found1.get.vehicleId.contains(VehicleId(42L)),
          found2.get.vehicleId.isEmpty
        )
      }
    ),

    suite("lastSeen обновление")(
      test("updateLastSeen устанавливает timestamp") {
        for
          repo  <- ZIO.service[DeviceRepository]
          _     <- repo.create(createReq(imei1))
          ts     = Instant.parse("2026-06-06T12:00:00Z")
          _     <- repo.updateLastSeen(imei1, ts)
          found <- repo.findByImei(imei1)
        yield assertTrue(found.get.lastSeenAt.contains(ts))
      }
    ),

    suite("existsByImei")(
      test("true если устройство существует") {
        for
          repo   <- ZIO.service[DeviceRepository]
          _      <- repo.create(createReq(imei1))
          exists <- repo.existsByImei(imei1)
        yield assertTrue(exists)
      },
      test("false если устройство не существует") {
        for
          repo   <- ZIO.service[DeviceRepository]
          exists <- repo.existsByImei(Imei.unsafe("000000000000000"))
        yield assertTrue(!exists)
      }
    )
  ).provide(TestDeviceRepo.live)
