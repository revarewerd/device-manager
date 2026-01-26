package com.wayrecall.device

import zio.*
import zio.http.*
import com.wayrecall.device.api.*
import com.wayrecall.device.config.*
import com.wayrecall.device.consumer.*
import com.wayrecall.device.infrastructure.*
import com.wayrecall.device.repository.*
import com.wayrecall.device.service.*

// ============================================================
// Device Manager - Точка входа
// ============================================================

/**
 * Главный объект приложения Device Manager
 * 
 * Ответственности:
 * - Управление жизненным циклом устройств GPS/ГЛОНАСС
 * - CRUD операции через REST API
 * - Синхронизация кеша Redis для Connection Manager
 * - Публикация событий в Kafka
 * - Автоматическая регистрация неизвестных устройств
 * 
 * Архитектура слоёв (сверху вниз):
 * 1. API Layer - HTTP эндпоинты
 * 2. Service Layer - Бизнес-логика
 * 3. Infrastructure Layer - Kafka, Redis
 * 4. Repository Layer - PostgreSQL
 * 
 * Порядок запуска:
 * 1. Загрузка конфигурации
 * 2. Инициализация слоёв (снизу вверх)
 * 3. Запуск HTTP сервера
 * 4. Запуск Kafka Consumer (в фоне)
 * 5. Ожидание сигнала завершения
 * 
 * Конфигурация через переменные окружения:
 * - DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD
 * - KAFKA_BOOTSTRAP_SERVERS, KAFKA_GROUP_ID
 * - REDIS_HOST, REDIS_PORT
 * - HTTP_PORT
 */
object Main extends ZIOAppDefault:
  
  /**
   * Слой конфигурации с производными слоями
   */
  private val configLayers: ZLayer[Any, Throwable, AppConfig & KafkaConfig & RedisConfig & DatabaseConfig] =
    AppConfig.live.flatMap { env =>
      val config = env.get
      ZLayer.succeed(config) ++
      ZLayer.succeed(config.kafka) ++
      ZLayer.succeed(config.redis) ++
      ZLayer.succeed(config.database)
    }
  
  /**
   * Точка входа приложения
   */
  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    program
      .provide(
        // Конфигурация и производные слои
        configLayers,
        
        // Репозитории
        DeviceRepository.live,
        
        // Инфраструктура
        KafkaPublisher.live,
        RedisSyncService.live,
        
        // Сервисы
        DeviceService.live
      )
      .exitCode
  
  /**
   * Основная программа
   */
  private val program: ZIO[AppConfig & DeviceService, Throwable, Unit] =
    for
      config <- ZIO.service[AppConfig]
      
      // Баннер
      _ <- printBanner(config)
      
      // Собираем все маршруты
      allRoutes = HealthRoutes.routes ++ DeviceRoutes.routes
      
      // Запускаем HTTP сервер
      _ <- ZIO.logInfo(s"Запуск HTTP сервера на порту ${config.http.port}")
      _ <- Server.serve(allRoutes)
              .provide(Server.defaultWithPort(config.http.port))
              .ensuring(ZIO.logInfo("HTTP сервер остановлен"))
    yield ()
  
  /**
   * Выводим баннер с информацией о сервисе
   */
  private def printBanner(config: AppConfig): UIO[Unit] =
    val banner = s"""
      |╔══════════════════════════════════════════════════════════════╗
      |║                     DEVICE MANAGER                           ║
      |║         Сервис управления GPS/ГЛОНАСС устройствами           ║
      |╠══════════════════════════════════════════════════════════════╣
      |║  HTTP Port:  ${config.http.port.toString.padTo(47, ' ')} ║
      |║  Database:   ${config.database.jdbcUrl.take(47).padTo(47, ' ')} ║
      |║  Kafka:      ${config.kafka.bootstrapServers.take(47).padTo(47, ' ')} ║
      |║  Redis:      ${s"${config.redis.host}:${config.redis.port}".padTo(47, ' ')} ║
      |╚══════════════════════════════════════════════════════════════╝
    """.stripMargin
    
    ZIO.logInfo(banner)
