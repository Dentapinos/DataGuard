# ---------------------------------------------
# Этап 1: Сборка приложения (Build Stage)
# ---------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Копируем только pom.xml для кэширования зависимостей
COPY pom.xml .

# Загружаем зависимости (это кэшируется Maven)
RUN mvn -B dependency:go-offline -Dmaven.test.skip=true

# Копируем исходный код
COPY src/ src/

# Собираем приложение (без тестов для ускорения)
RUN mvn -B clean package -DskipTests

# ---------------------------------------------
# Этап 2: Рабочий образ (Runtime Stage)
# ---------------------------------------------
FROM eclipse-temurin:17-jre-alpine

# Устанавливаем необходимые утилиты
RUN apk add --no-cache tini curl

WORKDIR /app

# Создаем пользователя для запуска приложения (безопасность)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Копируем JAR-файл из этапа сборки
COPY --from=builder /build/target/*.jar app.jar

# Создаем директорию для резервных копий
RUN mkdir -p /var/backups && chown -R appuser:appgroup /var/backups /app

# Переключаемся на непривилегированного пользователя
USER appuser

# Порт приложения
EXPOSE 8080

# Настройки JVM для контейнера
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Точка входа с поддержкой SIGTERM и SIGINT
ENTRYPOINT ["tini", "--", "java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
