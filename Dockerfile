# Образ claudeproxy: JRE 21 + готовый boot-jar (дашборд уже встроен в jar).
# Сборка — scripts/docker-build.bat (Windows) / scripts/docker-build.sh (Unix):
# они прогоняют gradlew bootJar и затем docker build.
# Конфигурация: том ./config:/app/config (application.yml), база — том
# ./data:/app/data (SQLite) либо PostgreSQL через переменные окружения
# SPRING_DATASOURCE_* (пример — docker-compose.postgres.yml).

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Непривилегированный пользователь; каталоги под томы data/config.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin claudeproxy \
    && mkdir -p /app/data /app/config \
    && chown -R claudeproxy:claudeproxy /app

COPY app/build/libs/claudeproxy-0.1.0.jar claudeproxy.jar

USER claudeproxy
EXPOSE 8080
VOLUME /app/data

# JAVA_OPTS — тюнинг JVM, например -e JAVA_OPTS=-Xmx1g.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar claudeproxy.jar"]
