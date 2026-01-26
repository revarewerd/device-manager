package com.wayrecall.device.api

import zio.*
import zio.http.*
import zio.json.*

// ============================================================
// Маршруты проверки здоровья сервиса
// ============================================================

/**
 * Health check эндпоинты
 * 
 * Стандартные эндпоинты:
 * - GET /health     - Простая проверка (alive)
 * - GET /health/ready - Готовность к работе (все зависимости доступны)
 */
object HealthRoutes:
  
  /**
   * Маршруты
   */
  val routes: Routes[Any, Nothing] =
    Routes(
      // GET /health - liveness probe
      Method.GET / "health" -> handler {
        Response.json(HealthResponse("healthy", "UP").toJson)
      },
      
      // GET /health/ready - readiness probe
      Method.GET / "health" / "ready" -> handler {
        // TODO: Добавить реальные проверки зависимостей
        Response.json(ReadinessResponse(
          status = "ready",
          checks = List(
            ComponentCheck("database", "UP"),
            ComponentCheck("kafka", "UP"),
            ComponentCheck("redis", "UP")
          )
        ).toJson)
      }
    )

// ============================================================
// DTO для Health API
// ============================================================

final case class HealthResponse(status: String, uptime: String) derives JsonCodec

final case class ComponentCheck(name: String, status: String) derives JsonCodec

final case class ReadinessResponse(
    status: String,
    checks: List[ComponentCheck]
) derives JsonCodec
