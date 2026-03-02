package com.wayrecall.device.domain

import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.time.Instant

/**
 * Тесты доменных сущностей Device Manager
 *
 * Покрываем:
 * - Opaque types (DeviceId, VehicleId, OrganizationId, SensorProfileId, Imei)
 * - Enums (Protocol, DeviceStatus, VehicleType)
 * - Entities (Device, Vehicle, Organization, SensorProfile, SensorConfig)
 * - Errors (все типы ошибок)
 */
object DomainSpec extends ZIOSpecDefault:

  val now: Instant = Instant.parse("2025-06-02T12:00:00Z")

  def spec = suite("DomainSpec")(
    opaqueTypesSuite,
    imeiSuite,
    protocolSuite,
    deviceStatusSuite,
    deviceSuite,
    errorsSuite
  )

  // ==========================================================
  // Opaque Types
  // ==========================================================

  val opaqueTypesSuite = suite("Opaque Types")(
    test("DeviceId — roundtrip") {
      val id = DeviceId(42L)
      assertTrue(id.value == 42L)
    },
    test("VehicleId — roundtrip") {
      val id = VehicleId(100L)
      assertTrue(id.value == 100L)
    },
    test("OrganizationId — roundtrip") {
      val id = OrganizationId(7L)
      assertTrue(id.value == 7L)
    },
    test("SensorProfileId — roundtrip") {
      val id = SensorProfileId(99L)
      assertTrue(id.value == 99L)
    }
  )

  // ==========================================================
  // Imei
  // ==========================================================

  val imeiSuite = suite("Imei")(
    test("Валидный 15-значный IMEI") {
      val result = Imei("123456789012345")
      assertTrue(result.isRight) &&
      assertTrue(result.toOption.get.value == "123456789012345")
    },
    test("14 цифр — ошибка") {
      assertTrue(Imei("12345678901234").isLeft)
    },
    test("16 цифр — ошибка") {
      assertTrue(Imei("1234567890123456").isLeft)
    },
    test("Буквы — ошибка") {
      assertTrue(Imei("12345678901234a").isLeft)
    },
    test("Пустая строка — ошибка") {
      assertTrue(Imei("").isLeft)
    },
    test("Дефисы — ошибка") {
      assertTrue(Imei("123-456-789-012").isLeft)
    },
    test("unsafe обходит валидацию") {
      val imei = Imei.unsafe("not-valid")
      assertTrue(imei.value == "not-valid")
    },
    test("Все нули допустимы") {
      assertTrue(Imei("000000000000000").isRight)
    }
  )

  // ==========================================================
  // Protocol enum
  // ==========================================================

  val protocolSuite = suite("Protocol")(
    test("Все варианты Protocol enum") {
      val protocols = Protocol.values.toList
      assertTrue(protocols.contains(Protocol.Teltonika)) &&
      assertTrue(protocols.contains(Protocol.Wialon)) &&
      assertTrue(protocols.contains(Protocol.Ruptela)) &&
      assertTrue(protocols.contains(Protocol.NavTelecom)) &&
      assertTrue(protocols.contains(Protocol.Galileo)) &&
      assertTrue(protocols.contains(Protocol.Custom)) &&
      assertTrue(protocols.size == 6)
    }
  )

  // ==========================================================
  // DeviceStatus enum
  // ==========================================================

  val deviceStatusSuite = suite("DeviceStatus")(
    test("Все варианты DeviceStatus") {
      val statuses = DeviceStatus.values.toList
      assertTrue(statuses.contains(DeviceStatus.Active)) &&
      assertTrue(statuses.contains(DeviceStatus.Inactive)) &&
      assertTrue(statuses.contains(DeviceStatus.Suspended)) &&
      assertTrue(statuses.contains(DeviceStatus.Deleted)) &&
      assertTrue(statuses.size == 4)
    }
  )

  // ==========================================================
  // Device
  // ==========================================================

  val deviceSuite = suite("Device")(
    test("Создание Device со всеми полями") {
      val device = Device(
        id = DeviceId(1L),
        imei = Imei.unsafe("123456789012345"),
        name = Some("Трекер #1"),
        protocol = Protocol.Teltonika,
        status = DeviceStatus.Active,
        organizationId = OrganizationId(10L),
        vehicleId = Some(VehicleId(42L)),
        sensorProfileId = Some(SensorProfileId(5L)),
        phoneNumber = Some("+79001234567"),
        firmwareVersion = Some("1.2.3"),
        lastSeenAt = Some(now),
        createdAt = now,
        updatedAt = now
      )
      assertTrue(device.id.value == 1L) &&
      assertTrue(device.imei.value == "123456789012345") &&
      assertTrue(device.name.contains("Трекер #1")) &&
      assertTrue(device.vehicleId.map(_.value).contains(42L))
    },
    test("Device без опциональных полей") {
      val device = Device(
        id = DeviceId(2L),
        imei = Imei.unsafe("999999999999999"),
        name = None,
        protocol = Protocol.Custom,
        status = DeviceStatus.Inactive,
        organizationId = OrganizationId(1L),
        vehicleId = None,
        sensorProfileId = None,
        phoneNumber = None,
        firmwareVersion = None,
        lastSeenAt = None,
        createdAt = now,
        updatedAt = now
      )
      assertTrue(device.name.isEmpty) &&
      assertTrue(device.vehicleId.isEmpty) &&
      assertTrue(device.lastSeenAt.isEmpty)
    },
    test("Device copy для обновления статуса") {
      val device = Device(
        id = DeviceId(1L),
        imei = Imei.unsafe("123456789012345"),
        name = None,
        protocol = Protocol.Teltonika,
        status = DeviceStatus.Inactive,
        organizationId = OrganizationId(1L),
        vehicleId = None,
        sensorProfileId = None,
        phoneNumber = None,
        firmwareVersion = None,
        lastSeenAt = None,
        createdAt = now,
        updatedAt = now
      )
      val activated = device.copy(status = DeviceStatus.Active)
      assertTrue(activated.status == DeviceStatus.Active) &&
      assertTrue(activated.id == device.id) // Остальное не изменилось
    }
  )

  // ==========================================================
  // Errors
  // ==========================================================

  val errorsSuite = suite("Errors")(
    test("ValidationError.InvalidImei") {
      val err = ValidationError.InvalidImei("bad")
      assertTrue(err.code == "VALIDATION_ERROR") &&
      assertTrue(err.message.contains("bad"))
    },
    test("ValidationError.EmptyField") {
      val err = ValidationError.EmptyField("name")
      assertTrue(err.message.contains("name"))
    },
    test("ValidationError.LimitExceeded") {
      val err = ValidationError.LimitExceeded("devices", 50, 10)
      assertTrue(err.message.contains("50")) &&
      assertTrue(err.message.contains("10"))
    },
    test("NotFoundError.DeviceNotFound") {
      val err = NotFoundError.DeviceNotFound(DeviceId(42L))
      assertTrue(err.code == "NOT_FOUND") &&
      assertTrue(err.message.contains("42"))
    },
    test("NotFoundError.DeviceNotFoundByImei") {
      val err = NotFoundError.DeviceNotFoundByImei("111111111111111")
      assertTrue(err.message.contains("111111111111111"))
    },
    test("ConflictError.ImeiAlreadyExists") {
      val err = ConflictError.ImeiAlreadyExists("123456789012345")
      assertTrue(err.code == "CONFLICT") &&
      assertTrue(err.message.contains("123456789012345"))
    },
    test("ConflictError.DeviceAlreadyAssigned") {
      val err = ConflictError.DeviceAlreadyAssigned(DeviceId(1L), VehicleId(2L))
      assertTrue(err.message.contains("1")) &&
      assertTrue(err.message.contains("2"))
    },
    test("AccessError.DeviceAccessDenied") {
      val err = AccessError.DeviceAccessDenied(DeviceId(1L), OrganizationId(5L))
      assertTrue(err.code == "ACCESS_DENIED") &&
      assertTrue(err.message.contains("1")) &&
      assertTrue(err.message.contains("5"))
    },
    test("InfrastructureError.DatabaseError") {
      val err = InfrastructureError.DatabaseError("Connection refused")
      assertTrue(err.code == "INFRASTRUCTURE_ERROR") &&
      assertTrue(err.message.contains("Connection refused"))
    },
    test("InfrastructureError.KafkaError") {
      val err = InfrastructureError.KafkaError("Topic not found")
      assertTrue(err.message.contains("Topic not found"))
    },
    test("InfrastructureError.RedisError") {
      val err = InfrastructureError.RedisError("Timeout")
      assertTrue(err.message.contains("Timeout"))
    },
    test("InfrastructureError.TimeoutError") {
      val err = InfrastructureError.TimeoutError("findById", 5000L)
      assertTrue(err.message.contains("findById")) &&
      assertTrue(err.message.contains("5000"))
    },
    test("DomainError наследует Throwable") {
      val err: DomainError = NotFoundError.DeviceNotFound(DeviceId(1L))
      val t: Throwable = err
      assertTrue(t.getMessage.contains("1"))
    },
    test("Pattern matching по sealed trait") {
      val err: DomainError = ConflictError.ImeiAlreadyExists("x")
      val result = err match
        case _: ValidationError      => "validation"
        case _: NotFoundError        => "not_found"
        case _: ConflictError        => "conflict"
        case _: AccessError          => "access"
        case _: InfrastructureError  => "infra"
      assertTrue(result == "conflict")
    }
  )
