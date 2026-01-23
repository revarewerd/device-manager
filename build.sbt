name := "device-manager"
version := "0.1.0"
scalaVersion := "3.4.0"

libraryDependencies ++= Seq(
  // ZIO Core
  "dev.zio" %% "zio" % "2.0.20",
  "dev.zio" %% "zio-streams" % "2.0.20",
  
  // Redis Client (Lettuce)
  "io.lettuce" % "lettuce-core" % "6.3.0.RELEASE",
  
  // PostgreSQL Driver
  "org.postgresql" % "postgresql" % "42.7.1",
  
  // ZIO JSON для сериализации команд
  "dev.zio" %% "zio-json" % "0.6.2",
  
  // Logging
  "dev.zio" %% "zio-logging" % "2.1.16",
  "dev.zio" %% "zio-logging-slf4j" % "2.1.16",
  "ch.qos.logback" % "logback-classic" % "1.4.14",
  
  // HTTP Server (для REST API)
  "dev.zio" %% "zio-http" % "3.0.0-RC4",
  
  // Testing
  "dev.zio" %% "zio-test" % "2.0.20" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.0.20" % Test
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
