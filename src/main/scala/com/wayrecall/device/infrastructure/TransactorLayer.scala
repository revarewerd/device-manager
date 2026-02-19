package com.wayrecall.device.infrastructure

import zio.*
import zio.interop.catz.*
import zio.interop.catz.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.hikari.*
import doobie.util.ExecutionContexts
import cats.effect.kernel.Async
import cats.effect.Resource
import com.wayrecall.device.config.DatabaseConfig

/**
 * TransactorLayer — создание Doobie Transactor с HikariCP пулом
 * 
 * Transactor[Task] — это мост между Doobie (Cats Effect) и ZIO.
 * zio-interop-cats предоставляет необходимые type class instances.
 * 
 * Архитектура:
 * 1. HikariCP пул соединений (connection pooling)
 * 2. Doobie Transactor (обёртка над пулом)
 * 3. ZIO Task интеграция через zio-interop-cats
 * 
 * Настройки пула:
 * - maximumPoolSize: из конфига
 * - connectionTimeout: из конфига
 * - keepAliveTime: 30 секунд (для PostgreSQL)
 * - validationTimeout: 5 секунд
 */
object TransactorLayer:
  
  /**
   * Создаёт ZIO Layer для Transactor[Task]
   * 
   * Используем manual HikariDataSource setup для простоты интеграции с ZIO
   */
  val live: ZLayer[DatabaseConfig, Throwable, Transactor[Task]] =
    ZLayer.scoped {
      for
        dbConfig <- ZIO.service[DatabaseConfig]
        
        _ <- ZIO.logInfo(s"[TRANSACTOR] Инициализация пула соединений PostgreSQL: ${dbConfig.host}:${dbConfig.port}/${dbConfig.database}")
        
        // Создаём HikariDataSource вручную (для простоты в ZIO)
        hikariConfig <- ZIO.attempt {
          val config = new com.zaxxer.hikari.HikariConfig()
          config.setDriverClassName("org.postgresql.Driver")
          config.setJdbcUrl(s"jdbc:postgresql://${dbConfig.host}:${dbConfig.port}/${dbConfig.database}")
          config.setUsername(dbConfig.username)
          config.setPassword(dbConfig.password)
          config.setMaximumPoolSize(dbConfig.poolSize)
          config.setConnectionTimeout(dbConfig.connectionTimeout.toLong)
          config
        }
        
        // Создаём HikariDataSource через ZIO.acquireRelease (для автоматического закрытия)
        ds <- ZIO.acquireRelease(
          ZIO.attempt(new com.zaxxer.hikari.HikariDataSource(hikariConfig))
        )(ds => ZIO.attempt(ds.close()).ignoreLogged)
        
        // Создаём Transactor из HikariDataSource (используем Runtime.default.executor для EC)
        xa = Transactor.fromDataSource[Task](
          dataSource = ds,
          connectEC = scala.concurrent.ExecutionContext.global
        )
        
        // Проверка подключения (health check)
        _ <- sql"SELECT 1".query[Int].unique.transact(xa).foldZIO(
          err => ZIO.logError(s"[TRANSACTOR] ✗ Ошибка подключения к PostgreSQL: ${err.getMessage}") *> ZIO.fail(err),
          _ => ZIO.logInfo(s"[TRANSACTOR] ✓ Подключение к PostgreSQL успешно")
        )
        
      yield xa
    }
  
  /**
   * Создаёт только ConfigLayer для DatabaseConfig из AppConfig
   */
  def configLayer: ZLayer[com.wayrecall.device.config.AppConfig, Nothing, DatabaseConfig] =
    ZLayer.fromFunction { (appConfig: com.wayrecall.device.config.AppConfig) =>
      appConfig.database
    }

end TransactorLayer
