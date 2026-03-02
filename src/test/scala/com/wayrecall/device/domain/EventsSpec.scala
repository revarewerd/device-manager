package com.wayrecall.device.domain

import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.time.Instant

/**
 * Тесты Kafka-событий Device Manager
 *
 * Покрываем:
 * - DomainEvent sealed trait hierarchy
 * - Все типы событий (Device, Vehicle, Organization)
 * - DeviceConfigCommand (Redis Pub/Sub)
 * - eventType строки для маршрутизации
 */
object EventsSpec extends ZIOSpecDefault:

  val now: Instant = Instant.parse("2025-06-02T12:00:00Z")
  val imei: Imei = Imei.unsafe("123456789012345")

  def spec = suite("EventsSpec")(
    deviceEventsSuite,
    vehicleEventsSuite,
    organizationEventsSuite,
    configCommandsSuite
  )

  // ==========================================================
  // События устройств
  // ==========================================================

  val deviceEventsSuite = suite("Device Events")(
    test("DeviceCreated — все поля и eventType") {
      val event = DeviceCreated(
        eventId = "evt-001",
        timestamp = now,
        deviceId = DeviceId(1L),
        imei = imei,
        protocol = Protocol.Teltonika,
        organizationId = OrganizationId(10L),
        vehicleId = Some(VehicleId(42L))
      )
      assertTrue(event.eventType == "device.created") &&
      assertTrue(event.source == "device-manager") &&
      assertTrue(event.eventId == "evt-001") &&
      assertTrue(event.deviceId.value == 1L) &&
      assertTrue(event.vehicleId.map(_.value).contains(42L))
    },
    test("DeviceCreated — без vehicleId") {
      val event = DeviceCreated(
        eventId = "evt-002",
        timestamp = now,
        deviceId = DeviceId(2L),
        imei = imei,
        protocol = Protocol.Wialon,
        organizationId = OrganizationId(5L),
        vehicleId = None
      )
      assertTrue(event.vehicleId.isEmpty)
    },
    test("DeviceUpdated — с картой изменений") {
      val event = DeviceUpdated(
        eventId = "evt-003",
        timestamp = now,
        deviceId = DeviceId(1L),
        imei = imei,
        changes = Map("name" -> "Новое имя", "protocol" -> "Ruptela")
      )
      assertTrue(event.eventType == "device.updated") &&
      assertTrue(event.changes.size == 2) &&
      assertTrue(event.changes("name") == "Новое имя")
    },
    test("DeviceDeleted — с причиной") {
      val event = DeviceDeleted(
        eventId = "evt-004",
        timestamp = now,
        deviceId = DeviceId(1L),
        imei = imei,
        reason = Some("Списан")
      )
      assertTrue(event.eventType == "device.deleted") &&
      assertTrue(event.reason.contains("Списан"))
    },
    test("DeviceDeleted — без причины") {
      val event = DeviceDeleted(
        eventId = "evt-005",
        timestamp = now,
        deviceId = DeviceId(2L),
        imei = imei,
        reason = None
      )
      assertTrue(event.reason.isEmpty)
    },
    test("DeviceActivated") {
      val event = DeviceActivated(
        eventId = "evt-006",
        timestamp = now,
        deviceId = DeviceId(1L),
        imei = imei
      )
      assertTrue(event.eventType == "device.activated") &&
      assertTrue(event.source == "device-manager")
    },
    test("DeviceDeactivated — с причиной") {
      val event = DeviceDeactivated(
        eventId = "evt-007",
        timestamp = now,
        deviceId = DeviceId(1L),
        imei = imei,
        reason = "Долг за оплату"
      )
      assertTrue(event.eventType == "device.deactivated") &&
      assertTrue(event.reason == "Долг за оплату")
    },
    test("DeviceAssignedToVehicle — с предыдущим ТС") {
      val event = DeviceAssignedToVehicle(
        eventId = "evt-008",
        timestamp = now,
        deviceId = DeviceId(1L),
        imei = imei,
        vehicleId = VehicleId(100L),
        previousVehicleId = Some(VehicleId(50L))
      )
      assertTrue(event.eventType == "device.assigned-to-vehicle") &&
      assertTrue(event.vehicleId.value == 100L) &&
      assertTrue(event.previousVehicleId.map(_.value).contains(50L))
    },
    test("DeviceAssignedToVehicle — первая привязка") {
      val event = DeviceAssignedToVehicle(
        eventId = "evt-009",
        timestamp = now,
        deviceId = DeviceId(1L),
        imei = imei,
        vehicleId = VehicleId(100L),
        previousVehicleId = None
      )
      assertTrue(event.previousVehicleId.isEmpty)
    },
    test("DeviceUnassignedFromVehicle") {
      val event = DeviceUnassignedFromVehicle(
        eventId = "evt-010",
        timestamp = now,
        deviceId = DeviceId(1L),
        imei = imei,
        vehicleId = VehicleId(42L)
      )
      assertTrue(event.eventType == "device.unassigned-from-vehicle") &&
      assertTrue(event.vehicleId.value == 42L)
    }
  )

  // ==========================================================
  // События ТС
  // ==========================================================

  val vehicleEventsSuite = suite("Vehicle Events")(
    test("VehicleCreated") {
      val event = VehicleCreated(
        eventId = "evt-v01",
        timestamp = now,
        vehicleId = VehicleId(1L),
        organizationId = OrganizationId(10L),
        name = "Камаз 5490",
        vehicleType = VehicleType.Truck
      )
      assertTrue(event.eventType == "vehicle.created") &&
      assertTrue(event.name == "Камаз 5490") &&
      assertTrue(event.vehicleType == VehicleType.Truck)
    },
    test("VehicleUpdated — пустая карта изменений") {
      val event = VehicleUpdated(
        eventId = "evt-v02",
        timestamp = now,
        vehicleId = VehicleId(1L),
        changes = Map.empty
      )
      assertTrue(event.eventType == "vehicle.updated") &&
      assertTrue(event.changes.isEmpty)
    },
    test("VehicleDeleted") {
      val event = VehicleDeleted(
        eventId = "evt-v03",
        timestamp = now,
        vehicleId = VehicleId(1L),
        reason = Some("Утилизация")
      )
      assertTrue(event.eventType == "vehicle.deleted") &&
      assertTrue(event.reason.contains("Утилизация"))
    }
  )

  // ==========================================================
  // События организаций
  // ==========================================================

  val organizationEventsSuite = suite("Organization Events")(
    test("OrganizationCreated") {
      val event = OrganizationCreated(
        eventId = "evt-o01",
        timestamp = now,
        organizationId = OrganizationId(1L),
        name = "ОАО Логистика",
        email = "admin@logistics.ru"
      )
      assertTrue(event.eventType == "organization.created") &&
      assertTrue(event.name == "ОАО Логистика") &&
      assertTrue(event.email == "admin@logistics.ru")
    },
    test("OrganizationUpdated") {
      val event = OrganizationUpdated(
        eventId = "evt-o02",
        timestamp = now,
        organizationId = OrganizationId(1L),
        changes = Map("name" -> "Новое название")
      )
      assertTrue(event.eventType == "organization.updated") &&
      assertTrue(event.changes.size == 1)
    },
    test("OrganizationDeactivated") {
      val event = OrganizationDeactivated(
        eventId = "evt-o03",
        timestamp = now,
        organizationId = OrganizationId(1L),
        reason = "Неоплата"
      )
      assertTrue(event.eventType == "organization.deactivated") &&
      assertTrue(event.reason == "Неоплата")
    }
  )

  // ==========================================================
  // DeviceConfigCommand (Redis Pub/Sub)
  // ==========================================================

  val configCommandsSuite = suite("DeviceConfigCommand")(
    test("EnableDevice") {
      val cmd = EnableDevice(imei = "123456789012345")
      assertTrue(cmd.imei == "123456789012345")
    },
    test("DisableDevice — с причиной") {
      val cmd = DisableDevice(imei = "123456789012345", reason = "Suspended by admin")
      assertTrue(cmd.imei == "123456789012345") &&
      assertTrue(cmd.reason == "Suspended by admin")
    },
    test("UpdateImeiMapping") {
      val cmd = UpdateImeiMapping(imei = "123456789012345", vehicleId = 42L)
      assertTrue(cmd.vehicleId == 42L)
    }
  )
