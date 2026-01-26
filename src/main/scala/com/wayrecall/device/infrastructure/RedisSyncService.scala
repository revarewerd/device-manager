package com.wayrecall.device.infrastructure

import zio.*
import zio.json.*
import io.lettuce.core.{RedisClient as LettuceClient, RedisURI}
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import com.wayrecall.device.domain.*
import com.wayrecall.device.config.RedisConfig
import com.wayrecall.device.service.RedisSync
import scala.jdk.FutureConverters.*
import java.time.Duration

// ============================================================
// REDIS SYNC
// ============================================================

/**
 * Синхронизация с Redis для Connection Manager
 * 
 * Ключи в Redis:
 * - vehicle:{imei} → vehicleId (маппинг для быстрого lookup)
 * - device:{imei} → JSON с информацией об устройстве
 * 
 * Pub/Sub каналы:
 * - device-config-changed → команды для Connection Manager
 * 
 * ✅ Чисто функциональный через ZIO
 * ✅ Автоматическое управление ресурсами
 */
object RedisSyncService:
  
  /**
   * Live реализация
   */
  final case class Live(
      commands: RedisAsyncCommands[String, String],
      config: RedisConfig
  ) extends RedisSync:
    
    override def syncDevice(device: Device): IO[DomainError, Unit] =
      val imei = device.imei.value
      
      for
        // 1. Сохраняем маппинг vehicle:{imei} → vehicleId
        _ <- device.vehicleId match
          case Some(vid) =>
            setWithTtl(s"vehicle:$imei", vid.value.toString, config.deviceCacheTtl)
          case None =>
            del(s"vehicle:$imei")
        
        // 2. Сохраняем информацию об устройстве
        deviceInfo = DeviceInfo(
          imei = imei,
          vehicleId = device.vehicleId.map(_.value),
          protocol = device.protocol.toString,
          status = device.status.toString,
          organizationId = device.organizationId.value
        )
        _ <- setWithTtl(s"device:$imei", deviceInfo.toJson, config.deviceCacheTtl)
        
        _ <- ZIO.logDebug(s"Устройство $imei синхронизировано в Redis")
      yield ()
    
    override def enableDevice(imei: Imei): IO[DomainError, Unit] =
      val command = EnableDevice(imei.value)
      publishCommand(command)
        .tap(_ => ZIO.logInfo(s"Команда EnableDevice отправлена для ${imei.value}"))
    
    override def disableDevice(imei: Imei, reason: String): IO[DomainError, Unit] =
      val command = DisableDevice(imei.value, reason)
      publishCommand(command)
        .tap(_ => ZIO.logInfo(s"Команда DisableDevice отправлена для ${imei.value}: $reason"))
    
    override def updateImeiMapping(imei: Imei, vehicleId: VehicleId): IO[DomainError, Unit] =
      for
        // Обновляем маппинг
        _ <- setWithTtl(s"vehicle:${imei.value}", vehicleId.value.toString, config.deviceCacheTtl)
        
        // Отправляем команду в Connection Manager
        command = UpdateImeiMapping(imei.value, vehicleId.value)
        _ <- publishCommand(command)
        
        _ <- ZIO.logDebug(s"Маппинг ${imei.value} → ${vehicleId.value} обновлён")
      yield ()
    
    override def removeImeiMapping(imei: Imei): IO[DomainError, Unit] =
      del(s"vehicle:${imei.value}")
        .tap(_ => ZIO.logDebug(s"Маппинг ${imei.value} удалён"))
    
    /**
     * SET с TTL
     */
    private def setWithTtl(key: String, value: String, ttlSeconds: Long): IO[DomainError, Unit] =
      ZIO.fromCompletionStage(commands.setex(key, ttlSeconds, value))
        .mapError(e => InfrastructureError.RedisError(e.getMessage))
        .unit
    
    /**
     * DEL
     */
    private def del(key: String): IO[DomainError, Unit] =
      ZIO.fromCompletionStage(commands.del(key))
        .mapError(e => InfrastructureError.RedisError(e.getMessage))
        .unit
    
    /**
     * PUBLISH команды в канал device-config-changed
     */
    private def publishCommand(command: DeviceConfigCommand): IO[DomainError, Unit] =
      val json = command match
        case c: EnableDevice => c.toJson
        case c: DisableDevice => c.toJson
        case c: UpdateImeiMapping => c.toJson
      
      ZIO.fromCompletionStage(commands.publish("device-config-changed", json))
        .mapError(e => InfrastructureError.RedisError(e.getMessage))
        .unit
  
  /**
   * Информация об устройстве для кэша
   */
  private final case class DeviceInfo(
      imei: String,
      vehicleId: Option[Long],
      protocol: String,
      status: String,
      organizationId: Long
  ) derives JsonCodec
  
  /**
   * ZIO Layer
   */
  val live: ZLayer[RedisConfig, Throwable, RedisSync] =
    ZLayer.scoped {
      for
        config <- ZIO.service[RedisConfig]
        
        // Создаём клиент
        client <- ZIO.acquireRelease(
          ZIO.attempt {
            val uri = RedisURI.builder()
              .withHost(config.host)
              .withPort(config.port)
              .withDatabase(config.database)
            
            config.password.foreach(pwd => uri.withPassword(pwd.toCharArray))
            
            LettuceClient.create(uri.build())
          }.tap(_ => ZIO.logInfo(s"Redis клиент создан: ${config.host}:${config.port}"))
        )(client =>
          ZIO.attempt(client.shutdown())
            .tap(_ => ZIO.logInfo("Redis клиент закрыт"))
            .ignore
        )
        
        // Создаём соединение
        connection <- ZIO.acquireRelease(
          ZIO.attempt(client.connect())
        )(conn =>
          ZIO.attempt(conn.close()).ignore
        )
        
        commands = connection.async()
        
      yield Live(commands, config)
    }
