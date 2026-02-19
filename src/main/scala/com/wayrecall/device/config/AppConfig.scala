package com.wayrecall.device.config

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

// ============================================================
// КОНФИГУРАЦИЯ ПРИЛОЖЕНИЯ
// ============================================================

/**
 * Корневая конфигурация Device Manager
 * 
 * Загружается из application.conf через ZIO Config
 */
final case class AppConfig(
    app: ApplicationConfig,
    http: HttpConfig,
    database: DatabaseConfig,
    kafka: KafkaConfig,
    redis: RedisConfig
)

/**
 * Общие настройки приложения
 */
final case class ApplicationConfig(
    defaultOrganizationId: Long  // ID организации по умолчанию для новых устройств
)

object AppConfig:
  /**
   * ZIO Layer для загрузки конфигурации
   * 
   * Загружает из application.conf (HOCON) с поддержкой переопределения
   * через переменные окружения (${?ENV_VAR} в HOCON)
   */
  val live: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer {
      TypesafeConfigProvider.fromResourcePath().kebabCase
        .load(deriveConfig[AppConfig].nested("app"))
    }

// ============================================================
// HTTP СЕРВЕР
// ============================================================

/**
 * Настройки HTTP сервера (ZIO HTTP)
 * 
 * @param host Хост для прослушивания
 * @param port Порт HTTP API
 */
final case class HttpConfig(
    host: String,
    port: Int
)

// ============================================================
// БАЗА ДАННЫХ (PostgreSQL)
// ============================================================

/**
 * Настройки подключения к PostgreSQL
 * 
 * @param host Хост БД
 * @param port Порт БД
 * @param database Имя базы данных
 * @param username Пользователь
 * @param password Пароль
 * @param poolSize Размер пула соединений HikariCP
 * @param connectionTimeout Таймаут подключения (мс)
 */
final case class DatabaseConfig(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String,
    poolSize: Int,
    connectionTimeout: Long
):
  /**
   * JDBC URL для PostgreSQL
   */
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"

// ============================================================
// KAFKA
// ============================================================

/**
 * Настройки Kafka
 * 
 * @param bootstrapServers Адреса брокеров
 * @param groupId ID группы потребителей
 * @param unknownDevicesTopic Топик для неизвестных устройств
 * @param topics Названия топиков
 * @param producer Настройки продюсера
 */
final case class KafkaConfig(
    bootstrapServers: String,
    groupId: String,
    unknownDevicesTopic: String,
    topics: KafkaTopicsConfig,
    producer: KafkaProducerConfig
)

/**
 * Названия Kafka топиков
 */
final case class KafkaTopicsConfig(
    deviceEvents: String,     // События устройств
    vehicleEvents: String,    // События ТС
    organizationEvents: String // События организаций
)

/**
 * Настройки Kafka продюсера
 */
final case class KafkaProducerConfig(
    acks: String,           // all, 1, 0
    lingerMs: Int,          // Задержка перед отправкой
    batchSize: Int,         // Размер батча
    compressionType: String // none, gzip, snappy, lz4
)

// ============================================================
// REDIS
// ============================================================

/**
 * Настройки Redis
 * 
 * @param host Хост Redis
 * @param port Порт Redis
 * @param password Пароль (опционально)
 * @param database Номер БД (0-15)
 * @param deviceCacheTtl TTL кэша устройств в секундах
 */
final case class RedisConfig(
    host: String,
    port: Int,
    password: Option[String],
    database: Int,
    deviceCacheTtl: Long
):
  /**
   * Redis URI для подключения
   */
  def uri: String = password match
    case Some(pwd) => s"redis://:$pwd@$host:$port/$database"
    case None => s"redis://$host:$port/$database"
