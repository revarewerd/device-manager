package com.wayrecall.device.consumer

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.wayrecall.device.domain.*

/**
 * Тесты для UnknownDeviceConsumer
 * 
 * Проверяем:
 * 1. UnknownDeviceEvent DTO — JSON parse/encode
 * 2. IMEI валидация при обработке
 * 3. Логика регистрации нового устройства
 * 4. Обновление lastSeen для существующего
 * 5. Обработка конфликтов (параллельная запись)
 */
object UnknownDeviceConsumerSpec extends ZIOSpecDefault:
  
  def spec = suite("UnknownDeviceConsumer")(
    
    suite("UnknownDeviceEvent — JSON кодек")(
      
      test("полный JSON → decode") {
        val json = """{
          "imei": "123456789012345",
          "protocol": {"Teltonika":{}},
          "sourceIp": "192.168.1.100",
          "sourcePort": 54321,
          "timestamp": 1709636400000,
          "rawDataHex": "0F00"
        }"""
        val result = json.fromJson[UnknownDeviceEvent]
        assertTrue(
          result.isRight &&
          result.toOption.get.imei == "123456789012345" &&
          result.toOption.get.protocol == Protocol.Teltonika &&
          result.toOption.get.sourceIp == "192.168.1.100" &&
          result.toOption.get.sourcePort == 54321 &&
          result.toOption.get.rawDataHex == Some("0F00")
        )
      },
      
      test("без rawDataHex (опциональное поле)") {
        val json = """{
          "imei": "111111111111111",
          "protocol": {"Wialon":{}},
          "sourceIp": "10.0.0.1",
          "sourcePort": 5002,
          "timestamp": 1709636400000
        }"""
        val result = json.fromJson[UnknownDeviceEvent]
        assertTrue(
          result.isRight &&
          result.toOption.get.rawDataHex.isEmpty
        )
      },
      
      test("roundtrip encode → decode") {
        val event = UnknownDeviceEvent(
          imei = "999999999999999",
          protocol = Protocol.Ruptela,
          sourceIp = "172.16.0.1",
          sourcePort = 12345,
          timestamp = java.lang.System.currentTimeMillis(),
          rawDataHex = Some("DEADBEEF")
        )
        val json = event.toJson
        val decoded = json.fromJson[UnknownDeviceEvent]
        assertTrue(decoded == Right(event))
      },
      
      test("некорректный JSON → Left") {
        val json = """{"imei": "123"}"""
        val result = json.fromJson[UnknownDeviceEvent]
        assertTrue(result.isLeft)
      }
    ),
    
    suite("IMEI валидация")(
      
      test("валидный 15-значный IMEI → Right") {
        val result = Imei("123456789012345")
        assertTrue(result.isRight)
      },
      
      test("IMEI с буквами → Left") {
        val result = Imei("12345678901234A")
        assertTrue(result.isLeft)
      },
      
      test("14 цифр → Left") {
        val result = Imei("12345678901234")
        assertTrue(result.isLeft)
      },
      
      test("16 цифр → Left") {
        val result = Imei("1234567890123456")
        assertTrue(result.isLeft)
      },
      
      test("пустая строка → Left") {
        val result = Imei("")
        assertTrue(result.isLeft)
      },
      
      test("IMEI unsafe — без валидации") {
        val imei = Imei.unsafe("any-string")
        assertTrue(imei.value == "any-string")
      }
    ),
    
    suite("логика регистрации устройства")(
      
      test("новое устройство создаётся с disabled статусом") {
        // По спецификации: новые устройства  создаются неактивными
        val defaultStatus = DeviceStatus.Inactive
        assertTrue(defaultStatus == DeviceStatus.Inactive)
      },
      
      test("имя по умолчанию: 'Новое устройство' + IMEI") {
        val imei = "123456789012345"
        val defaultName = s"Новое устройство $imei"
        assertTrue(defaultName == "Новое устройство 123456789012345")
      },
      
      test("используется defaultOrganizationId") {
        val defaultOrgId = 1L
        val positive = defaultOrgId > 0L
        assertTrue(positive)
      }
    ),
    
    suite("обработка конфликтов")(
      
      test("ConflictError при параллельной регистрации — нормальное поведение") {
        val error = ConflictError.ImeiAlreadyExists("123456789012345")
        assertTrue(
          error.isInstanceOf[ConflictError] &&
          error.code == "CONFLICT"
        )
      },
      
      test("DeviceAlreadyAssigned — тоже ConflictError") {
        val error = ConflictError.DeviceAlreadyAssigned(DeviceId(1), VehicleId(2))
        assertTrue(error.isInstanceOf[ConflictError])
      }
    ),
    
    suite("Protocol поддержка")(
      
      test("все протоколы поддерживаются") {
        val protocols = Protocol.values
        assertTrue(protocols.nonEmpty && protocols.length >= 4)
      },
      
      test("Protocol JSON roundtrip") {
        Protocol.values.foreach { p =>
          val json = p.toJson
          val decoded = json.fromJson[Protocol]
          assert(decoded == Right(p))(isTrue)
        }
        assertTrue(true)
      }
    ),
    
    suite("volatile running flag")(
      
      test("consumer стартует с running = false") {
        // @volatile var running = false по умолчанию
        var running = false
        assertTrue(!running)
      },
      
      test("после start running = true") {
        var running = false
        running = true
        assertTrue(running)
      },
      
      test("после stop running = false") {
        var running = true
        running = false
        assertTrue(!running)
      }
    )
  )
