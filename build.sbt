/**
 * Device Manager Service
 * 
 * Сервис управления устройствами (GPS трекерами) и транспортными средствами.
 * 
 * Функциональность:
 * - CRUD операции для устройств, ТС, организаций
 * - Привязка устройств к транспортным средствам
 * - Управление профилями датчиков
 * - Публикация событий в Kafka при изменениях
 * - Синхронизация с Redis для Connection Manager
 * 
 * Технологии:
 * - Scala 3 + ZIO 2 (чисто функциональный подход)
 * - PostgreSQL + Doobie (SQL)
 * - Kafka (события)
 * - Redis (кэш для Connection Manager)
 * - ZIO HTTP (REST API)
 */

val scala3Version = "3.4.0"

// Версии библиотек (единый источник правды)
val zioVersion = "2.0.20"
val zioHttpVersion = "3.0.0-RC4"
val zioJsonVersion = "0.6.2"
val zioConfigVersion = "4.0.0"
val zioLoggingVersion = "2.2.0"
val doobieVersion = "1.0.0-RC4"
val kafkaVersion = "3.6.1"
val lettuceVersion = "6.3.2.RELEASE"
val logbackVersion = "1.4.14"
val postgresVersion = "42.7.1"
val hikariVersion = "5.1.0"
val flywayVersion = "10.4.1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "device-manager",
    version := "0.1.0",
    scalaVersion := scala3Version,
    
    // Настройки компиляции
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-language:implicitConversions",
      "-language:higherKinds"
    ),
    
    // Зависимости
    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      
      // ZIO HTTP (REST API)
      "dev.zio" %% "zio-http" % zioHttpVersion,
      
      // ZIO JSON (сериализация)
      "dev.zio" %% "zio-json" % zioJsonVersion,
      
      // ZIO Config (конфигурация)
      "dev.zio" %% "zio-config" % zioConfigVersion,
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
      "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
      
      // ZIO Logging
      "dev.zio" %% "zio-logging" % zioLoggingVersion,
      "dev.zio" %% "zio-logging-slf4j2" % zioLoggingVersion,
      
      // Doobie (PostgreSQL)
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      
      // PostgreSQL драйвер
      "org.postgresql" % "postgresql" % postgresVersion,
      
      // HikariCP (пул соединений)
      "com.zaxxer" % "HikariCP" % hikariVersion,
      
      // Flyway (миграции БД)
      "org.flywaydb" % "flyway-core" % flywayVersion,
      "org.flywaydb" % "flyway-database-postgresql" % flywayVersion,
      
      // Kafka
      "org.apache.kafka" % "kafka-clients" % kafkaVersion,
      
      // Redis (Lettuce)
      "io.lettuce" % "lettuce-core" % lettuceVersion,
      
      // Logging
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      
      // Тестирование
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
    ),
    
    // Настройки тестов
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    
    // Главный класс
    Compile / mainClass := Some("com.wayrecall.device.Main"),
    
    // Assembly настройки (для Docker)
    assembly / assemblyJarName := "device-manager.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case "application.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )
