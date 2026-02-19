package com.wayrecall.device.consumer

import zio.*
import zio.json.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties
import scala.jdk.CollectionConverters.*
import com.wayrecall.device.domain.*
import com.wayrecall.device.service.{DeviceService, CreateDeviceCommand, UpdateDeviceCommand}
import com.wayrecall.device.config.AppConfig

// ============================================================
// Kafka Consumer для неизвестных устройств
// ============================================================

/**
 * Обработчик событий от Connection Manager
 * 
 * Подписывается на топик 'unknown-devices' и автоматически
 * регистрирует новые устройства при первом подключении.
 * 
 * Поток данных:
 * 1. Connection Manager получает пакет от неизвестного IMEI
 * 2. Публикует событие UnknownDeviceConnected
 * 3. Device Manager получает событие
 * 4. Проверяет, не зарегистрировано ли устройство
 * 5. Если нет - создаёт запись (disabled по умолчанию)
 * 6. Отправляет уведомление администратору
 */
trait UnknownDeviceConsumer:
  /** Запустить процесс потребления событий */
  def start: Task[Unit]
  
  /** Остановить консьюмер */
  def stop: UIO[Unit]

object UnknownDeviceConsumer:
  
  /**
   * ZIO Layer для UnknownDeviceConsumer
   */
  val live: ZLayer[AppConfig & DeviceService, Throwable, UnknownDeviceConsumer] =
    ZLayer.scoped {
      for
        config <- ZIO.service[AppConfig]
        deviceService <- ZIO.service[DeviceService]
        consumer <- createConsumer(config.kafka)
        impl = UnknownDeviceConsumerLive(
          consumer = consumer,
          deviceService = deviceService,
          topic = config.kafka.unknownDevicesTopic,
          defaultOrganizationId = config.app.defaultOrganizationId
        )
        // Регистрируем shutdown hook
        _ <- ZIO.addFinalizer(impl.stop *> ZIO.logInfo("Unknown device consumer остановлен"))
      yield impl
    }
  
  /**
   * Создание Kafka Consumer
   */
  private def createConsumer(config: com.wayrecall.device.config.KafkaConfig): Task[KafkaConsumer[String, String]] =
    ZIO.attempt {
      val props = new Properties()
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
      props.put(ConsumerConfig.GROUP_ID_CONFIG, config.groupId)
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
      props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000")
      // Более консервативные настройки для надёжности
      props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100")
      props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000")
      
      new KafkaConsumer[String, String](props)
    }

// ============================================================
// Реализация
// ============================================================

private final class UnknownDeviceConsumerLive(
    consumer: KafkaConsumer[String, String],
    deviceService: DeviceService,
    topic: String,
    defaultOrganizationId: Long
) extends UnknownDeviceConsumer:
  
  @volatile private var running = false
  
  /**
   * Запуск процесса потребления
   */
  override def start: Task[Unit] =
    ZIO.logInfo(s"Запуск Unknown Device Consumer на топике: $topic") *>
    ZIO.attempt {
      consumer.subscribe(java.util.Arrays.asList(topic))
      running = true
    } *>
    consumeLoop
      .forever
      .interruptible
      .catchAllCause { cause =>
        if cause.isInterrupted then
          ZIO.logInfo("Consumer прерван по запросу")
        else
          ZIO.logErrorCause("Ошибка в consumer loop", cause)
      }
  
  /**
   * Остановка консьюмера
   */
  override def stop: UIO[Unit] =
    ZIO.logInfo("Останавливаем Unknown Device Consumer") *>
    ZIO.succeed { running = false } *>
    ZIO.attempt(consumer.wakeup()).ignore *>
    ZIO.attempt(consumer.close()).ignore
  
  /**
   * Основной цикл потребления
   */
  private def consumeLoop: Task[Unit] =
    ZIO.attempt {
      consumer.poll(Duration.ofMillis(1000))
    }.flatMap { records =>
      if records.isEmpty then
        ZIO.unit
      else
        ZIO.foreachDiscard(records.asScala)(processRecord)
    }.when(running).unit
  
  /**
   * Обработка одной записи
   */
  private def processRecord(record: ConsumerRecord[String, String]): Task[Unit] =
    val eventJson = record.value()
    
    ZIO.logDebug(s"Получено событие: $eventJson") *>
    (for
      // Парсим событие
      event <- ZIO.fromEither(eventJson.fromJson[UnknownDeviceEvent])
                  .mapError(e => new RuntimeException(s"Ошибка парсинга JSON: $e"))
      
      // Логируем
      _ <- ZIO.logInfo(s"Неизвестное устройство: IMEI=${event.imei}, IP=${event.sourceIp}, протокол=${event.protocol}")
      
      // Проверяем IMEI
      validImei <- ZIO.fromEither(Imei(event.imei))
                      .mapError(e => new RuntimeException(s"Невалидный IMEI: $e"))
      
      // Пытаемся найти существующее устройство
      existingDevice <- deviceService.findByImei(validImei).either
      
      _ <- existingDevice match
        case Right(Some(device)) =>
          // Устройство уже зарегистрировано, обновляем lastSeenAt
          ZIO.logDebug(s"Устройство уже зарегистрировано: ${device.imei.value}") *>
          updateLastSeen(device)
        
        case Right(None) | Left(_) =>
          // Регистрируем новое устройство (неактивное)
          registerNewDevice(validImei, event)
    yield ())
      .catchAll { error =>
        ZIO.logError(s"Ошибка обработки события: ${error.getMessage}")
      }
  
  /**
   * Регистрация нового устройства
   */
  private def registerNewDevice(imei: Imei, event: UnknownDeviceEvent): Task[Unit] =
    val command = CreateDeviceCommand(
      imei = imei.value,
      name = Some(s"Новое устройство ${imei.value}"),
      protocol = event.protocol,
      organizationId = defaultOrganizationId,
      vehicleId = None,
      sensorProfileId = None,
      phoneNumber = None
    )
    
    ZIO.logInfo(s"Регистрируем новое устройство: ${imei.value}") *>
    deviceService.createDevice(command)
      .tap(device => ZIO.logInfo(s"Устройство зарегистрировано с ID: ${device.id}"))
      .tap(_ => notifyAdmin(imei, event))
      .unit
      .catchAll {
        case _: ConflictError =>
          // Устройство уже создано другим потоком - это нормально
          ZIO.logDebug(s"Устройство ${imei.value} уже было создано")
        case error =>
          ZIO.logError(s"Не удалось создать устройство: ${error.getMessage}")
      }
  
  /**
   * Обновление времени последнего подключения
   */
  private def updateLastSeen(device: Device): Task[Unit] =
    // TODO: Добавить метод updateLastSeen в DeviceService
    ZIO.logDebug(s"Обновляем lastSeenAt для устройства ${device.id}")
  
  /**
   * Уведомление администратора о новом устройстве
   */
  private def notifyAdmin(imei: Imei, event: UnknownDeviceEvent): Task[Unit] =
    // TODO: Интеграция с системой уведомлений
    ZIO.logInfo(s"[NOTIFICATION] Новое устройство обнаружено: IMEI=${imei.value}, IP=${event.sourceIp}")

// ============================================================
// DTO для событий
// ============================================================

/**
 * Событие о подключении неизвестного устройства
 * Отправляется Connection Manager при получении пакета
 * от незарегистрированного IMEI
 */
final case class UnknownDeviceEvent(
    imei: String,
    protocol: Protocol,
    sourceIp: String,
    sourcePort: Int,
    timestamp: Long,
    rawDataHex: Option[String] = None
) derives JsonCodec
