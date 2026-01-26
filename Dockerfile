# ============================================================
# Device Manager - Dockerfile
# Многоэтапная сборка для минимального образа
# ============================================================

# --------------------------
# Этап 1: Сборка
# --------------------------
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-21.0.2_13_1.9.8_3.4.0 AS builder

WORKDIR /app

# Копируем файлы сборки отдельно для кеширования зависимостей
COPY build.sbt .
COPY project/ project/

# Загружаем зависимости (этот слой кешируется при изменении только кода)
RUN sbt update

# Копируем исходный код
COPY src/ src/

# Собираем fat JAR
RUN sbt assembly

# --------------------------
# Этап 2: Runtime образ
# --------------------------
FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="WayRecall Team"
LABEL description="Device Manager - Сервис управления GPS/ГЛОНАСС устройствами"
LABEL version="1.0.0"

# Переменные окружения
ENV APP_HOME=/opt/device-manager
ENV LOG_DIR=/var/log/device-manager
ENV JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:+UseStringDeduplication"

# Создаём пользователя для безопасности
RUN groupadd -r devicemanager && \
    useradd -r -g devicemanager -d ${APP_HOME} devicemanager

# Создаём директории
RUN mkdir -p ${APP_HOME} ${LOG_DIR} && \
    chown -R devicemanager:devicemanager ${APP_HOME} ${LOG_DIR}

WORKDIR ${APP_HOME}

# Копируем собранный JAR
COPY --from=builder /app/target/scala-3.4.0/device-manager-assembly-1.0.0.jar app.jar
RUN chown devicemanager:devicemanager app.jar

# Переключаемся на непривилегированного пользователя
USER devicemanager

# Expose порты
# 8082 - HTTP API
EXPOSE 8082

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8082/health || exit 1

# Точка входа
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
