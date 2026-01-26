package com.wayrecall.device.infrastructure

import zio.*
import zio.json.*
import org.apache.kafka.clients.producer.{KafkaProducer as JKafkaProducer, ProducerConfig, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import com.wayrecall.device.domain.*
import com.wayrecall.device.config.KafkaConfig
import com.wayrecall.device.service.EventPublisher
import java.util.Properties
import scala.jdk.CollectionConverters.*

// ============================================================
// KAFKA PUBLISHER
// ============================================================

/**
 * Публикатор событий в Kafka
 * 
 * ✅ Чисто функциональный через ZIO
 * ✅ Автоматическое управление ресурсами (acquireRelease)
 * ✅ Асинхронная отправка через ZIO.async
 */
object KafkaPublisher:
  
  /**
   * Live реализация
   */
  final case class Live(
      producer: JKafkaProducer[String, String],
      config: KafkaConfig
  ) extends EventPublisher:
    
    override def publish(event: DomainEvent): IO[DomainError, Unit] =
      val topic = event match
        case _: DeviceCreated | _: DeviceUpdated | _: DeviceDeleted | 
             _: DeviceActivated | _: DeviceDeactivated |
             _: DeviceAssignedToVehicle | _: DeviceUnassignedFromVehicle =>
          config.topics.deviceEvents
        
        case _: VehicleCreated | _: VehicleUpdated | _: VehicleDeleted =>
          config.topics.vehicleEvents
        
        case _: OrganizationCreated | _: OrganizationUpdated | _: OrganizationDeactivated =>
          config.topics.organizationEvents
      
      // Ключ партиционирования - по типу события извлекаем ID
      val key = extractKey(event)
      
      // Сериализуем в JSON
      val value = event.toJson
      
      sendToKafka(topic, key, value)
        .tap(_ => ZIO.logDebug(s"Событие ${event.eventType} опубликовано в топик $topic"))
        .mapError(e => InfrastructureError.KafkaError(e.getMessage))
    
    /**
     * Извлекает ключ для партиционирования
     */
    private def extractKey(event: DomainEvent): String =
      event match
        case e: DeviceCreated => e.imei.value
        case e: DeviceUpdated => e.imei.value
        case e: DeviceDeleted => e.imei.value
        case e: DeviceActivated => e.imei.value
        case e: DeviceDeactivated => e.imei.value
        case e: DeviceAssignedToVehicle => e.imei.value
        case e: DeviceUnassignedFromVehicle => e.imei.value
        case e: VehicleCreated => e.vehicleId.value.toString
        case e: VehicleUpdated => e.vehicleId.value.toString
        case e: VehicleDeleted => e.vehicleId.value.toString
        case e: OrganizationCreated => e.organizationId.value.toString
        case e: OrganizationUpdated => e.organizationId.value.toString
        case e: OrganizationDeactivated => e.organizationId.value.toString
    
    /**
     * Асинхронная отправка в Kafka через callback
     */
    private def sendToKafka(topic: String, key: String, value: String): Task[RecordMetadata] =
      ZIO.async[Any, Throwable, RecordMetadata] { callback =>
        val record = new ProducerRecord[String, String](topic, key, value)
        
        producer.send(record, (metadata: RecordMetadata, exception: Exception) => {
          if exception != null then
            callback(ZIO.fail(exception))
          else
            callback(ZIO.succeed(metadata))
        })
      }
  
  /**
   * Создаёт Kafka producer с настройками
   */
  private def createProducer(config: KafkaConfig): JKafkaProducer[String, String] =
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.ACKS_CONFIG, config.producer.acks)
    props.put(ProducerConfig.LINGER_MS_CONFIG, config.producer.lingerMs.toString)
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, config.producer.batchSize.toString)
    props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, config.producer.compressionType)
    
    // Дополнительные настройки надёжности
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
    props.put(ProducerConfig.RETRIES_CONFIG, "3")
    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
    
    new JKafkaProducer[String, String](props)
  
  /**
   * ZIO Layer с автоматическим управлением ресурсами
   */
  val live: ZLayer[KafkaConfig, Throwable, EventPublisher] =
    ZLayer.scoped {
      for
        config <- ZIO.service[KafkaConfig]
        
        // Создаём producer с гарантией закрытия
        producer <- ZIO.acquireRelease(
          ZIO.attempt(createProducer(config))
            .tap(_ => ZIO.logInfo(s"Kafka producer создан: ${config.bootstrapServers}"))
        )(producer =>
          ZIO.attempt(producer.close())
            .tap(_ => ZIO.logInfo("Kafka producer закрыт"))
            .ignore
        )
      yield Live(producer, config)
    }
