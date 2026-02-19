package com.wayrecall.device.api

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.device.domain.*
import com.wayrecall.device.service.*

// ============================================================
// REST API для устройств
// ============================================================

/**
 * HTTP эндпоинты для управления устройствами
 * 
 * Маршруты:
 * - GET    /api/devices              - Список устройств организации
 * - GET    /api/devices/:id          - Получить устройство
 * - GET    /api/devices/imei/:imei   - Получить по IMEI
 * - POST   /api/devices              - Создать устройство
 * - PUT    /api/devices/:id          - Обновить устройство
 * - DELETE /api/devices/:id          - Удалить устройство
 * - POST   /api/devices/:id/activate   - Активировать
 * - POST   /api/devices/:id/deactivate - Деактивировать
 * - POST   /api/devices/:id/assign/:vehicleId   - Привязать к ТС
 * - DELETE /api/devices/:id/vehicle             - Отвязать от ТС
 */
object DeviceRoutes:
  
  /**
   * Все маршруты устройств
   */
  val routes: Routes[DeviceService, Response] =
    Routes(
      // GET /api/devices?organizationId=1
      Method.GET / "api" / "devices" -> handler { (request: Request) =>
        val orgIdParam = request.url.queryParams.get("organizationId")
        
        orgIdParam match
          case Some(orgIdStr) =>
            orgIdStr.toLongOption match
              case Some(orgId) =>
                DeviceService.getDevicesByOrganization(OrganizationId(orgId))
                  .map(devices => Response.json(devices.toJson))
                  .mapError(e => e: Throwable)
                  .catchAll(handleError)
              case None =>
                ZIO.succeed(badRequest("орganizationId должен быть числом"))
          case None =>
            ZIO.succeed(badRequest("Параметр organizationId обязателен"))
      },
      
      // GET /api/devices/:id
      Method.GET / "api" / "devices" / long("id") -> handler { (id: Long, _: Request) =>
        DeviceService.getDevice(DeviceId(id))
          .map(device => Response.json(device.toJson))
          .catchAll(handleError)
      },
      
      // GET /api/devices/imei/:imei
      Method.GET / "api" / "devices" / "imei" / string("imei") -> handler { (imei: String, _: Request) =>
        Imei(imei) match
          case Right(validImei) =>
            DeviceService.getDeviceByImei(validImei)
              .map(device => Response.json(device.toJson))
              .catchAll(handleError)
          case Left(err) =>
            ZIO.succeed(badRequest(err))
      },
      
      // POST /api/devices
      Method.POST / "api" / "devices" -> handler { (request: Request) =>
        (for
          body <- request.body.asString
          cmd <- ZIO.fromEither(body.fromJson[CreateDeviceRequest])
                    .mapError(e => ValidationError.EmptyField(s"JSON ошибка: $e"))
          command = CreateDeviceCommand(
            imei = cmd.imei,
            name = cmd.name,
            protocol = cmd.protocol,
            organizationId = cmd.organizationId,
            vehicleId = cmd.vehicleId,
            sensorProfileId = cmd.sensorProfileId,
            phoneNumber = cmd.phoneNumber
          )
          device <- DeviceService.createDevice(command)
        yield Response.json(device.toJson).status(Status.Created))
          .catchAll(handleError)
      },
      
      // PUT /api/devices/:id
      Method.PUT / "api" / "devices" / long("id") -> handler { (id: Long, request: Request) =>
        (for
          body <- request.body.asString
          cmd <- ZIO.fromEither(body.fromJson[UpdateDeviceRequest])
                    .mapError(e => ValidationError.EmptyField(s"JSON ошибка: $e"))
          command = UpdateDeviceCommand(
            name = cmd.name,
            protocol = cmd.protocol,
            vehicleId = cmd.vehicleId,
            sensorProfileId = cmd.sensorProfileId,
            phoneNumber = cmd.phoneNumber,
            firmwareVersion = cmd.firmwareVersion
          )
          device <- DeviceService.updateDevice(DeviceId(id), command)
        yield Response.json(device.toJson))
          .mapError(e => e: Throwable)
          .catchAll(handleError)
      },
      
      // DELETE /api/devices/:id
      Method.DELETE / "api" / "devices" / long("id") -> handler { (id: Long, request: Request) =>
        val reason = request.url.queryParams.get("reason")
        
        DeviceService.deleteDevice(DeviceId(id), reason)
          .as(Response.status(Status.NoContent))
          .mapError(e => e: Throwable)
          .catchAll(handleError)
      },
      
      // POST /api/devices/:id/activate
      Method.POST / "api" / "devices" / long("id") / "activate" -> handler { (id: Long, _: Request) =>
        DeviceService.activateDevice(DeviceId(id))
          .map(device => Response.json(device.toJson))
          .catchAll(handleError)
      },
      
      // POST /api/devices/:id/deactivate
      Method.POST / "api" / "devices" / long("id") / "deactivate" -> handler { (id: Long, request: Request) =>
        (for
          body <- request.body.asString
          req <- ZIO.fromEither(body.fromJson[DeactivateRequest])
                    .mapError(e => ValidationError.EmptyField(s"JSON ошибка: $e"))
          device <- DeviceService.deactivateDevice(DeviceId(id), req.reason)
        yield Response.json(device.toJson))
          .catchAll(handleError)
      },
      
      // POST /api/devices/:id/assign/:vehicleId
      Method.POST / "api" / "devices" / long("deviceId") / "assign" / long("vehicleId") -> 
        handler { (deviceId: Long, vehicleId: Long, _: Request) =>
          DeviceService.assignToVehicle(DeviceId(deviceId), VehicleId(vehicleId))
            .map(device => Response.json(device.toJson))
            .catchAll(handleError)
        },
      
      // DELETE /api/devices/:id/vehicle
      Method.DELETE / "api" / "devices" / long("id") / "vehicle" -> handler { (id: Long, _: Request) =>
        DeviceService.unassignFromVehicle(DeviceId(id))
          .map(device => Response.json(device.toJson))
          .catchAll(handleError)
      }
    )
  
  /**
   * Обработчик ошибок -> HTTP Response
   */
  private def handleError(error: Throwable): UIO[Response] =
    error match
      case e: ValidationError =>
        ZIO.succeed(Response.json(ErrorResponse(e.code, e.message).toJson).status(Status.BadRequest))
      
      case e: NotFoundError =>
        ZIO.succeed(Response.json(ErrorResponse(e.code, e.message).toJson).status(Status.NotFound))
      
      case e: ConflictError =>
        ZIO.succeed(Response.json(ErrorResponse(e.code, e.message).toJson).status(Status.Conflict))
      
      case e: AccessError =>
        ZIO.succeed(Response.json(ErrorResponse(e.code, e.message).toJson).status(Status.Forbidden))
      
      case e: InfrastructureError =>
        ZIO.logError(s"Ошибка инфраструктуры: ${e.message}") *>
        ZIO.succeed(Response.json(ErrorResponse(e.code, "Внутренняя ошибка сервера").toJson).status(Status.InternalServerError))
      
      case e: Throwable =>
        ZIO.logError(s"Неизвестная ошибка: ${e.getMessage}") *>
        ZIO.succeed(Response.json(ErrorResponse("UNKNOWN_ERROR", "Неизвестная ошибка").toJson).status(Status.InternalServerError))
  
  private def badRequest(message: String): Response =
    Response.json(ErrorResponse("BAD_REQUEST", message).toJson).status(Status.BadRequest)

// ============================================================
// DTO для API
// ============================================================

/**
 * Запрос на создание устройства
 */
final case class CreateDeviceRequest(
    imei: String,
    name: Option[String],
    protocol: Protocol,
    organizationId: Long,
    vehicleId: Option[Long],
    sensorProfileId: Option[Long],
    phoneNumber: Option[String]
) derives JsonCodec

/**
 * Запрос на обновление устройства
 */
final case class UpdateDeviceRequest(
    name: Option[String],
    protocol: Option[Protocol],
    vehicleId: Option[Option[Long]],
    sensorProfileId: Option[Option[Long]],
    phoneNumber: Option[String],
    firmwareVersion: Option[String]
) derives JsonCodec

/**
 * Запрос на деактивацию
 */
final case class DeactivateRequest(reason: String) derives JsonCodec

/**
 * Ответ с ошибкой
 */
final case class ErrorResponse(code: String, message: String) derives JsonCodec
